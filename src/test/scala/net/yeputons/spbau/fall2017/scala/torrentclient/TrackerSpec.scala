package net.yeputons.spbau.fall2017.scala.torrentclient

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.HttpRequestActor.{HttpRequestSucceeded, MakeHttpRequest}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker.{GetPeers, PeersListResponse}
import net.yeputons.spbau.fall2017.scala.torrentclient.bencode._
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

  val infoHash: Seq[Byte] =
    Seq(0x00, 0x01, 0x20, 0x7F, 0xF9, 0xFF).map(_.toByte)
  val infoHashStr = "%00%01%20%7F%F9%FF"

  def createTracker(uri: Uri, httpRequestActor: ActorRef): ActorRef =
    system.actorOf(Props(new Tracker(uri, infoHash, _ => httpRequestActor)))

  def successfulHttpResponse(data: BEntry): HttpRequestSucceeded = {
    val dataCoded = ByteString(BencodeEncoder(data).toArray)
    HttpRequestSucceeded(0, HttpResponse(entity = HttpEntity(dataCoded)), dataCoded)
  }

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

    "parses list of peers in verbose representation" in {
      val httpRequestProbe = TestProbe()
      val tracker = createTracker("/", httpRequestProbe.ref)
      httpRequestProbe.expectMsgClass(1.second, classOf[MakeHttpRequest])
      httpRequestProbe.reply(successfulHttpResponse(BDict.fromAsciiStringKeys(
        "interval" -> BNumber(10),
        "peers" -> BList(
          BDict.fromAsciiStringKeys(
            "peer id" -> BByteString.fromAsciiString("peer-123"),
            "ip" -> BByteString.fromAsciiString("1.example.com"),
            "port" -> BNumber(4567)
          ),
          BDict.fromAsciiStringKeys(
            "peer id" -> BByteString.fromAsciiString("peer-456"),
            "ip" -> BByteString.fromAsciiString("2.example.com"),
            "port" -> BNumber(4568)
          )
        )
      )))
      tracker ! GetPeers(514)
      expectMsg(1.second, PeersListResponse(514, Map(
        "peer-123".getBytes().toSeq -> new InetSocketAddress("1.example.com", 4567),
        "peer-456".getBytes().toSeq -> new InetSocketAddress("2.example.com", 4568)
      )))
    }
  }
}
