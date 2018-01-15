package net.yeputons.spbau.fall2017.scala.torrentclient.bencode

import scala.collection.{MapLike, SeqLike, mutable}

sealed trait BEntry
case class BByteString(value: Seq[Byte]) extends BEntry

case class BNumber(value: Long) extends BEntry

case class BList(value: BEntry*)
    extends BEntry
    with Seq[BEntry]
    with SeqLike[BEntry, BList] {
  override def apply(idx: Int): BEntry = value(idx)

  override def iterator: Iterator[BEntry] = value.iterator

  override def length: Int = value.length

  override protected[this] def newBuilder: mutable.Builder[BEntry, BList] =
    Seq.newBuilder.mapResult { x =>
      BList(x: _*)
    }
}

case class BDict(value: Map[Seq[Byte], BEntry])
    extends BEntry
    with Map[Seq[Byte], BEntry]
    with MapLike[Seq[Byte], BEntry, BDict] {
  def get(key: String): Option[BEntry] = value.get(key.getBytes("ASCII"))

  def apply(key: String): BEntry = value(key.getBytes("ASCII"))

  override def empty: BDict = BDict.empty

  override def +[V1 >: BEntry](kv: (Seq[Byte], V1)): Map[Seq[Byte], V1] =
    value + kv

  override def get(key: Seq[Byte]): Option[BEntry] = value.get(key)

  override def iterator: Iterator[(Seq[Byte], BEntry)] = value.iterator

  override def -(key: Seq[Byte]): BDict = BDict(value - key)
}

object BByteString {
  def fromAsciiString(s: String): BByteString =
    BByteString(s.getBytes("ASCII").toSeq)
}

object BList {
  def empty: BList = BList()
}

object BDict {
  def apply(entries: (Seq[Byte], BEntry)*): BDict = BDict(entries.toMap)
  def fromAsciiStringKeys(entries: (String, BEntry)*): BDict =
    BDict(entries.map { case (k, v) => (k.getBytes("ASCII").toSeq, v) }.toMap)
  def empty: BDict = BDict(Map.empty[Seq[Byte], BEntry])
}
