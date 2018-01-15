package net.yeputons.spbau.fall2017.scala.torrentclient.bencode

sealed trait BEntry
case class BByteString(value: Seq[Byte]) extends BEntry
case class BNumber(value: Long) extends BEntry
case class BList(value: BEntry*) extends BEntry
case class BDict(value: Map[Seq[Byte], BEntry]) extends BEntry

object BList {
  def empty: BList = BList()
}

object BDict {
  def apply(entries: (Seq[Byte], BEntry)*): BDict = BDict(entries.toMap)
  def empty: BDict = BDict(Map.empty[Seq[Byte], BEntry])
}
