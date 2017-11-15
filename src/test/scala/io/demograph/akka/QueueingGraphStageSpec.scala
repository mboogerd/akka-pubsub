package io.demograph.akka

import java.util.concurrent.atomic.AtomicReference

import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import io.demograph.ActorTestSpec
import io.demograph.akka.QueueingGraphStage.OverflowStrategies.DropHead
import io.demograph.akka.QueueingGraphStage.{BufferConsumer, OverflowStrategy}

import scala.concurrent.duration._

/**
  *
  */
class QueueingGraphStageSpec extends ActorTestSpec {

  behavior of "QueuingGraphStage"

  it should "queue elements" in {
    val (sourceProbe, _, sinkProbe) = bufferedStream[Int](DropHead, capacity = 2)

    withClue("Queueing demand is independent of downstream") {
      sourceProbe.expectRequest()
      sourceProbe.sendNext(1)
      sourceProbe.sendNext(2)

      sinkProbe.expectNoMessage(100.millis)
    }

    withClue("Once downstream signals demand, elements are delivered in order") {
      sinkProbe.request(2)
      sinkProbe.expectNext(1)
      sinkProbe.expectNext(2)
    }

    withClue("Once downstream stops demand, buffering continues") {
      sourceProbe.sendNext(3)
      sinkProbe.expectNoMessage(100.millis)
    }
  }

  it should "drop the head of the queue when required" in {
    val (sourceProbe, _, sinkProbe) = bufferedStream[Int](DropHead)

    sourceProbe.sendNext(1)
    sourceProbe.sendNext(2)
    sourceProbe.expectNoMessage(100.millis)

    withClue("1 was popped out because of pushing 2 while queue-capacity is just 1") {
      sinkProbe.request(1)
      sinkProbe.expectNext(2)
    }
  }

  it should "allow concurrent peeking into to the buffer" in {
    val (sourceProbe, buffer, sinkProbe) = bufferedStream[Int](DropHead, capacity = 2)

    sourceProbe.sendNext(1)
    sourceProbe.sendNext(2)
    eventually(buffer.get.peek(2) shouldBe Seq(1, 2))
    sinkProbe.request(2)
    sinkProbe.expectNext(1)
    sinkProbe.expectNext(2)
  }

  it should "not produce concurrently dequeued elements downstream" in {
    val (sourceProbe, buffer, sinkProbe) = bufferedStream[Int](DropHead, capacity = 2)

    sourceProbe.sendNext(1)
    sourceProbe.sendNext(2)
    eventually(buffer.get.dequeue() shouldBe 1)
    sinkProbe.request(2)
    sinkProbe.expectNext(2)
    sinkProbe.expectNoMessage(100.millis)
  }

  it should "not dequeue elements that are produced downstream" in {
    val (sourceProbe, buffer, sinkProbe) = bufferedStream[Int](DropHead, capacity = 2)

    sourceProbe.sendNext(1)
    sourceProbe.sendNext(2)
    sinkProbe.request(1)
    sinkProbe.expectNext(1)
    eventually(buffer.get.dequeue() shouldBe 2)
  }

  def bufferFlow[A](overflowStrategy: OverflowStrategy, capacity: Int = 1): QueueingGraphStage[A] =
    new QueueingGraphStage[A](capacity, overflowStrategy)

  def bufferedStream[A](overflowStrategy: OverflowStrategy, capacity: Int = 1): (TestPublisher.Probe[A], AtomicReference[BufferConsumer[A]], TestSubscriber.Probe[A]) = {
    val ((sourceProbe, buffer), sinkProbe) = TestSource.probe[A](system)
      .viaMat(bufferFlow[A](overflowStrategy, capacity))(Keep.both)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    sourceProbe.ensureSubscription()
    sinkProbe.ensureSubscription()

    (sourceProbe, buffer, sinkProbe)
  }
}
