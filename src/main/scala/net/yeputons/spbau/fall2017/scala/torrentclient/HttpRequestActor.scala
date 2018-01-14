package net.yeputons.spbau.fall2017.scala.torrentclient

import akka.actor.{Actor, ActorLogging, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import net.yeputons.spbau.fall2017.scala.torrentclient.HttpRequestActor.{
  MakeHttpRequest,
  HttpRequestFailed,
  HttpRequestSucceeded
}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object HttpRequestActor {
  case class MakeHttpRequest(requestId: Long, request: HttpRequest)
  case class HttpRequestSucceeded(requestId: Long,
                                  response: HttpResponse,
                                  data: String)
  case class HttpRequestFailed(requestId: Long, e: Throwable)

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
          s ! HttpRequestSucceeded(requestId, response, entity.data.toString())
        case Failure(e) =>
          s ! HttpRequestFailed(requestId, e)
      }
  }
}
