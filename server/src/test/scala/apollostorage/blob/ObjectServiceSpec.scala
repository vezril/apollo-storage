package apollostorage.blob

import apollostorage.domain.*
import apollostorage.domain.Command.CreateBucket
import apollostorage.persistence.{BucketEntity, BucketSharding}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import java.util.Comparator
import java.util.zip.CRC32C
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final class ObjectServiceSpec
    extends ScalaTestWithActorTestKit(
      apollostorage.ClusterTestSupport.clusterConfig
        .withFallback(EventSourcedBehaviorTestKit.config)
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  private given Timeout = Timeout(5.seconds)

  private var root: Path = scala.compiletime.uninitialized
  private var store: FileSystemBlobStore = scala.compiletime.uninitialized
  private var entityFor: BucketName => EntityRef[BucketEntity.Command] =
    scala.compiletime.uninitialized

  override protected def beforeAll(): Unit =
    super.beforeAll()
    root = Files.createTempDirectory("apollo-objsvc")
    store = FileSystemBlobStore(root)
    val sharding = apollostorage.ClusterTestSupport.formSingleNode(system)
    entityFor = b => BucketSharding.entityRef(sharding, b)

  override protected def afterAll(): Unit =
    if root != null then
      Files
        .walk(root)
        .sorted(Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)
    super.afterAll()

  private def checksumsOf(bytes: Array[Byte]): Checksums =
    val crc = new CRC32C(); crc.update(bytes)
    Checksums(
      f"${crc.getValue}%08x",
      MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString
    )

  private val meta = ObjectMetadata("text/plain", 0L)

  private def newBucket(name: String): BucketName =
    val bucket = BucketName.unsafe(name)
    entityFor(bucket)
      .askWithStatus[Done](replyTo =>
        BucketEntity.Execute(CreateBucket(bucket, Instant.EPOCH), replyTo)
      )
      .futureValue
    bucket

  private def lookup(bucket: BucketName, name: ObjectName): Option[ObjectEntry] =
    entityFor(bucket)
      .ask[Option[ObjectEntry]](replyTo => BucketEntity.GetObject(name, replyTo))
      .futureValue

  private def committedBlobCount: Long =
    Files
      .walk(root)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .count(p => !p.iterator().asScala.exists(_.toString == ".tmp"))

  "ObjectService.commit" should {

    "persist the blob then an ObjectCommitted carrying the ref and checksums" in {
      val bucket = newBucket("commitok")
      val svc = ObjectService(store, entityFor)
      val payload = "hello blobs".getBytes("UTF-8")
      val name = ObjectName.unsafe("greeting.txt")

      val result =
        svc.commit(bucket, name, meta, Source.single(ByteString(payload)), None).futureValue
      result.checksums shouldBe checksumsOf(payload)

      val entry = lookup(bucket, name).getOrElse(fail("expected committed object"))
      entry.blob shouldBe result.ref
      entry.checksums shouldBe checksumsOf(payload)
      store.get(result.ref).futureValue.isDefined shouldBe true
    }

    "persist no event and no blob when the payload stream fails (no event without a blob)" in {
      val bucket = newBucket("commitfail")
      val svc = ObjectService(store, entityFor)
      val name = ObjectName.unsafe("doomed.bin")
      val before = committedBlobCount

      val failing =
        Source(List(ByteString("ab"))).concat(Source.failed(new RuntimeException("boom")))
      svc.commit(bucket, name, meta, failing, None).failed.futureValue

      lookup(bucket, name) shouldBe None // no ObjectCommitted persisted
      committedBlobCount shouldBe before // no blob on disk
    }

    "persist no event when checksum verification fails" in {
      val bucket = newBucket("commitmismatch")
      val svc = ObjectService(store, entityFor)
      val name = ObjectName.unsafe("tampered.bin")
      val wrong = Checksums("deadbeef", "0" * 32)

      svc
        .commit(bucket, name, meta, Source.single(ByteString("data")), Some(wrong))
        .failed
        .futureValue
      lookup(bucket, name) shouldBe None
    }
  }

  "ObjectService.delete" should {

    "persist ObjectDeleted and remove the payload" in {
      val bucket = newBucket("deleteok")
      val svc = ObjectService(store, entityFor)
      val name = ObjectName.unsafe("gone.txt")
      val committed =
        svc.commit(bucket, name, meta, Source.single(ByteString("bytes")), None).futureValue

      svc.delete(bucket, name).futureValue
      lookup(bucket, name) shouldBe None
      store.get(committed.ref).futureValue shouldBe None
    }

    "leave an orphan (not a dangling object) when the blob delete fails" in {
      val bucket = newBucket("deleteorphan")
      val name = ObjectName.unsafe("orphan.txt")
      // Store that persists/reads normally but always fails delete.
      val deleteFailing = new BlobStore:
        def put(b: BucketName, d: Source[ByteString, Any], e: Option[Checksums]) =
          store.put(b, d, e)
        def get(r: BlobRef) = store.get(r)
        def delete(r: BlobRef) = Future.failed(new RuntimeException("delete boom"))
        def listBucketsOnDisk() = store.listBucketsOnDisk()
        def listStoredBlobs(b: BucketName) = store.listStoredBlobs(b)
        def listTempArtifacts(b: BucketName) = store.listTempArtifacts(b)
        def deleteTempArtifact(b: BucketName, id: String) = store.deleteTempArtifact(b, id)
      val svc = ObjectService(deleteFailing, entityFor)
      val committed =
        svc.commit(bucket, name, meta, Source.single(ByteString("bytes")), None).futureValue

      svc.delete(bucket, name).futureValue // succeeds despite blob-delete failure
      lookup(bucket, name) shouldBe None // object is gone (ObjectDeleted persisted)
      store.get(committed.ref).futureValue.isDefined shouldBe true // blob remains as an orphan
    }

    "reclaim the superseded blob when an object is overwritten" in {
      val bucket = newBucket("overwrite")
      val name = ObjectName.unsafe("f.txt")
      val svc = ObjectService(store, entityFor)
      val first = svc.commit(bucket, name, meta, Source.single(ByteString("v1")), None).futureValue
      val second =
        svc.commit(bucket, name, meta, Source.single(ByteString("version-2")), None).futureValue
      first.ref should not be second.ref
      store.get(first.ref).futureValue shouldBe None // superseded blob reclaimed
      store.get(second.ref).futureValue.isDefined shouldBe true // new generation is live
    }

    "leave the superseded blob as an orphan (not fail the commit) when its reclaim fails" in {
      val bucket = newBucket("overwrite-fail")
      val name = ObjectName.unsafe("f.txt")
      val deleteFailing = new BlobStore:
        def put(b: BucketName, d: Source[ByteString, Any], e: Option[Checksums]) =
          store.put(b, d, e)
        def get(r: BlobRef) = store.get(r)
        def delete(r: BlobRef) = Future.failed(new RuntimeException("delete boom"))
        def listBucketsOnDisk() = store.listBucketsOnDisk()
        def listStoredBlobs(b: BucketName) = store.listStoredBlobs(b)
        def listTempArtifacts(b: BucketName) = store.listTempArtifacts(b)
        def deleteTempArtifact(b: BucketName, id: String) = store.deleteTempArtifact(b, id)
      val svc = ObjectService(deleteFailing, entityFor)
      val first = svc.commit(bucket, name, meta, Source.single(ByteString("v1")), None).futureValue
      val second =
        svc.commit(bucket, name, meta, Source.single(ByteString("version-2")), None).futureValue
      lookup(bucket, name).map(_.blob) shouldBe Some(second.ref) // latest is live
      store.get(first.ref).futureValue.isDefined shouldBe true // superseded blob remains (orphan)
    }
  }
