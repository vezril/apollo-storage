package apollostorage.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The blob-store root comes from HOCON and is overridable by `BLOB_STORE_PATH` (design D8/D14).
 */
final class BlobConfigSpec extends AnyWordSpec with Matchers:

  private val base = """apollostorage.blob.root = "/var/lib/apollostorage/objects""""

  "blob root config" should {

    "use the committed default when no override is present" in {
      val config = ConfigFactory.parseString(base).resolve()
      AppConfig.blobRoot(config).toString shouldBe "/var/lib/apollostorage/objects"
    }

    "let BLOB_STORE_PATH override the default" in {
      val overridden = ConfigFactory
        .parseString("""apollostorage.blob.root = "/mnt/nas/objects"""")
        .withFallback(ConfigFactory.parseString(base))
        .resolve()
      AppConfig.blobRoot(overridden).toString shouldBe "/mnt/nas/objects"
    }
  }
