package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}

import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.Future

object ExpectPrefixFlow {

  /**
    * Accepts a chunked stream of `A` (each chunk is of type `T`), ensures
    * that this stream starts with a specific prefix and emits everything
    * except the prefix. Throws a [[PrefixMismatchException]] if first
    * bytes of the stream do not match `expectedPrefix`. Checks them immediately
    * after creation. Materializes to `Future[Unit]` which is completed when
    * the expected prefix is successfully read.
    *
    * **emits** when at least `expectedPrefix.length + 1` elements of type `A` were received
    *
    * **backpressures** when downstream backpressures or there are still available elements
    *
    * **completes** when upstream completes and all elements has been emitted
    *
    * @param expectedPrefix The prefix
    * @tparam A Type of a single element in the stream (e.g. [[Char]])
    * @tparam T Type of a chunk grouping multiple elements together (e.g. [[List[Char]])
    */
  def apply[A, T <: Seq[A]](expectedPrefix: T)(
      implicit cbf: CanBuildFrom[T, A, T]): Flow[T, T, Future[Unit]] = {
    def prefixReadFuture[U] =
      Flow[U].map(_ => ()).toMat(Sink.head)(Keep.right)
    Flow[T]
      .prepend(
        // If `expectedPrefix` is empty, `TakePrefixFlow` won't run until the first element, kickstart it
        Source.single(cbf().result())
      )
      .via(TakePrefixFlow[A, T](expectedPrefix.length))
      .prefixAndTail(1)
      .map {
        case x @ (immutable.Seq(realPrefix), tail) =>
          if (realPrefix != expectedPrefix) {
            throw PrefixMismatchException(realPrefix, expectedPrefix)
          }
          tail
      }
      .alsoToMat(prefixReadFuture)(Keep.right)
      .flatMapConcat(identity)
      .buffer(1, OverflowStrategy.backpressure) // Force checking the handshake even without pull requests
  }
}

case class PrefixMismatchException[T <: Seq[_]](realPrefix: T,
                                                expectedPrefix: T)
    extends Exception

object TakePrefixFlow {

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
  def apply[A, T <: Seq[A]](n: Int)(
      implicit cbf: CanBuildFrom[T, A, T]): Flow[T, T, NotUsed] =
    Flow[T]
      .statefulMapConcat { () =>
        var remaining = n
        var prefixBuilder = Option(cbf())
        prefixBuilder.foreach(_.sizeHint(n))

        buffer: T =>
          {
            prefixBuilder match {
              case None => immutable.Iterable(buffer)
              case Some(builder) =>
                val (prefix, suffix) = buffer.splitAt(remaining)
                remaining -= prefix.length
                builder ++= prefix
                if (remaining > 0) {
                  immutable.Iterable.empty
                } else {
                  prefixBuilder = None
                  if (suffix.isEmpty) {
                    immutable.Iterable(builder.result())
                  } else {
                    immutable.Iterable(builder.result(),
                                       (cbf() ++= suffix).result())
                  }
                }
            }
          }
      }
}
