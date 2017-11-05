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

import akka.actor.{ ActorPath, ActorRef, ActorSystem, Extension }
import akka.stream.Materializer
import akka.util.Timeout
import io.demograph.hyparview.PeerSamplingService
import org.log4s._
import org.reactivestreams.Publisher

import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class PeerSamplingExtensionImpl(config: PeerSamplingService.Config)(implicit system: ActorSystem, mat: Materializer) extends Extension {

  private[this] final val log = getLogger
  private implicit val timeout: Timeout = Timeout(5.seconds)

  import system.dispatcher

  private val peerSampler: PeerSamplingService = PeerSamplingService(config)

  def bootstrapService(actorPath: ActorPath): Unit = {
    val resolve = system.actorSelection(actorPath).resolveOne()

    resolve.onComplete {
      case Success(bootstrapNode) ⇒
        peerSampler.bootstrapService(bootstrapNode)
        log.info(s"Peer Sampling Service initiated join using bootstrap node $bootstrapNode")
      case Failure(t) ⇒
        log.error(t)("Peer Sampling Service failed to start because the bootstrap node could not be resolved")
    }
  }

  def peerPublisher: Publisher[ActorRef] = peerSampler.peerPublisher

  system.registerOnTermination(peerSampler.stopService())
}