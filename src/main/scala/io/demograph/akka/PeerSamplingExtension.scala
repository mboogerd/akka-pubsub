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

import akka.actor.{ ActorPath, ExtendedActorSystem, ExtensionId, ExtensionIdProvider }
import akka.stream.ActorMaterializer
import akka.util.Timeout
import io.demograph.hyparview.PeerSamplingService.Config
import pureconfig._
import org.log4s._
import pureconfig._

import scala.concurrent.duration._
import scala.util.Try
import eu.timepit.refined.pureconfig._
/**
 *
 */
object PeerSamplingExtension extends ExtensionId[PeerSamplingExtensionImpl] with ExtensionIdProvider {
  private[this] val log = getLogger
  private implicit val timeout: Timeout = Timeout(5.seconds)

  private implicit val actorPathReader: ConfigReader[ActorPath] =
    ConfigReader.fromStringTry(s ⇒ Try(ActorPath.fromString(s)))

  override val lookup: ExtensionId[PeerSamplingExtensionImpl] = PeerSamplingExtension

  override def createExtension(system: ExtendedActorSystem): PeerSamplingExtensionImpl = {
    val config = loadConfigOrThrow[Config](system.settings.config, "peer-sampling")
    val mat = ActorMaterializer()(system)
    val impl = new PeerSamplingExtensionImpl(config)(system, mat)

    config.contact match {
      case Some(bootstrap) ⇒ impl.bootstrapService(bootstrap)
      case None ⇒
        log.info("Peer Sampling Extension started but no bootstrap node is configured. Join should be performed manually")
    }

    impl
  }
}
