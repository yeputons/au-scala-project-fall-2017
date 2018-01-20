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

import scala.concurrent.Future
import scala.util.{Failure, Success}

class PeerConnection(
    handler: ActorRef,
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

  override def receive: Receive = {
    case HandshakeCompleted =>
      log.info("Handshake completed")
    case m: PeerMessage =>
      handler ! m
    case akka.actor.Status.Failure(e) =>
      e match {
        case e: StreamTcpException =>
          log.warning(s"TCP error, aborting: $e")
        case _ =>
          log.warning(f"Peer protocol error occurred, aborting connection: $e")
      }
      context.stop(self)
    case PeerConnection.OnCompleteMessage =>
      log.info("Stream terminated, stopping")
      context.stop(self)
    case Terminated(`connection`) =>
      log.warning("Connection actor stopped, stopping")
      context.stop(self)
  }

  override def unhandled(message: Any): Unit = {
    log.error(s"Unhandled message, stopping: $message")
    context.stop(self)
  }
}

object PeerConnection {
  def props(handler: ActorRef,
            infoHash: ByteString,
            myPeerId: ByteString,
            otherPeer: PeerInformation) =
    Props(
      new PeerConnection(
        handler,
        actorSystem =>
          PeerProtocol(infoHash,
                       myPeerId,
                       otherPeer.id.map(x => ByteString(x.toArray)))
            .join(Tcp(actorSystem).outgoingConnection(otherPeer.address))
      ))

  private case object OnCompleteMessage
}
