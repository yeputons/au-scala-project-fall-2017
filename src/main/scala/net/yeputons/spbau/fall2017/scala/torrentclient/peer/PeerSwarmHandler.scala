package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import java.net.URLEncoder

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.PeerInformation
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler._

import scala.collection.mutable

abstract class PeerSwarmHandler extends Actor with ActorLogging {
  val actorByPeer = mutable.Map.empty[PeerInformation, ActorRef]
  val peerByActor = mutable.Map.empty[ActorRef, PeerInformation]

  val actorsWithPiece = mutable.Map.empty[Int, mutable.Set[ActorRef]]
  val piecesOfActor = mutable.Map.empty[ActorRef, mutable.Set[Int]]

  def createPeerActor(peer: PeerInformation): ActorRef

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy() {
      case _: Exception => Stop
    }

  override def receive: Receive = {
    case AddPeer(peer) =>
      if (!actorByPeer.contains(peer)) {
        log.debug(
          s"Creating new actor for $peer, now there will be ${actorByPeer.size + 1} peers")
        val actor = createPeerActor(peer)
        actorByPeer += peer -> actor
        peerByActor += actor -> peer
        context.watch(actor)
      }
    case AddPieces(pieces) =>
      val actor = sender()
      if (peerByActor.contains(actor)) {
        pieces.foreach(piece =>
          actorsWithPiece.getOrElseUpdate(piece, mutable.Set.empty) += actor)
        piecesOfActor.getOrElseUpdate(actor, mutable.Set.empty) ++= pieces
      } else {
        log.debug(s"Unexpected AddPieces message from $actor")
      }
    case RemovePieces(pieces) =>
      val actor = sender()
      if (peerByActor.contains(actor)) {
        pieces.foreach(piece => actorsWithPiece.get(piece).foreach(_ -= actor))
        piecesOfActor.get(actor).foreach(_ --= pieces)
      } else {
        log.debug(s"Unexpected RemovePieces message from $actor")
      }
    case Terminated(actor) =>
      val peer = peerByActor(actor)
      log.debug(s"Actor for $peer terminated, ${actorByPeer.size - 1} peers left")
      actorByPeer -= peer
      peerByActor -= actor
      piecesOfActor.get(actor).foreach { pieces =>
        pieces.foreach(piece => actorsWithPiece.get(piece).foreach(_ -= actor))
      }
      piecesOfActor -= actor

    case PieceStatisticsRequest =>
      sender() ! PieceStatisticsResponse(
        actorsWithPiece.mapValues(_.size).toMap,
        piecesOfActor.map {
          case (actor, pieces) => (peerByActor(actor), pieces.size)
        }.toMap
      )
  }

  override def unhandled(message: Any): Unit = {
    log.error(s"Unhandled message, stopping: $message")
    context.stop(self)
  }
}

object PeerSwarmHandler {
  case class AddPeer(peer: PeerInformation)
  case class AddPieces(pieces: Set[Int])
  case class RemovePieces(pieces: Set[Int])
  case object PieceStatisticsRequest

  case class PieceStatisticsResponse(peersWithPiece: Map[Int, Int],
                                     piecesOfPeer: Map[PeerInformation, Int])

  def props(infoHash: ByteString, myPeerId: ByteString): Props =
    Props(new PeerSwarmHandlerImpl(infoHash, myPeerId))

  private class PeerSwarmHandlerImpl(infoHash: ByteString, myPeerId: ByteString)
      extends PeerSwarmHandler {
    override def createPeerActor(peer: PeerInformation): ActorRef = {
      val name = URLEncoder.encode(peer.address.getHostString, "ASCII") + "_" + peer.address.getPort
      context.actorOf(PeerHandler.props(self, infoHash, myPeerId, peer), name)
    }
  }
}
