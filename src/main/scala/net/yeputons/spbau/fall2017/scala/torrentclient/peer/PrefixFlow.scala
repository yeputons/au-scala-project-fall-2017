package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.collection.immutable

object ExpectPrefixFlow {
  def apply(expectedPrefix: ByteString): Flow[ByteString, ByteString, NotUsed] =
    TakePrefixFlow(expectedPrefix.length)
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

case class PrefixMismatchException(realPrefix: ByteString,
                                   expectedPrefix: ByteString)
    extends Exception

object TakePrefixFlow {
  def apply(n: Int): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString]
      .statefulMapConcat { () =>
        var remaining = n
        var collectedPrefix = Option(ByteString.empty)
        buffer: ByteString =>
          {
            collectedPrefix match {
              case None => immutable.Iterable(buffer)
              case Some(oldPrefix) =>
                val (prefix, suffix) = buffer.splitAt(remaining)
                remaining -= prefix.length
                val newPrefix = oldPrefix ++ prefix
                if (remaining > 0) {
                  collectedPrefix = Some(newPrefix)
                  immutable.Iterable.empty
                } else {
                  collectedPrefix = None
                  if (suffix.isEmpty) {
                    immutable.Iterable(newPrefix)
                  } else {
                    immutable.Iterable(newPrefix, suffix)
                  }
                }
            }
          }
      }
}
