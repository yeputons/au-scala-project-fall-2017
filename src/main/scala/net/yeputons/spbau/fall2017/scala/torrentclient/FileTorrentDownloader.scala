package net.yeputons.spbau.fall2017.scala.torrentclient

import java.io.{File, RandomAccessFile}

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  OneForOneStrategy,
  Props,
  SupervisorStrategy,
  Terminated,
  Timers
}
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.{
  PeerSwarmHandler,
  PieceDownloader
}
import net.yeputons.spbau.fall2017.scala.torrentclient.FileTorrentDownloader._
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{
  GetPeers,
  PeersListResponse,
  SubscribeToPeersList
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler.AddPeer
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PieceDownloader.PieceDownloaded

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

class FileTorrentDownloader(torrent: Torrent, storageFile: File)
    extends Actor
    with ActorLogging
    with Timers {
  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy() {
      case _: Exception => Restart
    }

  val peerId = {
    val prefix = ByteString("--SY0001--")
    val array = Array.fill[Byte](20 - prefix.length)(0)
    Random.nextBytes(array)
    prefix ++ ByteString(array)
  }
  val tracker = context.actorOf(
    Tracker.props(torrent.announce, torrent.infoHash),
    "tracker")
  val swarm = context.actorOf(
    PeerSwarmHandler.props(ByteString(torrent.infoHash.toArray),
                           peerId,
                           torrent.pieceHashes.size),
    "swarm")

  var remainingPieces: Set[Int] = torrent.pieceHashes.indices.toSet
  val pieceByActor = mutable.Map.empty[ActorRef, Int]

  timers.startPeriodicTimer(RespawnDownloadsTimer,
                            RespawnDownloads,
                            RespawnEvery)

  override def preStart(): Unit = {
    tracker ! SubscribeToPeersList
    tracker ! GetPeers
  }

  override def receive: Receive = {
    case PieceDownloaded(pieceId, data) =>
      pieceByActor.get(sender()) match {
        case Some(expectedPieceId) =>
          pieceByActor -= sender()
          require(expectedPieceId == pieceId)
          context.unwatch(sender())
          savePiece(pieceId, data)
          spawnDownloaders()
        case None =>
          log.info(
            s"Unexpected PieceDownloaded for piece $pieceId from ${sender()}")
      }
    case Terminated(actor) =>
      pieceByActor.get(sender()) match {
        case Some(pieceId) =>
          pieceByActor -= sender()
          log.info(
            s"PieceDownloader for piece $pieceId terminated, adding it back to the queue")
          remainingPieces += pieceId
          spawnDownloaders()
        case None =>
          log.info(s"Unknown actor terminated: ${sender()}")
      }
      spawnDownloaders()
    case PeersListResponse(peers) =>
      peers.foreach(swarm ! AddPeer(_))
      spawnDownloaders()
    case RespawnDownloads =>
      spawnDownloaders()
  }

  def spawnDownloaders(): Unit = {
    while (remainingPieces.nonEmpty && pieceByActor.size < MaxPiecesSimultaneously) {
      // TODO: choose the rarest piece first
      val id = remainingPieces.toSeq(Random.nextInt(remainingPieces.size))
      remainingPieces = remainingPieces - id

      val actor = context.actorOf(
        PieceDownloader
          .props(swarm, id, torrent.pieceLength(id), torrent.pieceHashes(id)),
        s"piece-$id")
      context.watch(actor)
      pieceByActor(actor) = id
    }
    if (remainingPieces.isEmpty && pieceByActor.isEmpty) {
      log.debug("Download completed, nothing to span")
    }
  }

  def savePiece(pieceId: Int, data: ByteString): Unit = {
    require(data.length == torrent.pieceLength(pieceId))
    val f = new RandomAccessFile(storageFile, "rws")
    try {
      f.seek(pieceId * torrent.basePieceLength)
      f.write(data.toArray)
    } finally {
      f.close()
    }
    log.debug(s"Saved piece $pieceId to $storageFile")
  }
}

object FileTorrentDownloader {
  private val MaxPiecesSimultaneously = 10
  private val RespawnEvery = 5.seconds

  def props(torrent: Torrent, storageFile: File): Props =
    Props(new FileTorrentDownloader(torrent, storageFile))

  private case object RespawnDownloadsTimer
  private case object RespawnDownloads
}
