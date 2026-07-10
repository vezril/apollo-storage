package apollostorage.domain

import apollostorage.domain.Command.*
import apollostorage.domain.Event.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

/**
 * Guards the shape of the command/event ADTs: exhaustiveness is compiler-checked (a non-exhaustive
 * match here would fail to compile), events fold to state with no external lookup, and commands
 * accept only validated value types.
 */
final class CommandEventSpec extends AnyWordSpec with Matchers:

  private val now = Instant.EPOCH
  private val bucket = BucketName.unsafe("b")
  private val obj = ObjectName.unsafe("k")
  private val meta = ObjectMetadata("application/octet-stream", 1L)
  private val sums = Checksums("c", "m")
  private val blob = BlobRef("blob://k")

  "Command hierarchy" should {
    "match exhaustively without a wildcard" in {
      def label(c: Command): String = c match
        case _: CreateBucket => "create-bucket"
        case _: DeleteBucket => "delete-bucket"
        case _: CommitObject => "commit-object"
        case _: DeleteObject => "delete-object"
      label(CreateBucket(bucket, now)) shouldBe "create-bucket"
      label(DeleteBucket(bucket, now)) shouldBe "delete-bucket"
      label(CommitObject(obj, meta, sums, blob, now)) shouldBe "commit-object"
      label(DeleteObject(obj, now)) shouldBe "delete-object"
    }
  }

  "Event hierarchy" should {
    "match exhaustively without a wildcard" in {
      def label(e: Event): String = e match
        case _: BucketCreated => "bucket-created"
        case _: BucketDeleted => "bucket-deleted"
        case _: ObjectCommitted => "object-committed"
        case _: ObjectDeleted => "object-deleted"
      label(BucketCreated(bucket, now)) shouldBe "bucket-created"
      label(BucketDeleted(bucket, now)) shouldBe "bucket-deleted"
      label(
        ObjectCommitted(obj, Generation.first, meta, sums, blob, now)
      ) shouldBe "object-committed"
      label(ObjectDeleted(obj, now)) shouldBe "object-deleted"
    }

    "be self-contained: state folds from an event list alone" in {
      val events = Seq(
        BucketCreated(bucket, now),
        ObjectCommitted(obj, Generation.first, meta, sums, blob, now),
        ObjectCommitted(obj, Generation.first.next, meta, sums, blob, now)
      )
      BucketDomain.replay(events) match
        case BucketState.Active(name, objects) =>
          name shouldBe bucket
          objects(obj).generation shouldBe Generation.unsafe(2)
        case other => fail(s"expected Active, got $other")
    }
  }

  "Commands" should {
    "carry validated value types, never raw Strings" in {
      // Compile-time guarantee: these constructors require value types. The test
      // documents intent; a raw String here would not compile.
      val c: CommitObject = CommitObject(obj, meta, sums, blob, now)
      c.name shouldBe a[ObjectName]
      c.checksums shouldBe a[Checksums]
      CreateBucket(bucket, now).name shouldBe a[BucketName]
    }
  }
