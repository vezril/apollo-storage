package apollostorage.persistence

import apollostorage.config.PostgresConfig
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/**
 * The startup probe must fail fast (bounded retries) when Postgres is unreachable, rather than
 * hanging or silently succeeding.
 */
final class PersistenceReadinessSpec extends AnyWordSpec with Matchers with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(15, Seconds), interval = Span(100, Millis))

  "PersistenceReadiness.check" should {
    "fail within bounded time when the database is unreachable" in {
      // Port 1 is not listening; connect-timeout keeps each attempt short.
      val cfg = PostgresConfig(
        host = "127.0.0.1",
        port = 1,
        database = "apollostorage",
        user = "apollostorage",
        password = "apollostorage",
        connectTimeout = 500.millis
      )
      val result = PersistenceReadiness.check(cfg, retries = 2, retryDelay = 200.millis)
      whenReady(result.failed) { ex =>
        ex shouldBe a[Throwable]
      }
    }
  }
