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

// I've encountered a Scala bug in this class: https://github.com/scala/bug/issues/10690
// To work around that, we check that no WrappedArray[Byte] is used as a key.
// In particular, we cannot simply use case class---that may leak potentially invalid map to
// a user.
class BDict(_value: Map[Seq[Byte], BEntry])
    extends BEntry
    with Map[Seq[Byte], BEntry]
    with MapLike[Seq[Byte], BEntry, BDict] {
  import BDict.normalizeKey

  val value: Map[Seq[Byte], BEntry] = _value.map { case (k, v) => (normalizeKey(k), v) }

  def get(key: String): Option[BEntry] = value.get(normalizeKey(key.getBytes("ASCII")))

  def apply(key: String): BEntry = value(normalizeKey(key.getBytes("ASCII")))

  override def empty: BDict = BDict.empty

  override def +[V1 >: BEntry](kv: (Seq[Byte], V1)): Map[Seq[Byte], V1] = {
    val (k, v) = kv
    value + ((BDict.normalizeKey(k), v))
  }

  override def get(key: Seq[Byte]): Option[BEntry] = value.get(normalizeKey(key))

  override def iterator: Iterator[(Seq[Byte], BEntry)] = value.iterator

  override def -(key: Seq[Byte]): BDict = BDict(value - normalizeKey(key))
}

object BByteString {
  def fromAsciiString(s: String): BByteString =
    BByteString(s.getBytes("ASCII").toSeq)
}

object BList {
  def empty: BList = BList()
}

object BDict {
  def apply(entries: Map[Seq[Byte], BEntry]): BDict = new BDict(entries)

  def apply(entries: (Seq[Byte], BEntry)*): BDict = new BDict(entries.toMap)

  def unapply(dict: BDict): Option[Map[Seq[Byte], BEntry]] = Some(dict.value)

  def fromAsciiStringKeys(entries: (String, BEntry)*): BDict =
    BDict(entries.map { case (k, v) => (k.getBytes("ASCII").toSeq, v) }.toMap)

  def empty: BDict = BDict(Map.empty[Seq[Byte], BEntry])

  private def normalizeKey(key: Seq[Byte]) = key match {
    case _: scala.collection.mutable.WrappedArray[Byte] => key.toList
    case _ => key
  }
}
