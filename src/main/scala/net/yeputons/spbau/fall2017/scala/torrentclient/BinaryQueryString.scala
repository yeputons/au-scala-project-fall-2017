package net.yeputons.spbau.fall2017.scala.torrentclient

class BinaryQueryString(args: Seq[String]) {
  def rawQueryString: Option[String] =
    if (args.isEmpty) None else Some(args.mkString("&"))

  def +[K, V](kv: (K, V))(implicit encK: BinaryQueryEncoder[K],
                          encV: BinaryQueryEncoder[V]): BinaryQueryString = {
    val s = encK(kv._1) + "=" + encV(kv._2)
    new BinaryQueryString(args :+ s)
  }
}

object BinaryQueryString {
  def apply(rawQueryString: Option[String]): BinaryQueryString =
    rawQueryString match {
      case None    => new BinaryQueryString(Seq.empty)
      case Some(s) => new BinaryQueryString(s.split('&'))
    }
}

trait BinaryQueryEncoder[-T] {
  def apply(v: T): String
}

object BinaryQueryEncoder {
  implicit object StringEncoder extends BinaryQueryEncoder[String] {
    override def apply(v: String): String = v
  }

  implicit object ArrayByteEncoder extends BinaryQueryEncoder[Array[Byte]] {
    override def apply(v: Array[Byte]): String =
      v.map(x => f"%%${x & 0xFF}%02x").mkString
  }
}
