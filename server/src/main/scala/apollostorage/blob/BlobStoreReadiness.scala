package apollostorage.blob

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

/**
 * Startup readiness for the blob store (design D14). Verifies the root exists, is a directory, and
 * is actually writable (a real probe file, since NFS can report a writable mount that rejects
 * writes). A failure means a misconfigured mount is surfaced up front rather than as silent write
 * loss at commit time.
 */
object BlobStoreReadiness:

  def check(root: Path): Try[Unit] =
    if !Files.exists(root) then
      Failure(new IllegalStateException(s"blob store root does not exist: $root"))
    else if !Files.isDirectory(root) then
      Failure(new IllegalStateException(s"blob store root is not a directory: $root"))
    else
      val probe = root.resolve(s".readiness-${UUID.randomUUID()}")
      Try {
        Files.createFile(probe)
        Files.delete(probe)
      }.recoverWith { case NonFatal(e) =>
        val _ = Try(Files.deleteIfExists(probe))
        Failure(
          new IllegalStateException(s"blob store root is not writable: $root (${e.getMessage})")
        )
      }.map(_ => ())
