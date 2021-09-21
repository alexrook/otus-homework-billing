package homework

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors

object RootActor {

  val name: String = "RootActor"

  sealed trait Command

  object Command {
    case class GetCustomerRegistry(replyTo: ActorRef[ActorRef[CustomerRegistry.Command]]) extends Command

    case class GetTariffRegistry(replyTo: ActorRef[ActorRef[TariffRegistry.Command]]) extends Command
  }

  def apply(): Behavior[Command] =
    Behaviors.setup[Command] { context =>
      val customersRegistryActor: ActorRef[CustomerRegistry.Command] =
        context.spawn(CustomerRegistry("001"), CustomerRegistry.name)

      context.watch(customersRegistryActor)

      val tariffRegistryActor: ActorRef[TariffRegistry.Command] =
        context.spawn(TariffRegistry("002"), TariffRegistry.name)

      context.watch(tariffRegistryActor)

      Behaviors.receiveMessage[Command] {

        case Command.GetCustomerRegistry(replyTo) =>
          replyTo ! customersRegistryActor
          Behaviors.same

        case Command.GetTariffRegistry(replyTo) =>
          replyTo ! tariffRegistryActor
          Behaviors.same
      }

    }

}
