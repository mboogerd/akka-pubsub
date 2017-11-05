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

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration._
/**
 *
 */
abstract class ActorTestSpec extends TestKit(ActorSystem()) with TestSpec with BeforeAndAfterAll {

  implicit val mat: Materializer = ActorMaterializer()

  implicit val timeout: Timeout = Timeout(1.second)

  val noMsgTimeout: FiniteDuration = 30.milliseconds

  override protected def afterAll(): Unit = system.terminate()
}
