package apollostorage.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Backend selection + S3 connection config (add-s3-backend-and-rest-api): `BLOB_BACKEND` chooses
 * filesystem (default) or s3; the S3 section carries endpoint/bucket/path-style, with credentials
 * from config/secrets and no defaults baked in source.
 */
final class BlobBackendConfigSpec extends AnyWordSpec with Matchers:

  private def cfg(s: String) = ConfigFactory.parseString(s)

  "AppConfig.blobBackend" should {
    "default resolution selects filesystem" in {
      AppConfig.blobBackend(cfg("""apollostorage.blob.backend = "filesystem"""")) shouldBe
        BlobBackend.Filesystem
    }
    "select s3 when configured" in {
      AppConfig.blobBackend(cfg("""apollostorage.blob.backend = "s3"""")) shouldBe BlobBackend.S3
    }
    "fail fast on an unknown backend" in {
      an[IllegalStateException] should be thrownBy
        AppConfig.blobBackend(cfg("""apollostorage.blob.backend = "azure""""))
    }
  }

  "AppConfig.s3" should {
    "parse the s3 connection section" in {
      val s = AppConfig.s3(cfg("""
        apollostorage.blob.s3 {
          endpoint = "https://nas:8010"
          region = "us-east-1"
          bucket = "apollo-blobs"
          path-style = true
          access-key = "ak"
          secret-key = "sk"
          tls-insecure = true
          truststore-path = ""
          truststore-password = ""
        }"""))
      s.endpoint shouldBe "https://nas:8010"
      s.region shouldBe "us-east-1"
      s.bucket shouldBe "apollo-blobs"
      s.pathStyle shouldBe true
      s.accessKey shouldBe "ak"
      s.secretKey shouldBe "sk"
      s.tlsInsecure shouldBe true
    }

    "carry no credential defaults when unset" in {
      val s = AppConfig.s3(cfg("""
        apollostorage.blob.s3 {
          endpoint = ""
          region = "us-east-1"
          bucket = ""
          path-style = true
          access-key = ""
          secret-key = ""
          tls-insecure = false
          truststore-path = ""
          truststore-password = ""
        }"""))
      s.accessKey shouldBe ""
      s.secretKey shouldBe ""
    }
  }
