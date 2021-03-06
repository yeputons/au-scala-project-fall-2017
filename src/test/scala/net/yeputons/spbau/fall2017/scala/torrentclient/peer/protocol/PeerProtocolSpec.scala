package net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.PeerMessage.BlockId
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

class PeerProtocolSpec
    extends TestKit(ActorSystem("peerProtocolSpec"))
    with WordSpecLike
    with Matchers {
  implicit val materializer: Materializer = ActorMaterializer()

  val infoHash = ByteString("this--is--info--hash")
  val myPeerId = ByteString("this-is-some-peer-id")
  val otherPeerId = ByteString("this-is-other-peerid")

  "PeerProtocol" must {
    "complete future right after handshake" in {
      val (f, remoteSource) =
        PeerProtocol(infoHash, myPeerId, Some(otherPeerId))
          .joinMat( // Remote end
            Flow.fromSinkAndSourceCoupledMat(
              Sink.ignore,
              TestSource.probe[ByteString]
            )(Keep.right))(Keep.both)
          .join(Flow.fromSinkAndSourceCoupled(
            Sink.ignore,
            TestSource.probe[PeerMessage])) // Local end
          .run()
      remoteSource.sendNext(
        ByteString(19) ++ ByteString("BitTorrent protocol") ++
          ByteString(0, 0, 0, 0, 0, 0, 0, 0) ++
          infoHash ++ otherPeerId.slice(0, 19)
      )
      an[TimeoutException] should be thrownBy Await.result(f, 100.milliseconds)
      remoteSource.sendNext(otherPeerId.slice(19, 20))
      Await.result(f, 100.milliseconds)
    }
  }

  "Framing atop Handshake" must {
    "work when looped" in {
      val loop = Framing()
        .atop(Handshake(infoHash, myPeerId, Some(otherPeerId)))
        .atop(Handshake(infoHash, otherPeerId, Some(myPeerId)).reversed)
        .atop(Framing().reversed)
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
      val loop = Framing()
        .atop(Handshake(infoHash, myPeerId, Some(myPeerId)))
        .atop(Handshake(infoHash, otherPeerId, Some(myPeerId)).reversed)
        .atop(Framing().reversed)
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

    "work when looped and wrong peer id is ignored from one side" in {
      val loop = Framing()
        .atop(Handshake(infoHash, myPeerId, None))
        .atop(Handshake(infoHash, otherPeerId, Some(myPeerId)).reversed)
        .atop(Framing().reversed)
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

    "send and receive correct bytes" in {
      val bigString = ByteString(Array.fill[Byte](258)(-1))

      val localSource =
        Source(immutable.Seq(ByteString("hi"), bigString, ByteString("hello")))
      val remoteSource = Source(
        immutable.Seq(
          ByteString(19) ++ ByteString("BitTorrent protocol") ++
            // Some extension bits from qBittorrent
            ByteString(0, 0, 0, 0, 0, 24, 0, 5),
          infoHash ++ otherPeerId ++ ByteString(0, 0, 0, 3),
          ByteString("wow"),
          ByteString(0, 0, 0, 2) ++ ByteString("it") ++ ByteString(0, 0, 0, 0),
          ByteString(0, 0, 1, 2) ++ bigString
        )
      )

      val flow
        : Flow[ByteString, ByteString, Future[immutable.Seq[ByteString]]] =
        Framing()
          .atop(Handshake(infoHash, myPeerId, Some(otherPeerId)))
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
        ByteString(19) ++ ByteString("BitTorrent protocol") ++
          ByteString(0, 0, 0, 0, 0, 0, 0, 0) ++
          infoHash ++ myPeerId ++
          ByteString(0, 0, 0, 2) ++ ByteString("hi") ++
          ByteString(0, 0, 1, 2) ++ bigString ++
          ByteString(0, 0, 0, 5) ++ ByteString("hello")
      )
    }
  }

  private val blockId = BlockId(1234, 5678, 9012)
  private val blockStartSerialized = ByteString(
    0x00, 0x00, 0x04, 0xD2, 0x00, 0x00, 0x16, 0x2E
  )
  private val blockIdSerialized = blockStartSerialized ++
    ByteString(0x00, 0x00, 0x23, 0x34)

  private val examples = Map(
    "KeepAlive" -> (PeerMessage.KeepAlive, ByteString.empty),
    "Choke" -> (PeerMessage.Choke, ByteString(0)),
    "Unchoke" -> (PeerMessage.Unchoke, ByteString(1)),
    "Interested" -> (PeerMessage.Interested, ByteString(2)),
    "NotInterested" -> (PeerMessage.NotInterested, ByteString(3)),
    "HasNewPiece" -> (
      PeerMessage.HasNewPiece(123456),
      ByteString(4, 0x00, 0x01, 0xE2, 0x40)
    ),
    "empty HasPieces" -> (PeerMessage.HasPieces(Set.empty), ByteString(5)),
    "HasPieces" -> (
      PeerMessage.HasPieces(Set(1, 2, 10, 12)),
      // 0123 4567 | 89ab cdef
      // 0110 0000 | 0010 1000
      ByteString(5, 0x60, 0x28)
    ),
    "BlockRequest" -> (
      PeerMessage.BlockRequest(blockId),
      ByteString(6) ++ blockIdSerialized
    ),
    "BlockAvailable" -> {
      val data = ByteString((1 to 9012).map(_.toByte).toArray)
      (
        PeerMessage.BlockAvailable(BlockId(1234, 5678, 9012), data),
        ByteString(7) ++ blockStartSerialized ++ data
      )
    },
    "BlockRequestCancel" -> (
      PeerMessage.BlockRequestCancel(blockId),
      ByteString(8) ++ blockIdSerialized
    ),
  )

  "MessagesParsing" when {
    for ((key, (message, encoded)) <- examples) {
      f"working with $key" must {
        "encode" in {
          MessagesParsing.decodeMessage(encoded) shouldBe message
        }
        "decode" in {
          MessagesParsing.encodeMessage(message) shouldBe encoded
        }
      }
    }
  }
}
