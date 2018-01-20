package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  ActorRefFactory,
  Props,
  Terminated
}
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.PeerInformation
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerMessage._

import scala.collection.mutable

class PeerHandler(connectionFactory: (ActorRefFactory, ActorRef) => ActorRef)
    extends Actor
    with ActorLogging {

  val connection = connectionFactory(context, self)
  context.watch(connection)

  var otherChoked = true
  var otherInterested = false
  val otherAvailable = mutable.Set.empty[Int]

  override def preStart(): Unit = log.debug("PeerHandler started")

  override def postStop(): Unit = log.debug("PeerHandler stopped")

  override def receive: Receive = {
    case Terminated(`connection`) =>
      context.stop(self)
    case msg: PeerMessage =>
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
          log.debug(s"Received a $pieceId: ${data.length}")
      }
  }

  override def unhandled(message: Any): Unit = {
    log.error(s"Unhandled message, stopping: $message")
    context.stop(self)
  }
}

object PeerHandler {
  def props(infoHash: ByteString,
            myPeerId: ByteString,
            otherPeer: PeerInformation) =
    Props(
      new PeerHandler({
        case (factory, parent) =>
          factory.actorOf(
            PeerConnection.props(parent, infoHash, myPeerId, otherPeer))
      })
    )
}
