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
import io.demograph.hyparview.HyParViewActor.InitiateShuffle

/**
 *
 */
class HyParViewLoquatSpec extends HyParViewBaseSpec {

  behavior of "HyParView - Loquat changes"

  it should "periodically attempt to proactively promote passive peers to active ones" in {
    val passiveNode = TestProbe()
    val peer = hyparviewActor(passiveView = filledPartialView(passiveNode.ref))

    // We have conflated shuffles and pro-active promotions. There seems to be little benefit to creating a separate
    // scheduled task for the promotion of passive peers
    peer ! InitiateShuffle

    activeView(peer) shouldBe Set(passiveNode.ref)
    passiveView(peer) shouldBe 'empty
  }
}
