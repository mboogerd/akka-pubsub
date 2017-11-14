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

import akka.actor.{ ActorPath, ActorRef, ActorSystem, PoisonPill }
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.{ Materializer, OverflowStrategy }
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import io.demograph.hyparview.HyParViewActor.InitiateJoin
import io.demograph.hyparview.PeerSamplingService.Config
import io.demograph.peersampling.{ PeerSamplingService ⇒ PSS }
import org.log4s._
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
/**
 *
 */
class PeerSamplingService(config: Config)(implicit system: ActorSystem, mat: Materializer) extends PSS[ActorRef] {

  private[this] val log = getLogger

  private val (queue, actorPublisher) = Source.queue[ActorRef](20, OverflowStrategy.dropHead)
    .toMat(Sink.asPublisher(fanout = true))(Keep.both)
    .run()

  override def peerPublisher: Publisher[ActorRef] = {
    Source.fromPublisher(actorPublisher)
      .buffer(config.bufferSize.value, OverflowStrategy.dropHead)
      .runWith(Sink.asPublisher(false))
  }

  def bootstrapService(bootstrapNode: ActorRef): Unit = {
    hyParViewActor ! InitiateJoin(bootstrapNode)
  }

  def stopService(): Unit = {
    hyParViewActor ! PoisonPill
    queue.complete()
  }

  private[hyparview] val hyParViewActor: ActorRef = {
    val actorName = config.name.getOrElse("hyparview")
    system.actorOf(HyParViewActor.props(config.hyparview, queue), actorName)
  }

  // This is a bit of a hack. Make sure there is always a live Subscriber, so that `peerPublisher` doesn't cancel when
  // all Subscribers cancel (such that new Subscribers can still subscribe)
  val _ = peerPublisher.subscribe(new Subscriber[ActorRef] {
    override def onError(t: Throwable): Unit = log.error(t)("Peer Publisher Failed")
    override def onComplete(): Unit = log.info("Peer Publisher Completed")
    override def onNext(t: ActorRef): Unit = {}
    override def onSubscribe(s: Subscription): Unit = {} // never signal demand, nor cancel
  })
}

object PeerSamplingService {

  case class Config(
    bufferSize: Int Refined NonNegative,
    contact: Option[ActorPath],
    name: Option[String],
    hyparview: HyParViewConfig)

  def apply(config: Config)(implicit system: ActorSystem, mat: Materializer): PeerSamplingService =
    new PeerSamplingService(config)
}