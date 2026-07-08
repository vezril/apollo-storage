package apollostorage.api

import apollostorage.grpc.{ObjectApi, ObjectApiHandler}
import grpc.health.v1.{Health, HealthHandler}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.scaladsl.{ServerReflection, ServiceHandler}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.Future

/**
 * Binds the gRPC surface (object API + health) over cleartext HTTP/2 (design D17). Requires
 * `pekko.http.server.preview.enable-http2 = on`.
 */
object GrpcServer:

  def handler(objectApi: ObjectApi, health: Health)(using
      system: ActorSystem[?]
  ): HttpRequest => Future[HttpResponse] =
    ServiceHandler.concatOrNotFound(
      ObjectApiHandler.partial(objectApi),
      HealthHandler.partial(health),
      // Server reflection lets tools like grpcurl introspect without local protos.
      ServerReflection.partial(List(ObjectApi, Health))
    )

  def bind(handler: HttpRequest => Future[HttpResponse], host: String, port: Int)(using
      system: ActorSystem[?]
  ): Future[ServerBinding] =
    Http()(system).newServerAt(host, port).bind(handler)
