package apollostorage.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The auto-migrate toggle (design D65): default on, overridable off. */
final class DbConfigSpec extends AnyWordSpec with Matchers:

  private def autoMigrate(value: String): Boolean =
    AppConfig.autoMigrate(
      ConfigFactory.parseString(s"apollostorage.db.auto-migrate = $value")
    )

  "AppConfig.autoMigrate" should {
    "read the configured flag" in {
      autoMigrate("true") shouldBe true
      autoMigrate("false") shouldBe false
    }

    "default to on in the shipped config" in {
      AppConfig.autoMigrate(ConfigFactory.load()) shouldBe true
    }
  }
