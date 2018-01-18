package net.yeputons.spbau.fall2017.scala.torrentclient

import akka.actor.{ActorLogging, FSM}

object PeerConnection {
  case object SendKeepalive
  case object Choke
  case object Unchoke
  case object Interested
  case object Uninterested
  // case class NewPieceAvailable  // Event bus

  case class PieceId(index: Long, begin: Long, length: Long)

  case class PieceRequest(piece: PieceId)
  case class PieceRequestCancel(piece: PieceId)
  case class PieceAvailable(piece: PieceId, data: Seq[Byte])

  trait State
  case object Connecting extends State
  case object WaitingForHandshake extends State
  case object Working extends State

  case class OtherPeerState(choked: Boolean, interested: Boolean, hasPieces: Set[Int])
}

class PeerConnection(infoHash: Seq[Byte], peerId: Seq[Byte]) extends FSM[PeerConnection.State, PeerConnection.OtherPeerState] with ActorLogging {
  import PeerConnection._

  startWith(Connecting, OtherPeerState(choked = true, interested = false, Set.empty))
}
