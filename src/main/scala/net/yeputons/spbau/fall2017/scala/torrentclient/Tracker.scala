package net.yeputons.spbau.fall2017.scala.torrentclient

import java.net.{InetAddress, InetSocketAddress}
import java.nio.{ByteBuffer, ByteOrder}

import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  ActorRefFactory,
  Props,
  Timers
}
import akka.http.scaladsl.model._
import net.yeputons.spbau.fall2017.scala.torrentclient.HttpRequestActor.{
  HttpRequestFailed,
  HttpRequestSucceeded,
  MakeHttpRequest
}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker._
import net.yeputons.spbau.fall2017.scala.torrentclient.bencode._

import scala.concurrent.duration.{FiniteDuration, _}

object Tracker {
  case class Peer(address: InetSocketAddress, id: Option[Seq[Byte]])

  case class GetPeers(requestId: Long)
  case object UpdatePeersList

  case class PeersListResponse(requestId: Long, peers: Set[Peer])

  final val DefaultHttpReadTimeout: FiniteDuration = 5.seconds
  final val DefaultRetryTimeout: FiniteDuration = 5.seconds

  def props(baseAnnounceUri: Uri,
            infoHash: Seq[Byte],
            httpReadTimeout: FiniteDuration = DefaultHttpReadTimeout,
            retryTimeout: FiniteDuration = DefaultRetryTimeout) =
    Props(
      new Tracker(baseAnnounceUri,
                  infoHash,
                  _.actorOf(HttpRequestActor.props(httpReadTimeout)),
                  retryTimeout))

  private case object UpdateTimer

}

class Tracker(baseAnnounceUri: Uri,
              infoHash: Seq[Byte],
              httpRequestsActorFactory: ActorRefFactory => ActorRef,
              retryTimeout: FiniteDuration = DefaultRetryTimeout)
    extends Actor
    with ActorLogging
    with Timers {
  val httpRequestActor: ActorRef = httpRequestsActorFactory(context)
  var peers: Set[Peer] = Set.empty

  override def preStart(): Unit = {
    super.preStart()
    self ! UpdatePeersList
  }

  override def receive: Receive = {
    case GetPeers(requestId) =>
      sender() ! PeersListResponse(requestId, peers)
    case UpdatePeersList =>
      updatePeersList()
    case HttpRequestSucceeded(_, httpResponse, data) =>
      if (httpResponse.status != StatusCodes.OK) {
        log.warning(
          s"Tracker returned non-success code: ${httpResponse.status}, message of length ${data.length} " +
            s"follows:\n${data.map(_.toChar).mkString}")
        retryAfterDelay()
      } else {
        BencodeDecoder(data) match {
          case Right(result) =>
            log.debug(
              s"Got ${data.length} chars of data from the tracker, successfully decoded")
            processTrackerResponse(result)
          case Left(msg) =>
            log.warning(
              s"Got ${data.length} chars of data from the tracker, unable to decode: $msg")
            retryAfterDelay()
        }
      }
    case HttpRequestFailed(_, e) =>
      log.error(e, s"Error while sending request to a tracker")
      retryAfterDelay()
  }

  def updatePeersList(): Unit = {
    timers.cancel(UpdateTimer)

    val queryString =
      BinaryQueryString(baseAnnounceUri.rawQueryString) +
        ("info_hash" -> infoHash) +
        ("compact" -> "1") +
        ("peer_id" -> "01234567890123456789") + // TODO: use a better peer_id
        ("port" -> "0") +
        ("uploaded" -> "0") +
        ("downloaded" -> "0") +
        ("left" -> "0")
    val uri = baseAnnounceUri.copy(rawQueryString = queryString.rawQueryString)

    log.info(s"Sending a request to tracker at $uri")
    httpRequestActor ! MakeHttpRequest(
      0,
      HttpRequest(method = HttpMethods.GET, uri = uri))
  }

  def processTrackerResponse(data: BEntry): Unit = {
    log.debug(s"processTrackerResponse($data)")
    // TODO: update peers automatically after 'interval'
    data.asInstanceOf[BDict]("peers") match {
      case peersList: BList =>
        peers = peersList.flatMap {
          case peer: BDict =>
            val id = peer.get("peer id").map(_.asInstanceOf[BByteString].value)
            val address = InetSocketAddress.createUnresolved(
              new String(peer("ip").asInstanceOf[BByteString].value.toArray,
                         "UTF-8"),
              peer("port").asInstanceOf[BNumber].value.toInt)
            Some(Peer(address, id))
          case x =>
            log.warning(
              s"Unexpected item in the 'peers' field from tracker: $x")
            None
        }.toSet
      case peersList: BByteString =>
        // BEP 23 "Tracker Returns Compact Peer Lists"
        if (peersList.value.length % 6 != 0) {
          log.warning(
            s"Unexpected length in the 'peers' field from tracker: ${peersList.value.length}, is not divisible by 6")
        }
        peers = peersList.value
          .grouped(6)
          .filter(_.lengthCompare(6) == 0)
          .map { data =>
            val (ipBytes, portBytes) = data.splitAt(4)
            val ip = InetAddress.getByAddress(ipBytes.toArray)
            val port = ByteBuffer
              .wrap(portBytes.toArray)
              .order(ByteOrder.BIG_ENDIAN)
              .getShort() & 0xFFFF
            Peer(new InetSocketAddress(ip, port), None)
          }
          .toSet
      case x =>
        log.warning(s"Unexpected type of the 'peers' field from tracker: $x")
    }
  }

  def retryAfterDelay(): Unit = {
    log.info(s"Retry after $retryTimeout")
    timers.startSingleTimer(UpdateTimer, UpdatePeersList, retryTimeout)
  }
}
