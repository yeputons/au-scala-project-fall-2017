package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection.{
  ReceivedPeerMessage,
  SendPeerMessage
}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandlerSpec.PeerHandlerWithMock
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerSwarmHandler.AddPieces
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.PeerMessage._
import org.scalatest.WordSpecLike

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.util.Success

class PeerHandlerSpec
    extends TestKit(ActorSystem("PeerHandlerSpec"))
    with WordSpecLike {
  "PeerHandler" must {
    def fixture = new {
      val watcher = TestProbe()
      val connection = TestProbe()
      val connectionCreated = Promise[Unit]
      val swarmHandler = TestProbe()
      val handler = system.actorOf(
        PeerHandlerWithMock
          .props(connection.ref, connectionCreated, swarmHandler.ref))
      watcher.watch(handler)

      Await.result(connectionCreated.future, 100.milliseconds)
    }

    "create connection on start, handle data, stop on connection stop" in {
      val f = fixture
      import f._

      connection.send(handler, ReceivedPeerMessage(KeepAlive))
      connection.send(handler, ReceivedPeerMessage(Unchoke))

      watcher.expectNoMessage(100.milliseconds)
      system.stop(connection.ref)
      watcher.expectTerminated(handler)
    }

    "pass data about pieces to swarm handler" in {
      val f = fixture
      import f._

      connection.send(handler, ReceivedPeerMessage(HasPieces(Set(10, 20, 30))))
      swarmHandler.expectMsg(AddPieces(Set(10, 20, 30)))

      connection.send(handler, ReceivedPeerMessage(HasNewPiece(15)))
      swarmHandler.expectMsg(AddPieces(Set(15)))
    }

    "send keep alive every so often" in {
      val connection = TestProbe()
      val connectionCreated = Promise[Unit]
      val handler = system.actorOf(
        PeerHandlerWithMock
          .props(connection.ref,
                 connectionCreated,
                 TestProbe().ref,
                 keepAlivePeriod = 500.milliseconds))
      Await.result(connectionCreated.future, 100.milliseconds)

      connection.expectNoMessage(250.milliseconds)
      connection.expectMsg(500.milliseconds, SendPeerMessage(KeepAlive))

      connection.expectNoMessage(250.milliseconds)
      connection.expectMsg(500.milliseconds, SendPeerMessage(KeepAlive))
    }

    "drop connection after no messages for a while" in {
      val watcher = TestProbe()
      val connection = TestProbe()
      val connectionCreated = Promise[Unit]
      val handler = system.actorOf(
        PeerHandlerWithMock
          .props(connection.ref,
                 connectionCreated,
                 TestProbe().ref,
                 keepAlivePeriod = 1.minute,
                 keepAliveTimeout = 500.milliseconds))
      watcher.watch(handler)
      Await.result(connectionCreated.future, 100.milliseconds)

      connection.send(handler, ReceivedPeerMessage(Interested))
      watcher.expectNoMessage(250.milliseconds)

      connection.send(handler, ReceivedPeerMessage(NotInterested))
      watcher.expectNoMessage(250.milliseconds)

      connection.send(handler, ReceivedPeerMessage(Choke))
      watcher.expectNoMessage(250.milliseconds)

      connection.send(handler, ReceivedPeerMessage(Unchoke))
      watcher.expectNoMessage(250.milliseconds)

      watcher.expectTerminated(handler, 750.milliseconds)
    }

    "drop connection after no messages initially" in {
      val watcher = TestProbe()
      val handler = system.actorOf(
        PeerHandlerWithMock
          .props(TestProbe().ref,
                 Promise[Unit],
                 TestProbe().ref,
                 keepAlivePeriod = 1.minute,
                 keepAliveTimeout = 500.milliseconds))
      watcher.watch(handler)
      watcher.expectTerminated(handler, 750.milliseconds)
    }
  }
}

object PeerHandlerSpec {
  private class PeerHandlerWithMock(connection: ActorRef,
                                    connectionCreated: Promise[Unit],
                                    swarmHandler: ActorRef,
                                    _keepAlivePeriod: FiniteDuration,
                                    _keepAliveTimeout: FiniteDuration)
      extends PeerHandler(swarmHandler) {
    override def createConnection(): ActorRef = {
      connectionCreated.complete(Success(()))
      connection
    }

    override def keepAlivePeriod: FiniteDuration = _keepAlivePeriod

    override def keepAliveTimeout: FiniteDuration = _keepAliveTimeout
  }

  object PeerHandlerWithMock {
    def props(connection: ActorRef,
              connectionCreated: Promise[Unit],
              swarmHandler: ActorRef,
              keepAlivePeriod: FiniteDuration = 2.minutes,
              keepAliveTimeout: FiniteDuration = 3.minutes) =
      Props(
        new PeerHandlerWithMock(connection,
                                connectionCreated,
                                swarmHandler,
                                keepAlivePeriod,
                                keepAliveTimeout))
  }
}
