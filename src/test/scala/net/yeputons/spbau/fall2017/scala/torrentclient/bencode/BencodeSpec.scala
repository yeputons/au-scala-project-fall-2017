package net.yeputons.spbau.fall2017.scala.torrentclient.bencode

import java.io.File
import java.nio.file.Files

import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable.Seq

class BencodeSpec extends WordSpecLike with Matchers {
  type Examples[T] = Map[String, Map[String, T]]

  val examples: Examples[(BEntry, Seq[Byte])] = Map(
    "strings" -> Map(
      "empty byte string" -> (BByteString(Seq.empty), Seq[Byte](48, 58)), // '0:'
      "text string" -> (
        BByteString.fromAsciiString("Hello"),
        "5:Hello".getBytes().to[Seq] // '0:'
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
    ),
    "lists" -> Map(
      "empty list" -> (BList.empty, Seq[Byte](108, 101)), // le
      "list of text strings" -> (
        BList(
          BByteString.fromAsciiString("hi"),
          BByteString.fromAsciiString("atomeel"),
          BByteString.fromAsciiString("zoo"),
        ),
        "l2:hi7:atomeel3:zooe".getBytes().to[Seq]
      ),
      "list of strings, ints and lists" -> (
        BList(
          BByteString.fromAsciiString("hello"),
          BNumber(-100),
          BByteString.fromAsciiString("hello"),
          BList(
            BNumber(4),
            BByteString.fromAsciiString("llele"),
            BList(),
          ),
          BNumber(100),
        ),
        "l5:helloi-100e5:helloli4e5:lleleleei100ee".getBytes().to[Seq]
      ),
    ),
    "dicts" -> Map(
      "empty dict" -> (BDict.empty, Seq[Byte](100, 101)), // de
      "dict of ints" -> (
        BDict(
          Seq[Byte](97, 98) -> BNumber(1), // ab -> 1
          Seq[Byte](97, 97) -> BNumber(0) // aa -> 0
        ),
        ("d" + ("2:aa" + "i0e") + ("2:ab" + "i1e") + "e").getBytes().to[Seq]
      ),
      "dict of ints, text strings, and lists" -> (
        BDict.fromAsciiStringKeys(
          "number" -> BNumber(-100),
          "hello" -> BByteString.fromAsciiString("worlde"),
          "m" -> BDict.fromAsciiStringKeys(
            "list" -> BList(BNumber(5), BNumber(0)),
          ),
        ),
        ("d" +
          ("5:hello" + "6:worlde") +
          ("1:m" + "d4:listli5ei0eee") +
          ("6:number" + "i-100e") +
          "e").getBytes().to[Seq],
      ),
    ),
  )

  val encodesDecodedExamples: Examples[Seq[Byte]] =
    examples.mapValues(_.mapValues(_._2)) +
      ("real torrents" ->
        new File(getClass.getResource("/torrents").toURI.getPath)
          .listFiles()
          .toSeq
          .map { f =>
            f.getName -> Files.readAllBytes(f.toPath).to[Seq]
          }
          .toMap)
  val decodesEncodedExamples: Examples[BEntry] =
    examples.mapValues(_.mapValues(_._1))

  "BencodeEncoder(BencodeDecoder(_))" when {
    encodesDecodedExamples.foreach {
      case (groupName, elements) =>
        s"working with $groupName".must {
          elements.foreach {
            case (elemName, encoded) =>
              s"encodes decoded $elemName" in {
                val Right(decoded) = BencodeDecoder(encoded)
                BencodeEncoder(decoded) shouldBe encoded
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
                BencodeDecoder(BencodeEncoder(decoded)) shouldBe Right(decoded)
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
                BencodeDecoder(encoded) shouldBe Right(decoded)
              }
          }
        }
    }
  }

  "BList" when {
    "empty" must {
      "be equal to empty Seq" in {
        BList.empty shouldBe Seq()
      }
      "be equal to itself" in {
        BList.empty shouldBe BList.empty
      }
    }
    "has some data" must {
      "be equal to a Seq with that data" in {
        BList(BNumber(20), BNumber(30)) shouldBe Seq(BNumber(20), BNumber(30))
      }
      "be equal to itself" in {
        val l = BList(BNumber(20), BNumber(30))
        l shouldBe l
      }
      "provides by-index access" in {
        val l = BList(BNumber(20), BNumber(30))
        l(0) shouldBe BNumber(20)
        l(1) shouldBe BNumber(30)
      }
      "work with map correctly" in {
        BList(BNumber(20), BNumber(30)).map {
          case BNumber(x) => BNumber(x + 1)
          case x          => x
        } shouldBe
          BList(BNumber(21), BNumber(31))
      }
    }
  }

  "BDict" when {
    "empty" must {
      "be equal to empty Map" in {
        BDict.empty shouldBe Map()
      }
      "be equal to itself" in {
        BDict.empty shouldBe BDict.empty
      }
    }
    "has some data" must {
      "be equal to a Map with that data" in {
        BDict.fromAsciiStringKeys(
          "0" -> BNumber(10),
          "1" -> BNumber(20)
        ) shouldBe Map(
          Seq[Byte](48) -> BNumber(10),
          Seq[Byte](49) -> BNumber(20)
        )
      }
      "be equal to itself" in {
        val d = BDict.fromAsciiStringKeys(
          "0" -> BNumber(10),
          "1" -> BNumber(20)
        )
        d shouldBe d
      }
      "provides by-Seq[Byte] access" in {
        val d = BDict(
          Seq[Byte](0, -127) -> BNumber(10),
          Seq[Byte](1, 127) -> BNumber(20)
        )
        d(Seq[Byte](0, -127)) shouldBe BNumber(10)
        d.get(Seq[Byte](1, 127)) shouldBe Some(BNumber(20))
        d.get(Seq[Byte](0)) shouldBe None
      }
      "provides by-String access" in {
        val d = BDict.fromAsciiStringKeys(
          "0" -> BNumber(10),
          "1" -> BNumber(20)
        )
        d("0") shouldBe BNumber(10)
        d.get("1") shouldBe Some(BNumber(20))
        d.get("foo") shouldBe None
      }
      "work with map correctly" in {
        BDict
          .fromAsciiStringKeys(
            "0" -> BNumber(10),
            "1" -> BNumber(20)
          )
          .map {
            case (Seq(k), BNumber(v)) =>
              (Seq(k + 1).map(_.toByte), BNumber(v + 2))
          } shouldBe Map(
          Seq[Byte](49) -> BNumber(12),
          Seq[Byte](50) -> BNumber(22)
        )
      }
    }
  }
}
