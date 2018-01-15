package net.yeputons.spbau.fall2017.scala.torrentclient

import java.net.InetSocketAddress

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
  type PeerId = Seq[Byte]
  type Peers = Map[PeerId, InetSocketAddress]

  case class GetPeers(requestId: Long)
  case object UpdatePeersList

  case class PeersListResponse(requestId: Long, peers: Peers)

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
  var peers: Peers = Map.empty

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
            s"follows:\n$data")
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
        ("info_hash" -> infoHash)
    val uri = baseAnnounceUri.copy(rawQueryString = queryString.rawQueryString)

    log.info(s"Sending a request to tracker at $uri")
    httpRequestActor ! MakeHttpRequest(
      0,
      HttpRequest(method = HttpMethods.GET, uri = uri))
  }

  def processTrackerResponse(data: BEntry): Unit = {
    log.debug(s"processTrackerResponse($data)")
    val peersList =
      data.asInstanceOf[BDict]("peers".getBytes().toSeq).asInstanceOf[BList]
    peers = peersList.flatMap {
      case peer: BDict =>
        val id = peer("peer id").asInstanceOf[BByteString].value
        val host = peer("ip").asInstanceOf[BByteString].value
        val port = peer("port").asInstanceOf[BNumber].value.toInt
        Some(
          id -> new InetSocketAddress(new String(host.toArray, "UTF-8"), port)
        )
      case x =>
        log.warning(s"Unexpected item in the 'peers' field from tracker: $x")
        None
    }.toMap
  }

  def retryAfterDelay(): Unit = {
    log.info(s"Retry after $retryTimeout")
    timers.startSingleTimer(UpdateTimer, UpdatePeersList, retryTimeout)
  }
}
