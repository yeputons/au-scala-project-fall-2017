package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import akka.util.ByteString

case class PeerProtocolDecodeException(message: String, frame: ByteString)
    extends Exception(s"$message in frame $frame")
