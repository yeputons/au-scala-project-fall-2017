package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.stream.{ActorMaterializer, OverflowStrategy, StreamTcpException}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.PeerInformation
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection.{OnReceived, ReceivedPeerMessage, SendPeerMessage}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.Handshake.HandshakeCompleted
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.{Handshake, PeerMessage, PeerProtocol}

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Wraps a Akka Streams connection to a peer.  Stops whenever the stream
  * fails or completes, logs events in the meantime. Passes all [[PeerMessage]]
  * from the stream to `handler`, wrapped in [[ReceivedPeerMessage]]. Passes
  * all [[SendPeerMessage]] to the stream.
  *
  * Has the only abstract `def connectionFlow` which allows mocking connection flow in tests.
  * See [[net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection.PeerTcpConnection]]
  * for the default implementation which works with TCP.
  *
  * @param handler The actor which will receive [[PeerMessage]] from the stream
  */
abstract class PeerConnection(handler: ActorRef)
    extends Actor
    with ActorLogging {

  def connectionFlow
    : Flow[PeerMessage, PeerMessage, Future[HandshakeCompleted.type]]

  import context.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val (connection: ActorRef, handshakeCompletedFuture) =
    Source
      .actorRef(100, OverflowStrategy.fail)
      .viaMat(connectionFlow)(Keep.both)
      .map(OnReceived)
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
    case OnReceived(m) =>
      handler ! ReceivedPeerMessage(m)
    case SendPeerMessage(m) =>
      connection ! m
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

  /**
    * Asks [[PeerConnection]] to send `msg` down the connection.
    */
  case class SendPeerMessage(msg: PeerMessage)

  /**
    * Sent by [[PeerConnection]] to `handler` whenever
    * `msg` is received from the connection.
    */
  case class ReceivedPeerMessage(msg: PeerMessage)

  /**
    * Creates [[Props]] for the [[PeerConnection]] actor which
    * will handle a TCP connection to a specific peer.
    * @param handler Actor which will receive [[ReceivedPeerMessage]] from the peer
    * @param infoHash `info_hash` to use during handshake.
    * @param myPeerId 20-byte [[ByteString]] specifying which peer id to use during handshake
    * @param otherPeer Connection information and optional peer id to be expected during the handshake
    */
  def props(handler: ActorRef,
            infoHash: ByteString,
            myPeerId: ByteString,
            otherPeer: PeerInformation) =
    Props(new PeerTcpConnection(handler, infoHash, myPeerId, otherPeer))

  /**
    * Default implementation of [[PeerConnection]] which
    * uses TCP for communication with a peer.
    */
  private class PeerTcpConnection(handler: ActorRef,
                                  infoHash: ByteString,
                                  myPeerId: ByteString,
                                  otherPeer: PeerInformation)
    extends PeerConnection(handler) {
    override def connectionFlow: Flow[PeerMessage,
      PeerMessage,
      Future[Handshake.HandshakeCompleted.type]] =
      PeerProtocol(infoHash,
        myPeerId,
        otherPeer.id.map(x => ByteString(x.toArray)))
        .join(Tcp(context.system).outgoingConnection(otherPeer.address))
  }

  private case class OnReceived(msg: PeerMessage)
  private case object OnCompleteMessage
}
