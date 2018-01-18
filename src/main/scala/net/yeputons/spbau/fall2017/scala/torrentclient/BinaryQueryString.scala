package net.yeputons.spbau.fall2017.scala.torrentclient

/**
  * Builds HTTP query strings form both text and binary data.
  * E.g. `flag&binary=%00%ff%ba` is two parameters: `flag` and `binary=%00%ff%ba`.
  *
  * Standard methods allow constructing from [[String]] only, which cannot
  * store arbitrary octets.
  *
  * @param args List of an already existing arguments, properly escaped.
  *             They're not required to be of form `key=value`.
  */
class BinaryQueryString(args: Seq[String]) {

  /**
    * @return The exact query string which should be passed to the server.
    */
  def rawQueryString: Option[String] =
    if (args.isEmpty) None else Some(args.mkString("&"))

  /**
    * Appends a parameter of `key=value` form as the last parameter.
    * @param kv A tuple of `key` and `value`
    * @param encK Escapes `key` propely for HTTP query
    * @param encV Escapes `value` propely for HTTP query
    * @return A new builder with appended parameter.
    */
  def +[K, V](kv: (K, V))(implicit encK: BinaryQueryEncoder[K],
                          encV: BinaryQueryEncoder[V]): BinaryQueryString = {
    val s = encK(kv._1) + "=" + encV(kv._2)
    new BinaryQueryString(args :+ s)
  }
}

object BinaryQueryString {

  /**
    * Constructs a [[BinaryQueryString]] from an existing query string.
    */
  def apply(rawQueryString: Option[String]): BinaryQueryString =
    rawQueryString match {
      case None    => new BinaryQueryString(Seq.empty)
      case Some(s) => new BinaryQueryString(s.split('&'))
    }
}

/**
  * Escapes `T` so it can be used as an HTTP query parameter.
  */
trait BinaryQueryEncoder[-T] {
  def apply(v: T): String
}

object BinaryQueryEncoder {
  implicit object StringEncoder extends BinaryQueryEncoder[String] {
    override def apply(v: String): String =
      java.net.URLEncoder.encode(v, "UTF-8")
  }

  implicit object ArrayByteEncoder extends BinaryQueryEncoder[Seq[Byte]] {
    override def apply(v: Seq[Byte]): String =
      v.map(x => f"%%${x & 0xFF}%02X").mkString
  }
}
