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

import akka.actor.PoisonPill
import akka.testkit.TestProbe
import io.demograph.hyparview.Messages.{ Disconnect, Neighbor, NeighborReply }

/**
 *
 */
class HyParViewActiveViewSpec extends HyParViewBaseSpec {

  behavior of "Active View Management"

  it should "promote a passive node to active with priority if the last active one fails" in {
    val candidateNode = TestProbe()
    val failingNode = TestProbe()

    val peer = hyparviewActor(
      activeView = unboundedPartialView(failingNode.ref),
      passiveView = unboundedPartialView(candidateNode.ref))

    failingNode.ref ! PoisonPill

    candidateNode.expectMsg(Neighbor(peer, prio = true))

    withClue("We optimistically add the candidate to the active view") {
      passiveView(peer) shouldBe 'empty
      activeView(peer) should contain only candidateNode.ref
    }
  }

  it should "promote a passive node to active without priority if an active one fails" in {
    val candidateNode = TestProbe()
    val failingNode = TestProbe()
    val otherNode = TestProbe()

    val peer = hyparviewActor(
      activeView = unboundedPartialView(failingNode.ref, otherNode.ref),
      passiveView = unboundedPartialView(candidateNode.ref))

    failingNode.ref ! PoisonPill

    candidateNode.expectMsg(Neighbor(peer, prio = false))

    withClue("We optimistically add the candidate to the active view") {
      passiveView(peer) shouldBe 'empty
      activeView(peer) should contain only (otherNode.ref, candidateNode.ref)
    }
  }

  it should "always accept prioritized neighbour requests" in {
    val activeNode = TestProbe()
    val neighborNode = TestProbe()

    val peer = hyparviewActor(
      activeView = filledPartialView(activeNode.ref),
      passiveView = unboundedPartialView())

    peer ! Neighbor(neighborNode.ref, prio = true)

    neighborNode.expectMsg(NeighborReply(peer, accepted = true))
    activeNode.expectMsg(Disconnect(peer))

    activeView(peer) should contain only neighborNode.ref
  }

  it should "accept non-prioritized neighbour requests when its active view is not full" in {
    val activeNode = TestProbe()
    val neighborNode = TestProbe()

    val peer = hyparviewActor(
      activeView = unboundedPartialView(activeNode.ref),
      passiveView = unboundedPartialView())

    peer ! Neighbor(neighborNode.ref, prio = false)

    neighborNode.expectMsg(NeighborReply(peer, accepted = true))
    activeNode.expectNoMessage(noMsgTimeout)

    activeView(peer) should contain only (neighborNode.ref, activeNode.ref)
  }

  it should "reject neighbour requests if the active view is full and no priority is given to the request" in {
    val activeNode = TestProbe()
    val neighborNode = TestProbe()

    val peer = hyparviewActor(
      activeView = filledPartialView(activeNode.ref),
      passiveView = unboundedPartialView())

    peer ! Neighbor(neighborNode.ref, prio = false)

    neighborNode.expectMsg(NeighborReply(peer, accepted = false))
    activeNode.expectNoMessage(noMsgTimeout)

    activeView(peer) should contain only activeNode.ref
  }

  it should "a rejected neighbour request should result in demotion of active to passive node, and a re-attempt to promote _another_ node" in {
    val rejectedNeighbour = TestProbe()
    val activeNode = TestProbe()
    val candidateNode = TestProbe()

    val peer = hyparviewActor(
      activeView = filledPartialView(rejectedNeighbour.ref, activeNode.ref),
      passiveView = unboundedPartialView(candidateNode.ref))

    peer ! NeighborReply(rejectedNeighbour.ref, accepted = false)
    candidateNode.expectMsg(Neighbor(peer, prio = false))

    activeView(peer) shouldBe Set(activeNode.ref, candidateNode.ref)
    passiveView(peer) shouldBe Set(rejectedNeighbour.ref)
  }
}
