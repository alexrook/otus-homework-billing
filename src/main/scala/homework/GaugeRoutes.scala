package homework

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import homework.GaugeRegistryRoot.Event

import java.util.UUID
import scala.concurrent.Future

class GaugeRoutes(gaugeRegistry: ActorRef[GaugeRegistryRoot.Command])(implicit val system: ActorSystem[_])
    extends FailFastCirceSupport {

  import io.circe.generic.auto._

  implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("my-app.ask-timeout"))

  val routes: Route =
    pathPrefix("gauges") {
      pathEnd {
        get {
          onSuccess(getState) { event =>
            complete((StatusCodes.OK, event))
          }
        } ~
        post {
          parameter("customerId".as[UUID]) { customerId =>
            entity(as[Gauge]) { gauge =>
              onSuccess(createGauge(customerId, gauge)) { event =>
                complete((StatusCodes.Created, event))
              }
            }
          }
        }
      } ~
      path(JavaUUID) { gaugeId =>
        get {
          onSuccess(getGauge(gaugeId)) { event =>
            complete((StatusCodes.OK, event))
          }
        } ~
        put {
          entity(as[Gauge]) { gauge =>
            onSuccess(updateGauge(gaugeId, gauge)) { event =>
              complete((StatusCodes.OK, event))
            }
          }
        } ~
        delete {
          onSuccess(dropGauge(gaugeId)) { event =>
            complete((StatusCodes.OK, event))
          }
        }
      }
    }

  def createGauge(customerId: UUID, gauge: Gauge): Future[Event.Added] =
    gaugeRegistry.askWithStatus(
      GaugeRegistryRoot.Command.CreateGauge(customerId, UUID.randomUUID(), gauge, _)
    )

  def updateGauge(gaugeId: UUID, gauge: Gauge): Future[GaugeRegistry.Event.Updated] =
    gaugeRegistry.askWithStatus(GaugeRegistryRoot.Command.UpdateGauge(gaugeId, gauge, _))

  def dropGauge(gaugeId: UUID): Future[Event.Dropped] =
    gaugeRegistry.askWithStatus(GaugeRegistryRoot.Command.DropGauge(gaugeId, _))

  def getState: Future[Event.StateResponse] =
    gaugeRegistry.askWithStatus(GaugeRegistryRoot.Command.GetState)

  def getGauge(gaugeId: UUID): Future[GaugeRegistry.State] =
    gaugeRegistry.askWithStatus(GaugeRegistryRoot.Command.GetGauge(gaugeId, _))

}
