package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import java.security.MessageDigest

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated, Timers}
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandler.{
  BlockDownloaded,
  DownloadBlock
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler.{
  PeerForPieceRequest,
  PeerForPieceResponse
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PieceDownloader._
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.PeerMessage.BlockId

import scala.collection.mutable
import scala.concurrent.duration._

class PieceDownloader(peerSwarmHandler: ActorRef,
                      pieceId: Int,
                      pieceLength: Int,
                      pieceHash: Seq[Byte])
    extends Actor
    with ActorLogging
    with Timers {

  val blocksCount = (pieceLength + BlockSize - 1) / BlockSize
  val blockIds = (0 until blocksCount).map { blockId =>
    val blockStart = blockId * BlockSize
    val blockEnd = math.min(blockStart + BlockSize, pieceLength)
    BlockId(pieceId = pieceId, begin = blockStart, blockEnd - blockStart)
  }
  val blocks: mutable.Seq[Option[ByteString]] =
    mutable.Seq.fill(blocksCount)(Option.empty[ByteString])

  var peer = Option.empty[ActorRef]

  override def preStart(): Unit = {
    context.watch(peerSwarmHandler)
    choosePeer()
  }

  override def receive: Receive = {
    case Terminated(`peerSwarmHandler`) =>
      throw new IllegalStateException("PeerSwarmHandler terminated")
    case Terminated(terminatedPeer) =>
      if (peer.isDefined && peer.get == terminatedPeer) {
        log.debug("Peer died, choosing another one")
        choosePeer()
      } else {
        log.debug(
          s"Unexpected peer terminated message: got $terminatedPeer, my current peer is $peer")
      }
    case NoBlocksTimedOut =>
      log.debug(
        "No blocks received from the peer for a while, choosing another one")
      choosePeer()
    case PeerForPieceResponse(`pieceId`, newPeer) =>
      peer = Some(newPeer)
      log.debug(s"Will try downloading piece $pieceId from $newPeer")
      blocks.zip(blockIds).foreach {
        case (None, blockId) =>
          newPeer ! DownloadBlock(blockId)
        case (_, _) =>
      }
    case BlockDownloaded(blockId @ BlockId(`pieceId`, begin, length), data) =>
      val id = begin / BlockSize
      if (blockId != blockIds(id)) {
        throw new IllegalArgumentException(
          s"Invalid BlockDownloaded message: expected ${blockIds(id)}, got $blockId")
      }
      blocks(id) = Some(data)
      timers.startSingleTimer(NoBlocksTimeoutTimer,
                              NoBlocksTimedOut,
                              NoBlocksTimeout)
      checkBlocks()
  }

  def checkBlocks(): Unit = {
    if (blocks.contains(None)) {
      return
    }
    val digest = MessageDigest.getInstance("SHA-1")
    blocks.foreach {
      case Some(data) => digest.update(data.asByteBuffer)
      case None       => require(false)
    }
    val realHash = digest.digest().toSeq
    if (pieceHash == realHash) {
      log.debug(s"Successfully downloaded piece $pieceId")
      context.parent ! PieceDownloaded(pieceId,
                                       blocks.map(_.get).reduce(_ ++ _))
      context.stop(self)
    }
    log.warning(
      s"Downloaded piece $pieceId of length $pieceLength with an invalid hash: got $realHash instead of $pieceHash, retrying")
    for (i <- 0 to blocks.size)
      blocks(i) = None
    choosePeer()
  }

  def choosePeer(): Unit = {
    log.debug(s"Choosing new peer for downloading piece $pieceId")
    peer = None
    peerSwarmHandler ! PeerForPieceRequest(pieceId)
    timers.startSingleTimer(NoBlocksTimeoutTimer,
                            NoBlocksTimedOut,
                            NoBlocksTimeout)
  }

  override def unhandled(message: Any): Unit = {
    log.error(s"Unhandled message, stopping: $message")
    context.stop(self)
  }
}

object PieceDownloader {
  val BlockSize: Int = 1 << 14
  val NoBlocksTimeout = 10.seconds

  case class PieceDownloaded(pieceId: Int, data: ByteString)

  def props(peerSwarmHandler: ActorRef,
            pieceId: Int,
            pieceLength: Int,
            pieceHash: Seq[Byte]): Props =
    Props(
      new PieceDownloader(peerSwarmHandler, pieceId, pieceLength, pieceHash))

  case object NoBlocksTimeoutTimer
  case object NoBlocksTimedOut
}
