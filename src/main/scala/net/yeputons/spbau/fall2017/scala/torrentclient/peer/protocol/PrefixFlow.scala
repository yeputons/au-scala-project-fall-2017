package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

import scala.collection.generic.CanBuildFrom
import scala.collection.{immutable, mutable}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object ExpectPrefixFlow {

  /**
    * Accepts a chunked stream of `A` (each chunk is of type `T`), ensures
    * that this stream starts with a specific prefix and emits everything
    * except the prefix. Throws a [[PrefixMismatchException]] if first
    * bytes of the stream do not match `expectedPrefix`. Checks them immediately
    * after creation. Materializes to a `Future` which is completed when
    * the expected prefix is successfully read.
    *
    * **emits** when at least `expectedPrefix.length + 1` elements of type `A` were received
    *
    * **backpressures** when downstream backpressures or there are still available elements
    *
    * **completes** when upstream completes and all elements has been emitted
    *
    * @param prefixLength Length of the expected prefix
    * @param prefixPredicate Function which returns `true` iff the prefix passed is OK
    * @param matchedMessage The value which completes the future
    * @tparam A Type of a single element in the stream (e.g. [[Char]])
    * @tparam T Type of a chunk grouping multiple elements together (e.g. [[List[Char]])
    */
  def apply[A, T <: Seq[A], R](prefixLength: Int,
                               prefixPredicate: T => Try[Unit],
                               matchedMessage: R)(
      implicit cbf: CanBuildFrom[T, A, T]): Flow[T, T, Future[R]] = {
    def prefixReadFuture[U] =
      Flow[U].map(_ => matchedMessage).toMat(Sink.head)(Keep.right)
    Flow[T]
      .via(new TakePrefixFlow[A, T](prefixLength))
      .prefixAndTail(1)
      .map {
        case x @ (immutable.Seq(realPrefix), tail) =>
          prefixPredicate(realPrefix) match {
            case Success(()) =>
            case Failure(t)  => throw t
          }
          tail
      }
      .alsoToMat(prefixReadFuture)(Keep.right)
      .flatMapConcat(identity)
      .buffer(1, OverflowStrategy.backpressure) // Force checking the handshake even without pull requests
  }

  def apply[A, T <: Seq[A], R](expectedPrefix: T, matchedMessage: R = ())(
      implicit cbf: CanBuildFrom[T, A, T]): Flow[T, T, Future[R]] =
    apply[A, T, R](
      expectedPrefix.length, { realPrefix: T =>
        if (realPrefix == expectedPrefix) Success(())
        else Failure(PrefixMismatchException(realPrefix, expectedPrefix))
      },
      matchedMessage
    )
}

case class PrefixMismatchException[T <: Seq[_]](realPrefix: T,
                                                expectedPrefix: T)
    extends Exception

/**
  * Accepts a chunked stream of `A` (each chunk is of type `T`) and emits
  * the same elements (chunked), but with the first chunk having a specific length.
  * For example, when `n=3`, `Seq(1, 2), Seq(3, 4), Seq(5, 6), Seq(7, 8)` will be
  * transformed to `Seq(1, 2, 3), Seq(4), Seq(5, 6), Seq(7, 8)`.
  *
  * **emits** when at least `n` elements of type `A` were received
  *
  * **backpressures** when downstream backpressures or there are still available elements
  *
  * **completes** when upstream completes and all elements has been emitted
  *
  * @param n The required length of the first chunk
  * @tparam A Type of a single element in the stream (e.g. [[Char]])
  * @tparam T Type of a chunk grouping multiple elements together (e.g. [[List[Char]])
  */
class TakePrefixFlow[A, T <: Seq[A]](n: Int)(
    implicit cbf: CanBuildFrom[T, A, T])
    extends GraphStage[FlowShape[T, T]] {
  val in = Inlet[T]("TakePrefixFlow.in")
  val out = Outlet[T]("TakePrefixFlow.out")
  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      sealed trait State
      case class FindingPrefix(remaining: Int,
                               prefixBuilder: mutable.Builder[A, T])
          extends State
      case class UnsentChunk(chunk: T) extends State
      case object Identity extends State

      var state: State =
        if (n == 0) UnsentChunk(cbf().result())
        else {
          val builder = cbf()
          builder.sizeHint(n)
          FindingPrefix(n, builder)
        }

      def push(buffer: T): State =
        state match {
          case FindingPrefix(oldRemaining, prefixBuilder) =>
            val (prefix, suffix) = buffer.splitAt(oldRemaining)
            val newRemaining = oldRemaining - prefix.length
            prefixBuilder ++= prefix
            if (newRemaining > 0) {
              pull(in)
              FindingPrefix(newRemaining, prefixBuilder)
            } else {
              push(out, prefixBuilder.result())
              if (suffix.nonEmpty) {
                UnsentChunk((cbf() ++= suffix).result())
              } else {
                Identity
              }
            }
          case UnsentChunk(chunk) =>
            throw new IllegalStateException(
              "Should not ever push from UnsentChunk state")
          case Identity =>
            push(out, buffer)
            Identity
        }

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit =
            state = push(grab(in))

          override def onUpstreamFinish(): Unit =
            state match {
              case FindingPrefix(_, prefixBuilder) =>
                failStage(PrefixTooShortException(prefixBuilder.result(), n))
              case UnsentChunk(_) => completeStage()
              case Identity       => completeStage()
            }
        }
      )

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit =
            state match {
              case FindingPrefix(_, _) => pull(in)
              case UnsentChunk(chunk) =>
                push(out, chunk)
                state = Identity
              case Identity => pull(in)
            }
        }
      )
    }
}

case class PrefixTooShortException[T](realPrefix: T, expectedLength: Int)
    extends Exception(
      s"Prefix is too short: expected $expectedLength bytes, got $realPrefix")
