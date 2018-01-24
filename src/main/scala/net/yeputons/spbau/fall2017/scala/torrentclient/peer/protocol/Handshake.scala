package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Source}
import akka.util.ByteString

import scala.concurrent.Future
import scala.util.{Failure, Success}

object Handshake {
  private final val Header: ByteString =
    ByteString(19) ++ ByteString("BitTorrent protocol")
  private final val SupportedExtension: ByteString =
    ByteString(0, 0, 0, 0, 0, 0, 0, 0)

  /**
    * Creates a [[BidiFlow]] which performs handshake with a remote peer.
    * Assumes that local client is connected to `I1` and `O2`, and the remote
    * client is connected to `O1` and `I2`. Each flow is a chunked stream of
    * bytes, exact chunking does not matter.
    *
    * Fails with [[HandshakeCompleted]]  if handshake fails, otherwise passes
    * data as-is until completion. Other exceptions are also possible.
    *
    * Materializes to a `Future[HandshakeCompleted.type]` which completes right
    * after handshake is successfully completed (even if no data exchange follows),
    * and fails whenever the stream fails.
    */
  def apply(infoHash: ByteString,
            myPeerId: ByteString,
            otherPeerId: Option[ByteString])
    : BidiFlow[ByteString,
               ByteString,
               ByteString,
               ByteString,
               Future[HandshakeCompleted.type]] = {
    require(infoHash.length == 20, "infoHash should be exactly 20 bytes")
    require(myPeerId.length == 20, "myPeerId should be exactly 20 bytes")
    otherPeerId.foreach(x =>
      require(x.length == 20, "otherPeerId should be exactly 20 bytes"))

    val localToRemoteFlow =
      Flow[ByteString].prepend(
        Source.single(Header ++ SupportedExtension ++ infoHash ++ myPeerId))
    val remoteToLocalFlow =
      ExpectPrefixFlow[Byte, ByteString, HandshakeCompleted.type](
        Header.length + SupportedExtension.length + 40, { prefix: ByteString =>
          val (realHeader, tail1) = prefix.splitAt(Header.size)
          val (_, tail2) = tail1.splitAt(SupportedExtension.size)
          val (realInfoHash, realOtherPeerId) = tail2.splitAt(infoHash.size)
          if (realHeader != Header)
            Failure(InvalidPartException("header", realHeader, Header))
          else if (realInfoHash != infoHash)
            Failure(InvalidPartException("info_hash", realInfoHash, infoHash))
          else if (!otherPeerId.forall(_ == realOtherPeerId))
            Failure(
              InvalidPartException("otherPeerId",
                                   realOtherPeerId,
                                   otherPeerId.get))
          else
            Success(())
        },
        HandshakeCompleted
      )
    BidiFlow.fromFlowsMat(localToRemoteFlow, remoteToLocalFlow)(Keep.right)
  }

  /**
    * A singleton which is signalled by [[Handshake.apply]] once handshake
    * is successfully performed.
    */
  case object HandshakeCompleted

  case class HandshakeException(message: String) extends Exception(message)
  case class InvalidPartException(part: String,
                                  real: ByteString,
                                  expected: ByteString)
      extends Exception(s"invalid $part: got $real instead of $expected")
}
