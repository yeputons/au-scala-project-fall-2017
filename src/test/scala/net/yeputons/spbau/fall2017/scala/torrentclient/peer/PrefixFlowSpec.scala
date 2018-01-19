package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class PrefixFlowSpec
    extends TestKit(ActorSystem("ExpectPrefixFlowSpec"))
    with WordSpecLike
    with Matchers {
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  "ExpectPrefixFlow" when {
    def run(prefix: String) =
      TestSource
        .probe[ByteString]
        .via(ExpectPrefixFlow(ByteString(prefix)))
        .toMat(TestSink.probe[ByteString])(Keep.both)
        .run()

    "prefix is empty" must {
      "pass data intact" in {
        val (pub, sub) = run("")
        pub.sendNext(ByteString("hello"))
        pub.sendNext(ByteString("cold"))
        pub.sendNext(ByteString("world"))
        sub.request(1)
        sub.expectNext(ByteString("hello"))
        sub.request(2)
        sub.expectNext(ByteString("cold"))
        sub.expectNext(ByteString("world"))
        pub.sendComplete()
        sub.expectComplete()
      }

      "pass errors intact" in {
        val (pub, sub) = run("")
        sub.request(1)
        pub.sendNext(ByteString("hello"))
        sub.expectNext(ByteString("hello"))
        val err = new Exception()
        pub.sendError(err)
        sub.expectError(err)
      }
    }

    "prefix matches" must {
      "detect prefix as a single message" in {
        val (pub, sub) = run("prefix")
        sub.request(1)
        pub.sendNext(ByteString("prefix"))
        sub.expectNoMessage(100.milliseconds)
        pub.sendNext(ByteString("message"))
        sub.expectNext(ByteString("message"))
        pub.sendComplete()
        sub.expectComplete()
      }
      "detect partitioned prefix" in {
        val (pub, sub) = run("prefix")
        sub.request(1)
        pub.sendNext(ByteString("pref"))
        sub.expectNoMessage(100.milliseconds)
        pub.sendNext(ByteString("ix"))
        sub.expectNoMessage(100.milliseconds)
        pub.sendNext(ByteString("message"))
        sub.expectNext(ByteString("message"))
        pub.sendComplete()
        sub.expectComplete()
      }
      "detect prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.request(1)
        pub.sendNext(ByteString("prefixmessage"))
        sub.expectNext(ByteString("message"))
        pub.sendComplete()
        sub.expectComplete()
      }
      "detect partitioned prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.request(1)
        pub.sendNext(ByteString("pref"))
        sub.expectNoMessage(100.milliseconds)
        pub.sendNext(ByteString("ixmessage"))
        sub.expectNext(ByteString("message"))
        pub.sendComplete()
        sub.expectComplete()
      }
    }

    "prefix mismatches with no pull request" must {
      "detect prefix as a single message" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()
        pub.sendNext(ByteString("prefiy"))
        sub.expectError(
          PrefixMismatchException(ByteString("prefiy"), ByteString("prefix")))
      }
      "detect partitioned prefix" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()
        pub.sendNext(ByteString("prefi"))
        sub.expectNoMessage(100.milliseconds)
        pub.sendNext(ByteString("y"))
        sub.expectError(
          PrefixMismatchException(ByteString("prefiy"), ByteString("prefix")))
      }
      "detect prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()
        pub.sendNext(ByteString("prefiymessage"))
        sub.expectError(
          PrefixMismatchException(ByteString("prefiy"), ByteString("prefix")))
      }
      "detect partitioned prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()
        pub.sendNext(ByteString("pref"))
        sub.expectNoMessage(100.milliseconds)
        pub.sendNext(ByteString("iymessage"))
        sub.expectError(
          PrefixMismatchException(ByteString("prefiy"), ByteString("prefix")))
      }
    }
  }

  "TakePrefixFlow" when {
    def run(n: Int, data: String*): immutable.Seq[String] =
      Await
        .result(
          Source(immutable.Seq(data: _*).map(ByteString(_)))
            .via(TakePrefixFlow[Byte, ByteString](n))
            .runWith(Sink.seq),
          100.milliseconds
        )
        .map(_.utf8String)

    "n=0" in {
      run(0, "foo", "bar", "baz") shouldBe Seq("", "foo", "bar", "baz")
    }
    "prefix is a separate message" in {
      run(2, "hi", "hello", "world") shouldBe Seq("hi", "hello", "world")
    }
    "prefix is splitted across messages" in {
      run(5, "he", "l", "lo", "world") shouldBe Seq("hello", "world")
    }
    "prefix and messages are chunked arbitrary" in {
      run(5, "he", "l", "lowo", "rld") shouldBe Seq("hello", "wo", "rld")
    }
  }
}
