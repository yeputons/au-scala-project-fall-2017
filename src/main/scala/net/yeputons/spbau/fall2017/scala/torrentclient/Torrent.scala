package net.yeputons.spbau.fall2017.scala.torrentclient

import java.security.MessageDigest

import akka.http.scaladsl.model.Uri
import net.yeputons.spbau.fall2017.scala.torrentclient.bencode._

case class Torrent(announce: Uri,
                   infoHash: Seq[Byte],
                   pieceLength: Int,
                   pieceHashes: Seq[Seq[Byte]],
                   fileLength: Long)

object Torrent {
  def apply(torrentFile: BEntry): Torrent = {
    import BEntry.Conversions
    val torrentData: BDict = torrentFile.getDict

    val announceBytes = torrentData("announce").getByteString.toArray
    val announce = Uri(new String(announceBytes, "UTF-8"))

    val info = torrentData("info").getDict
    val infoHash: Array[Byte] = MessageDigest
      .getInstance("SHA-1")
      .digest(BencodeEncoder(info).toArray)

    val pieceLength = info("piece length").getNumber.toInt

    val pieces = info("pieces").getByteString
    require(pieces.length % 20 == 0)
    val pieceHashes = pieces.grouped(20).toSeq

    val fileLength = info("length").getNumber

    Torrent(announce, infoHash, pieceLength, pieceHashes, fileLength)
  }

  def apply(torrentBytes: Seq[Byte]): Torrent = {
    val torrentFile = BencodeDecoder(torrentBytes) match {
      case Right(data) => data
      case Left(msg) =>
        throw new IllegalArgumentException(s"Invalid .torrent data: $msg")
    }
    if (BencodeEncoder(torrentFile) != torrentBytes) {
      throw new IllegalArgumentException(
        ".torrent data is not a canonical Bencode")
    }
    Torrent(torrentFile)
  }
}
