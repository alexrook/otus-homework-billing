package homework

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{ EventEnvelope, PersistenceQuery }
import akka.persistence.typed.PersistenceId
import akka.stream.alpakka.slick.scaladsl.SlickSession
import akka.stream.alpakka.slick.scaladsl._
import akka.stream.scaladsl._
import slick.jdbc.{ PositionedParameters, SetParameter }

import java.sql.SQLType
import java.util.{ Date, UUID }
import scala.concurrent.ExecutionContextExecutor

object AccountStats {

  import AccountRegistryRoot.Money

  val name: String = "AccountStats"

  sealed trait Command

  object Command {
    final case class AddAccountStat(customerId: UUID,
                                    consumed:   Int,
                                    balance:    Money,
                                    moneyMove:  Money,
                                    actionId:   Long,
                                    ts:         Long)
        extends Command
  }

  final case class State(accountLatestActions: Map[UUID, Long]) {

    def withNewActionId(customerId: UUID, actionId: Long) =
      new State(
        this.accountLatestActions + (customerId -> actionId)
      )

  }

  implicit val uuidSetParameter: SetParameter[UUID] = new SetParameter[UUID] {
    override def apply(uuid: UUID, stmt: PositionedParameters): Unit =
      stmt.setObject(uuid, java.sql.Types.OTHER)
  }

  object State {
    def empty: State = new State(accountLatestActions = Map.empty[UUID, Long])
  }

  def apply(accountRegistryRootPID: String)(implicit system: ActorSystem[_]): Behavior[Command] = {

    implicit val ec: ExecutionContextExecutor = system.executionContext

    val readJournal: LeveldbReadJournal =
      PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    implicit val session: SlickSession = SlickSession.forConfig("slick-postgres")
    import session.profile.api._

    system.whenTerminated.foreach { _ =>
      session.close()
    }

    Behaviors.setup { implicit ctx =>
      ctx.log.debug("Actor created")
      readJournal
        .eventsByPersistenceId(accountRegistryRootPID, 0, Long.MaxValue) //TODO: add offset
        .collect {
          case EventEnvelope(_, _, _, AccountRegistryRoot.Event.AccountMovement(customerId, account)) =>
            Command.AddAccountStat(
              customerId = customerId,
              consumed   = account.consumed,
              balance    = account.balance,
              actionId   = account.actionId,
              moneyMove  = account.moneyMove,
              ts         = new Date().getTime
            )
        }
        .log(name = "AccountStat")
        .via {
          Slick.flow { accountStat: Command.AddAccountStat =>
            import accountStat._
            sqlu"INSERT INTO account_stats VALUES($actionId, $customerId,$consumed, $balance, $moneyMove::money, $ts) ON CONFLICT DO NOTHING"
          }
        }
        .runWith(Sink.ignore)

      Behaviors.empty

    }

  }

}
