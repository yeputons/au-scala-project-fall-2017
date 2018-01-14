package net.yeputons.spbau.fall2017.scala.torrentclient

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import net.yeputons.spbau.fall2017.scala.torrentclient.HttpRequestActor.MakeHttpRequest
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{
  GetPeers,
  PeersListResponse
}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class TrackerSpec
    extends TestKit(ActorSystem("TrackerSpec"))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockFactory {
  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val infoHash: Array[Byte] =
    Array(0x00, 0x01, 0x20, 0x7F, 0xF9, 0xFF).map(_.toByte)
  val infoHashStr = "%00%01%20%7f%f9%ff"

  def createTracker(uri: Uri, httpRequestActor: ActorRef): ActorRef =
    system.actorOf(Props(new Tracker(uri, infoHash, _ => httpRequestActor)))

  "The Tracker actor" must {
    "answer with no peers in the beginning" in {
      val tracker = createTracker(Uri./, TestProbe().ref)
      tracker ! GetPeers(123)
      expectMsg(1.second, PeersListResponse(123, Map.empty))
      tracker ! PoisonPill
    }

    "makes correct GET request in the beginning" in {
      val httpRequestProbe = TestProbe()
      val tracker = createTracker("/foo/bar", httpRequestProbe.ref)
      httpRequestProbe.expectMsg(
        1.second,
        MakeHttpRequest(0,
                        HttpRequest(HttpMethods.GET,
                                    Uri(s"/foo/bar?info_hash=$infoHashStr"))))
      tracker ! PoisonPill
    }

    "makes correct GET request when there is query string initially" in {
      val httpRequestProbe = TestProbe()
      val tracker =
        createTracker("/foo/bar?code=10&foo=%20", httpRequestProbe.ref)
      httpRequestProbe.expectMsg(
        1.second,
        MakeHttpRequest(
          0,
          HttpRequest(HttpMethods.GET,
                      Uri(s"/foo/bar?code=10&foo=%20&info_hash=$infoHashStr"))))
      tracker ! PoisonPill
    }
  }
}
