package apollostorage.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Parsing of scoped-auth config (design D57/D58/D61): AUTH_PRINCIPALS scopes, legacy AUTH_TOKENS as
 * write, and fail-fast on malformed entries.
 */
final class AuthConfigSpec extends AnyWordSpec with Matchers:

  private def auth(tokens: String, principals: String): AuthConfig =
    AppConfig.auth(
      ConfigFactory.parseString(
        s"""apollostorage.auth {
           |  enabled = true
           |  tokens = "$tokens"
           |  principals = "$principals"
           |}""".stripMargin
      )
    )

  "AppConfig.auth" should {

    "parse scoped principals from AUTH_PRINCIPALS" in {
      val cfg = auth(tokens = "", principals = "readtok:read, writetok:write")
      cfg.principals should contain theSameElementsAs Seq(
        Principal("readtok", Scope.Read),
        Principal("writetok", Scope.Write)
      )
    }

    "treat legacy flat tokens as write scope (back-compat)" in {
      auth(
        tokens = "legacyA, legacyB",
        principals = ""
      ).principals should contain theSameElementsAs Seq(
        Principal("legacyA", Scope.Write),
        Principal("legacyB", Scope.Write)
      )
    }

    "combine legacy tokens and scoped principals" in {
      val cfg = auth(tokens = "full", principals = "ro:read")
      cfg.principals should contain theSameElementsAs Seq(
        Principal("full", Scope.Write),
        Principal("ro", Scope.Read)
      )
    }

    "fail fast on an unknown scope" in {
      val ex = intercept[IllegalStateException](auth(tokens = "", principals = "tok:admin"))
      ex.getMessage should include("scope")
    }

    "fail fast when a token contains the ':' delimiter" in {
      intercept[IllegalStateException](auth(tokens = "", principals = "to:ken:read"))
    }
  }

  "Scope.satisfies" should {
    "make write a superset of read" in {
      Scope.Write.satisfies(Scope.Read) shouldBe true
      Scope.Write.satisfies(Scope.Write) shouldBe true
      Scope.Read.satisfies(Scope.Read) shouldBe true
      Scope.Read.satisfies(Scope.Write) shouldBe false
    }
  }
