package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestKit
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable
import scala.collection.immutable.WrappedString
import scala.concurrent.{Await, TimeoutException}
import scala.concurrent.duration._
import scala.util.Failure

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
        .viaMat(
          ExpectPrefixFlow[Char, WrappedString, Unit](new WrappedString(prefix))
        )(Keep.both)
        .map(_.self)
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

    "prefix is empty" must {
      "pass data intact" in {
        val ((pub, f), sub) = run("")
        Await.result(f, 100.milliseconds)

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
        val ((pub, f), sub) = run("")
        Await.result(f, 100.milliseconds)
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
        val ((pub, f), sub) = run("prefix")

        pub.sendNext("prefix")
        Await.result(f, 100.milliseconds)

        sub.request(1)
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("message")
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
      "detect partitioned prefix" in {
        val ((pub, f), sub) = run("prefix")

        pub.sendNext("pref")

        an[TimeoutException] should be thrownBy Await.ready(f, 100.milliseconds)
        sub.request(1)
        sub.expectNoMessage(100.milliseconds)
        f.isCompleted shouldBe false

        pub.sendNext("ix")
        Await.result(f, 100.milliseconds)
        sub.expectNoMessage(100.milliseconds)

        pub.sendNext("message")
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
      "detect prefix with a message" in {
        val ((pub, f), sub) = run("prefix")

        pub.sendNext("prefixmessage")
        Await.result(f, 100.milliseconds)

        sub.request(1)
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
      "detect partitioned prefix with a message" in {
        val ((pub, f), sub) = run("prefix")

        pub.sendNext("pref")
        an[TimeoutException] should be thrownBy Await.ready(f, 100.milliseconds)
        sub.request(1)
        sub.expectNoMessage(100.milliseconds)
        f.isCompleted shouldBe false

        pub.sendNext("ixmessage")
        Await.result(f, 100.milliseconds)
        sub.expectNext("message")

        pub.sendComplete()
        sub.expectComplete()
      }
    }

    "prefix mismatches with no pull request" must {
      "detect prefix as a single message" in {
        val ((pub, f), sub) = run("prefix")
        sub.ensureSubscription()

        f.isCompleted shouldBe false
        pub.sendNext("prefiy")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
        Await.ready(f, 100.milliseconds).value.get shouldBe a[Failure[_]]
      }
      "detect partitioned prefix" in {
        val ((pub, f), sub) = run("prefix")
        sub.ensureSubscription()

        pub.sendNext("prefi")
        sub.expectNoMessage(100.milliseconds)
        f.isCompleted shouldBe false

        pub.sendNext("y")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
        Await.ready(f, 100.milliseconds).value.get shouldBe a[Failure[_]]
      }
      "detect prefix with a message" in {
        val ((pub, f), sub) = run("prefix")
        sub.ensureSubscription()

        pub.sendNext("prefiymessage")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
        Await.ready(f, 100.milliseconds).value.get shouldBe a[Failure[_]]
      }
      "detect partitioned prefix with a message" in {
        val ((pub, f), sub) = run("prefix")
        sub.ensureSubscription()

        pub.sendNext("pref")
        sub.expectNoMessage(100.milliseconds)
        f.isCompleted shouldBe false

        pub.sendNext("iymessage")
        sub.expectError(
          PrefixMismatchException[WrappedString]("prefiy", "prefix"))
        Await.ready(f, 100.milliseconds).value.get shouldBe a[Failure[_]]
      }
    }

    "prefix is too short" must {
      "fail without pull requests" in {
        val ((pub, f), sub) = run("prefix")
        sub.ensureSubscription()

        f.isCompleted shouldBe false
        pub.sendNext("prefi")
        pub.sendComplete()
        sub.expectError(PrefixTooShortException[WrappedString]("prefi", 6))
        Await.ready(f, 100.milliseconds).value.get shouldBe a[Failure[_]]
      }
    }
  }

  "TakePrefixFlow" when {
    def run(n: Int, data: String*): immutable.Seq[String] =
      Await
        .result(
          Source(immutable.Seq(data: _*).map(new WrappedString(_)))
            .via(new TakePrefixFlow[Char, WrappedString](n))
            .map(_.self)
            .runWith(Sink.seq),
          100.milliseconds
        )

    def runProbes(n: Int) =
      TestSource
        .probe[WrappedString]
        .via(new TakePrefixFlow[Char, WrappedString](n))
        .toMat(TestSink.probe[WrappedString])(Keep.both)
        .run()

    "n=0" must {
      "send correct data" in {
        run(0, "foo", "bar", "baz") shouldBe Seq("", "foo", "bar", "baz")
      }
      "send first value without pulling the input" in {
        val (pub, sub) = runProbes(0)
        sub.ensureSubscription()

        sub.request(1)
        sub.expectNext("")

        sub.request(1)
        sub.expectNoMessage(100.milliseconds)
        pub.sendNext("hello")
        sub.expectNext("hello")

        pub.sendComplete()
        sub.expectComplete()
      }
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
    "prefix is longer than the message" in {
      the[PrefixTooShortException[WrappedString]] thrownBy {
        run(5, "hi", "me")
      } shouldBe PrefixTooShortException[WrappedString]("hime", 5)
    }
  }
}
