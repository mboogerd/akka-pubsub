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

package io.demograph

import _root_.akka.stream.scaladsl._
import _root_.akka.stream.testkit.scaladsl.TestSink
import _root_.akka.stream.testkit.TestSubscriber
import _root_.akka.stream._
import _root_.akka.NotUsed

import scala.concurrent.Future
import scala.concurrent.duration._

class Experiment extends ActorTestSpec {

  import system.dispatcher

  // Some table with indexed strings
  var dbTable: Seq[(Long, String)] = Seq.empty
  // Simulates a database query on `dbTable`
  def query(offset: Long): Future[Seq[(Long, String)]] = Future.successful(dbTable.takeWhile(_._1 > offset))

  private def streamingQuery(offset: Long): Source[String, NotUsed] = Source
    .unfoldAsync(offset) { lastOffset =>
      query(lastOffset).map(result =>
        Some(result.foldLeft((lastOffset, Seq.empty[String])) {
          case ((maxOffset, seq), (newOffset, s)) => (maxOffset.max(newOffset), s +: seq)
        }))
    }
    // Throttle on empty sequences, don't throttle (or throttle less) on non-empty results
    .log("pre-throttle")
    .throttle(1, 200.milli, 1, s => if (s.isEmpty) 1 else 0, ThrottleMode.Shaping)
    .mapConcat(_.toList)

  it should "quick manual test" in {
    val probe: TestSubscriber.Probe[String] =
      streamingQuery(0).log("elements-produced").runWith(TestSink.probe[String])

    probe.ensureSubscription()

    // Record shouldn't appear (we assume having seen offset 0 already, and no demand)
    dbTable = Seq((0L, "first"))
    Thread.sleep(500)

    // "second" record should appear, but no demand yet for "third" (and thus no need for polling in general)
    dbTable = Seq((2L, "third"), (1L, "second"), (0L, "first"))
    probe.request(1)
    Thread.sleep(500)

    // Now there is demand for "third" and more! So we expect "third" and from them on periodic queries for more data
    probe.request(2)
    Thread.sleep(500)
  }

}
