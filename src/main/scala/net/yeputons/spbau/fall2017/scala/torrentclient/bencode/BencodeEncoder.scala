package net.yeputons.spbau.fall2017.scala.torrentclient.bencode

object BencodeEncoder {
  import scala.math.Ordering.Implicits.seqDerivedOrdering

  def apply(value: BEntry): Seq[Byte] = value match {
    case BByteString(str) => encode(str.length) ++ Seq(':'.toByte) ++ str
    case BNumber(num)     => Seq('i'.toByte) ++ encode(num) ++ Seq('e'.toByte)
    case BList(list) =>
      Seq('l'.toByte) ++ list.flatMap(apply) ++ Seq('e'.toByte)
    case BDict(dict) =>
      Seq('d'.toByte) ++
        dict.toSeq
          .sortBy(_._1)
          .flatMap {
            case (k, v) => apply(BByteString(k)) ++ apply(v)
          } ++
        Seq('e'.toByte)
  }

  private def encode(n: Long): Seq[Byte] = n.toString.getBytes
}
