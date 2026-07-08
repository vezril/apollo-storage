package apollostorage.blob

import apollostorage.domain.Checksums

/** Failures raised by the blob store (surfaced as failed `Future`s). */
sealed abstract class BlobStoreException(message: String) extends RuntimeException(message)

object BlobStoreException:
  /**
   * The streamed bytes did not match caller-supplied expected checksums; no committed blob was
   * written (design D10).
   */
  final case class ChecksumMismatch(expected: Checksums, actual: Checksums)
      extends BlobStoreException(s"checksum mismatch: expected $expected but computed $actual")

  /** A resolved blob path escaped the store root — a corrupt or hostile reference. */
  final case class InvalidReference(ref: String)
      extends BlobStoreException(s"blob reference escapes the store root: $ref")
