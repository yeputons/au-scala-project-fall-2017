package net.yeputons.spbau.fall2017.scala.torrentclient.peer

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerConnection.ReceivedPeerMessage
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.PeerHandlerSpec.PeerHandlerWithMock
import net.yeputons.spbau.fall2017.scala.torrentclient.peer.protocol.PeerMessage._
import org.scalatest.WordSpecLike

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.util.Success

class PeerHandlerSpec
    extends TestKit(ActorSystem("PeerHandlerSpec"))
    with WordSpecLike {
  "PeerHandler" must {
    "create connection on start, handle data, stop on connection stop" in {
      val watcher = TestProbe()
      val connection = TestProbe()
      val connectionCreated = Promise[Unit]
      val handler = system.actorOf(PeerHandlerWithMock.props(connection.ref, connectionCreated))
      watcher.watch(handler)

      Await.result(connectionCreated.future, 100.milliseconds)
      connection.send(handler, ReceivedPeerMessage(KeepAlive))
      connection.send(handler, ReceivedPeerMessage(Unchoke))

      watcher.expectNoMessage(100.milliseconds)
      system.stop(connection.ref)
      watcher.expectTerminated(handler)
    }
  }
}

object PeerHandlerSpec {
  private class PeerHandlerWithMock(connection: ActorRef,
                                    connectionCreated: Promise[Unit])
      extends PeerHandler {
    override def createConnection(): ActorRef = {
      connectionCreated.complete(Success(()))
      connection
    }
  }

  object PeerHandlerWithMock {
    def props(connection: ActorRef, connectionCreated: Promise[Unit]) =
      Props(new PeerHandlerWithMock(connection, connectionCreated))
  }
}
