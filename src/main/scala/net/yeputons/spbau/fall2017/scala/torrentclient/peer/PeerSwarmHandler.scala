package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  OneForOneStrategy,
  SupervisorStrategy,
  Terminated
}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.PeerInformation
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler._

import scala.collection.mutable

abstract class PeerSwarmHandler extends Actor with ActorLogging {
  val actorByPeer = mutable.Map.empty[PeerInformation, ActorRef]
  val peerByActor = mutable.Map.empty[ActorRef, PeerInformation]

  val actorsWithPiece = mutable.Map.empty[Int, mutable.Set[ActorRef]]
  val piecesOfActor = mutable.Map.empty[ActorRef, mutable.Set[Int]]

  def createActor(peer: PeerInformation): ActorRef

  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy() {
      case _: Exception => Stop
    }

  override def receive: Receive = {
    case AddPeer(peer) =>
      if (!actorByPeer.contains(peer)) {
        log.debug(s"Creating new actor for $peer")
        val actor = createActor(peer)
        actorByPeer += peer -> actor
        peerByActor += actor -> peer
        context.watch(actor)
      }
    case AddPieces(pieces) =>
      val actor = sender()
      if (peerByActor.contains(actor)) {
        piecesOfActor(actor) ++= pieces
        pieces.foreach(actorsWithPiece(_) += actor)
      } else {
        log.debug(s"Unexpected AddPieces message from $actor")
      }
    case RemovePieces(pieces) =>
      val actor = sender()
      if (peerByActor.contains(actor)) {
        piecesOfActor(actor) --= pieces
        pieces.foreach(actorsWithPiece(_) -= actor)
      } else {
        log.debug(s"Unexpected RemovePieces message from $actor")
      }
    case Terminated(actor) =>
      val peer = peerByActor(actor)
      log.debug(s"Actor for $peer terminated")
      actorByPeer -= peer
      peerByActor -= actor
      piecesOfActor(actor).foreach(actorsWithPiece(_) -= actor)
      piecesOfActor -= actor

    case PieceStatisticsRequest =>
      sender() ! PieceStatisticsResponse(actorsWithPiece.map {
        case (piece, actors) => (piece, actors.size)
      }.toMap)
  }
}

object PeerSwarmHandler {
  case class AddPeer(peer: PeerInformation)
  case class AddPieces(pieces: Set[Int])
  case class RemovePieces(pieces: Set[Int])
  case object PieceStatisticsRequest

  case class PieceStatisticsResponse(actorsWithPiece: Map[Int, Int])
}
