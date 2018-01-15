package net.yeputons.spbau.fall2017.scala.torrentclient.bencode

import org.scalatest.{Matchers, WordSpecLike}

class BencodeSpec extends WordSpecLike with Matchers {
  type Examples[T] = Map[String, Map[String, T]]

  val examples: Examples[(BEntry, Seq[Byte])] = Map(
    "strings" -> Map(
      "empty byte string" -> (BByteString(Seq.empty), Seq[Byte](48, 58)), // '0:'
      "text string" -> (
        BByteString("Hello".getBytes()),
        "5:Hello".getBytes().toSeq // '0:'
      ), {
        val data = (0 to 523).map(x => (x & 0xFF).toByte)
        "all byte string" -> (BByteString(data), Seq[Byte](53, 50, 52, 58) ++ data) // '524:...'
      },
    ),
    "numbers" -> Map(
      "zero" -> (BNumber(0), Seq[Byte](105, 48, 101)), // i0e
      "one" -> (BNumber(1), Seq[Byte](105, 49, 101)),
      "negative one" -> (BNumber(-1), Seq[Byte](105, 45, 49, 101)), // i-1e
      "big number" -> (
        BNumber(1234567890123L),
        Seq[Byte](105, 49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 49, 50, 51, 101)
      ),
      "negative big number" -> (
        BNumber(-1234567890123L),
        Seq[Byte](105, 45, 49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 49, 50, 51,
          101)
      ),
    )
  )

  val encodesDecodedExamples: Examples[Seq[Byte]] = examples.mapValues(_.mapValues(_._2))
  val decodesEncodedExamples: Examples[BEntry] = examples.mapValues(_.mapValues(_._1))

  "BencodeEncoder(BencodeDecoder(_))" when {
    encodesDecodedExamples.foreach {
      case (groupName, elements) =>
        s"working with $groupName".must {
          elements.foreach {
            case (elemName, encoded) =>
              s"encodes decoded $elemName" in {
                BencodeEncoder(BencodeDecoder(encoded)) shouldBe encoded
              }
          }
        }
    }
  }

  "BencodeDecoder(BencodeEncoder(_))" when {
    decodesEncodedExamples.foreach {
      case (groupName, elements) =>
        s"working with $groupName".must {
          elements.foreach {
            case (elemName, decoded) =>
              s"decodes encoded $elemName" in {
                BencodeDecoder(BencodeEncoder(decoded)) shouldBe decoded
              }
          }
        }
    }
  }

  "BencodeEncoder" when {
    examples.foreach {
      case (groupName, elements) =>
        s"encoding $groupName".must {
          elements.foreach {
            case (elemName, (decoded, encoded)) =>
              s"work with $elemName" in {
                BencodeEncoder(decoded) shouldBe encoded
              }
          }
        }
    }
  }

  "BencodeDecoder" when {
    examples.foreach {
      case (groupName, elements) =>
        s"decoding $groupName".must {
          elements.foreach {
            case (elemName, (decoded, encoded)) =>
              s"work with $elemName" in {
                BencodeDecoder(encoded) shouldBe decoded
              }
          }
        }
    }
  }
}
