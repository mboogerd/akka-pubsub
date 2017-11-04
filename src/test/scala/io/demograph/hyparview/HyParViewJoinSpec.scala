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

import akka.actor.ActorSystem
import akka.testkit.{ TestKit, TestProbe }
import eu.timepit.refined.api.Refined
import io.demograph.hyparview.HyParViewActor.Inspect
import io.demograph.hyparview.Messages.{ Disconnect, ForwardJoin, Join }
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative

/**
 *
 */
class HyParViewJoinSpec extends TestKit(ActorSystem()) with HyParViewSpec {

  behavior of "Join"

  it should "allow inspections" in {
    val probe = TestProbe()
    probe.send(hyparviewActor(), Inspect)
    probe.expectMsg((PartialView.empty(0), PartialView.empty(0)))
  }

  it should "be sent to the Contact node upon initialization" in {
    val probe = TestProbe()
    val peer = hyparviewActor(contact = probe.ref)
    probe.expectMsg(Join(peer))
  }

  it should "forward a received join to the active-view, excluding the new-node" in {
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val probe3 = TestProbe()

    val peer = hyparviewActor(
      activeView = unboundedPartialView(probe1.ref, probe2.ref),
      passiveView = unboundedPartialView(probe3.ref))

    peer ! Join(probe3.ref)

    probe1.expectMsg(ForwardJoin(probe3.ref, makeConfig().activeRWL, peer))
    probe2.expectMsg(ForwardJoin(probe3.ref, makeConfig().activeRWL, peer))

    probe3.expectNoMessage(noMsgTimeout)
  }

  it should "make room for a joined node if no room is left" in {
    val actvProbe1 = TestProbe()
    val actvProbe2 = TestProbe()
    val pasvProbe = TestProbe()
    val activeProbes = Map(actvProbe1.ref → actvProbe1, actvProbe2.ref → actvProbe2)

    val newNode = TestProbe().ref
    val peer = hyparviewActor(
      activeView = filledPartialView(activeProbes.keys.toSeq: _*),
      passiveView = unboundedPartialView(pasvProbe.ref))

    peer ! Join(newNode)

    pasvProbe.expectNoMessage(noMsgTimeout)

    val refs = activeView(peer)
    refs should have size 2
    refs should contain(newNode)
    refs should contain oneOf (actvProbe1.ref, actvProbe2.ref)

    val disconnected = (activeProbes -- refs).head._2
    val stillActive = activeProbes(refs.head)

    disconnected.expectMsg(Disconnect(peer))
    stillActive.expectMsg(ForwardJoin(newNode, makeConfig().activeRWL, peer))

    passiveView(peer) should contain only (pasvProbe.ref, disconnected.ref)
  }

  it should "add a node of a join-forward to the active view once TTL hits zero" in {
    val probe = TestProbe()
    val newNode = TestProbe()
    val peer = hyparviewActor(activeView = PartialView(1, Set(probe.ref)))
    peer ! ForwardJoin(newNode.ref, 0, newNode.ref)

    activeView(peer) should contain only newNode.ref
  }

  it should "add a node of a join-forward to the active view if it is empty" in {
    val newNode = TestProbe()
    val peer = hyparviewActor(activeView = PartialView(1, Set.empty))
    peer ! ForwardJoin(newNode.ref, 1, newNode.ref)

    activeView(peer) should contain only newNode.ref
  }

  it should "continue forwarding a join until TTL is zero" in {
    val relayNode = TestProbe().ref
    val existingNode = TestProbe()
    val newNode1 = TestProbe()
    val newNode2 = TestProbe()
    val passiveRWL: Int Refined NonNegative = 3
    val peer = hyparviewActor(
      activeView = unboundedPartialView(existingNode.ref),
      passiveView = unboundedPartialView(),
      config = makeConfig().copy(passiveRWL = passiveRWL))

    withClue("not include the node of a forwarded join in the passive view (in general)") {
      peer ! ForwardJoin(newNode1.ref, 4, relayNode)
      existingNode.expectMsg(ForwardJoin(newNode1.ref, passiveRWL, peer))
      passiveView(peer) shouldBe 'empty
    }

    withClue("include the node of a join-forward in the passive view once the random walk reaches the configured threshold") {
      peer ! ForwardJoin(newNode2.ref, passiveRWL, relayNode)
      existingNode.expectMsg(ForwardJoin(newNode2.ref, 2, peer))
      passiveView(peer) should contain only newNode2.ref
    }

    activeView(peer) should contain only existingNode.ref
  }

}
