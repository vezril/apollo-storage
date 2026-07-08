package apollostorage.persistence

import apollostorage.domain.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/**
 * Mandatory schema-evolution regression suite (event-persistence spec / design D4): every event
 * constructor must round-trip through the configured Jackson-CBOR serializer and compare equal.
 */
final class EventSerializationSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyWordSpecLike
    with Matchers:

  private val serialization = SerializationExtension(system)
  private val now = Instant.parse("2026-07-08T12:34:56Z")
  private val bucket = BucketName.unsafe("media")
  private val obj = ObjectName.unsafe("photos/2026/x.jpg")
  private val meta = ObjectMetadata("image/jpeg", 2048L, Map("owner" -> "calvin"))
  private val sums = Checksums("crc32c-abc", "md5-def")
  private val blob = BlobRef("blob://media/photos/2026/x.jpg")

  private def roundTrip(event: Event): Event =
    val serializer = serialization.findSerializerFor(event)
    val bytes = serializer.toBinary(event)
    val manifest = org.apache.pekko.serialization.Serializers.manifestFor(serializer, event)
    serialization.deserialize(bytes, serializer.identifier, manifest).get.asInstanceOf[Event]

  "Every event constructor" should {
    val samples: Seq[Event] = Seq(
      Event.BucketCreated(bucket, now),
      Event.BucketDeleted(bucket, now),
      Event.ObjectCommitted(obj, Generation.unsafe(2), meta, sums, blob, now),
      Event.ObjectDeleted(obj, now)
    )

    samples.foreach { event =>
      s"round-trip ${event.getClass.getSimpleName} through Jackson CBOR" in {
        val serializer = serialization.findSerializerFor(event)
        serializer.getClass.getName should include("Cbor")
        roundTrip(event) shouldBe event
      }
    }
  }
