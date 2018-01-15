package net.yeputons.spbau.fall2017.scala.torrentclient.apps

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{GetPeers, Peer, PeersListResponse}
import net.yeputons.spbau.fall2017.scala.torrentclient.bencode._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

object ShowPeersForTorrentApp {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      System.err.println("Expected arguments: <path-to-torrent-file>")
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

    val torrentInfo = torrentData("info".getBytes()).asInstanceOf[BDict]
    val baseAnnounceUri = Uri(
      new String(
        torrentData("announce").asInstanceOf[BByteString].value.toArray,
        "UTF-8"))
    val infoHash: Array[Byte] = MessageDigest.getInstance("SHA-1").digest(BencodeEncoder(torrentInfo).toArray)

    val actorSystem = ActorSystem("show-peers-for-torrent")
    val tracker = actorSystem.actorOf(Tracker.props(baseAnnounceUri, infoHash))
    implicit val timeout: Timeout = Timeout(5.seconds)

    val random = new scala.util.Random()
    var peers = Set.empty[Peer]
    while (peers.isEmpty) {
      Thread.sleep(1000)
      val id = random.nextLong()
      Await.result(tracker ? GetPeers(id), Duration.Inf) match {
        case PeersListResponse(`id`, newPeers) =>
          peers = newPeers
      }
    }
    actorSystem.terminate()

    System.out.println(s"Got some peers:\n$peers")
  }
}
