package net.yeputons.spbau.fall2017.scala.torrentclient.apps

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import akka.actor.{ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.http.scaladsl.model.Uri
import akka.util.{ByteString, Timeout}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{
  GetPeers,
  PeerInformation,
  PeersListResponse
}
import net.yeputons.spbau.fall2017.scala.torrentclient.bencode._
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection

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
          |and prints a list of peers received from it.
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
    val tracker = actorSystem.actorOf(Tracker.props(baseAnnounceUri, infoHash))
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

    System.out.println(s"Got some peers:\n$peers")
    peers.foreach { peer =>
      System.out.println(f"Connecting to $peer...")
      val peerActor = actorSystem.actorOf(
        PeerConnection.props(ByteString(infoHash),
                             ByteString("01234567890123456789"),
                             peer),
        java.net.URLEncoder.encode(peer.address.getHostString, "ASCII") +
          "_" + peer.address.getPort
      )
    }
    // Do not terminate the actor system
  }
}
