package apollostorage.api

import apollostorage.config.{AuthConfig, Principal, Scope, TlsConfig}
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
    val cfg = AuthConfig(
      enabled = true,
      principals = Seq(Principal("read-tok", Scope.Read), Principal("write-tok", Scope.Write))
    )
    val auth = new TokenAuthenticator(cfg)

    "authorize a read token for a read operation" in {
      auth.authorize(metadataWith(Some("Bearer read-tok")), Scope.Read) // no exception
    }

    "authorize a write token for read and write operations" in {
      auth.authorize(metadataWith(Some("Bearer write-tok")), Scope.Read)
      auth.authorize(metadataWith(Some("Bearer write-tok")), Scope.Write)
    }

    "reject a read token for a write operation with PERMISSION_DENIED" in {
      val ex =
        intercept[GrpcServiceException](
          auth.authorize(metadataWith(Some("Bearer read-tok")), Scope.Write)
        )
      ex.status.getCode shouldBe Status.Code.PERMISSION_DENIED
    }

    "reject a missing token with UNAUTHENTICATED" in {
      val ex = intercept[GrpcServiceException](auth.authorize(metadataWith(None), Scope.Read))
      ex.status.getCode shouldBe Status.Code.UNAUTHENTICATED
    }

    "reject an unknown token with UNAUTHENTICATED" in {
      val ex =
        intercept[GrpcServiceException](
          auth.authorize(metadataWith(Some("Bearer nope")), Scope.Read)
        )
      ex.status.getCode shouldBe Status.Code.UNAUTHENTICATED
    }

    "be a no-op when disabled" in {
      new TokenAuthenticator(AuthConfig(enabled = false, principals = Nil))
        .authorize(metadataWith(None), Scope.Write)
    }

    "fail fast when enabled with no principals" in {
      intercept[IllegalStateException](
        new TokenAuthenticator(AuthConfig(enabled = true, principals = Nil))
      )
    }

    "authorize HTTP requests by scope (admin endpoint)" in {
      auth.authorizeHttp(Some("Bearer write-tok"), Scope.Write) shouldBe AuthOutcome.Ok
      auth.authorizeHttp(Some("Bearer read-tok"), Scope.Write) shouldBe AuthOutcome.Forbidden
      auth.authorizeHttp(None, Scope.Write) shouldBe AuthOutcome.Unauthenticated
    }
  }

  "SecurityWarnings" should {
    "warn when auth is enabled but TLS is disabled" in {
      val ws = SecurityWarnings.warnings(
        TlsConfig(enabled = false, "", ""),
        AuthConfig(enabled = true, Seq(Principal("t", Scope.Write)))
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
        AuthConfig(enabled = true, Seq(Principal("t", Scope.Write)))
      ) shouldBe empty
    }
  }
