package net.yeputons.spbau.fall2017.scala.torrentclient.apps

import java.nio.file.{Files, Paths}
import java.security.MessageDigest

import akka.actor.{ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.http.scaladsl.model.Uri
import akka.util.{ByteString, Timeout}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{GetPeers, PeerInformation, PeersListResponse}
import net.yeputons.spbau.fall2017.scala.torrentclient.bencode._
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler.AddPeer
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.{PeerHandler, PeerSwarmHandler}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

object ShowPeersForTorrentApp {
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
    val torrentData: BDict = BencodeDecoder(torrentBytes) match {
      case Right(x: BDict) => x
      case Right(_) =>
        System.err.println(
          s"Invalid .torrent file: expected top-level dictionary")
        sys.exit(1)
      case Left(msg) =>
        System.err.println(s"Invalid .torrent file: $msg")
        sys.exit(1)
    }
    if (BencodeEncoder(torrentData) != torrentBytes.toSeq) {
      System.err.println(".torrent file is not a valid Bencode!")
      sys.exit(1)
    }

    val torrentInfo = torrentData("info").asInstanceOf[BDict]
    val baseAnnounceUri = Uri(
      new String(
        torrentData("announce").asInstanceOf[BByteString].value.toArray,
        "UTF-8"))
    val infoHash: Array[Byte] = MessageDigest
      .getInstance("SHA-1")
      .digest(BencodeEncoder(torrentInfo).toArray)

    val actorSystem = ActorSystem("show-peers-for-torrent")
    val tracker =
      actorSystem.actorOf(Tracker.props(baseAnnounceUri, infoHash), "tracker")
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
      PeerSwarmHandler.props(ByteString(infoHash),
                             ByteString("01234567890123456789")),
      "swarm")
    peers.foreach(swarm ! AddPeer(_))
    // Do not terminate the actor system
  }
}
