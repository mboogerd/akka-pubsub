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

package io.demograph.vicinity

import akka.actor.Actor

/**
 * The Vicinity Actor has some of the same responsibilities as the HyParViewActor
 * - Shuffle (with a different selection method; should include the node profiles / descriptors)
 * - ShuffleReply (with a different selection method; should include the node profiles / descriptors)
 * - Neighbour / NeighbourReply (perhaps not required explicitly?)
 * - Join (profile-update)
 *
 * but no:
 * - ForwardJoin?
 * - Disconnect?
 *
 *
 * Inputs:
 * - Config
 * - Profile
 * - Selection function (maximizes peers for utility with the given profile)
 *
 * Improvements
 * - Round-robin neighbour selection (could be good for HyParView too) ++
 * - Exclude received Shuffle node descriptors from a ShuffleReply -+
 * - Combine views (union of random (hyparview) and structural (vicinity) view, to select new neighbours from)
 * - Combine views for shuffle (draw the best selection for a shuffle target from the union as well)
 *
 * FixPoint variables (requires idempotent derivations)
 * ViewStruct = ViewRand zip ViewStruct map (_ union _) map (select(peerProfile, selectionSize))
 * - on timer: update
 * - on update ViewRand: update?
 * - on Shuffle/Reply: ViewStruct = select(peerProfile, selectionSize)(ViewStruct union DiscoveredPeers union ViewRand)
 *
 * Alternatively:
 * There is a tradeoff between the amount of randomness that can be delivered by the PSS and the actual requirements
 * by the layers using the PSS. Ideally, the PSS has a sample ready each time the upper layer requires it, and performs
 * minimal buffering to accomplish that. This would imply that upper layers need their demand to be transfered to the
 * PSS and that on its turn needs to adjust its Shuffling behavior in line with the demand. However, we do need a
 * minimal amount of shuffling, for the sake of connectedness and recency of the entries available in the passive view.
 *
 * Possibly a good question: What is worse, not having enough randomness, or having too much? Not enough implies that
 * the cluster-seeking functionality of Vicinity performs suboptimally, and in fact may not converge. This lack of
 * convergence requires a systematic lack of randomness however, not an incidental one.
 *
 * OverflowingQueue with take and peek
 *
 */
class VicinityActor(config: VicinityConfig) extends Actor {
  override def receive: Receive = ???
}
