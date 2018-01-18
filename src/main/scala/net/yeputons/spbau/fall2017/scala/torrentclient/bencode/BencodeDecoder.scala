package net.yeputons.spbau.fall2017.scala.torrentclient.bencode

import scala.collection.immutable
import scala.util.parsing.combinator._
import scala.util.parsing.input.{Position, Reader}

object BencodeDecoder extends Parsers {
  override type Elem = Byte

  /**
    * Deserializes a sequence of bytes into a Bencode entry.
    * @param value A single Bencode entry (e.g. a single list or a single dictionary), represented as bytes.
    * @return `Right(result)` if `value` was successfully and fully parsed.
    *        Left(message)` if there was an error during parsing, that error is described in `message`.
    */
  def apply(value: Seq[Byte]): Either[String, BEntry] =
    // value.tail may work in non-const time, which is undesirable as we
    // take .tail a lot in the reader, so let's convert it to List
    entry(new SeqByteReader(value.toList, new SeqBytePosition(value, 1))) match {
      case Success(result, _)   => Right(result)
      case NoSuccess(msg, next) => Left(s"$msg at position ${next.pos}")
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
    override def toString(): String = (column + 1).toString
    override protected def lineContents: String = new String(s.toArray)
    def next: SeqBytePosition = new SeqBytePosition(s, pos + 1)
  }

  lazy val entry: Parser[BEntry] =
    number | list | dict | string

  lazy val number: Parser[BNumber] =
    'i'.toByte ~> integer <~ 'e'.toByte ^^ BNumber

  lazy val list: Parser[BList] =
    'l'.toByte ~> rep(entry) <~ 'e'.toByte ^^ { x =>
      BList.apply(x: _*)
    }

  lazy val dict: Parser[BDict] =
    'd'.toByte ~> rep(dictItem) <~ 'e'.toByte ^^ (items => BDict(items.toMap))
  lazy val dictItem: Parser[(immutable.Seq[Byte], BEntry)] =
    string.map(_.value) ~ entry ^^ { case k ~ v => (k.to[immutable.Seq], v) }

  lazy val string: Parser[BByteString] =
    stringLength >> { len =>
      repN(len.toInt, elem("any", _ => true)).map(BByteString.apply)
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
