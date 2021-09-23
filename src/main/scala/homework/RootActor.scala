package homework

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object RootActor {

  val name: String = "RootActor"

  sealed trait Command

  object Command {
    case class GetCustomerRegistry(replyTo: ActorRef[ActorRef[CustomerRegistry.Command]]) extends Command
    case class GetTariffRegistry(replyTo: ActorRef[ActorRef[TariffRegistry.Command]]) extends Command
    case class GetGaugeRegistryRoot(replyTo: ActorRef[ActorRef[GaugeRegistryRoot.Command]]) extends Command
  }

  def apply(): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      val customersRegistryActor: ActorRef[CustomerRegistry.Command] =
        ctx.spawn(CustomerRegistry("001"), CustomerRegistry.name)

      ctx.watch(customersRegistryActor)

      val tariffRegistryActor: ActorRef[TariffRegistry.Command] =
        ctx.spawn(TariffRegistry("002"), TariffRegistry.name)

      ctx.watch(tariffRegistryActor)

      val gaugeRegistryActor: ActorRef[GaugeRegistryRoot.Command] =
        ctx.spawn(GaugeRegistryRoot("003", customerRegistryPersistenceId = "001")(ctx.system), GaugeRegistryRoot.name)

      ctx.watch(tariffRegistryActor)

      Behaviors.receiveMessage[Command] {

        case Command.GetCustomerRegistry(replyTo) =>
          replyTo ! customersRegistryActor
          Behaviors.same

        case Command.GetTariffRegistry(replyTo) =>
          replyTo ! tariffRegistryActor
          Behaviors.same

        case Command.GetGaugeRegistryRoot(replyTo) =>
          replyTo ! gaugeRegistryActor
          Behaviors.same

      }

    }

}
