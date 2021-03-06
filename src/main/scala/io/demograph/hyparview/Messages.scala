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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative

/**
 *
 */
object Messages {

  case class Join(newNode: ActorRef)

  case class ForwardJoin(newNode: ActorRef, timeToLive: Int Refined NonNegative, forwarder: ActorRef)

  case class Shuffle(exchangeSet: Set[ActorRef], timeToLive: Int Refined NonNegative, origin: ActorRef)

  case class ShuffleReply(exchangeSet: Set[ActorRef])

  case class Neighbor(peer: ActorRef, prio: Boolean)

  case class NeighborReply(peer: ActorRef, accepted: Boolean)

  case class Disconnect(peer: ActorRef)
}
