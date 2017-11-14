package io.demograph.hyparview

import akka.actor.{ActorPath, ActorRef, ActorSystem}
import akka.remote.testkit.MultiNodeSpec
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.ImplicitSender
import akka.util.Timeout
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import io.demograph.hyparview.HyParViewTopology._
import io.demograph.hyparview.PeerSamplingService.Config
import org.reactivestreams.Publisher
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  *
  */
class HyParViewSpecMultiJvmBootstrap extends HyParViewSpec
class HyParViewSpecMultiJvmNode1 extends HyParViewSpec
class HyParViewSpecMultiJvmNode2 extends HyParViewSpec

class HyParViewSpec extends MultiNodeSpec(HyParViewTopology) with BaseMultiNodeSpec with ImplicitSender with ScalaFutures with IntegrationPatience {
  override def initialParticipants: Int = roles.size

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val mat = ActorMaterializer()(system)

  "HyParView" must {

    "join" in {
      runOn(bootstrap) {
        val service = peerSampler(system)
        enterBarrier("singleton")
        enterBarrier("terminating")
      }
      runOn(node1) {
        enterBarrier("singleton")
        val service = peerSampler(system)
        val bootstrapNode = Await.result(system.actorSelection(bootstrapPath).resolveOne(), 5.seconds)
        service.bootstrapService(bootstrapNode)
        println(take(service.peerPublisher, 3).futureValue)
        enterBarrier("terminating")
      }
      runOn(node2) {
        enterBarrier("singleton")
        val service = peerSampler(system)
        val bootstrapNode = Await.result(system.actorSelection(bootstrapPath).resolveOne(), 5.seconds)
        service.bootstrapService(bootstrapNode)
        println(take(service.peerPublisher, 3).futureValue)
        enterBarrier("terminating")
      }
    }
  }

  def bootstrapPath: ActorPath = node(bootstrap) / "user" / "hyparview"

  def peerSampler(system: ActorSystem, setupConfig: Config ⇒ Config = identity): PeerSamplingService = {
    val peerSamplingService =
      PeerSamplingService(setupConfig(Config(10, None, None, hyParViewConfig())))(system, mat)

    system.registerOnTermination(peerSamplingService.stopService())
    peerSamplingService
  }

  def hyParViewConfig(
                  maxActiveViewSize: Int Refined NonNegative = 4,
                  maxPassiveViewSize: Int Refined NonNegative = 8,
                  activeRWL: Int Refined NonNegative = 3,
                  passiveRWL: Int Refined NonNegative = 2,
                  shuffleRWL: Int Refined NonNegative = 1,
                  shuffleActive: Int Refined NonNegative = 2,
                  shufflePassive: Int Refined NonNegative = 2,
                  shuffleInterval: FiniteDuration = 1.second): HyParViewConfig = {

    HyParViewConfig(maxActiveViewSize, maxPassiveViewSize, activeRWL, passiveRWL, shuffleRWL, shuffleActive, shufflePassive, shuffleInterval)
  }

  def take(publisher: Publisher[ActorRef], n: Int): Future[immutable.Seq[ActorRef]] =
    Source.fromPublisher(publisher).take(n).runWith(Sink.seq)
}