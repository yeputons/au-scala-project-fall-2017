package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import akka.util.ByteString

/**
  * Common ancestor for all BitTorrent peer protocol messages (see BEP 3).
  */
sealed trait PeerMessage

object PeerMessage {
  case object KeepAlive extends PeerMessage

  case object Choke extends PeerMessage
  case object Unchoke extends PeerMessage
  case object Interested extends PeerMessage
  case object NotInterested extends PeerMessage
  case class HasNewPiece(pieceId: Int) extends PeerMessage
  case class HasPieces(hasPieces: Set[Int]) extends PeerMessage
  case class BlockRequest(block: BlockId) extends PeerMessage
  case class BlockAvailable(block: BlockId, data: ByteString)
      extends PeerMessage
  case class BlockRequestCancel(block: BlockId) extends PeerMessage

  case class BlockId(index: Int, begin: Int, length: Int)
}
