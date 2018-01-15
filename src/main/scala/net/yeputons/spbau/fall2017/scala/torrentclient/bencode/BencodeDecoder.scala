package net.yeputons.spbau.fall2017.scala.torrentclient.bencode

import scala.util.parsing.combinator._
import scala.util.parsing.input.{Position, Reader}

case class BencodeDecodingException(message: String)
    extends IllegalArgumentException(s"Unable to decode Bencode: $message")

object BencodeDecoder extends Parsers {
  override type Elem = Byte

  def apply(value: Seq[Byte]): BEntry =
    entry(new SeqByteReader(value, new SeqBytePosition(value, 1))) match {
      case Success(result, _) => result
      case NoSuccess(msg, _)  => throw BencodeDecodingException(msg)
    }

  private class SeqByteReader(s: Seq[Byte], val pos: SeqBytePosition)
      extends Reader[Byte] {
    override def first: Byte = s.head

    override def rest: Reader[Byte] = new SeqByteReader(s.tail, pos.next)

    override def atEnd: Boolean = s.isEmpty
  }

  private class SeqBytePosition(s: Seq[Byte], pos: Int) extends Position {
    override def line: Int = 1
    override def column: Int = pos
    override protected def lineContents: String = new String(s.toArray)
    def next: SeqBytePosition = new SeqBytePosition(s, pos + 1)
  }

  lazy val entry: Parser[BEntry] =
    number | list | dict | string

  lazy val number: Parser[BNumber] =
    'i'.toByte ~> integer <~ 'e'.toByte ^^ BNumber

  lazy val list: Parser[BList] =
    'l'.toByte ~> rep(entry) <~ 'e'.toByte ^^ BList

  lazy val dict: Parser[BDict] =
    'd'.toByte ~> rep(dictItem) <~ 'e'.toByte ^^ (items => BDict(items.toMap))
  lazy val dictItem: Parser[(Seq[Byte], BEntry)] =
    string.map(_.value) ~ entry ^^ { x =>
      (x._1, x._2)
    }

  lazy val string: Parser[BByteString] =
    stringLength >> { len =>
      repN(len.toInt, elem("any", _ => true)).map(BByteString)
    }
  lazy val stringLength: Parser[Long] =
    integer <~ ':'.toByte

  lazy val integer: Parser[Long] =
    (digits ^^ identity) | ('-'.toByte ~> digits ^^ (-_))
  lazy val digits: Parser[Long] =
    rep1(digit) ^^ { bytes =>
      new String(bytes.toArray).toLong
    }
  lazy val digit: Parser[Byte] = elem("digit", c => c >= '0' && c <= '9')
}
