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

package io.demograph.akka

import akka.NotUsed
import akka.actor.{ ActorRef, ActorSelection, ActorSystem, Extension }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.Timeout
import io.demograph.hyparview.PeerSamplingService
import org.log4s._
import org.reactivestreams.Publisher

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class PeerSamplingExtensionImpl(config: PeerSamplingService.Config, contact: ActorSelection)(implicit system: ActorSystem, mat: Materializer) extends Extension {

  private[this] final val log = getLogger
  private implicit val timeout: Timeout = Timeout(5.seconds)

  import system.dispatcher

  private val bootstrapPeerService: Future[PeerSamplingService] = {
    // TODO: Retry mechanism might be a good idea
    val resolve = contact.resolveOne().map(PeerSamplingService(config, _))
    resolve.onComplete {
      case Success(_) ⇒ log.info("Peer Sampling Service started successfully")
      case Failure(t) ⇒ log.error(t)("Peer Sampling Service failed to start")
    }
    resolve
  }

  private[akka] def peerSource: Future[Source[ActorRef, NotUsed]] =
    bootstrapPeerService.map(_.peerPublisher).map(Source.fromPublisher)

  def peerPublisher: Publisher[ActorRef] =
    Source.fromFutureSource(peerSource).runWith(Sink.asPublisher(false))

  system.registerOnTermination(Await.ready(bootstrapPeerService.map(_.stopService()), 5.seconds))
}