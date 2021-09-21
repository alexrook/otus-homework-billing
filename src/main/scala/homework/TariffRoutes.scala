package homework

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import homework.TariffRegistry.Event

import scala.concurrent.Future

class TariffRoutes(registry: ActorRef[TariffRegistry.Command])(implicit val system: ActorSystem[_])
    extends FailFastCirceSupport {

  import io.circe.generic.auto._

  implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("my-app.ask-timeout"))

  val routes: Route =
    pathPrefix("tariff") {
      pathEnd {
        get {
          onSuccess(getState) { event =>
            complete((StatusCodes.OK, event))
          }
        } ~
        post {
          entity(as[Tariff]) { tariff =>
            onSuccess(setTariff(tariff)) { event =>
              complete((StatusCodes.Created, event))
            }
          }
        }
      }
    }

  def setTariff(tariff: Tariff): Future[Event.Updated] =
    registry.askWithStatus(TariffRegistry.Command.SetTariff(tariff, _))

  def getState: Future[Event.StateResponse] =
    registry.askWithStatus(TariffRegistry.Command.GetState)

}
