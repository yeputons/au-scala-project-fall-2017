package net.yeputons.spbau.fall2017.scala.torrentclient.apps

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.{ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import net.yeputons.spbau.fall2017.scala.torrentclient.{FileTorrentDownloader, Torrent, Tracker}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{GetPeers, PeerInformation, PeersListResponse}
import net.yeputons.spbau.fall2017.scala.torrentclient.apps.ShowPeersForTorrentApp.getClass
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler._
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, _}

object DownloadTorrentApp {
  val log = LoggerFactory.getLogger(getClass)
  implicit val executionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.global

  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      System.err.println(
        """
          |Expected arguments: <path-to-torrent-file> <output-file>
          |
          |This test app parses a .torrent file and tries to download it
          |to a specific file.
        """.stripMargin)
      sys.exit(1)
    }
    val torrentBytes: Array[Byte] = Files.readAllBytes(Paths.get(args(0)))
    val torrent: Torrent = Torrent(torrentBytes)
    val storageFile = new File(args(1))
    if (!storageFile.exists()) {
      if (!storageFile.createNewFile()) {
        System.err.println(s"Cannot create new file at $storageFile")
        sys.exit(1)
      }
    }
    if (!storageFile.canWrite) {
      System.err.println(s"Cannot write to $storageFile")
      sys.exit(1)
    }

    val actorSystem = ActorSystem("download-torrent")
    val downloader = actorSystem.actorOf(FileTorrentDownloader.props(torrent, storageFile), "torrent")
    // Do not terminate the actor system
  }
}
