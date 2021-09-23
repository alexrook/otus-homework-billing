package homework

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ ExceptionHandler, Route }
import akka.util.Timeout

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

object Boot {

  import akka.http.scaladsl.server.Directives._

  val myExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case be: BusinessException =>
        extractUri { uri =>
          complete((StatusCodes.BadRequest, be.msg))
        }
    }

  def main(args: Array[String]): Unit = {

    import RootActor.Command

    implicit val system: ActorSystem[Command] = ActorSystem[Command](RootActor.apply, RootActor.name)

    // needed for the future flatMap/onComplete in the end
    implicit val executionContext: ExecutionContextExecutor = system.executionContext
    implicit val scheduler:        Scheduler                = system.scheduler
    implicit val timeout:          Timeout                  = Timeout(5.seconds)

    val server: Future[Http.ServerBinding] =
      for {
        customersRegistryActor <- system.ask(Command.GetCustomerRegistry)
        tariffRegistryActor    <- system.ask(Command.GetTariffRegistry)
        gaugeRegistryRootActor <- system.ask(Command.GetGaugeRegistryRoot)

        customersRoutes: CustomersRoutes = new CustomersRoutes(customersRegistryActor)(system)
        tariffRoutes = new TariffRoutes(tariffRegistryActor)
        gaugeRoutes  = new GaugeRoutes(gaugeRegistryRootActor)

        routes: Route = handleExceptions(myExceptionHandler) {
          customersRoutes.routes ~ tariffRoutes.routes ~ gaugeRoutes.routes
        }

        server <- Http().newServerAt("localhost", 8080).bind(routes)
      } yield server

    server.onComplete {
      case Success(binding) =>
        println(binding)
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        println(ex.getMessage)
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }

  }

}
