package apollostorage.api

import apollostorage.config.{AuthConfig, TlsConfig}
import io.grpc.Status
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.grpc.scaladsl.Metadata
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit coverage for the TLS context loader, the token authenticator, and the insecure-configuration
 * warnings (design D34/D35/D38/D39).
 */
final class SecurityUnitSpec extends AnyWordSpec with Matchers:

  private def keystore(name: String): String =
    getClass.getClassLoader.getResource(s"tls/$name").getPath

  private def metadataWith(auth: Option[String]): Metadata =
    val builder = new org.apache.pekko.grpc.scaladsl.MetadataBuilder
    auth.foreach(v => builder.addText("authorization", v))
    builder.build()

  "TlsContext" should {
    "load an HTTPS context from a PKCS#12 keystore" in {
      val ctx =
        TlsContext.httpsServer(TlsConfig(enabled = true, keystore(name = "server.p12"), "changeit"))
      ctx.isSecure shouldBe true
    }

    "fail fast when the keystore is missing" in {
      val ex = intercept[IllegalStateException](
        TlsContext.httpsServer(TlsConfig(enabled = true, "/no/such/keystore.p12", "changeit"))
      )
      ex.getMessage should include("/no/such/keystore.p12")
    }

    "fail fast when the password is wrong" in {
      intercept[IllegalStateException](
        TlsContext.httpsServer(TlsConfig(enabled = true, keystore(name = "server.p12"), "wrong"))
      )
    }
  }

  "TokenAuthenticator" should {
    val cfg = AuthConfig(enabled = true, tokens = Seq("s3cret-a", "s3cret-b"))
    val auth = new TokenAuthenticator(cfg)

    "accept a valid bearer token" in {
      auth.check(metadataWith(Some("Bearer s3cret-b"))) // no exception
    }

    "reject a missing token with UNAUTHENTICATED" in {
      val ex = intercept[GrpcServiceException](auth.check(metadataWith(None)))
      ex.status.getCode shouldBe Status.Code.UNAUTHENTICATED
    }

    "reject an invalid token with UNAUTHENTICATED" in {
      val ex = intercept[GrpcServiceException](auth.check(metadataWith(Some("Bearer nope"))))
      ex.status.getCode shouldBe Status.Code.UNAUTHENTICATED
    }

    "be a no-op when disabled" in {
      new TokenAuthenticator(AuthConfig(enabled = false, tokens = Nil)).check(metadataWith(None))
    }

    "fail fast when enabled with no tokens" in {
      intercept[IllegalStateException](
        new TokenAuthenticator(AuthConfig(enabled = true, tokens = Nil))
      )
    }
  }

  "SecurityWarnings" should {
    "warn when auth is enabled but TLS is disabled" in {
      val ws = SecurityWarnings.warnings(
        TlsConfig(enabled = false, "", ""),
        AuthConfig(enabled = true, Seq("t"))
      )
      ws.exists(_.contains("bearer tokens travel in cleartext")) shouldBe true
    }

    "warn when both are disabled" in {
      val ws = SecurityWarnings.warnings(TlsConfig(false, "", ""), AuthConfig(false, Nil))
      ws.exists(_.contains("TLS is DISABLED")) shouldBe true
      ws.exists(_.contains("Authentication is DISABLED")) shouldBe true
    }

    "be silent when both are enabled" in {
      SecurityWarnings.warnings(
        TlsConfig(enabled = true, "k", "p"),
        AuthConfig(enabled = true, Seq("t"))
      ) shouldBe empty
    }
  }
