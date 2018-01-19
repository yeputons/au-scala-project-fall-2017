package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestKit
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable
import scala.collection.immutable.WrappedString
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
        .probe[String]
        .map(new WrappedString(_))
        .via(ExpectPrefixFlow[Char, WrappedString](new WrappedString(prefix)))
        .map(_.self)
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

    "prefix is empty" must {
      "pass data intact" in {
        val (pub, sub) = run("")

        pub.sendNext("hello")
        pub.sendNext("cold")
        pub.sendNext("world")

        sub.request(1)
        sub.expectNext("hello")
        sub.request(2)
        sub.expectNext("cold")
        sub.expectNext("world")

        pub.sendComplete()
        sub.expectComplete()
      }

      "pass errors intact" in {
        val (pub, sub) = run("")
        sub.request(1)

        pub.sendNext("hello")
        sub.expectNext("hello")

        val err = new Exception()
        pub.sendError(err)
        sub.expectError(err)
      }
    }

    "prefix matches" must {
      "detect prefix as a single message" in {
        val (pub, sub) = run("prefix")
        sub.request(1)

        pub.sendNext("prefix")
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("message")
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
      "detect partitioned prefix" in {
        val (pub, sub) = run("prefix")
        sub.request(1)

        pub.sendNext("pref")
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("ix")
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("message")
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
      "detect prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.request(1)

        pub.sendNext("prefixmessage")
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
      "detect partitioned prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.request(1)

        pub.sendNext("pref")
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("ixmessage")
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
    }

    "prefix mismatches with no pull request" must {
      "detect prefix as a single message" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()

        pub.sendNext("prefiy")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
      }
      "detect partitioned prefix" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()

        pub.sendNext("prefi")
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("y")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
      }
      "detect prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()

        pub.sendNext("prefiymessage")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
      }
      "detect partitioned prefix with a message" in {
        val (pub, sub) = run("prefix")
        sub.ensureSubscription()

        pub.sendNext("pref")
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("iymessage")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
      }
    }
  }

  "TakePrefixFlow" when {
    def run(n: Int, data: String*): immutable.Seq[String] =
      Await
        .result(
          Source(immutable.Seq(data: _*).map(new WrappedString(_)))
            .via(TakePrefixFlow[Char, WrappedString](n))
            .map(_.self)
            .runWith(Sink.seq),
          100.milliseconds
        )

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
