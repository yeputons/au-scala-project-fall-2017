package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow

import scala.collection.generic.CanBuildFrom
import scala.collection.immutable

object ExpectPrefixFlow {
  def apply[A, T <: Seq[A]](expectedPrefix: T)(
      implicit cbf: CanBuildFrom[T, A, T]): Flow[T, T, NotUsed] =
    TakePrefixFlow[A, T](expectedPrefix.length)
      .prefixAndTail(1)
      .flatMapConcat {
        case (immutable.Seq(realPrefix), tail) =>
          if (realPrefix != expectedPrefix) {
            throw PrefixMismatchException(realPrefix, expectedPrefix)
          }
          tail
      }
      .buffer(1, OverflowStrategy.backpressure) // Force checking the handshake even without pull requests
}

case class PrefixMismatchException[T <: Seq[_]](realPrefix: T,
                                                expectedPrefix: T)
    extends Exception

object TakePrefixFlow {
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
