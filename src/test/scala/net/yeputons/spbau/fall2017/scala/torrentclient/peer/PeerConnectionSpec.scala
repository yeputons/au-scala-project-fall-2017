package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.Flow
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.{TestKit, TestProbe}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection.{
  ReceivedPeerMessage,
  SendPeerMessage
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnectionSpec._
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.PeerMessage.{
  HasNewPiece,
  HasPieces,
  Interested
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.{
  Handshake,
  PeerMessage
}
import org.scalatest.WordSpecLike

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.Success

class PeerConnectionSpec
    extends TestKit(ActorSystem("PeerConnectionSpec"))
    with WordSpecLike {
  "PeerConnection" must {
    def fixture = new {
      val probe: TestProbe = TestProbe()
      val connection: ActorRef =
        system.actorOf(PeerConnectionWithMock.props(probe.ref))
      probe.watch(connection)
      val PeerConnectionProbes(sub, pub, handshakeCompleted) =
        probe.expectMsgClass(classOf[PeerConnectionProbes])
    }

    "pass PeerMessage" in {
      val f = fixture
      import f._
      handshakeCompleted.complete(Success(Handshake.HandshakeCompleted))
      probe.expectNoMessage(100.milliseconds)

      pub.sendNext(HasPieces(Set(10, 15, 18)))
      probe.expectMsg(ReceivedPeerMessage(HasPieces(Set(10, 15, 18))))

      pub.sendNext(HasNewPiece(3))
      probe.expectMsg(ReceivedPeerMessage(HasNewPiece(3)))

      sub.request(1)
      probe.send(connection, SendPeerMessage(HasPieces(Set(20, 21, 22))))
      sub.expectNext(HasPieces(Set(20, 21, 22)))

      sub.request(1)
      probe.send(connection, SendPeerMessage(HasNewPiece(10)))
      sub.expectNext(HasNewPiece(10))

      pub.sendNext(Interested)
      probe.expectMsg(ReceivedPeerMessage(Interested))

      pub.sendError(new Exception("Test exception"))
      probe.expectTerminated(connection)
    }

    "silently stop on incoming stream complete" in {
      val f = fixture
      import f._
      pub.sendComplete()
      probe.expectTerminated(connection)
    }

    "silently stop on incoming stream error" in {
      val f = fixture
      import f._
      pub.sendError(new Exception("Test exception"))
      probe.expectTerminated(connection)
      probe.expectNoMessage(100.milliseconds)
    }
  }
}

object PeerConnectionSpec {
  case class PeerConnectionProbes(
      sub: TestSubscriber.Probe[PeerMessage],
      pub: TestPublisher.Probe[PeerMessage],
      handshakeCompleted: Promise[Handshake.HandshakeCompleted.type])

  private class PeerConnectionWithMock(handler: ActorRef)
      extends PeerConnection(handler) {
    import context.system
    override def connectionFlow
      : Flow[PeerMessage,
             PeerMessage,
             Future[Handshake.HandshakeCompleted.type]] =
      Flow.fromSinkAndSourceCoupledMat(TestSink.probe[PeerMessage],
                                       TestSource.probe[PeerMessage]) {
        case (sub, pub) =>
          val probes = PeerConnectionProbes(sub, pub, Promise())
          handler ! probes
          probes.handshakeCompleted.future
      }
  }

  object PeerConnectionWithMock {
    def props(handler: ActorRef) = Props(new PeerConnectionWithMock(handler))
  }
}
