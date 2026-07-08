package apollostorage.blob

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/**
 * The startup blob-store probe must pass on a writable root and fail fast, with the path named, on
 * a missing or read-only root (design D14).
 */
final class BlobStoreReadinessSpec extends AnyWordSpec with Matchers:

  "BlobStoreReadiness.check" should {

    "succeed on a writable directory" in {
      val dir = Files.createTempDirectory("apollo-ready-ok")
      try BlobStoreReadiness.check(dir).isSuccess shouldBe true
      finally Files.deleteIfExists(dir)
    }

    "fail with the path named when the root does not exist" in {
      val missing = Path.of("/nonexistent/apollo/objects/xyz")
      val result = BlobStoreReadiness.check(missing)
      result.isFailure shouldBe true
      result.failed.get.getMessage should include(missing.toString)
    }

    "fail on a read-only directory" in {
      val dir = Files.createTempDirectory("apollo-ready-ro")
      try
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        // If we can still create a file (e.g. running as root), the check can't
        // observe read-only — skip rather than assert a false negative.
        val probe = dir.resolve(".probe")
        val actuallyReadOnly =
          try { Files.createFile(probe); Files.delete(probe); false }
          catch case _: Throwable => true
        assume(actuallyReadOnly, "directory is still writable (likely running as root)")

        val result = BlobStoreReadiness.check(dir)
        result.isFailure shouldBe true
        result.failed.get.getMessage should include(dir.toString)
      finally
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.deleteIfExists(dir)
    }
  }
