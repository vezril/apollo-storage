package apollostorage.domain

import java.time.Instant

/**
 * Commands directed at a single bucket aggregate. Every command accepts only validated value types
 * (`BucketName`, `ObjectName`, `Checksums`, …) — raw unvalidated `String`s cannot enter the
 * aggregate.
 *
 * `at` is the caller-supplied instant the command was issued; the pure decider stamps produced
 * events with it, keeping transitions deterministic (no hidden clock reads). The entity layer fills
 * it with `Instant.now()` on receipt.
 */
sealed trait Command:
  def at: Instant

object Command:
  final case class CreateBucket(name: BucketName, at: Instant) extends Command
  final case class DeleteBucket(name: BucketName, at: Instant) extends Command

  final case class CommitObject(
      name: ObjectName,
      metadata: ObjectMetadata,
      checksums: Checksums,
      blob: BlobRef,
      at: Instant
  ) extends Command

  final case class DeleteObject(name: ObjectName, at: Instant) extends Command
