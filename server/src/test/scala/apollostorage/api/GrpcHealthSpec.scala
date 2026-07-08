package apollostorage.api

import com.typesafe.config.ConfigFactory
import grpc.health.v1.{HealthCheckRequest, HealthCheckResponse, HealthClient, HealthHandler}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

/** The gRPC health service reflects the readiness flag (design D7/D19). */
final class GrpcHealthSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("pekko.http.server.preview.enable-http2 = on")
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  private val ready = new AtomicBoolean(false)
  private var client: HealthClient = scala.compiletime.uninitialized

  override protected def beforeAll(): Unit =
    super.beforeAll()
    val handler: HttpRequest => Future[HttpResponse] =
      HealthHandler(new HealthServiceImpl(() => ready.get()))
    val binding = Http()(system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
    client = HealthClient(
      GrpcClientSettings
        .connectToServiceAt("127.0.0.1", binding.localAddress.getPort)(system)
        .withTls(false)
    )

  "grpc.health.v1.Health.Check" should {
    "report NOT_SERVING before readiness and SERVING after" in {
      ready.set(false)
      client.check(HealthCheckRequest()).futureValue.status shouldBe
        HealthCheckResponse.ServingStatus.NOT_SERVING

      ready.set(true)
      client.check(HealthCheckRequest()).futureValue.status shouldBe
        HealthCheckResponse.ServingStatus.SERVING
    }
  }
