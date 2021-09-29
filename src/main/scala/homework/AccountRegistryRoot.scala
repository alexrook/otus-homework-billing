package homework

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.pattern.StatusReply
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{ EventEnvelope, PersistenceQuery }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import akka.util.Timeout

import java.util.UUID

object AccountRegistryRoot {

  val name = "AccountRegistryRoot"
  type Money = Int

  final case class Account(balance: Money, consumed: Int, moneyMove: Money, gauges: Map[UUID, Long], actionId: Long) {
    def withIncActionId: Account = this.copy(actionId = actionId + 1L)
  }

  object Account {

    def empty: Account =
      Account(balance = 0, consumed = 0, moneyMove = 0, gauges = Map.empty[UUID, Long], actionId = 0L)

  }

  sealed trait Command

  object Command {

    final case class AddAccount(customerId:  UUID) extends Command
    final case class DropAccount(customerId: UUID) extends Command
    final case class SetTariff(amount:       Money) extends Command
    final case class Withdraw(customerId:    UUID, gaugeId: UUID, consumed: Int, seqNo: Long) extends Command
    final case class Deposit(customerId:     UUID, amount: Money, replyTo: ActorRef[StatusReply[Event.AccountMovement]])
        extends Command
    final case class GetAccountState(customerId: UUID, replyTo: ActorRef[StatusReply[Event.AccountMovement]])
        extends Command

  }

  sealed trait Event

  object Event {
    final case class Added(customerId:           UUID) extends Event
    final case class Dropped(customerId:         UUID) extends Event
    final case class NewTariff(amount:           Int) extends Event
    final case class AccountMovement(customerId: UUID, account: Account) extends Event
  }

  final case class State(accounts: Map[UUID, Account], tariff: Int)

