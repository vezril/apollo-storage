package apollostorage.blob

import apollostorage.domain.BucketName
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}

/**
 * Enumeration used by blob-gc reconciliation (design D51): stored payloads (ref + age), temp-write
 * debris, and the buckets that actually hold blobs on disk.
 */
final class BlobEnumerationSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  private given scala.concurrent.ExecutionContext = system.executionContext

  private def newStore(): (FileSystemBlobStore, Path) =
    val root = Files.createTempDirectory("apollo-enum")
    (FileSystemBlobStore(root), root)

  private def put(store: FileSystemBlobStore, bucket: String, bytes: String) =
    store.put(BucketName.unsafe(bucket), Source.single(ByteString(bytes)), None).futureValue

  "FileSystemBlobStore enumeration" should {

    "list buckets that hold blobs on disk" in {
      val (store, _) = newStore()
      put(store, "alpha", "a")
      put(store, "beta", "b")
      store.listBucketsOnDisk().futureValue.map(_.value).toSet shouldBe Set("alpha", "beta")
    }

    "list stored blobs with their refs and modified times" in {
      val (store, _) = newStore()
      val r1 = put(store, "alpha", "one")
      val r2 = put(store, "alpha", "two")
      val stored = store.listStoredBlobs(BucketName.unsafe("alpha")).futureValue
      stored.map(_.ref).toSet shouldBe Set(r1.ref, r2.ref)
      stored.foreach(_.modifiedAt should not be null)
    }

    "list temp-write artifacts separately and delete them" in {
      val (store, root) = newStore()
      put(store, "alpha", "x")
      // Simulate aborted-write debris in the bucket's .tmp dir.
      val tmpDir = root.resolve("alpha").resolve(".tmp")
      Files.createDirectories(tmpDir)
      Files.writeString(tmpDir.resolve("half-written"), "junk")
      val temps = store.listTempArtifacts(BucketName.unsafe("alpha")).futureValue
      temps.map(_.id) should contain("half-written")
      // stored-blob enumeration must NOT include .tmp debris
      store
        .listStoredBlobs(BucketName.unsafe("alpha"))
        .futureValue
        .map(_.ref.value)
        .exists(_.contains(".tmp")) shouldBe false
      store.deleteTempArtifact(BucketName.unsafe("alpha"), "half-written").futureValue shouldBe true
      Files.exists(tmpDir.resolve("half-written")) shouldBe false
    }
  }
