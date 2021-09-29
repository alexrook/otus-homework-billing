package homework

import akka.{ NotUsed, pattern }
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.pattern.StatusReply
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{ EventEnvelope, PersistenceQuery }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EffectBuilder, EventSourcedBehavior, ReplyEffect }
import akka.stream.scaladsl.Source
import akka.util.Timeout
import homework.GaugeRegistryRoot.{ findGaugeCoordinates, replyError }

import java.util.UUID
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.util.{ Failure, Success }

final case class Gauge(value: Int)

object GaugeRegistryRoot {

  val name = "GaugeRegistryRoot"

  sealed trait Command

  object Command {

    final case class CreateGauge(
      customerId: UUID,
      gaugeId:    UUID,
      gauge:      Gauge,
      replyTo:    ActorRef[StatusReply[Event.Added]]
    ) extends Command

    final case class UpdateGauge(
      gaugeId: UUID,
      gauge:   Gauge,
      replyTo: ActorRef[StatusReply[GaugeRegistry.Event.Updated]]
    ) extends Command

    final case class DropGauge(gaugeId: UUID, replyTo: ActorRef[StatusReply[Event.Dropped]]) extends Command
    final case class GetGauge(gaugeId:  UUID, replyTo: ActorRef[StatusReply[GaugeRegistry.State]]) extends Command

    final case class GetState(replyTo: ActorRef[StatusReply[StateResponse]]) extends Command

    final case class AddCustomer(customerId:        UUID) extends Command
    final case class DropCustomerGauges(customerId: UUID) extends Command
    final case class DropCustomer(customerId:       UUID) extends Command

  }

  /**
    * customerId -> (gaugeId -> gaugeActorRef)
    */
  type GaugesActorsByCustomer = Map[UUID, Map[UUID, ActorRef[GaugeRegistry.Command]]]

  sealed trait Event

  object Event {
    final case class Added(customerId:         UUID, gaugeId: UUID, gauge: Gauge) extends Event
    final case class Dropped(customerId:       UUID, gaugeId: UUID) extends Event
    final case class CustomerAdded(customerId: UUID) extends Event
    final case class CustomerDrop(customerId:  UUID) extends Event
  }

  /**
    * @param gaugeByCustomer карта customer -> (gaugeId -> Gauge Actor)
    */
  final case class State(gaugeByCustomer: GaugesActorsByCustomer)

  case class GaugeCoordinates(customerId: UUID, gaugeId: UUID, actorRef: ActorRef[GaugeRegistry.Command])

  final case class StateResponse(gaugesByCustomer: Map[UUID, Set[UUID]])

  implicit class EffectBuilderOps(builder: EffectBuilder[Event, State])(implicit ctx: ActorContext[_]) {

    def thenLog(event: Event): EffectBuilder[Event, State] =
      builder.thenRun { _ =>
        ctx.log.debug("Event[{}] added to journal", event)
      }

  }

