package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  ActorSystem,
  Props,
  Terminated
}
import akka.stream.{ActorMaterializer, OverflowStrategy, StreamTcpException}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.PeerInformation
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandshake.HandshakeCompleted
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerMessage._

import scala.concurrent.Future
import scala.collection.mutable
import scala.util.{Failure, Success}

class PeerConnection(
    connectionFactory: ActorSystem => Flow[PeerMessage,
                                           PeerMessage,
                                           Future[HandshakeCompleted.type]])
    extends Actor
    with ActorLogging {

  import context.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val (connection: ActorRef, handshakeCompletedFuture) =
    Source
      .actorRef(100, OverflowStrategy.fail)
      .viaMat(connectionFactory(context.system))(Keep.both)
      .to(Sink.actorRef(self, PeerConnection.OnCompleteMessage))
      .run()
  context.watch(connection)
  handshakeCompletedFuture.onComplete {
    case Success(x) => self ! x
    case Failure(_) => // Do nothing, the failure will be received from the stream
  }

  var otherChoked = true
  var otherInterested = false
  val otherAvailable = mutable.Set.empty[Int]

  override def receive: Receive = {
    case KeepAlive =>
      log.info("Received KeepAlive")
    case HandshakeCompleted =>
      log.info("Handshake completed")
    case akka.actor.Status.Failure(e) =>
      e match {
        case e: StreamTcpException =>
          log.warning(s"TCP error, aborting: $e")
        case _ =>
          log.warning(f"Peer protocol error occured, aborting connection: $e")
      }
      context.stop(self)
    case PeerConnection.OnCompleteMessage =>
      log.info("Stream terminated, stopping")
      context.stop(self)
    case Terminated(`connection`) =>
      log.warning("Connection actor stopped, stopping")
      context.stop(self)

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
  }

  override def unhandled(message: Any): Unit = {
    log.error(s"Unhandled message, stopping: $message")
    context.stop(self)
  }
}

object PeerConnection {
  def props(infoHash: ByteString,
            myPeerId: ByteString,
            otherPeer: PeerInformation) =
    Props(
      new PeerConnection(
        actorSystem =>
          PeerProtocol(infoHash,
                       myPeerId,
                       otherPeer.id.map(x => ByteString(x.toArray)))
            .join(Tcp(actorSystem).outgoingConnection(otherPeer.address))))

  case object OnCompleteMessage
}
