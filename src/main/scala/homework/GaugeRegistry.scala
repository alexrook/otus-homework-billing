package homework

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }

import java.util.UUID

object GaugeRegistry {

  val name: String = "GaugeRegistry"

  val tagGaugeUpdated:  String = "gaugeUpdated"
  val tagGaugeConsumed: String = "gaugeConsumed"
  val tagGaugeDropped:  String = "gaugeDropped"

  sealed trait Event

  object Event {
    case class Updated(customerId:  UUID, gaugeId: UUID, gauge: Gauge) extends Event
    case class Dropped(customerId:  UUID, gaugeId: UUID) extends Event
    case class Consumed(customerId: UUID, gaugeId: UUID, consumed: Int) extends Event
  }

  sealed trait Command

  object Command {
    case class UpdateGauge(gauge:  Gauge, replyTo: ActorRef[StatusReply[Event.Updated]]) extends Command
    case class DropGauge(gaugeId:  UUID) extends Command
    case class Consume(customerId: UUID, gaugeId: UUID, consumed: Int) extends Command
    case object StopSelf extends Command
  }

  /**
    * @param consumed количество потребленных услуг
    * @param currentValue текущее показание счетчика
    */
  final case class State(consumed: Int, currentValue: Int)

  def apply(customerId: UUID, pId: String, tariffPId: String, gaugeId: UUID, gauge: Gauge): Behavior[Command] = {

    implicit val cId: UUID = customerId

    val persistenceId = PersistenceId.ofUniqueId(pId)

    Behaviors.setup { implicit ctx =>
      EventSourcedBehavior
        .withEnforcedReplies(
          persistenceId  = persistenceId,
          emptyState     = State(0, gauge.value),
          commandHandler = (state: State, command: Command) => handleCommand(gaugeId, state, command),
          eventHandler   = (state: State, event: Event) => handleEvent(state, event)
        )
        .withTagger {
          case _: Event.Updated => Set(tagGaugeUpdated)
          case _: Event.Dropped => Set(tagGaugeDropped)
        }
        .receiveSignal {
          case (state: State, PostStop) =>
            ctx.log.info(s"GaugeRegistry[{}] with state[{}]: Got the PostStop signal.", gaugeId, state)
        }
    }

  }

  def handleCommand(gaugeId: UUID, state: State, command: Command)(
    implicit ctx:            ActorContext[Command],
    customerId:              UUID
  ): ReplyEffect[Event, State] =
    command match {

      case Command.UpdateGauge(gauge, replyTo) if gauge.value >= state.currentValue =>
        val event: Event.Updated = Event.Updated(customerId = customerId, gaugeId, gauge = gauge)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenReply(replyTo) { _: State =>
            StatusReply.success(event)
          }

      case Command.UpdateGauge(gauge, replyTo) =>
        ctx.log.warn(
          s"The transmitted gauge reading[{}] is less than the current[{}]",
          gauge.value,
          state.currentValue
        )
        Effect.reply(replyTo)(
          replyError(
            s"The transmitted gauge reading[${gauge.value}] is less than the current[${state.currentValue}]"
          )
        )

      case Command.Consume(customerId, gaugeId, consumed) =>
        val event = Event.Consumed(customerId, gaugeId, consumed)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenNoReply()

      case cmd @ Command.DropGauge(gaugeId) =>
        ctx.log.debug(s"{} received", cmd)
        val event = Event.Dropped(customerId, gaugeId)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenStop()

        Effect.noReply

    }

  def handleEvent(state: State, event: Event)(implicit ctx: ActorContext[Command]): State =
    event match {

      case e @ Event.Updated(customerId, gaugeId, gauge) =>
        ctx.log.debug("Event {} received", e)

        val newConsumed: Int = gauge.value - state.currentValue
        ctx.self ! Command.Consume(customerId, gaugeId, newConsumed)

        state.copy(consumed = newConsumed, currentValue = gauge.value)

      case e: Event.Consumed =>
        ctx.log.debug("Event {} received", e)
        state

      case e: Event.Dropped =>
        ctx.log.debug("Event {} received", e)
        state

    }

  def replyError(msg: String)(implicit ctx: ActorContext[Command]): StatusReply[Nothing] = {
    ctx.log.warn(msg)
    StatusReply.error(new BusinessException(404, msg))
  }

}