  def apply(pId:                                                        String, customerRegistryPersistenceId: String)(implicit
                                                                system: ActorSystem[_]): Behavior[Command] = {
    val persistenceId: PersistenceId = PersistenceId.ofUniqueId(pId)

    val readJournal: LeveldbReadJournal =
      PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    Behaviors.setup { implicit ctx =>
      implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("my-app.ask-timeout"))

      val customersEvents: Source[EventEnvelope, NotUsed] = readJournal
        .eventsByPersistenceId(customerRegistryPersistenceId, 0, Long.MaxValue)

      customersEvents
        .collect {
          case EventEnvelope(_, _, _, event: CustomerRegistry.Event.Added)   => Command.AddCustomer(event.id)
          case EventEnvelope(_, _, _, event: CustomerRegistry.Event.Deleted) => Command.DropCustomerGauges(event.id)
        }
        .runForeach { customerEvent =>
          ctx.self ! customerEvent
        }

      EventSourcedBehavior.withEnforcedReplies(
        persistenceId = persistenceId,
        emptyState = State(
          Map
            .empty[UUID, Map[UUID, ActorRef[GaugeRegistry.Command]]]
            .withDefault(_ => Map.empty[UUID, ActorRef[GaugeRegistry.Command]])
        ),
        commandHandler = (state: State, command) => handleCommand(state, command)(ctx, timeout),
        eventHandler   = (state, event) => handleEvent(state, event)
      )
    }

  }

  def handleCommand(
    state:        State,
    command:      Command
  )(implicit ctx: ActorContext[Command], timeout: Timeout): ReplyEffect[Event, State] =
    command match {

      case Command.CreateGauge(customerId, gaugeId, gauge, replyTo) if state.gaugeByCustomer.contains(customerId) =>
        ctx.log.info(s"Receive command `create gauge[{}], customer id[{}]`", gauge, customerId)

        val event = Event.Added(customerId, gaugeId, gauge)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenReply(replyTo) { _: State =>
            StatusReply.success(event)
          }

      case Command.CreateGauge(customerId, _, gauge, replyTo) =>
        ctx.log.info(s"no customer found[{}] when creating gauge[{}]", gauge, customerId)
        Effect.reply(replyTo)(replyError(s"No customer found[$customerId]"))

      case Command.UpdateGauge(gaugeId, gauge, replyTo) =>
        ctx.log.debug(s"Receive command update gauge[{}]", gauge)

        if (tellToGaugeActor(gaugeId, state, GaugeRegistry.Command.UpdateGauge(gauge, replyTo))) {
          Effect.noReply
        } else {
          Effect.reply(replyTo)(replyError("Customer or gauge does not exist or has been deleted"))
        }

      case Command.DropGauge(gaugeId, replyTo) =>
        ctx.log.debug(s"Receive command `drop gauge[{}], customer id[{}]`", gaugeId)
        findGaugeCoordinates(gaugeId, state.gaugeByCustomer) match {
          case Some(coordinates) =>
            val event = Event.Dropped(coordinates.customerId, gaugeId)
            Effect
              .persist(event)
              .thenRun { _: State =>
                ctx.log.debug(s"{} added to journal", event)
              }
              .thenReply(replyTo) { _: State =>
                StatusReply.success(event)
              }

          case None => Effect.reply(replyTo)(replyError("Customer or gauge does not exist or has been deleted"))
        }

      case Command.GetGauge(gaugeId, replyTo: ActorRef[StatusReply[GaugeRegistry.State]]) =>
        if (tellToGaugeActor(gaugeId, state, GaugeRegistry.Command.GetState(replyTo))) {
          Effect.noReply
        } else {
          Effect.reply(replyTo)(replyError(s"The gauge[$gaugeId] not found"))
        }

      case Command.AddCustomer(customerId) if state.gaugeByCustomer.contains(customerId) =>
        //TODO:нужно избавится от такого дублирования события
        //Это событие возникает дважды во время проигрывания журналов CustomerRegistry и GaugeRegistryRoot
        ctx.log.warn("state already contains customerId[{}]", customerId)
        Effect.noReply

      case Command.AddCustomer(customerId) =>
        val event = Event.CustomerAdded(customerId)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenNoReply()

      case Command.DropCustomerGauges(customerId) =>
        implicit val ec: ExecutionContextExecutor = ctx.executionContext

        ctx.log.debug("Command DropCustomerGauges[{}] received, we will disable customer gauges counters", customerId)
        Future
          .sequence {
            state.gaugeByCustomer(customerId).map {
              case (gaugeId: UUID, _: ActorRef[GaugeRegistry.Command]) =>
                ctx.self.askWithStatus(Command.DropGauge(gaugeId, _))(timeout, ctx.system.scheduler)
            }
          }
          .onComplete {
            case Failure(ex) =>
              ctx.log.error(s"An error occurred while drop gauges for customer[$customerId]", ex)
            case Success(_) =>
              ctx.self.tell(Command.DropCustomer(customerId))
          }

        Effect.noReply

      case Command.DropCustomer(customerId) =>
        val event = Event.CustomerDrop(customerId)
        EffectBuilderOps(
          Effect
            .persist(event)
        ).thenLog(event)
          .thenNoReply()

      case Command.GetState(replyTo) =>
        ctx.log.debug(s"Receive command get state")
        val response: Map[UUID, Set[UUID]] =
          state.gaugeByCustomer.map {
            case (customerId, gauges) => customerId -> gauges.keySet
          }
        Effect.reply(replyTo)(
          StatusReply.success(StateResponse(response))
        )

    }

  def handleEvent(state: State, event: Event)(implicit ctx: ActorContext[Command]): State =
    event match {

      case e @ Event.Added(customerId, gaugeId, gauge) =>
        ctx.log.debug("Event {} received", e)

        val gaugeRegistry: ActorRef[GaugeRegistry.Command] = ctx.spawn(
          behavior = GaugeRegistry(customerId, gaugeId.toString, "", gaugeId, gauge),
          name     = s"${GaugeRegistry.name}-$gaugeId"
        )

        ctx.watch(gaugeRegistry)

        val newGaugesForCustomer: Map[UUID, ActorRef[GaugeRegistry.Command]] =
          state.gaugeByCustomer(customerId) + (gaugeId -> gaugeRegistry)
        state.copy(state.gaugeByCustomer + (customerId -> newGaugesForCustomer))

      case e @ Event.Dropped(customerId, gaugeId) =>
        ctx.log.debug("Event {} received", e)

        tellToGaugeActor(gaugeId, state, GaugeRegistry.Command.DropGauge(gaugeId))

        val newGaugesForCustomer: Map[UUID, ActorRef[GaugeRegistry.Command]] =
          state.gaugeByCustomer(customerId) - gaugeId
        state.copy(state.gaugeByCustomer + (customerId -> newGaugesForCustomer))

      case e @ Event.CustomerAdded(customerId) =>
        ctx.log.debug("Event {} received", e)
        val newGaugeByUser = state.gaugeByCustomer + (customerId -> Map.empty[UUID, ActorRef[GaugeRegistry.Command]])
        state.copy(gaugeByCustomer = newGaugeByUser)

      case e @ Event.CustomerDrop(customerId) =>
        ctx.log.info("Event {} received", e)
        state.copy(gaugeByCustomer = state.gaugeByCustomer - customerId)

    }

  def replyError(msg: String)(implicit ctx: ActorContext[Command]): StatusReply[Nothing] = {
    ctx.log.warn(msg)
    StatusReply.error(new BusinessException(404, msg))
  }

  def tellToGaugeActor(gaugeId:                                                          UUID, state: State, command: GaugeRegistry.Command)(implicit
                                                                                    ctx: ActorContext[_]): Boolean =
    findGaugeCoordinates(gaugeId, state.gaugeByCustomer) match {
      case Some(coordinates) =>
        coordinates.actorRef.tell(command)
        true
      case None =>
        false
    }

  def findGaugeCoordinates(gaugeId: UUID, gaugeByCustomer: GaugesActorsByCustomer): Option[GaugeCoordinates] =
    gaugeByCustomer
      .find {
        case (_, gauges) => gauges.contains(gaugeId)
      }
      .map {
        case (customerId, gauges) => GaugeCoordinates(customerId, gaugeId, gauges(gaugeId))
      }

}
