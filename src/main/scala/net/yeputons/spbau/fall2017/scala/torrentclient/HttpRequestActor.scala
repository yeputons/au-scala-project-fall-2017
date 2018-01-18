package net.yeputons.spbau.fall2017.scala.torrentclient

import akka.actor.{Actor, ActorLogging, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import net.yeputons.spbau.fall2017.scala.torrentclient.HttpRequestActor.{
  HttpRequestFailed,
  HttpRequestSucceeded,
  MakeHttpRequest
}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/**
  * Provides a way to send single HTTP requests via Actors system.
  * Decouples sending requests from their processing and enables
  * easier mocking in tests.
  *
  * Stateless. Passes all HTTP requests to Akka HTTP, and then
  * answers when a separate message when the response is fully read.
  */
object HttpRequestActor {
/**
    * Ask the actor to make another HTTP request.
    * The actor will respond to the sender with either [[HttpRequestSucceeded]] or [[HttpRequestFailed]].
    * @param requestId An arbitrary integer so the sender can tell requests apart.
    * @param request [[HttpRequest]] which will be sent via Akka HTTP
    */
  case class MakeHttpRequest(requestId: Long, request: HttpRequest)

  /**
    * Response to [[MakeHttpRequest]] meaning that an HTTP request was
    * completed and the response entity was fully read without any exceptions.
    * @param requestId The same `requestId` which was passed in the initial [[MakeHttpRequest]]
    * @param response [[HttpResponse]] from Akka HTTP
    * @param data Data from `response.entity`
    */
  case class HttpRequestSucceeded(requestId: Long,
                                  response: HttpResponse,
                                  data: ByteString)

  /**
    * Response to [[MakeHttpRequest]] meaning that there was an exception during processing an HTTP request.
    * @param requestId The same `requestId` which was passed in the initial [[MakeHttpRequest]]
    * @param e The error thrown while performing the request
    */
  case class HttpRequestFailed(requestId: Long, e: Throwable)

  /**
    * Returns [[Props]] for a new [[HttpRequestActor]] actor.
    * @param httpReadTimeout Maximal time allowed for reading server's response in full
    */
  def props(httpReadTimeout: FiniteDuration) =
    Props(new HttpRequestActor(httpReadTimeout))
}

class HttpRequestActor(httpReadTimeout: FiniteDuration)
    extends Actor
    with ActorLogging {
  final implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(context.system))
  import context.dispatcher
  val http = Http(context.system)

  // TODO: cancel requests in flight when actor is stopped
  override def receive: Receive = {
    case MakeHttpRequest(requestId, request) =>
      val f = for {
        response <- http.singleRequest(request)
        entity <- response.entity.toStrict(httpReadTimeout)
      } yield (response, entity)

      val s = sender()
      f.onComplete {
        case Success((response, entity)) =>
          s ! HttpRequestSucceeded(requestId, response, entity.data)
        case Failure(e) =>
          s ! HttpRequestFailed(requestId, e)
      }
  }
}
