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

import com.typesafe.config.Config
import pureconfig._
import pureconfig.error.ConfigReaderFailures

import scala.concurrent.duration.FiniteDuration

/**
 * TODO: Refactor some fields to be positive bytes instead (refinement types)
 */
case class HyParViewConfig(
  maxActiveViewSize: Int,
  maxPassiveViewSize: Int,
  activeRWL: Int,
  passiveRWL: Int,
  shuffleRWL: Int,
  shuffleActive: Int,
  shufflePassive: Int,
  shuffleInterval: FiniteDuration) {

  assert(maxActiveViewSize >= 0, "maxActiveViewSize must be a non-negative integer")
  assert(maxPassiveViewSize >= 0, "maxPassiveViewSize must be a non-negative integer")
  assert(activeRWL >= 0, "activeRWL must be a non-negative integer")
  assert(passiveRWL >= 0, "passiveRWL must be a non-negative integer")
  assert(shuffleRWL >= 0, "shuffleRWL must be a non-negative integer")
  assert(shuffleActive >= 0, "shuffleActive must be a non-negative integer")
  assert(shufflePassive >= 0, "shuffleActive must be a non-negative integer")

  assert(shuffleActive >= shufflePassive, "shuffleActive should be greater than or equal to shufflePassive")
}

object HyParViewConfig {
  def apply(config: Config): Either[ConfigReaderFailures, HyParViewConfig] =
    loadConfig[HyParViewConfig](config.getConfig("hyparview"))
}
