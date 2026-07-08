package apollostorage.api

import apollostorage.grpc.{ObjectApi, ObjectApiPowerApi, ObjectApiPowerApiHandler}
import grpc.health.v1.{Health, HealthHandler}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.scaladsl.{ServerReflection, ServiceHandler}
import org.apache.pekko.http.scaladsl.{Http, HttpsConnectionContext}
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.Future

/**
 * Binds the gRPC surface (object API + health) over HTTP/2. Serves TLS when an
 * `HttpsConnectionContext` is supplied (design D34), else cleartext h2c (D17). The object API uses
 * the power API so handlers can authenticate request metadata.
 */
object GrpcServer:

  def handler(objectApi: ObjectApiPowerApi, health: Health)(using
      system: ActorSystem[?]
  ): HttpRequest => Future[HttpResponse] =
    ServiceHandler.concatOrNotFound(
      ObjectApiPowerApiHandler.partial(objectApi),
      HealthHandler.partial(health),
      ServerReflection.partial(List(ObjectApi, Health))
    )

  def bind(
      handler: HttpRequest => Future[HttpResponse],
      host: String,
      port: Int,
      https: Option[HttpsConnectionContext]
  )(using system: ActorSystem[?]): Future[ServerBinding] =
    val server = Http()(system).newServerAt(host, port)
    https.fold(server)(server.enableHttps).bind(handler)
