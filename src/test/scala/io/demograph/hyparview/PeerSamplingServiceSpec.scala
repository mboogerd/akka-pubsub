/*
 * Copyright 2017 Merlijn Boogerd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.demograph.hyparview

import akka.actor.ActorRef
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Materializer }
import akka.testkit.TestProbe
import eu.timepit.refined.auto._
import io.demograph.hyparview.HyParViewActor.{ InitiateJoin, InitiateShuffle }
import io.demograph.hyparview.Messages._
import io.demograph.hyparview.PeerSamplingService.Config
import org.reactivestreams.Publisher

import scala.collection.immutable
import scala.concurrent.Future
/**
 *
 */
class PeerSamplingServiceSpec extends HyParViewBaseSpec {

  behavior of "PeerSamplingService"

  override implicit val mat: Materializer = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(1, 1))

  it should "include the contact as the first produced ActorRef" in withService() { peerSampler =>
    val contact = TestProbe().ref
    val peerPublisher = peerSampler.peerPublisher
    peerSampler.hyParViewActor ! InitiateJoin(contact)
    first(peerPublisher).futureValue shouldBe contact
  }

  it should "include actors discovered through Join requests" in withService() { peerSampler =>
    val newNode = TestProbe().ref

    val monitor = first(peerSampler.peerPublisher)
    peerSampler.hyParViewActor ! Join(newNode)

    monitor.futureValue shouldBe newNode
  }

  it should "include actors discovered through ForwardJoin requests" in withService() { peerSampler =>
    val newNode = TestProbe().ref
    val monitor = first(peerSampler.peerPublisher)

    peerSampler.hyParViewActor ! ForwardJoin(newNode, Int.MaxValue, newNode)

    monitor.futureValue shouldBe newNode
  }

  it should "include actors discovered through Neighbour requests" in withService() { peerSampler =>
    val newNode = TestProbe().ref
    val monitor = first(peerSampler.peerPublisher)

    peerSampler.hyParViewActor ! Neighbor(newNode, prio = false)

    monitor.futureValue shouldBe newNode
  }

  it should "include actors discovered through Shuffle requests" in withService() { peerSampler =>
    val newNode = TestProbe().ref
    val (s1, s2) = (TestProbe().ref, TestProbe().ref)
    val monitor = take(peerSampler.peerPublisher, 3)

    peerSampler.hyParViewActor ! Shuffle(Set(s1, s2), Int.MaxValue, newNode)

    monitor.futureValue should contain theSameElementsAs Set(newNode, s1, s2)
  }

  it should "include actors discovered through ShuffleReply requests" in withService() { peerSampler =>
    val contact = TestProbe().ref
    val (s1, s2) = (TestProbe().ref, TestProbe().ref)
    peerSampler.hyParViewActor ! Neighbor(contact, prio = true) // make sure the active view is filled

    peerSampler.hyParViewActor ! InitiateShuffle
    val monitor = take(peerSampler.peerPublisher, 2)
    peerSampler.hyParViewActor ! ShuffleReply(Set(s1, s2))

    monitor.futureValue should contain theSameElementsAs Set(s1, s2)
  }

  it should "stream elements towards multiple subscribers" in withService() { peerSampler =>
    val peers1 = first(peerSampler.peerPublisher)
    val peers2 = first(peerSampler.peerPublisher)

    val newNode = TestProbe().ref
    peerSampler.hyParViewActor ! Join(newNode)

    peers1.futureValue shouldBe newNode
    peers2.futureValue shouldBe newNode
  }

  it should "drop old elements if more are discovered than consumed" in withService(setupConfig = _.copy(bufferSize = 1)) { peerSampler =>
    val forgottenNodes = (1 to 10).map(_ ⇒ TestProbe().ref)
    val finalNode = TestProbe().ref
    val peerPublisher = peerSampler.peerPublisher

    (forgottenNodes :+ finalNode).foreach(node ⇒ peerSampler.hyParViewActor ! Join(node))

    Thread.sleep(200)

    withClue("With bufferSize == 1 only the last pushed element is remembered") {
      takeNow(peerPublisher)(1) shouldBe Seq(finalNode)
    }
  }

  it should "allow Stream subscription even after all previous Subscribers stopped" in withService() { peerSampler =>
    withClue("The first subscriber subscribes and then stops") {
      val firstSubscriber = peerSampler.peerPublisher

      val newNode = TestProbe().ref
      peerSampler.hyParViewActor ! Join(newNode)

      first(firstSubscriber).futureValue
    }

    withClue("The second subscriber then follows and should still be served") {
      val secondSubscriber = first(peerSampler.peerPublisher)

      val newNode = TestProbe().ref
      peerSampler.hyParViewActor ! Join(newNode)

      secondSubscriber.futureValue shouldBe newNode
    }
  }

  it should "allow subscriptions to consume at different rates" in withService(setupConfig = _.copy(bufferSize = 2)) { peerSampler =>
    // We use buffer-size 2, as using buffer-size 1 drops elements very fast (and therefore will fail the test)
    val forgottenNodes = (1 to 10).map(_ ⇒ TestProbe().ref)
    val (finalNode1, finalNode2) = (TestProbe().ref, TestProbe().ref)
    val pp1 = peerSampler.peerPublisher
    val pp2 = peerSampler.peerPublisher
    val observeAll = take(pp1, 12)

    (forgottenNodes :+ finalNode1 :+ finalNode2).foreach(node ⇒ peerSampler.hyParViewActor ! Join(node))

    withClue("The first consumer consumes all produced elements") {
      observeAll.futureValue
    }
    withClue("The second consumer only starts consuming after the first one completes, seeing only the last value") {
      take(pp2, 2).futureValue shouldBe Seq(finalNode1, finalNode2)
    }
  }

  ignore should "shutdown gracefully" in withService() { peerSampler =>
    // TODO: This command should not make subscribers fail (with AbruptTerminationException), they should complete gracefully!
    service().stopService()
  }

  /* Test Utility Methods */

  def service(contact: ActorRef = TestProbe().ref, setupConfig: Config ⇒ Config = identity): PeerSamplingService = {
    val peerSamplingService = PeerSamplingService(setupConfig(Config(10, Some(contact.path), None, makeConfig())))
    system.registerOnTermination(peerSamplingService.stopService())
    peerSamplingService
  }

  def withService(contact: ActorRef = TestProbe().ref, setupConfig: Config ⇒ Config = identity)(test: PeerSamplingService ⇒ Any): Unit = {
    val peerSamplingService = PeerSamplingService(setupConfig(Config(10, Some(contact.path), None, makeConfig())))
    test(peerSamplingService)
    peerSamplingService.stopService()
  }

  def takeNow(publisher: Publisher[ActorRef])(n: Int): Seq[ActorRef] = take(publisher, n).futureValue

  def take(publisher: Publisher[ActorRef], n: Int): Future[immutable.Seq[ActorRef]] =
    Source.fromPublisher(publisher).take(n).runWith(Sink.seq)

  def first(publisher: Publisher[ActorRef]): Future[ActorRef] =
    Source.fromPublisher(publisher).runWith(Sink.head)
}
