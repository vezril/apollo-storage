package apollostorage.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}

/**
 * The pure orphan policy (design D52/D53): live refs are kept, unreferenced-and-old blobs are
 * reclaimable, unreferenced-but-recent blobs are spared by the grace period.
 */
final class BlobReconciliationSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-09T12:00:00Z")
  private val grace = Duration.ofHours(24)
  private val old = now.minus(Duration.ofHours(48))
  private val recent = now.minus(Duration.ofHours(1))

  "orphans" should {
    "keep live refs, reclaim unreferenced-and-old, and spare unreferenced-but-recent" in {
      val live = Set(BlobRef("b/aa/live"))
      val onDisk = Vector(
        BlobRef("b/aa/live") -> old, // referenced -> kept even though old
        BlobRef("b/bb/orphan-old") -> old, // unreferenced + old -> reclaimable
        BlobRef("b/cc/orphan-new") -> recent // unreferenced + recent -> spared
      )
      BlobReconciliation.orphans(onDisk, live, now, grace) shouldBe Vector(
        BlobRef("b/bb/orphan-old")
      )
    }

    "return nothing when every on-disk ref is live" in {
      val live = Set(BlobRef("x"), BlobRef("y"))
      BlobReconciliation.orphans(
        Vector(BlobRef("x") -> old, BlobRef("y") -> old),
        live,
        now,
        grace
      ) shouldBe empty
    }
  }

  "isReclaimable" should {
    "be true only when age is at least the grace period" in {
      BlobReconciliation.isReclaimable(old, now, grace) shouldBe true
      BlobReconciliation.isReclaimable(recent, now, grace) shouldBe false
      BlobReconciliation.isReclaimable(now.minus(grace), now, grace) shouldBe true // exactly grace
    }
  }
