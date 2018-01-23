package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import akka.NotUsed
import akka.stream.scaladsl
import akka.stream.scaladsl.BidiFlow
import akka.util.ByteString

object Framing {
  final val MaximalFrameSize = 1 << 24

  /**
    * Creates a [[BidiFlow]] which implements BitTorrent peer framing protocol.
    * Assumes that frames go to `I1`/come out of `O2`, and TCP chunks
    * go to `I2`/come out of `O2`. Assumes that handshake is already performed
    * and corresponding bytes will never occur.
    * @return
    */
  def apply()
    : BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    scaladsl.Framing.simpleFramingProtocol(MaximalFrameSize)
}
