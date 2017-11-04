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

import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.stream.scaladsl.SourceQueue
import eu.timepit.refined.api.Refined
import io.demograph.hyparview.HyParViewActor.{ InitiateShuffle, Inspect }
import io.demograph.hyparview.Messages._
import eu.timepit.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative

/**
 *
 */
object HyParViewActor {

  private[hyparview] case object Inspect

  def props(config: HyParViewConfig, contact: ActorRef, queue: SourceQueue[ActorRef]): Props =
    props(config, contact, queue, PartialView.empty(config.maxPassiveViewSize.value))

  def props(config: HyParViewConfig, contact: ActorRef, queue: SourceQueue[ActorRef], passiveView: PartialView[ActorRef]): Props =
    props(config, contact, queue, PartialView.empty(config.maxActiveViewSize.value) + contact, passiveView)

  private[hyparview] def props(
    config: HyParViewConfig,
    contact: ActorRef,
    queue: SourceQueue[ActorRef],
    activeView: PartialView[ActorRef],
    passiveView: PartialView[ActorRef]): Props =
    Props(new HyParViewActor(config, contact, queue, activeView, passiveView))

  case object InitiateShuffle
}

class HyParViewActor private (
  config: HyParViewConfig,
  contact: ActorRef,
  queue: SourceQueue[ActorRef],
  initActiveView: PartialView[ActorRef],
  initPassiveView: PartialView[ActorRef]) extends Actor {

  import config._
  import context.dispatcher

  var activeView: PartialView[ActorRef] = initActiveView
  var passiveView: PartialView[ActorRef] = initPassiveView

  initialize()

  override def receive: Receive = {
    case InitiateShuffle ⇒ initiateShuffle()
    case Shuffle(exchangeSet, ttl, origin) ⇒ handleShuffle(exchangeSet, ttl, origin)
    case Neighbor(peer, prio) ⇒ handleNeighbourRequest(peer, prio)
    case NeighborReply(peer, accepted) ⇒ handleNeighbourReply(peer, accepted)
    case Join(newNode) ⇒ handleJoin(newNode)
    case ForwardJoin(newNode, ttl, forwarder) ⇒ handleForwardJoin(newNode, ttl, forwarder)
    case Disconnect(peer) ⇒ handleDisconnect(peer)
    case Terminated(peer) ⇒ handleDisconnect(peer)
    case Inspect ⇒ sender ! (activeView, passiveView)
  }

  def shuffling(shuffleRequest: Shuffle): Receive = receive orElse {
    case ShuffleReply(exchangeSet) ⇒ handleShuffleReply(shuffleRequest, exchangeSet)
  }

  def initiateShuffle(): Unit = {
    // TODO: Consider using round-robin instead
    val shuffleTarget = activeView.randomElement
    val activePart = (activeView - shuffleTarget).sample(shuffleActive.value)
    val passivePart = passiveView.sample(shufflePassive.value)
    val shuffleRequest = Shuffle(activePart ++ passivePart, shuffleRWL, self)
    shuffleTarget ! shuffleRequest

    context.become(shuffling(shuffleRequest))
  }

  def handleShuffle(exchangeSet: Set[ActorRef], ttl: Int Refined NonNegative, origin: ActorRef): Unit = {
    publishDiscovery(exchangeSet + origin)
    if (ttl.value == 1 || activeView.size <= 1) {
      // construct a response with candidates from our passive view
      val sample = (passiveView -- exchangeSet - origin).sample(exchangeSet.size + 1)
      origin ! ShuffleReply(sample)

      // incorporate the exchanged identifiers in our passive view
      passiveView = passiveView.mergeRespectingCapacity(exchangeSet + origin, prioritizedRemoval = sample)
    } else {
      // forward the request
      val shuffleTarget = (activeView - origin).randomElement
      refineV[NonNegative](ttl.value - 1).foreach { newTTL ⇒
        shuffleTarget ! Shuffle(exchangeSet, newTTL, origin)
      }
    }
  }

  def handleShuffleReply(request: Shuffle, exchangeSet: Set[ActorRef]): Unit = {
    publishDiscovery(exchangeSet)
    passiveView = passiveView.mergeRespectingCapacity(exchangeSet, prioritizedRemoval = request.exchangeSet)
    context.become(receive)
  }

  private def handleJoin(newNode: ActorRef): Unit = {
    publishDiscovery(newNode)
    if (!activeView.contains(newNode) && activeView.isFull) dropRandomElementFromActiveView()
    activeView.foreach(_ ! ForwardJoin(newNode, activeRWL, self))
    promotePeer(newNode)
  }

  def handleForwardJoin(newNode: ActorRef, ttl: Int Refined NonNegative, forwarder: ActorRef): Unit = {
    publishDiscovery(newNode)
    if (ttl.value == 0 || activeView.isEmpty) {
      addNodeToActiveView(newNode)
    } else {
      if (ttl == passiveRWL) addNodeToPassiveView(newNode)

      val candidates = activeView - forwarder
      if (candidates.nonEmpty) {
        val destination = candidates.randomElement
        refineV[NonNegative](ttl.value - 1).foreach { newTTL ⇒
          destination ! ForwardJoin(newNode, newTTL, self)
        }
      }
    }
  }

  def handleDisconnect(peer: ActorRef): Unit = {
    if (activeView.contains(peer)) {
      activeView -= peer
      context.unwatch(peer)
    }

    if (passiveView.nonEmpty) {
      val candidate = passiveView.randomElement
      candidate ! Neighbor(self, prio = activeView.isEmpty)
      promotePeer(candidate)
      passiveView -= candidate
    }
  }

  def handleNeighbourRequest(peer: ActorRef, prio: Boolean): Unit = {
    publishDiscovery(peer)

    if (prio && activeView.isFull) dropRandomElementFromActiveView()

    if (activeView.isFull) {
      peer ! NeighborReply(self, accepted = false)
    } else {
      peer ! NeighborReply(self, accepted = true)
      promotePeer(peer)
      passiveView -= peer
    }
  }

  def handleNeighbourReply(peer: ActorRef, accepted: Boolean): Unit = {
    if (!accepted) {
      handleDisconnect(peer)
      passiveView += peer
    }
  }

  def addNodeToActiveView(newNode: ActorRef): Unit = {
    if (newNode != self && !activeView.contains(newNode)) {
      if (activeView.isFull) dropRandomElementFromActiveView()
      promotePeer(newNode)
    }
  }

  def addNodeToPassiveView(newNode: ActorRef): Unit = {
    if (newNode != self && !activeView.contains(newNode) && !passiveView.contains(newNode)) {
      if (passiveView.isFull) {
        val node = passiveView.randomElement
        passiveView -= node
      }
      passiveView += newNode
    }
  }

  def dropRandomElementFromActiveView(): Unit = {
    val node = activeView.randomElement
    node ! Disconnect(self)
    context.unwatch(node)
    activeView -= node
    passiveView += node
  }

  def promotePeer(peer: ActorRef): Unit = {
    activeView += peer
    context.watch(peer)
  }

  def publishDiscovery(actorRef: ActorRef): Unit = publishDiscovery(Set(actorRef))

  def publishDiscovery(actorRefs: Set[ActorRef]): Unit = (actorRefs -- passiveView -- activeView).foreach(queue.offer)

  def initialize(): Unit = {
    // The first ActorRef to be published should be the initial point of contact
    queue.offer(contact)
    // Attempt to join using the contact node
    contact ! Join(self)
    // Watch everything already in the active view
    activeView.foreach(context.watch)
    // Schedule a periodic shuffling process
    context.system.scheduler.schedule(shuffleInterval, shuffleInterval, self, InitiateShuffle)
  }
}
