package apollostorage.blob

import apollostorage.domain.{BlobRef, BucketName, Checksums}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.time.Instant
import scala.concurrent.Future

/**
 * Outcome of storing a payload: the store-assigned reference, the byte count, and the checksums
 * computed while streaming.
 */
final case class BlobPutResult(ref: BlobRef, size: Long, checksums: Checksums)

/** A stored payload as seen by reconciliation: its reference, last-modified time, and size. */
final case class StoredBlob(ref: BlobRef, modifiedAt: Instant, sizeBytes: Long)

/** A leftover temp-write artifact (aborted write) under a bucket's `.tmp` dir. */
final case class TempArtifact(id: String, modifiedAt: Instant, sizeBytes: Long)

/**
 * Storage for object payloads, kept out of the event journal (design D3). All byte movement is
 * streaming; the store owns the opaque `BlobRef` layout (D9).
 */
trait BlobStore:

  /**
   * Stream a payload to durable storage, computing crc32c + md5 in a single pass. If `expected` is
   * supplied and differs from the computed checksums, the put fails and no committed blob remains
   * (design D10). Returns the store-assigned opaque reference.
   */
  def put(
      bucket: BucketName,
      data: Source[ByteString, Any],
      expected: Option[Checksums]
  ): Future[BlobPutResult]

  /** Stream a stored payload by reference, or `None` if no blob exists there. */
  def get(ref: BlobRef): Future[Option[Source[ByteString, Any]]]

  /** Best-effort delete; `true` if a blob was removed, `false` if none existed. */
  def delete(ref: BlobRef): Future[Boolean]

  /** Buckets that currently hold payloads on disk (blob-gc reconciliation, design D51). */
  def listBucketsOnDisk(): Future[Vector[BucketName]]

  /** Stored payloads under a bucket (excludes `.tmp` debris), each with its last-modified time. */
  def listStoredBlobs(bucket: BucketName): Future[Vector[StoredBlob]]

  /** Leftover temp-write artifacts under a bucket's `.tmp` dir. */
  def listTempArtifacts(bucket: BucketName): Future[Vector[TempArtifact]]

  /** Best-effort delete of a temp-write artifact; `true` if one was removed. */
  def deleteTempArtifact(bucket: BucketName, id: String): Future[Boolean]
