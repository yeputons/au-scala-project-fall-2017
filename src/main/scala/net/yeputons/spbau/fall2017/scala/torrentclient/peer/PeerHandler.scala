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

  val downloadQueue = mutable.Map.empty[BlockId, mutable.Set[ActorRef]]
  val downloading = mutable.Map.empty[BlockId, mutable.Set[ActorRef]]

  var meInterested = false

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
    case DownloadBlock(blockId) =>
      if (downloading.contains(blockId)) {
        log.debug(s"Download request for $blockId, already downloading")
        downloading(blockId) += sender()
      } else {
        log.debug(s"Download request for $blockId, add to queue")
        downloadQueue.getOrElseUpdate(blockId, mutable.Set.empty) += sender()
        sendDownloadRequest()
      }
    case ReceivedPeerMessage(msg) =>
      timers.startSingleTimer(NoMessagesTimer, NoMessages, keepAliveTimeout)
      msg match {
        case KeepAlive =>
          log.info("Received KeepAlive")
        case Choke =>
          otherChoked = true
          log.debug("Peer choked")
          abortDownloads()
        case Unchoke =>
          otherChoked = false
          log.debug("Peer unchoked")
          sendDownloadRequest()
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
          val actors = downloading.getOrElse(blockId, Set.empty)
          log.debug(
            s"Received a $blockId: ${data.length} bytes, sending to ${actors.size} actors")
          actors.foreach(_ ! BlockDownloaded(blockId, data))
          downloading -= blockId
          updateInterested()
      }
  }

  def abortDownloads(): Unit = {
    if (downloading.nonEmpty) {
      log.debug(
        s"Aborting downloads for: ${downloading.keys}, moved back to queue")
      downloading.foreach {
        case (blockId, actors) =>
          downloadQueue.getOrElseUpdate(blockId, mutable.Set.empty) ++= actors
      }
      downloading.clear()
    }
  }

  def sendDownloadRequest(): Unit = {
    updateInterested()
    if (downloadQueue.nonEmpty) {
      if (otherChoked) {
        log.debug(s"Don't request ${downloadQueue.size} blocks because peer is choked")
      } else {
        downloadQueue.foreach {
          case (block, actors) =>
            log.debug(s"Requesting $block")
            connection ! SendPeerMessage(BlockRequest(block))
            downloading.getOrElseUpdate(block, mutable.Set.empty) ++= actors
        }
        downloadQueue.clear()
      }
    }
  }

  def updateInterested(): Unit = {
    if (downloading.isEmpty && downloadQueue.isEmpty) {
      if (meInterested) {
        log.debug("Sending NotInterested")
        connection ! SendPeerMessage(NotInterested)
        meInterested = false
      }
    } else {
      if (!meInterested) {
        log.debug("Sending Interested")
        connection ! SendPeerMessage(Interested)
        meInterested = true
      }
    }
  }

  override def unhandled(message: Any): Unit = {
    log.error(s"Unhandled message, stopping: $message")
    context.stop(self)
  }
}

object PeerHandler {
  case class DownloadBlock(blockId: BlockId)

  case class BlockDownloaded(blockId: BlockId, data: ByteString)

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
