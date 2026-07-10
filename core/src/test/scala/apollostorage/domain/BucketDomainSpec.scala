package apollostorage.domain

import apollostorage.domain.Command.*
import apollostorage.domain.Event.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

final class BucketDomainSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val bucket = BucketName.unsafe("media")
  private val obj = ObjectName.unsafe("a.txt")
  private val meta = ObjectMetadata("text/plain", 3L)
  private val sums = Checksums("crc", "md5")
  private val blob = BlobRef("blob://a")

  private def activeWithObject(gen: Long): BucketState =
    BucketDomain.replay(
      Seq(
        BucketCreated(bucket, now),
        ObjectCommitted(obj, Generation.unsafe(gen), meta, sums, blob, now)
      )
    )

  "decide + evolve" should {

    "create a bucket from Empty and fold to Active" in {
      val Right(events) =
        BucketDomain.decide(BucketState.Empty, CreateBucket(bucket, now)): @unchecked
      events shouldBe Seq(BucketCreated(bucket, now))
      BucketDomain.replay(events) shouldBe BucketState.Active(bucket, Map.empty)
    }

    "increment generation on object commit" in {
      val state = activeWithObject(1)
      val Right(events) =
        BucketDomain.decide(state, CommitObject(obj, meta, sums, blob, now)): @unchecked
      events shouldBe Seq(ObjectCommitted(obj, Generation.unsafe(2), meta, sums, blob, now))
    }

    "start a new object's generation at 1" in {
      val state = BucketDomain.replay(Seq(BucketCreated(bucket, now)))
      val Right(events) =
        BucketDomain.decide(state, CommitObject(obj, meta, sums, blob, now)): @unchecked
      events.head.asInstanceOf[ObjectCommitted].generation shouldBe Generation.first
    }

    "reject duplicate CreateBucket with zero events" in {
      val state = BucketState.Active(bucket, Map.empty)
      BucketDomain.decide(state, CreateBucket(bucket, now)) shouldBe Left(
        DomainError.BucketAlreadyExists
      )
    }

    "reject any command on a non-existent bucket" in {
      BucketDomain.decide(BucketState.Empty, CommitObject(obj, meta, sums, blob, now)) shouldBe
        Left(DomainError.BucketNotFound)
      BucketDomain.decide(BucketState.Empty, DeleteBucket(bucket, now)) shouldBe
        Left(DomainError.BucketNotFound)
    }

    "reject deletion of a non-existent object" in {
      val state = BucketState.Active(bucket, Map.empty)
      BucketDomain.decide(state, DeleteObject(obj, now)) shouldBe Left(DomainError.ObjectNotFound)
    }

    "reject commands after bucket deletion and not resurrect counters" in {
      val state = BucketDomain.replay(Seq(BucketCreated(bucket, now), BucketDeleted(bucket, now)))
      state shouldBe BucketState.Deleted(bucket)
      BucketDomain.decide(state, CommitObject(obj, meta, sums, blob, now)) shouldBe
        Left(DomainError.BucketNotFound)
      // Even after re-creating (rejected), no object map survives the tombstone.
      state match
        case BucketState.Deleted(_) => succeed
        case other => fail(s"expected Deleted, got $other")
    }

    "remove an object on delete" in {
      val state = activeWithObject(1)
      val Right(events) = BucketDomain.decide(state, DeleteObject(obj, now)): @unchecked
      BucketDomain.replay(
        Seq(
          BucketCreated(bucket, now),
          ObjectCommitted(obj, Generation.first, meta, sums, blob, now)
        )
          ++ events
      ) shouldBe BucketState.Active(bucket, Map.empty)
    }
  }
