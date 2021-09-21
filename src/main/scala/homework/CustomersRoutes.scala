package homework

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import homework.CustomerRegistry.Event

import java.util.UUID
import scala.concurrent.Future

class CustomersRoutes(cRegistry: ActorRef[CustomerRegistry.Command])(implicit val system: ActorSystem[_])
    extends FailFastCirceSupport {

  import io.circe.generic.auto._

  implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("my-app.ask-timeout"))

  val routes: Route =
    pathPrefix("customers") {
      pathEnd {
        get {
          onSuccess(getState) { event =>
            complete((StatusCodes.OK, event))
          }
        } ~
        post {
          entity(as[Customer]) { customer =>
            onSuccess(createCustomer(customer)) { event =>
              complete((StatusCodes.Created, event))
            }
          }
        }
      } ~
      path(JavaUUID) { customerId =>
        put {
          entity(as[Customer]) { customer =>
            onSuccess(updateCustomer(customerId, customer)) { event =>
              complete((StatusCodes.OK, event))
            }
          }
        } ~
        delete {
          onSuccess(deleteCustomer(customerId)) { event =>
            complete((StatusCodes.OK, event))
          }
        }
      }
    }

  def createCustomer(customer: Customer): Future[Event.Added] =
    cRegistry.askWithStatus(CustomerRegistry.Command.CreateCustomer(UUID.randomUUID(), customer, _))

  def updateCustomer(id: UUID, customer: Customer): Future[Event.Updated] =
    cRegistry.askWithStatus(CustomerRegistry.Command.UpdateCustomer(id, customer, _))

  def deleteCustomer(id: UUID): Future[Event.Deleted] =
    cRegistry.askWithStatus(CustomerRegistry.Command.DeleteCustomer(id, _))

  def getState: Future[Event.StateResponse] =
    cRegistry.askWithStatus(CustomerRegistry.Command.GetState)

}
