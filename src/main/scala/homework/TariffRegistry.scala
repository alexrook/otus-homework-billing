package homework

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }

final case class Tariff(amount: Int)

object TariffRegistry {

  val name = "TariffRegistry"

  sealed trait Command

  object Command {
    final case class SetTariff(tariff: Tariff, replyTo: ActorRef[StatusReply[Event.Updated]]) extends Command

    final case class GetState(replyTo: ActorRef[StatusReply[Event.StateResponse]]) extends Command
  }

  sealed trait Event

  object Event {
    final case class Updated(tariff: Tariff) extends Event

    final case class StateResponse(currentTariff: Tariff) extends Event
  }

  final case class State(currentTariff: Tariff)

  def apply(pId: String): Behavior[Command] = {
    val persistenceId: PersistenceId = PersistenceId.ofUniqueId(pId)

    Behaviors.setup { ctx =>
      EventSourcedBehavior.withEnforcedReplies(
        persistenceId = persistenceId,
        emptyState = State(Tariff(0)),
        commandHandler = (state, command) => handleCommand(state, command, ctx),
        eventHandler = (state, event) => handleEvent(state, event, ctx)
      )
    }

  }

  def handleCommand(
    state:   State,
    command: Command,
    ctx:     ActorContext[Command]
  ): ReplyEffect[Event, State] =
    command match {

      case Command.SetTariff(tariff, replyTo) =>
        ctx.log.info(s"Receive set tariff[{}]", tariff)

        val updated = Event.Updated(tariff)
        Effect
          .persist(updated)
          .thenRun { _: State =>
            ctx.log.info(s"Event `set tariff[{}]` added to journal", updated)
          }
          .thenReply(replyTo) { _: State =>
            StatusReply.success(updated)
          }

      case Command.GetState(replyTo) =>
        ctx.log.info(s"Receive command get state")
        Effect.reply(replyTo)(
          StatusReply.success(Event.StateResponse(state.currentTariff))
        )

    }

  def handleEvent(state: State, event: Event, ctx: ActorContext[Command]): State =
    event match {

      case e: Event.Updated =>
        ctx.log.debug("Event Update[{}] received", e)
        state.copy(currentTariff = e.tariff)

      case e: Event.StateResponse => //Это событие не должно возникать ?
        ctx.log.debug("Event StateResponse[{}] received", e)
        state

    }

  def replyError(msg: String, ctx: ActorContext[Command]): StatusReply[Nothing] = {
    ctx.log.warn(msg)
    StatusReply.error(new BusinessException(404, msg))
  }

}
