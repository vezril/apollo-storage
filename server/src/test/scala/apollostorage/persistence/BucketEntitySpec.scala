package apollostorage.persistence

import apollostorage.domain.*
import apollostorage.domain.Command.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/**
 * In-memory (persistence-testkit) verification of the entity behavior. Event serialization is
 * verified here too (verifyEvents = true).
 */
final class BucketEntitySpec
    extends ScalaTestWithActorTestKit(
      EventSourcedBehaviorTestKit.config.withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val bucket = BucketName.unsafe("media")
  private val obj = ObjectName.unsafe("a.txt")
  private val meta = ObjectMetadata("text/plain", 3L)
  private val sums = Checksums("crc", "md5")
  private val blob = BlobRef("blob://a")

  // Verify event serialization only; commands (carry an ActorRef) and state
  // (has a singleton Empty case) are not part of the on-disk contract here.
  private val serialization = EventSourcedBehaviorTestKit.SerializationSettings.enabled
    .withVerifyEquality(true)
    .withVerifyCommands(false)
    .withVerifyState(false)

  private def newKit =
    EventSourcedBehaviorTestKit[BucketEntity.Command, Event, BucketState](
      system,
      BucketEntity(bucket),
      serialization
    )

  "persistenceId" should {
    "be the frozen contract bucket|<name>" in {
      BucketEntity.persistenceId("media").id shouldBe "bucket|media"
    }
  }

  "BucketEntity" should {

    "persist exactly one event and reply success on CreateBucket" in {
      val kit = newKit
      val result =
        kit.runCommand[StatusReply[Done]](rt => BucketEntity.Execute(CreateBucket(bucket, now), rt))
      result.reply.isSuccess shouldBe true
      result.events shouldBe Seq(Event.BucketCreated(bucket, now))
    }

    "persist nothing and reply error on a rejected command" in {
      val kit = newKit
      kit.runCommand[StatusReply[Done]](rt => BucketEntity.Execute(CreateBucket(bucket, now), rt))
      val result =
        kit.runCommand[StatusReply[Done]](rt => BucketEntity.Execute(CreateBucket(bucket, now), rt))
      result.reply.isError shouldBe true
      result.reply.getError.getMessage should include("already exists")
      result.events shouldBe empty
    }

    "increment generation across commits" in {
      val kit = newKit
      kit.runCommand[StatusReply[Done]](rt => BucketEntity.Execute(CreateBucket(bucket, now), rt))
      val first = kit.runCommand[StatusReply[Done]](rt =>
        BucketEntity.Execute(CommitObject(obj, meta, sums, blob, now), rt)
      )
      val second = kit.runCommand[StatusReply[Done]](rt =>
        BucketEntity.Execute(CommitObject(obj, meta, sums, blob, now), rt)
      )
      first.events.head.asInstanceOf[Event.ObjectCommitted].generation shouldBe Generation.first
      second.events.head
        .asInstanceOf[Event.ObjectCommitted]
        .generation shouldBe Generation.unsafe(2)
    }
  }
