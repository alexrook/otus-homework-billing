package homework

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import homework.AccountRegistryRoot.Event

import java.util.UUID
import scala.concurrent.Future

case class Deposit(amount: Int)

class AccountRoutes(registry: ActorRef[AccountRegistryRoot.Command])(implicit val system: ActorSystem[_])
    extends FailFastCirceSupport {

  import io.circe.generic.auto._

  implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("my-app.ask-timeout"))

  val routes: Route =
    pathPrefix("accounts") {
      path(JavaUUID) { accountId => // accountId==customerId
        get {
          onSuccess(getState(accountId)) { event =>
            complete((StatusCodes.OK, event))
          }
        } ~
        put {
          entity(as[Deposit]) { entity: Deposit =>
            onSuccess(deposit(accountId, entity.amount)) { event =>
              complete((StatusCodes.Created, event))
            }
          }
        }
      }
    }

  def deposit(customerId: UUID, amount: Int): Future[Event.Deposited] =
    registry.askWithStatus(AccountRegistryRoot.Command.Deposit(customerId, amount, _))

  def getState(customerId: UUID): Future[AccountState] =
    registry.askWithStatus(AccountRegistryRoot.Command.GetAccountState(customerId, _))

}
