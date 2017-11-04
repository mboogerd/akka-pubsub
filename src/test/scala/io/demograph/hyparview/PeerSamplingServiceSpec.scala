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

import akka.actor.{ ActorRef, ActorSystem }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Materializer }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.{ TestKit, TestProbe }
import io.demograph.hyparview.HyParViewActor.InitiateShuffle
import io.demograph.hyparview.Messages._
import io.demograph.hyparview.PeerSamplingService.Config
import org.reactivestreams.Publisher

import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import eu.timepit.refined.auto._
/**
 *
 */
class PeerSamplingServiceSpec extends TestKit(ActorSystem()) with HyParViewSpec {

  behavior of "PeerSamplingService"

  override implicit val mat: Materializer = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(1, 1))

  it should "include the contact as the first produced ActorRef" in {
    val contact = TestProbe().ref
    val peerPublisher = service(contact).peerPublisher
    first(peerPublisher).futureValue shouldBe contact
  }

  it should "include actors discovered through Join requests" in {
    val pss = service()
    val newNode = TestProbe().ref

    val monitor = first(pss.peerPublisher)
    pss.hyParViewActor ! Join(newNode)

    monitor.futureValue shouldBe newNode
  }

  it should "include actors discovered through ForwardJoin requests" in {
    val pss = service()
    val newNode = TestProbe().ref
    val monitor = first(pss.peerPublisher)

    pss.hyParViewActor ! ForwardJoin(newNode, Int.MaxValue, newNode)

    monitor.futureValue shouldBe newNode
  }

  it should "include actors discovered through Neighbour requests" in {
    val pss = service()
    val newNode = TestProbe().ref
    val monitor = first(pss.peerPublisher)

    pss.hyParViewActor ! Neighbor(newNode, prio = false)

    monitor.futureValue shouldBe newNode
  }

  it should "include actors discovered through Shuffle requests" in {
    val contact = TestProbe().ref
    val pss = service(contact)
    val newNode = TestProbe().ref
    val (s1, s2) = (TestProbe().ref, TestProbe().ref)
    val monitor = take(pss.peerPublisher, 3)

    pss.hyParViewActor ! Shuffle(Set(s1, s2), Int.MaxValue, newNode)

    monitor.futureValue should contain theSameElementsAs Set(newNode, s1, s2)
  }

  it should "include actors discovered through ShuffleReply requests" in {
    val contact = TestProbe().ref
    val pss = service(contact)
    val (s1, s2) = (TestProbe().ref, TestProbe().ref)
    val monitor = take(pss.peerPublisher, 2)

    pss.hyParViewActor ! InitiateShuffle
    pss.hyParViewActor ! ShuffleReply(Set(s1, s2))

    monitor.futureValue should contain theSameElementsAs Set(s1, s2)
  }

  it should "stream elements towards multiple subscribers" in {
    val pss = service()

    val peers1 = first(pss.peerPublisher)
    val peers2 = first(pss.peerPublisher)

    val newNode = TestProbe().ref
    pss.hyParViewActor ! Join(newNode)

    peers1.futureValue shouldBe newNode
    peers2.futureValue shouldBe newNode
  }

  it should "drop old elements if more are discovered than consumed" in {
    val pss = service(setupConfig = _.copy(bufferSize = 1))
    val forgottenNodes = (1 to 10).map(_ ⇒ TestProbe().ref)
    val finalNode = TestProbe().ref
    val peerPublisher = pss.peerPublisher

    (forgottenNodes :+ finalNode).foreach(node ⇒ pss.hyParViewActor ! Join(node))

    Thread.sleep(200)

    withClue("With bufferSize == 1 only the last pushed element is remembered") {
      takeNow(peerPublisher)(1) shouldBe Seq(finalNode)
    }
  }

  it should "allow Stream subscription even after all previous Subscribers stopped" in {
    val pss = service()

    withClue("The first subscriber subscribes and then stops") {
      val firstSubscriber = pss.peerPublisher

      val newNode = TestProbe().ref
      pss.hyParViewActor ! Join(newNode)

      first(firstSubscriber).futureValue
    }

    withClue("The second subscriber then follows and should still be served") {
      val secondSubscriber = first(pss.peerPublisher)

      val newNode = TestProbe().ref
      pss.hyParViewActor ! Join(newNode)

      secondSubscriber.futureValue shouldBe newNode
    }
  }

  it should "allow subscriptions to consume at different rates" in {
    val pss = service(setupConfig = _.copy(bufferSize = 1))
    val forgottenNodes = (1 to 10).map(_ ⇒ TestProbe().ref)
    val finalNode = TestProbe().ref
    val pp1 = pss.peerPublisher
    val pp2 = pss.peerPublisher
    val takeAll = take(pp1, 11)

    (forgottenNodes :+ finalNode).foreach(node ⇒ pss.hyParViewActor ! Join(node))

    withClue("The first consumer consumes all produced elements") {
      takeAll.futureValue
    }
    withClue("The second consumer only starts consuming after the first one completes, seeing only the last value") {
      takeNow(pp2)(1) shouldBe Seq(finalNode)
    }
  }

  ignore should "shutdown gracefully" in {
    // TODO: This command should not make subscribers fail (with AbruptTerminationException), they should complete gracefully!
    service().stopService()
  }

  /* Test Utility Methods */

  def service(contact: ActorRef = TestProbe().ref, setupConfig: Config ⇒ Config = identity): PeerSamplingService = {
    val peerSamplingService = PeerSamplingService(setupConfig(Config(10, contact.path, makeConfig())), contact)
    system.registerOnTermination(peerSamplingService.stopService())
    peerSamplingService
  }

  def takeNow(publisher: Publisher[ActorRef])(n: Int): Seq[ActorRef] = take(publisher, n).futureValue

  def take(publisher: Publisher[ActorRef], n: Int): Future[immutable.Seq[ActorRef]] =
    Source.fromPublisher(publisher).take(n).runWith(Sink.seq)

  def first(publisher: Publisher[ActorRef]): Future[ActorRef] =
    Source.fromPublisher(publisher).runWith(Sink.head)
}
