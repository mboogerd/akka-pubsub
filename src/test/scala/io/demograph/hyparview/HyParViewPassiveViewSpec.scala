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

import akka.testkit.TestProbe
import eu.timepit.refined.auto._
import io.demograph.hyparview.HyParViewActor.InitiateShuffle
import io.demograph.hyparview.Messages.{ Shuffle, ShuffleReply }

import scala.concurrent.duration._

/**
 *
 */
class HyParViewPassiveViewSpec extends HyParViewBaseSpec {

  behavior of "Passive View Maintenance"

  it should "periodically initiate a shuffle" in {
    val activeNode = TestProbe()

    val config = makeConfig(shuffleInterval = 100.milliseconds)
    val peer = hyparviewActor(config, activeView = unboundedPartialView(activeNode.ref))

    activeNode.expectMsg(Shuffle(Set.empty, config.shuffleRWL, peer))
    activeNode.expectNoMessage(50.milliseconds)
    activeNode.expectMsg(Shuffle(Set.empty, config.shuffleRWL, peer))
  }

  it should "include peers from both passive and active views (up to configured bounds)" in {
    val (ap1, ap2, ap3, ap4, ap5) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val (pp1, pp2, pp3, pp4, pp5) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val activePeers = Set(ap1, ap2, ap3, ap4, ap5).map(_.ref)
    val passivePeers = Set(pp1, pp2, pp3, pp4, pp5).map(_.ref)

    val config = makeConfig(
      shuffleInterval = 20.milliseconds,
      shuffleActive = 5, // not enough peers available, make sure the shuffle target is not included
      shufflePassive = 4 // can choose a subset
    )

    val peer = hyparviewActor(
      config,
      activeView = predictablePartialView(Int.MaxValue, ap1.ref, ap2.ref, ap3.ref, ap4.ref, ap5.ref),
      passiveView = unboundedPartialView(pp1.ref, pp2.ref, pp3.ref, pp4.ref, pp5.ref))

    val msg: Shuffle = ap1.expectMsgPF() { case msg: Shuffle ⇒ msg }
    withClue("4 active and 4 passive peers")(msg.exchangeSet should have size 8)
    withClue("Shuffle destination should not be included")(msg.exchangeSet should contain allElementsOf (activePeers - ap1.ref))
    withClue("1 passive peer could not fit the threshold")((passivePeers -- msg.exchangeSet) should contain oneElementOf passivePeers)

    msg.origin shouldBe peer
    msg.timeToLive shouldBe config.shuffleRWL
  }

  it should "have the Shuffle continue a random walk (TTL decremented) if TTL > 0 and |activeView| > 1" in {
    val initiator = TestProbe()
    val activeNode = TestProbe()

    val peer = hyparviewActor(activeView = filledPartialView(initiator.ref, activeNode.ref))

    peer ! Shuffle(Set.empty, 2, initiator.ref)

    activeNode.expectMsg(Shuffle(Set.empty, 1, initiator.ref))
  }

  it should "handle a Shuffle request if its TTL hits zero (after decrementing)" in {
    val initiator = TestProbe()
    val activeNode = TestProbe()
    val shuffledNode = TestProbe()
    val passiveNode = TestProbe()

    val peer = hyparviewActor(
      activeView = unboundedPartialView(initiator.ref, activeNode.ref),
      passiveView = unboundedPartialView(passiveNode.ref, shuffledNode.ref))

    peer ! Shuffle(Set(shuffledNode.ref), 1, initiator.ref)
    initiator.expectMsg(ShuffleReply(Set(passiveNode.ref)))

    passiveView(peer) should contain only (passiveNode.ref, shuffledNode.ref, initiator.ref)
  }

  it should "handle a Shuffle request if |activeView| <= 1" in {
    val initiator = TestProbe()
    val activeNode = TestProbe()
    val shuffledNode = TestProbe()
    val passiveNode = TestProbe()

    val peer = hyparviewActor(
      activeView = unboundedPartialView(activeNode.ref),
      passiveView = unboundedPartialView(passiveNode.ref, shuffledNode.ref))

    peer ! Shuffle(Set(shuffledNode.ref), 2, initiator.ref)
    initiator.expectMsg(ShuffleReply(Set(passiveNode.ref)))

    passiveView(peer) should contain only (passiveNode.ref, shuffledNode.ref, initiator.ref)
  }

  it should "integrate the exchangeSet included in a ShuffleReply" in {
    val shuffledNode = TestProbe()
    val shuffleTarget = TestProbe()
    val peer = hyparviewActor(activeView = unboundedPartialView(shuffleTarget.ref))

    peer ! InitiateShuffle
    shuffleTarget.expectMsg(Shuffle(Set.empty, makeConfig().shuffleRWL, peer))
    peer ! ShuffleReply(Set(shuffledNode.ref))

    eventually(passiveView(peer) should contain(shuffledNode.ref))
  }
}
