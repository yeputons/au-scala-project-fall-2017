package net.yeputons.spbau.fall2017.scala.torrentclient

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, Timers}

import scala.concurrent.duration._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import net.yeputons.spbau.fall2017.scala.torrentclient.Tracker._
import org.saunter.bencode.BencodeDecoder

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object Tracker {
  type PeerId = Array[Byte]
  type Peers = Map[PeerId, InetSocketAddress]

  sealed trait TrackerMessage
  case class GetPeers(requestId: Long) extends TrackerMessage
  case object UpdatePeersList extends TrackerMessage
  case class Announced(httpResponse: HttpResponse, data: String) extends TrackerMessage
  case class AnnounceFailed(e: Throwable) extends TrackerMessage

  case class PeersList(requestId: Long, peers: Peers)

  final val DefaultRetryTimeout: FiniteDuration = 5.seconds

  private case object UpdateTimer
}

class Tracker(baseAnnounceUri: Uri, infoHash: Array[Byte], httpResponseReadTimeout: FiniteDuration = 5.seconds, retryTimeout: FiniteDuration = 5.seconds) extends Actor with ActorLogging with Timers {
  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  val http = Http(context.system)

  var peers: Peers = Map.empty

  def httpRequest(request: HttpRequest): Future[HttpResponse] = http.singleRequest(request)

  override def preStart(): Unit = {
    super.preStart()
    self ! UpdatePeersList
  }

  override def receive: Receive = {
    case m: TrackerMessage => m match {
      case GetPeers(requestId) => sender() ! PeersList(requestId, peers)
      case UpdatePeersList =>
        updatePeersList()
      case Announced(httpResponse, data) =>
        if (httpResponse.status != StatusCodes.OK) {
          log.warning(s"Tracker returned non-success code: ${httpResponse.status}, message of length ${data.length} follows:\n$data")
          retryAfterDelay()
        } else {
          BencodeDecoder.decode(data) match {
            case BencodeDecoder.Success(result, _) =>
              log.debug(s"Got ${data.length} chars of data from the tracker, successfully decoded")
              processTrackerResponse(result)
            case BencodeDecoder.NoSuccess(msg, _) =>
              log.warning(s"Got ${data.length} chars of data from the tracker, unable to decode: $msg")
              retryAfterDelay()
          }
        }
      case AnnounceFailed(e) =>
        log.error(e, s"Error while sending request to a tracker")
        retryAfterDelay()
    }
  }

  def updatePeersList(): Unit = {
    timers.cancel(UpdateTimer)

    val queryString =
      BinaryQueryString(baseAnnounceUri.rawQueryString) +
        ("info_hash" -> infoHash)
    val uri = baseAnnounceUri.copy(rawQueryString = queryString.rawQueryString)

    log.info(s"Sending a request to tracker at $uri")
    import context.dispatcher
    val f = for {
      response <- httpRequest(HttpRequest(method = HttpMethods.GET, uri = uri))
      entity <- response.entity.toStrict(httpResponseReadTimeout)
    } yield (response, entity)

    import scala.util.{Failure, Success}
    f.onComplete {
      case Success((httpResponse, entity)) => self ! Announced(httpResponse, entity.data.toString())
      case Failure(e) => self ! AnnounceFailed(e)
    }
  }

  def processTrackerResponse(data: Any): Unit = {
    log.debug(s"processTrackerResponse($data)")
  }

  def retryAfterDelay(): Unit = {
    log.info(s"Retry after $retryTimeout")
    timers.startSingleTimer(UpdateTimer, UpdatePeersList, retryTimeout)
  }
}