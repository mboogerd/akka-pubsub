package io.demograph.akka

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

import akka.event.Logging
import akka.stream._
import akka.stream.stage._
import io.demograph.akka.QueueingGraphStage.OverflowStrategies._
import io.demograph.akka.QueueingGraphStage.{BoundedBuffer, BoundedBufferImpl, BufferConsumer, OverflowStrategy}

/**
  * Stolen from Akka. This is mostly a copy of `akka.streams.impl.fusing.Ops.Buffer` with the exception that on
  * `preStart`, it initializes a `BoundedBuffer` inside a `AtomicReference`, allowing concurrent access to its elements
  */
class QueueingGraphStage[A](size: Int, overflowStrategy: OverflowStrategy) extends GraphStageWithMaterializedValue[FlowShape[A, A], AtomicReference[BufferConsumer[A]]] {

  val in = Inlet[A](Logging.simpleName(this) + ".in")
  val out = Outlet[A](Logging.simpleName(this) + ".out")
  override val shape = FlowShape(in, out)


  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, AtomicReference[BufferConsumer[A]]) = {
    // The mutable state for this GraphStage is materialized outside! the GraphStageLogic. Note however that in this
    // case it is a concurrency-safe mutable datastructure.
    val buffer: AtomicReference[BoundedBuffer[A]] = new AtomicReference[BoundedBuffer[A]](BoundedBufferImpl(size))


    def updateBuffer(f: BoundedBuffer[A] ⇒ BoundedBuffer[A]): BoundedBuffer[A] = {
      buffer.getAndUpdate(new UnaryOperator[BoundedBuffer[A]] {
        override def apply(t: BoundedBuffer[A]): BoundedBuffer[A] = f(t)
      })
    }

    val logic = new GraphStageLogic(shape) with InHandler with OutHandler {
      val enqueueAction: A ⇒ Unit =
        overflowStrategy match {
          case DropHead ⇒ elem ⇒
            updateBuffer(b ⇒ (if (b.isFull) b.dropHead() else b).enqueue(elem))
            pull(in)
          case DropTail ⇒ elem ⇒
            updateBuffer(b ⇒ (if (b.isFull) b.dropTail() else b).enqueue(elem))
            pull(in)
          case DropBuffer ⇒ elem ⇒
            updateBuffer(b ⇒ (if (b.isFull) b.clear() else b).enqueue(elem))
            pull(in)
          case DropNew ⇒ elem ⇒
            updateBuffer(b ⇒ if (b.isFull) b.enqueue(elem) else b)
            pull(in)
          case Fail ⇒ elem ⇒
            if (buffer.get.isFull) failStage(BufferOverflowException(s"Buffer overflow (max capacity was: $size)!"))
            else {
              updateBuffer(_.enqueue(elem))
              pull(in)
            }
        }

      override def preStart(): Unit = pull(in)

      override def onPush(): Unit = {
        val elem = grab(in)
        // If out is available, then it has been pulled but no dequeued element has been delivered.
        // It means the buffer at this moment is definitely empty,
        // so we just push the current element to out, then pull.
        if (isAvailable(out)) {
          push(out, elem)
          pull(in)
        } else {
          enqueueAction(elem)
        }
      }

      override def onPull(): Unit = {
        // Update the internal buffer
        val old: BoundedBuffer[A] = updateBuffer { b ⇒ if (b.nonEmpty) b.dropHead() else b }
        // Push out the head element, if one existed
        if (old.nonEmpty) push(out, old.dequeue())

        if (isClosed(in)) {
          // if its size is 1, it means that element is just pushed from this immutable buffer
          if (old.size <= 1) completeStage()
        } else if (!hasBeenPulled(in)) {
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (buffer.get.isEmpty) completeStage()
      }

      setHandlers(in, out, this)
    }

    // Return the GraphStageLogic and materialized (concrrent) buffer
    (logic, buffer.asInstanceOf[AtomicReference[BufferConsumer[A]]])
  }
}

object QueueingGraphStage {

  sealed abstract class DelayOverflowStrategy extends Serializable

  sealed abstract class OverflowStrategy extends DelayOverflowStrategy

  private[akka] object OverflowStrategies {

    /**
      * INTERNAL API
      */
    private[akka] case object DropHead extends OverflowStrategy

    /**
      * INTERNAL API
      */
    private[akka] case object DropTail extends OverflowStrategy

    /**
      * INTERNAL API
      */
    private[akka] case object DropBuffer extends OverflowStrategy

    /**
      * INTERNAL API
      */
    private[akka] case object DropNew extends OverflowStrategy

    /**
      * INTERNAL API
      */
    private[akka] case object Fail extends OverflowStrategy

    /**
      * INTERNAL API
      */
    private[akka] case object EmitEarly extends DelayOverflowStrategy

  }

  /**
    * This is an alternative to the Akka private `akka.stream.impl.Buffer` with the set of operations allowing to
    * inspect/shrink the contents of a Buffer
    *
    * @tparam A
    */
  trait BufferConsumer[A] {
    def isEmpty: Boolean

    def nonEmpty: Boolean

    def size: Int

    def peek(n: Int): Traversable[A]

    def dropHead(): BoundedBuffer[A]

    def dropTail(): BoundedBuffer[A]

    def clear(): BoundedBuffer[A]

    def dequeue(): A
  }

  /**
    * This is an alternative to the Akka private `akka.stream.impl.Buffer`. It adds the ability to grow the contents of
    * a buffer, respecting a `capacity` constraint
    *
    * @tparam A
    */
  trait BoundedBuffer[A] extends BufferConsumer[A] {
    def capacity: Int

    def isFull: Boolean

    def enqueue(a: A): BoundedBuffer[A]
  }


  /**
    * This is a lame Vector implementation of `BoundedBuffer`, because:
    * 1. I would feel guilty stealing all the Lightbend team's code
    * 2. I don't want to spend time right now writing a properly optimized datastructure
    *
    * The reason for having an alternative implementation at all is the ability to share the underlying buffer with
    * other threads due to it relying on AtomicReference with an immutable internal Vector.
    *
    * @param capacity
    * @tparam A
    */
  case class BoundedBufferImpl[A](capacity: Int) extends BoundedBuffer[A] {

    protected val vector: Vector[A] = Vector.empty

    override def isFull: Boolean = vector.size >= capacity

    override def isEmpty: Boolean = vector.isEmpty

    override def nonEmpty: Boolean = vector.nonEmpty

    override def size: Int = vector.size

    override def peek(n: Int): Traversable[A] = vector.take(n)

    override def dropHead(): BoundedBuffer[A] = fromVector(vector.tail)

    override def dropTail(): BoundedBuffer[A] = fromVector(Vector(vector.head))

    def clear(): BoundedBuffer[A] = new BoundedBufferImpl[A](capacity) {
      override protected val vector: Vector[A] = Vector.empty
    }

    override def dequeue(): A = vector.head

    override def enqueue(a: A): BoundedBuffer[A] = {
      if (!isFull) fromVector(vector :+ a)
      else throw BufferOverflowException(s"Cannot add $a to full buffer")
    }

    private def fromVector(v: Vector[A]): BoundedBuffer[A] = new BoundedBufferImpl[A](capacity) {
      override val vector: Vector[A] = v
    }
  }

}
