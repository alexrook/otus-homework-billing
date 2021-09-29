package homework

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }

object RootActor {

  val name: String = "RootActor"

  sealed trait Command

  object Command {
    case class GetCustomerRegistry(replyTo:    ActorRef[ActorRef[CustomerRegistry.Command]]) extends Command
    case class GetTariffRegistry(replyTo:      ActorRef[ActorRef[TariffRegistry.Command]]) extends Command
    case class GetGaugeRegistryRoot(replyTo:   ActorRef[ActorRef[GaugeRegistryRoot.Command]]) extends Command
    case class GetAccountRegistryRoot(replyTo: ActorRef[ActorRef[AccountRegistryRoot.Command]]) extends Command
    case class GetAccountStats(replyTo:        ActorRef[ActorRef[AccountStats.Command]]) extends Command
  }

  def apply(): Behavior[Command] =
    Behaviors.setup[Command] { ctx: ActorContext[_] =>
      val customersRegistryActor: ActorRef[CustomerRegistry.Command] =
        ctx.spawn(CustomerRegistry("001"), CustomerRegistry.name)
      ctx.watch(customersRegistryActor)

      val tariffRegistryActor: ActorRef[TariffRegistry.Command] =
        ctx.spawn(TariffRegistry("002"), TariffRegistry.name)
      ctx.watch(tariffRegistryActor)

      val gaugeRegistryActorRoot: ActorRef[GaugeRegistryRoot.Command] =
        ctx.spawn(GaugeRegistryRoot("003", customerRegistryPersistenceId = "001")(ctx.system), GaugeRegistryRoot.name)
      ctx.watch(gaugeRegistryActorRoot)

      val accountRegistryActor: ActorRef[AccountRegistryRoot.Command] =
        ctx.spawn(
          AccountRegistryRoot(pId = "004", customersPID = "001", tariffPID = "002")(ctx.system),
          AccountRegistryRoot.name
        )
      ctx.watch(accountRegistryActor)

      val accountStatsActor: ActorRef[AccountStats.Command] =
        ctx.spawn(
          AccountStats(accountRegistryRootPID = "004")(ctx.system),
          AccountStats.name
        )
      ctx.watch(accountStatsActor)

      Behaviors.receiveMessage[Command] {

        case Command.GetCustomerRegistry(replyTo) =>
          replyTo ! customersRegistryActor
          Behaviors.same

        case Command.GetTariffRegistry(replyTo) =>
          replyTo ! tariffRegistryActor
          Behaviors.same

        case Command.GetGaugeRegistryRoot(replyTo) =>
          replyTo ! gaugeRegistryActorRoot
          Behaviors.same

        case Command.GetAccountRegistryRoot(replyTo) =>
          replyTo ! accountRegistryActor
          Behaviors.same

        case Command.GetAccountStats(replyTo) =>
          replyTo ! accountStatsActor
          Behaviors.same

      }

    }

}
