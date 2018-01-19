package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class PeerProtocolSpec
    extends TestKit(ActorSystem("peerProtocolSpec"))
    with WordSpecLike
    with Matchers {
  implicit val materializer: Materializer = ActorMaterializer()

  "PeerFraming atop Handshake" must {
    val infoHash = ByteString("this--is--info--hash")
    val myPeerId = ByteString("this-is-some-peer-id")
    val otherPeerId = ByteString("this-is-other-peerid")

    "work when looped" in {
      val loop = PeerFraming()
        .atop(PeerHandshake(infoHash, myPeerId, otherPeerId))
        .atop(PeerHandshake(infoHash, otherPeerId, myPeerId).reversed)
        .atop(PeerFraming().reversed)
        .join(Flow.fromFunction(identity))
      val (pub, sub) =
        TestSource
          .probe[ByteString]
          .via(loop)
          .toMat(TestSink.probe[ByteString])(Keep.both)
          .run()
      pub.sendNext(ByteString("hello"))
      pub.sendNext(ByteString.empty)
      pub.sendNext(ByteString("meow"))
      sub.request(1)
      sub.expectNext(ByteString("hello"))
      sub.request(1)
      sub.expectNext(ByteString.empty)
      sub.request(2)
      sub.expectNext(ByteString("meow"))
      sub.expectNoMessage(100.milliseconds)

      pub.sendNext(ByteString("foo"))
      sub.expectNext(ByteString("foo"))
    }

    "fail when looped and peer id is incorrect" in {
      val loop = PeerFraming()
        .atop(PeerHandshake(infoHash, myPeerId, myPeerId))
        .atop(PeerHandshake(infoHash, otherPeerId, myPeerId).reversed)
        .atop(PeerFraming().reversed)
        .join(Flow.fromFunction(identity))
      val (pub, sub) =
        TestSource
          .probe[ByteString]
          .via(loop)
          .toMat(TestSink.probe[ByteString])(Keep.both)
          .run()
      sub.ensureSubscription()
      sub.expectError()
    }

    "send and receive correct bytes" in {
      val bigString = ByteString(Array.fill[Byte](258)(-1))

      val localSource =
        Source(immutable.Seq(ByteString("hi"), bigString, ByteString("hello")))
      val remoteSource = Source(
        immutable.Seq(
          ByteString("19BitTorrent protocol") ++
            ByteString(0, 0, 0, 0, 0, 0, 0, 0),
          infoHash ++ otherPeerId ++ ByteString(0, 0, 0, 3),
          ByteString("wow"),
          ByteString(0, 0, 0, 2) ++ ByteString("it") ++ ByteString(0, 0, 0, 0),
          ByteString(0, 0, 1, 2) ++ bigString
        )
      )

      val flow
        : Flow[ByteString, ByteString, Future[immutable.Seq[ByteString]]] =
        PeerFraming()
          .atop(PeerHandshake(infoHash, myPeerId, otherPeerId))
          .joinMat(
            Flow.fromSinkAndSourceMat(Sink.seq[ByteString], remoteSource)(
              Keep.left))(Keep.right)
      val (remoteResult, localResult) =
        localSource
          .viaMat(flow)(Keep.right)
          .toMat(Sink.seq[ByteString])(Keep.both)
          .run()

      Await.result(localResult, 100.milliseconds) shouldBe Seq(
        ByteString("wow"),
        ByteString("it"),
        ByteString.empty,
        bigString)

      Await.result(remoteResult, 100.milliseconds).flatten shouldBe (
        ByteString("19BitTorrent protocol") ++
          ByteString(0, 0, 0, 0, 0, 0, 0, 0) ++
          infoHash ++ myPeerId ++
          ByteString(0, 0, 0, 2) ++ ByteString("hi") ++
          ByteString(0, 0, 1, 2) ++ bigString ++
          ByteString(0, 0, 0, 5) ++ ByteString("hello")
      )
    }
  }
}
