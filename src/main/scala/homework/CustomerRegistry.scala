package homework

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }

import java.util.UUID

final case class Customer(shortName: String, fullName: String)

class BusinessException(val code: Int, val msg: String) extends Exception(msg)

object CustomerRegistry {

  val name: String = "CustomerRegistry"

  sealed trait Command

  object Command {

    final case class CreateCustomer(id: UUID, user: Customer, replyTo: ActorRef[StatusReply[Event.Added]])
        extends Command

    final case class UpdateCustomer(id: UUID, user: Customer, replyTo: ActorRef[StatusReply[Event.Updated]])
        extends Command

    final case class DeleteCustomer(id: UUID, replyTo: ActorRef[StatusReply[Event.Deleted]]) extends Command

    final case class GetState(replyTo: ActorRef[StatusReply[Event.StateResponse]]) extends Command
  }

  sealed trait Event

  object Event {
    final case class Added(id: UUID, customer: Customer) extends Event

    final case class Updated(id: UUID, customer: Customer) extends Event

    final case class Deleted(id: UUID) extends Event

    final case class StateResponse(customers: Set[UUID]) extends Event

  }

  final case class State(customers: Set[UUID])

  def apply(pId: String): Behavior[Command] = {
    val persistenceId: PersistenceId = PersistenceId.ofUniqueId(pId)

    Behaviors.setup { ctx =>
      EventSourcedBehavior.withEnforcedReplies(
        persistenceId = persistenceId,
        emptyState = State(Set.empty[UUID]),
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

      case cmd @ Command.CreateCustomer(id, customer, replyTo) =>
        ctx.log.info(s"Receive command {}", cmd)
        if (state.customers.contains(id)) {
          Effect.reply(replyTo)(
            replyError(s"The customer with id[$id] already exists", ctx)
          )
        } else {
          val eAdd = Event.Added(id = id, customer = customer)
          Effect
            .persist(eAdd)
            .thenRun { _: State =>
              ctx.log.info(s"{} added to journal", eAdd)
            }
            .thenReply(replyTo) { _: State =>
              StatusReply.success(eAdd)
            }
        }

      case cmd @ Command.UpdateCustomer(id, customer, replyTo) =>
        ctx.log.info(s"Receive command {}", cmd)
        if (state.customers.contains(id)) {
          val eUpdate = Event.Updated(id = id, customer = customer)
          Effect
            .persist(eUpdate)
            .thenRun { _: State =>
              ctx.log.info(s"{}} added to journal", eUpdate)
            }
            .thenReply(replyTo) { _: State =>
              StatusReply.success(eUpdate)
            }
        } else {
          Effect.reply(replyTo)(
            replyError(s"The customer with id[$id] does not exists", ctx)
          )
        }

      case cmd @ Command.DeleteCustomer(id, replyTo) =>
        ctx.log.info(s"Receive command {}", cmd)
        if (state.customers.contains(id)) {
          val eDelete = Event.Deleted(id = id)
          Effect
            .persist(eDelete)
            .thenRun { _: State =>
              ctx.log.info(s"{} added to journal", eDelete)
            }
            .thenReply(replyTo) { _: State =>
              StatusReply.success(eDelete)
            }
        } else {
          Effect.reply(replyTo)(
            replyError(s"The customer with id[$id] does not exists", ctx)
          )
        }

      case Command.GetState(replyTo) =>
        ctx.log.info(s"Receive command get state")
        Effect.reply(replyTo)(
          StatusReply.success(Event.StateResponse(state.customers))
        )

    }

  def handleEvent(state: State, event: Event, ctx: ActorContext[Command]): State =
    event match {

      case e: Event.Added =>
        ctx.log.debug("Event {} received", e)
        state.copy(state.customers + e.id)

      case e: Event.Updated =>
        ctx.log.debug("Event {}} received", e)
        state

      case e: Event.Deleted =>
        ctx.log.debug("Event {} received", e)
        state.copy(state.customers - e.id)

      case e: Event.StateResponse => //Это событие не должно возникать ?
        ctx.log.debug("Event {} received", e)
        state

    }

  def replyError(msg: String, ctx: ActorContext[Command]): StatusReply[Nothing] = {
    ctx.log.warn(msg)
    StatusReply.error(new BusinessException(404, msg))
  }

}
