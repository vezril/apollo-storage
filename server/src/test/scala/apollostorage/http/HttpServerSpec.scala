package apollostorage.http

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
 * Verifies bind/startup semantics: a real bind serves /health, and binding a second server on the
 * same port fails fast (occupied-port edge case).
 */
final class HttpServerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll:

  private val testKit = ActorTestKit()
  private given system: ActorSystem[?] = testKit.system

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val routes = HealthRoutes("0.0.0-test", () => true)

  "HttpServer.bind" should {

    "bind an ephemeral port and serve /health" in {
      val binding = HttpServer.bind(routes, "127.0.0.1", 0).futureValue
      val port = binding.localAddress.getPort
      port should be > 0
      val resp = Http()(system)
        .singleRequest(HttpRequest(uri = s"http://127.0.0.1:$port/health"))
        .futureValue
      resp.status shouldBe StatusCodes.OK
      resp.discardEntityBytes()
      Await.result(binding.unbind(), 5.seconds)
    }

    "fail fast when the port is already occupied" in {
      val first = HttpServer.bind(routes, "127.0.0.1", 0).futureValue
      val port = first.localAddress.getPort
      val second: Try[Http.ServerBinding] =
        Try(Await.result(HttpServer.bind(routes, "127.0.0.1", port), 5.seconds))
      second match
        case Failure(_) => succeed
        case Success(b) =>
          Await.result(b.unbind(), 5.seconds)
          fail("expected bind on an occupied port to fail")
      Await.result(first.unbind(), 5.seconds)
    }
  }
