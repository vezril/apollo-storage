package apollostorage.blob

import apollostorage.domain.{BlobRef, BucketName, Checksums}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.CRC32C
import scala.jdk.CollectionConverters.*

final class FileSystemBlobStoreSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyWordSpecLike
    with Matchers:

  private val bucket = BucketName.unsafe("media")
  private var root: Path = scala.compiletime.uninitialized
  private var store: FileSystemBlobStore = scala.compiletime.uninitialized

  override protected def beforeAll(): Unit =
    super.beforeAll()
    root = Files.createTempDirectory("apollo-blob-test")
    store = FileSystemBlobStore(root)

  override protected def afterAll(): Unit =
    if root != null then
      Files
        .walk(root)
        .sorted(Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)
    super.afterAll()

  // --- helpers ---------------------------------------------------------------

  private def checksumsOf(bytes: Array[Byte]): Checksums =
    val crc = new CRC32C()
    crc.update(bytes)
    val md5 = MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString
    Checksums(f"${crc.getValue}%08x", md5)

  private def source(bytes: Array[Byte], chunk: Int = 4): Source[ByteString, ?] =
    Source(bytes.grouped(chunk).map(ByteString(_)).toList)

  private def readBack(ref: BlobRef): Array[Byte] =
    store.get(ref).futureValue match
      case Some(src) => src.runFold(ByteString.empty)(_ ++ _).futureValue.toArray
      case None => fail(s"expected blob at $ref")

  private def committedBlobCount: Long =
    Files
      .walk(root)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .count(p => !p.iterator().asScala.exists(_.toString == ".tmp"))

  private def tempFileCount: Long =
    Files
      .walk(root)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .count(p => p.iterator().asScala.exists(_.toString == ".tmp"))

  // --- tests -----------------------------------------------------------------

  "FileSystemBlobStore.put" should {

    "store a multi-chunk payload with correct checksums, size, and readable bytes" in {
      val payload = ("the quick brown fox " * 100).getBytes("UTF-8")
      val result = store.put(bucket, source(payload), expected = None).futureValue
      result.size shouldBe payload.length.toLong
      result.checksums shouldBe checksumsOf(payload)
      readBack(result.ref) shouldBe payload
    }

    "handle an empty payload" in {
      val result = store.put(bucket, Source.empty, expected = None).futureValue
      result.size shouldBe 0L
      result.checksums shouldBe checksumsOf(Array.emptyByteArray)
      readBack(result.ref) shouldBe Array.emptyByteArray
    }

    "verify supplied checksums and accept a match" in {
      val payload = "verify me".getBytes("UTF-8")
      val result = store.put(bucket, source(payload), Some(checksumsOf(payload))).futureValue
      readBack(result.ref) shouldBe payload
    }

    "reject a checksum mismatch, leaving no committed blob and no temp file" in {
      val before = committedBlobCount
      val payload = "real content".getBytes("UTF-8")
      val wrong = Checksums("deadbeef", "00000000000000000000000000000000")
      val ex = store.put(bucket, source(payload), Some(wrong)).failed.futureValue
      ex shouldBe a[BlobStoreException.ChecksumMismatch]
      committedBlobCount shouldBe before // nothing new committed
      tempFileCount shouldBe 0L
    }

    "leave no committed blob or temp file when the stream fails mid-way" in {
      val before = committedBlobCount
      val failing = Source(List(ByteString("abc"), ByteString("def")))
        .concat(Source.failed(new RuntimeException("boom")))
      store.put(bucket, failing, expected = None).failed.futureValue
      committedBlobCount shouldBe before
      tempFileCount shouldBe 0L
    }
  }

  "FileSystemBlobStore references" should {

    "assign a distinct opaque, sharded ref per put and keep prior blobs intact" in {
      val a = "first version".getBytes("UTF-8")
      val b = "second version".getBytes("UTF-8")
      val refA = store.put(bucket, source(a), None).futureValue.ref
      val refB = store.put(bucket, source(b), None).futureValue.ref

      refA.value should not be refB.value
      refA.value should startWith(s"${bucket.value}/")
      refA.value.split("/") should have length 3 // bucket / shard / id
      // both blobs remain readable — a new commit never overwrites a prior blob
      readBack(refA) shouldBe a
      readBack(refB) shouldBe b
    }
  }

  "FileSystemBlobStore.get / delete" should {

    "return None for an unknown reference and delete removes the blob" in {
      store.get(BlobRef("media/zz/doesnotexist")).futureValue shouldBe None
      val ref = store.put(bucket, source("temp".getBytes("UTF-8")), None).futureValue.ref
      store.delete(ref).futureValue shouldBe true
      store.get(ref).futureValue shouldBe None
      store.delete(ref).futureValue shouldBe false // already gone
    }
  }
