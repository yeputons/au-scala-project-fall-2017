package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor._
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.PeerInformation
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection.{
  ReceivedPeerMessage,
  SendPeerMessage
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandler._
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler.AddPieces
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.PeerMessage._

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * Tracks a state of a BitTorrent peer. Stops whenever connection stops.
  *
  * Allows mocking of the connection actor in tests via abstract `def createConnection`.
  * See [[net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandler.PeerTcpHandler]]
  * for the default implementation which uses [[PeerConnection]].
  */
abstract class PeerHandler(swarmHandler: ActorRef)
    extends Actor
    with ActorLogging
    with Timers {

  def createConnection(): ActorRef

  def keepAlivePeriod: FiniteDuration = 2.minutes

  def keepAliveTimeout: FiniteDuration = 3.minutes

  val connection: ActorRef = createConnection()
  context.watch(connection)

  var otherChoked = true
  var otherInterested = false
  val otherAvailable = mutable.Set.empty[Int]

  override def preStart(): Unit = {
    log.debug("PeerHandler started")
    timers.startPeriodicTimer(KeepAliveTimer, SendKeepAlive, keepAlivePeriod)
    timers.startSingleTimer(NoMessagesTimer, NoMessages, keepAliveTimeout)
  }

  override def postStop(): Unit = log.debug("PeerHandler stopped")

  override def receive: Receive = {
    case Terminated(`connection`) =>
      context.stop(self)
    case SendKeepAlive =>
      log.debug("Sending KeepAlive")
      connection ! SendPeerMessage(KeepAlive)
    case NoMessages =>
      log.debug("No messages received for a long time, stopping")
      context.stop(self)
    case ReceivedPeerMessage(msg) =>
      timers.startSingleTimer(NoMessagesTimer, NoMessages, keepAliveTimeout)
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
          swarmHandler ! AddPieces(pieces)
          log.debug(
            s"New information about pieces: ${otherAvailable.size} available")
        case HasNewPiece(piece) =>
          otherAvailable += piece
          swarmHandler ! AddPieces(Set(piece))
          log.debug(
            s"Piece $piece is now available; ${otherAvailable.size} in total")

        case BlockRequest(blockId) =>
          log.debug(s"Peer requested $blockId")
        case BlockRequestCancel(blockId) =>
          log.debug(s"Peer cancelled request for $blockId")
        case BlockAvailable(blockId, data) =>
          log.debug(s"Received a $blockId: ${data.length} bytes")
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
    *
    * @param swarmHandler [[ActorRef]] to a [[PeerSwarmHandler]] actor
    */
  def props(swarmHandler: ActorRef,
            infoHash: ByteString,
            myPeerId: ByteString,
            otherPeer: PeerInformation) =
    Props(new PeerTcpHandler(swarmHandler, infoHash, myPeerId, otherPeer))

  class PeerTcpHandler(swarmHandler: ActorRef,
                       infoHash: ByteString,
                       myPeerId: ByteString,
                       otherPeer: PeerInformation)
      extends PeerHandler(swarmHandler) {
    override def createConnection(): ActorRef =
      context.actorOf(PeerConnection.props(self, infoHash, myPeerId, otherPeer),
                      "conn")
  }

  /**
    * Timer which asks the actor to send `KeepAlive` every so often
    */
  private case object KeepAliveTimer
  private case object SendKeepAlive

  /**
    * Timer which drops the connection after a while with no messages
    */
  private case object NoMessagesTimer
  private case object NoMessages
}
