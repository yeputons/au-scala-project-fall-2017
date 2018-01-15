package net.yeputons.spbau.fall2017.scala.torrentclient

import org.scalatest.{Matchers, WordSpecLike}

class BinaryQueryStringSpec extends WordSpecLike with Matchers {
  private val Binary1 = Seq(0, 38, 61, 99, 127, 128, 250, 255).map(_.toByte)
  private val BinaryEncoded1 = "%00%26%3D%63%7F%80%FA%FF"
  private val Binary2 = Seq(0, 38, 61, 100, 127, 128, 251, 255).map(_.toByte)
  private val BinaryEncoded2 = "%00%26%3D%64%7F%80%FB%FF"

  "BinaryQueryString" when {
    "empty" must {
      "return None as raw value" in {
        new BinaryQueryString(Seq()).rawQueryString shouldBe None
      }
      "append String -> String" in {
        (new BinaryQueryString(Seq()) + ("foo" -> "bar")).rawQueryString shouldBe
          Some("foo=bar")
      }
      "append unescaped String -> String" in {
        (new BinaryQueryString(Seq()) + ("foo&= !" -> "bar !")).rawQueryString shouldBe
          Some("foo%26%3D+%21=bar+%21")
      }
      "append String -> Seq[Byte]" in {
        (new BinaryQueryString(Seq()) + ("foo !" -> Binary1)).rawQueryString shouldBe
          Some(s"foo+%21=$BinaryEncoded1")
      }
      "append Seq[Byte] -> String" in {
        (new BinaryQueryString(Seq()) + (Binary1 -> "foo !")).rawQueryString shouldBe
          Some(s"$BinaryEncoded1=foo+%21")
      }
      "append Seq[Byte] -> Seq[Byte]" in {
        (new BinaryQueryString(Seq()) + (Binary1 -> Binary2)).rawQueryString shouldBe
          Some(s"$BinaryEncoded1=$BinaryEncoded2")
      }
      "append multiple parameters" in {
        (new BinaryQueryString(Seq()) +
          ("foo&= !" -> "bar&= !") +
          ("foo&= !" -> Binary1) +
          (Binary1 -> "foo&= !") +
          (Binary1 -> Binary2)).rawQueryString shouldBe
          Some(
            "foo%26%3D+%21=bar%26%3D+%21" + "&" +
              s"foo%26%3D+%21=$BinaryEncoded1" + "&" +
              s"$BinaryEncoded1=foo%26%3D+%21" + "&" +
              s"$BinaryEncoded1=$BinaryEncoded2"
          )
      }
    }
    "initialized from a query" must {
      val q = "foo=bar&xxx=yyy&%FF=%f0%aB"
      "return the same query" in {
        BinaryQueryString(Some(q)).rawQueryString shouldBe Some(q)
      }
      "append multiple parameters" in {
        (BinaryQueryString(Some(q)) + ("baz&=" -> "boo&=") + (Binary1 -> Binary2)).rawQueryString shouldBe
          Some(s"$q&baz%26%3D=boo%26%3D&$BinaryEncoded1=$BinaryEncoded2")
      }
    }
  }
}
