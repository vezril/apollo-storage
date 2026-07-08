package apollostorage.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Verifies environment-style overrides take precedence over the HOCON defaults in persistence.conf
 * (event-persistence spec / design D8). `${?POSTGRES_HOST}` resolves from the config root, so
 * injecting the key there simulates the env var winning over the committed default.
 */
final class PersistenceConfigSpec extends AnyWordSpec with Matchers:

  "persistence.conf" should {

    "use the committed defaults when no overrides are present" in {
      val config = ConfigFactory.parseResources("persistence.conf").resolve()
      val pg = AppConfig.postgres(config)
      pg.host shouldBe "localhost"
      pg.port shouldBe 5432
      pg.database shouldBe "apollostorage"
    }

    "let POSTGRES_HOST / POSTGRES_PORT override the HOCON defaults" in {
      val overrides = ConfigFactory.parseString("""
        POSTGRES_HOST = "db.internal"
        POSTGRES_PORT = 6543
      """)
      val config =
        overrides.withFallback(ConfigFactory.parseResources("persistence.conf")).resolve()
      val pg = AppConfig.postgres(config)
      pg.host shouldBe "db.internal"
      pg.port shouldBe 6543
    }
  }
