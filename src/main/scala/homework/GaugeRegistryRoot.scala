package homework

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }

import java.util.UUID
import scala.annotation.tailrec

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

    final case class UpdateGaugeA(
      customerId: UUID,
      gaugeId:    UUID,
      gauge:      Gauge,
      replyTo:    ActorRef[StatusReply[Event.Updated]]
    ) extends Command

    final case class UpdateGauge(gaugeId: UUID, gauge: Gauge, replyTo: ActorRef[StatusReply[Event.Updated]])
        extends Command

    final case class DropGaugeA(customerId: UUID, gaugeId: UUID, replyTo: ActorRef[StatusReply[Event.Dropped]])
        extends Command

    final case class DropGauge(gaugeId: UUID, replyTo: ActorRef[StatusReply[Event.Dropped]]) extends Command

    final case class GetState(replyTo: ActorRef[StatusReply[Event.StateResponse]]) extends Command
  }

  sealed trait Event

  object Event {
    final case class Added(customerId: UUID, gaugeId: UUID, gauge: Gauge) extends Event

    final case class Updated(customerId: UUID, gaugeId: UUID, gauge: Gauge) extends Event

    final case class Dropped(customerId: UUID, gaugeId: UUID) extends Event

    final case class StateResponse(gaugesByCustomer: Map[UUID, Set[UUID]]) extends Event
  }

  final case class State(gaugeByUser: Map[UUID, Set[UUID]])

  def apply(pId: String, customerRegistry: ActorRef[CustomerRegistry.Command]): Behavior[Command] = {
    val persistenceId: PersistenceId = PersistenceId.ofUniqueId(pId)

    Behaviors.setup { ctx =>
      EventSourcedBehavior.withEnforcedReplies(
        persistenceId = persistenceId,
        emptyState = State(Map.empty[UUID, Set[UUID]].withDefault(_ => Set.empty[UUID])),
        commandHandler = (state, command) => handleCommand(state, command, ctx, customerRegistry),
        eventHandler = (state, event) => handleEvent(state, event, ctx)
      )
    }

  }

  @tailrec
  def handleCommand(
    state:            State,
    command:          Command,
    ctx:              ActorContext[Command],
    customerRegistry: ActorRef[CustomerRegistry.Command]
  ): ReplyEffect[Event, State] =
    command match {

      case Command.CreateGauge(customerId, gaugeId, gauge, replyTo) =>
        ctx.log.info(s"Receive command `create gauge[{}], customer id[{}]`", gauge, customerId)

        val event = Event.Added(customerId, gaugeId, gauge)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"Event[{}] added to journal", event)
          }
          .thenReply(replyTo) { _: State =>
            StatusReply.success(event)
          }

      case Command.UpdateGaugeA(customerId, gaugeId, gauge, replyTo) =>
        ctx.log.debug(s"Receive command `update gauge[{}], customer id[{}]`", gauge, customerId)

        val event = Event.Updated(customerId, gaugeId, gauge)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"Event[{}] added to journal", event)
          }
          .thenReply(replyTo) { _: State =>
            StatusReply.success(event)
          }

      case Command.UpdateGauge(gaugeId, gauge, replyTo) =>
        findCustomerByGaugeId(gaugeId, state.gaugeByUser) match {
          case Some(customerId) =>
            handleCommand(state, Command.UpdateGaugeA(customerId, gaugeId, gauge, replyTo), ctx, customerRegistry)
          case None => Effect.reply(replyTo)(replyError("Customer does not exist or has been deleted", ctx))
        }

      case Command.DropGaugeA(customerId, gaugeId, replyTo) =>
        ctx.log.debug(s"Receive command `update gauge[{}], customer id[{}]`", gaugeId, customerId)

        val event = Event.Dropped(customerId, gaugeId)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"Event[{}] added to journal", event)
          }
          .thenReply(replyTo) { _: State =>
            StatusReply.success(event)
          }

      case Command.DropGauge(gaugeId, replyTo) =>
        findCustomerByGaugeId(gaugeId, state.gaugeByUser) match {
          case Some(customerId) =>
            handleCommand(state, Command.DropGaugeA(customerId, gaugeId, replyTo), ctx, customerRegistry)
          case None => Effect.reply(replyTo)(replyError("Customer does not exist or has been deleted", ctx))
        }

      case Command.GetState(replyTo) =>
        ctx.log.debug(s"Receive command get state")
        Effect.reply(replyTo)(
          StatusReply.success(Event.StateResponse(state.gaugeByUser))
        )

    }

  def handleEvent(state: State, event: Event, ctx: ActorContext[Command]): State =
    event match {

      case e @ Event.Added(customerId, gaugeId, _) =>
        ctx.log.debug("Event Added[{}] received", e)
        val newGaugesForCustomer: Set[UUID] = state.gaugeByUser(customerId) + gaugeId
        state.copy(state.gaugeByUser + (customerId -> newGaugesForCustomer))

      case e: Event.Updated =>
        ctx.log.debug("Event Updated[{}] received", e)
        state

      case e @ Event.Dropped(customerId, gaugeId) =>
        ctx.log.debug("Event Dropped[{}] received", e)
        val newGaugesForCustomer: Set[UUID] = state.gaugeByUser(customerId) - gaugeId
        state.copy(state.gaugeByUser + (customerId -> newGaugesForCustomer))

      case e: Event.StateResponse => //Это событие не должно возникать ?
        ctx.log.debug("Event StateResponse[{}] received", e)
        state

    }

  def replyError(msg: String, ctx: ActorContext[Command]): StatusReply[Nothing] = {
    ctx.log.warn(msg)
    StatusReply.error(new BusinessException(404, msg))
  }

  def findCustomerByGaugeId(gaugeId: UUID, gaugeByUser: Map[UUID, Set[UUID]]): Option[UUID] =
    gaugeByUser
      .find {
        case (_, gauges) => gauges.contains(gaugeId)
      }
      .map {
        case (customerId, _) => customerId
      }

}
