package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import akka.stream.scaladsl.{BidiFlow, Keep}
import akka.util.ByteString

import scala.concurrent.Future

object PeerProtocol {

  /**
    * Creates [[BidiFlow]] which fully implements BitTorrent peer protocol
    * via TCP, including handshake and framing. `I1` and `O2` are streams of
    * [[PeerMessage]], `I2`/`O1` are chunks of data from TCP protocol.
    *
    * Materializes to a `Future[HandshakeCompleted.type]` which completes right
    * after handshake is successfully completed (even if no data exchange follows),
    * and fails whenever the stream fails.
    *
    * @param infoHash `info_hash` to use during handshake. Protocol fails if it doesn't match peer's `info_hash`.
    * @param myPeerId 20-byte [[ByteString]] specifying which peer id to use during handshake
    * @param otherPeerId Optional 20-byte [[ByteString]] specifying which peer id
    *                    do we expect from the other side. Protocol fails if it doesn't match.
    */
  def apply(infoHash: ByteString,
            myPeerId: ByteString,
            otherPeerId: Option[ByteString])
    : BidiFlow[PeerMessage,
               ByteString,
               ByteString,
               PeerMessage,
               Future[Handshake.HandshakeCompleted.type]] =
    MessagesParsing()
      .atop(Framing())
      .atopMat(Handshake(infoHash, myPeerId, otherPeerId))(Keep.right)
}
