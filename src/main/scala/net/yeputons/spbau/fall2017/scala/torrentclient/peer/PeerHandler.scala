package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor._
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.PeerInformation
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection.ReceivedPeerMessage
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.PeerMessage._

import scala.collection.mutable

/**
  * Tracks a state of a BitTorrent peer. Stops whenever connection stops.
  *
  * Allows mocking of the connection actor in tests via abstract `def createConnection`.
  * See [[net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandler.PeerTcpHandler]]
  * for the default implementation which uses [[PeerConnection]].
  */
abstract class PeerHandler() extends Actor with ActorLogging {

  def createConnection(): ActorRef

  val connection: ActorRef = createConnection()
  context.watch(connection)

  var otherChoked = true
  var otherInterested = false
  val otherAvailable = mutable.Set.empty[Int]

  override def preStart(): Unit = log.debug("PeerHandler started")

  override def postStop(): Unit = log.debug("PeerHandler stopped")

  override def receive: Receive = {
    case Terminated(`connection`) =>
      context.stop(self)
    case ReceivedPeerMessage(msg) =>
      msg match {
        case KeepAlive =>
          log.info("Received KeepAlive")
        case Choke =>
          otherChoked = true
          log.debug("Peer choked")
        case Unchoke =>
          otherChoked = false
          log.debug("Peer unchoked")
        case Interested =>
          otherInterested = true
          log.debug("Peer is interested")
        case NotInterested =>
          otherInterested = false
          log.debug("Peer is not interested")

        case HasPieces(pieces) =>
          otherAvailable.clear()
          otherAvailable ++= pieces
          log.debug(
            s"New information about pieces: ${otherAvailable.size} available")
        case HasNewPiece(piece) =>
          otherAvailable += piece
          log.debug(
            s"Piece $piece is now available; ${otherAvailable.size} in total")

        case PieceRequest(pieceId) =>
          log.debug(s"Peer requested $pieceId")
        case PieceRequestCancel(pieceId) =>
          log.debug(s"Peer cancelled request for $pieceId")
        case PieceAvailable(pieceId, data) =>
          log.debug(s"Received a $pieceId: ${data.length} bytes")
      }
  }

  override def unhandled(message: Any): Unit = {
    log.error(s"Unhandled message, stopping: $message")
    context.stop(self)
  }
}

object PeerHandler {

  /**
    * Creates [[Props]] for the [[PeerHandler]] actor for establishing TCP
    * connection with a BitTorrent peer. Parameters are directly passed
    * to [[PeerConnection.props]].
    */
  def props(infoHash: ByteString,
            myPeerId: ByteString,
            otherPeer: PeerInformation) =
    Props(new PeerTcpHandler(infoHash, myPeerId, otherPeer))

  class PeerTcpHandler(infoHash: ByteString,
                       myPeerId: ByteString,
                       otherPeer: PeerInformation)
      extends PeerHandler {
    override def createConnection(): ActorRef =
      context.actorOf(PeerConnection.props(self, infoHash, myPeerId, otherPeer),
                      "conn")
  }
}
