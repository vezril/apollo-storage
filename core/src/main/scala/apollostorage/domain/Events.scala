package apollostorage.domain

import java.time.Instant

/**
 * Facts that have happened to a bucket aggregate. Each event is self-contained: folding a state
 * purely from an event list requires no external lookup. These are the on-disk journal contract —
 * evolve additively only (design D4).
 */
sealed trait Event extends CborSerializable:
  def at: Instant

object Event:
  final case class BucketCreated(name: BucketName, at: Instant) extends Event
  final case class BucketDeleted(name: BucketName, at: Instant) extends Event

  final case class ObjectCommitted(
      name: ObjectName,
      generation: Generation,
      metadata: ObjectMetadata,
      checksums: Checksums,
      blob: BlobRef,
      at: Instant
  ) extends Event

  final case class ObjectDeleted(name: ObjectName, at: Instant) extends Event
