package apollostorage.domain

import java.time.{Duration, Instant}

/**
 * The pure orphan-reclamation policy (design D52/D53) — no Pekko, no I/O, so the safety rules are
 * test-provable independent of the filesystem. A stored blob is an orphan iff no live object
 * references it; it is *reclaimable* only if it is also at least the grace period old, which
 * protects blobs written just before their commit event (and any recent write).
 */
object BlobReconciliation:

  /** The subset of `onDisk` (ref + last-modified) that is unreferenced by `live` and old enough. */
  def orphans(
      onDisk: Iterable[(BlobRef, Instant)],
      live: Set[BlobRef],
      now: Instant,
      grace: Duration
  ): Vector[BlobRef] =
    onDisk.iterator.collect {
      case (ref, modifiedAt) if !live.contains(ref) && isReclaimable(modifiedAt, now, grace) => ref
    }.toVector

  /** True iff the age (`now - modifiedAt`) is at least `grace`. */
  def isReclaimable(modifiedAt: Instant, now: Instant, grace: Duration): Boolean =
    Duration.between(modifiedAt, now).compareTo(grace) >= 0
