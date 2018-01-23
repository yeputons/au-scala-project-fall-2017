package net.yeputons.spbau.fall2017.scala.torrentclient.apps

import java.nio.file.{Files, Paths}

import akka.actor.{ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import net.yeputons.spbau.fall2017.scala.torrentclient.{Torrent, Tracker}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{
  GetPeers,
  PeerInformation,
  PeersListResponse
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler._
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, _}

object ShowPeersForTorrentApp {
  val log = LoggerFactory.getLogger(getClass)
  implicit val executionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.global

  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      System.err.println(
        """
          |Expected arguments: <path-to-torrent-file>
          |
          |This test app parses a .torrent file, connects to a tracker,
          |and prints a list of peers received from it. Then it connects
          |to all received peers and prints received information to
          |DEBUG log.
        """.stripMargin)
      sys.exit(1)
    }
    val torrentBytes: Array[Byte] = Files.readAllBytes(Paths.get(args(0)))
    val torrent: Torrent = Torrent(torrentBytes)

    val actorSystem = ActorSystem("show-peers-for-torrent")
    val tracker =
      actorSystem.actorOf(Tracker.props(torrent.announce, torrent.infoHash),
                          "tracker")
    implicit val timeout: Timeout = Timeout(5.seconds)

    val random = new scala.util.Random()
    var peers = Set.empty[PeerInformation]
    while (peers.isEmpty) {
      Thread.sleep(1000)
      val id = random.nextLong()
      Await.result(tracker ? GetPeers(id), Duration.Inf) match {
        case PeersListResponse(`id`, newPeers) =>
          peers = newPeers
      }
    }
    tracker ! PoisonPill

    val swarm = actorSystem.actorOf(
      PeerSwarmHandler.props(ByteString(torrent.infoHash.toArray),
                             ByteString("01234567890123456789"),
                             torrent.pieceHashes.size),
      "swarm")
    peers.foreach(swarm ! AddPeer(_))

    while (true) {
      Thread.sleep(10000)
      val PieceStatisticsResponse(peersWithPiece, piecesOfPeer) =
        Await.result(swarm ? PieceStatisticsRequest, 1000.milliseconds)
      log.info(
        s"Know about ${peersWithPiece.size} pieces and ${piecesOfPeer.size} peers")
      if (peersWithPiece.nonEmpty) {
        log.info(
          s"Each piece is available on " +
            s"${peersWithPiece.min}..${peersWithPiece.max} peers " +
            s"average is ${peersWithPiece.sum / peersWithPiece.size}"
        )
      }
      if (piecesOfPeer.nonEmpty) {
        log.info(
          s"Each peer holds between " +
            s"${piecesOfPeer.values.min} and ${piecesOfPeer.values.max} pieces, " +
            s"average is ${piecesOfPeer.values.sum / piecesOfPeer.values.size}"
        )
      }
      (swarm ? PeerForPieceRequest(0)).map {
        case PeerForPieceResponse(0, peer) =>
          log.info(s"Current peer for piece 0 is $peer")
      }
    }

    // Do not terminate the actor system
  }
}