  def apply(pId:     String, customersPID: String, tariffPID: String)(
    implicit system: ActorSystem[_]
  ): Behavior[Command] = {

    val persistenceId: PersistenceId = PersistenceId.ofUniqueId(pId)

    val readJournal: LeveldbReadJournal =
      PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    Behaviors.setup { implicit ctx =>
      implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("my-app.ask-timeout"))

      readJournal
        .eventsByPersistenceId(customersPID, 0, Long.MaxValue)
        .collect {
          case EventEnvelope(_, _, _, CustomerRegistry.Event.Added(customerId, _)) => Command.AddAccount(customerId)
          case EventEnvelope(_, _, _, CustomerRegistry.Event.Deleted(customerId))  => Command.DropAccount(customerId)
        }
        .runForeach { command =>
          ctx.self ! command
        }

      readJournal
        .eventsByPersistenceId(tariffPID, 0, Long.MaxValue)
        .collect {
          case EventEnvelope(_, _, _, TariffRegistry.Event.Updated(tariff)) =>
            Command.SetTariff(amount = tariff.amount)
        }
        .runForeach { command =>
          ctx.self ! command
        }

      readJournal
        .eventsByTag(GaugeRegistry.tagGaugeUpdated)
        .collect {
          case EventEnvelope(_, _, sequenceNo: Long, GaugeRegistry.Event.Updated(customerId, gaugeId, _, consumed)) =>
            Command.Withdraw(customerId, gaugeId = gaugeId, consumed = consumed, sequenceNo)
        }
        .runForeach { command =>
          ctx.self ! command
        }

      EventSourcedBehavior.withEnforcedReplies(
        persistenceId  = persistenceId,
        emptyState     = State(Map.empty[UUID, Account], 0),
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

      case Command.AddAccount(customerId) if state.accounts.contains(customerId) =>
        ctx.log.warn(s"The account[{}] already exists", customerId)
        Effect.noReply

      case Command.AddAccount(customerId) =>
        ctx.log.info(s"account[{}] creation command", customerId)
        val event = Event.Added(customerId)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenNoReply()

      case Command.DropAccount(customerId) if state.accounts.contains(customerId) =>
        ctx.log.info(s"account[{}] deletion command", customerId)
        val event = Event.Dropped(customerId)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenNoReply()

      case Command.DropAccount(customerId) =>
        ctx.log.warn(s"account[{}] deletion command, The account not exists", customerId)
        Effect.noReply

      case Command.SetTariff(amount) =>
        ctx.log.info(s"set tariff[{}] command", amount)
        val event = Event.NewTariff(amount)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenNoReply()

      case Command.Withdraw(customerId, gaugeId, consumed: Int, newSeqNo: Long)
          if state.accounts.contains(customerId) =>
        ctx.log.info(s"account[{}] withdraw command", customerId)
        //событие для нового значения счетчика может не существовать
        val oldSeqNoForGauge: Long = state.accounts(customerId).gauges.getOrElse(gaugeId, -1)

        if (newSeqNo > oldSeqNoForGauge) {
          val moneyMove:  Money   = state.tariff * consumed
          val oldAccount: Account = state.accounts(customerId)
          val newAccount: Account = oldAccount
            .copy(
              consumed  = oldAccount.consumed + consumed,
              balance   = oldAccount.balance - moneyMove,
              moneyMove = -moneyMove, //отрицательное движение по счету
              gauges    = oldAccount.gauges + (gaugeId -> newSeqNo),
            )
            .withIncActionId

          val event = Event.AccountMovement(customerId, newAccount)
          Effect
            .persist(event)
            .thenRun { _: State =>
              ctx.log.debug(s"{} added to journal", event)
            }
            .thenNoReply()
        } else {
          ctx.log.debug(s"withdraw command seqNo<= account.seqNo")
          Effect.noReply
        }

      case w: Command.Withdraw =>
        ctx.log.warn(s"account[{}] withdraw command, The account not exists", w.customerId)
        Effect.noReply

      case cmd @ Command.Deposit(customerId, amount, replyTo) if state.accounts.contains(customerId) =>
        ctx.log.info(s"account {} command", cmd)
        val moneyMove: Money = amount

        val oldAccount: Account = state.accounts(customerId)
        val newAccount: Account = oldAccount
          .copy(balance = oldAccount.balance + moneyMove, moneyMove = moneyMove)
          .withIncActionId

        val event = Event.AccountMovement(customerId, newAccount)
        Effect
          .persist(event)
          .thenRun { _: State =>
            ctx.log.debug(s"{} added to journal", event)
          }
          .thenReply(replyTo) { _: State =>
            StatusReply.success(event)
          }

      case Command.Deposit(customerId, _, replyTo) =>
        ctx.log.warn(s"deposit command, account {} does not exists", customerId)
        Effect.reply(replyTo)(replyError(s"The account[$customerId] does not exists"))

      case Command.GetAccountState(customerId, replyTo) if state.accounts.contains(customerId) =>
        ctx.log.debug(s"get account[{}] state command", customerId)
        Effect.reply(replyTo) {
          StatusReply.success(Event.AccountMovement(customerId, state.accounts(customerId)))
        }

      case Command.GetAccountState(customerId, replyTo) =>
        ctx.log.warn(s"get account state command, account {} does not exists", customerId)
        Effect.reply(replyTo)(replyError(s"The account[$customerId] does not exists"))

    }

  def handleEvent(state: State, event: Event)(implicit ctx: ActorContext[Command]): State =
    event match {
      case Event.Added(customerId) =>
        state.copy(accounts = state.accounts + (customerId -> Account.empty))

      case Event.Dropped(customerId) =>
        state.copy(accounts = state.accounts - customerId)

      case Event.NewTariff(newTariff) =>
        state.copy(tariff = newTariff)

      case ev @ Event.AccountMovement(customerId, account) =>
        state.copy(accounts = state.accounts + (customerId -> account))

    }

  def replyError(msg: String)(implicit ctx: ActorContext[Command]): StatusReply[Nothing] = {
    ctx.log.warn(msg)
    StatusReply.error(new BusinessException(404, msg))
  }

}
