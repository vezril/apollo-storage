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

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * The reconciliation sweep (design D54): dry-run reports without deleting; a confirmed sweep
 * reclaims only aged orphans + stale `.tmp` debris, keeps live objects, and survives a failed
 * unlink. Each test uses its own blob root (isolated) but shares the cluster + journal.
 */
final class BlobGcSpec
    extends ScalaTestWithActorTestKit(
      apollostorage.ClusterTestSupport.clusterConfig
        .withFallback(EventSourcedBehaviorTestKit.config)
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  private given Timeout = Timeout(5.seconds)
  private val grace = Duration.ofHours(24)
  private val meta = ObjectMetadata("text/plain", 0L)

  private var entityFor: BucketName => EntityRef[BucketEntity.Command] =
    scala.compiletime.uninitialized

  override protected def beforeAll(): Unit =
    super.beforeAll()
    val sharding = apollostorage.ClusterTestSupport.formSingleNode(system)
    entityFor = b => BucketSharding.entityRef(sharding, b)

  private def newStore(): (FileSystemBlobStore, Path) =
    val root = Files.createTempDirectory("apollo-gc")
    (FileSystemBlobStore(root), root)

  private def createBucket(name: String): BucketName =
    val bucket = BucketName.unsafe(name)
    entityFor(bucket)
      .askWithStatus[Done](rt => BucketEntity.Execute(CreateBucket(bucket, Instant.now()), rt))
      .futureValue
    bucket

  /** Write a blob directly (no commit) → an orphan on disk that no entity references. */
  private def orphanBlob(
      store: FileSystemBlobStore,
      root: Path,
      bucket: BucketName,
      bytes: String,
      ageHours: Long
  ): BlobRef =
    val put = store.put(bucket, Source.single(ByteString(bytes)), None).futureValue
    Files.setLastModifiedTime(
      root.resolve(put.ref.value),
      FileTime.from(Instant.now().minus(Duration.ofHours(ageHours)))
    )
    put.ref

  "BlobGc" should {

    "report orphans on a dry run without deleting anything" in {
      val (store, root) = newStore()
      val bucket = createBucket("gc-dry")
      val live = ObjectService(store, entityFor)
        .commit(
          bucket,
          ObjectName.unsafe("keep.txt"),
          meta,
          Source.single(ByteString("live")),
          None
        )
        .futureValue
      val oldOrphan = orphanBlob(store, root, bucket, "old-orphan", ageHours = 48)
      val newOrphan = orphanBlob(store, root, bucket, "new-orphan", ageHours = 1)

      val report = new BlobGc(store, entityFor, grace).sweep(delete = false).futureValue
      report.dryRun shouldBe true
      report.orphansFound shouldBe 1 // only the aged orphan; the recent one is spared
      report.reclaimed shouldBe 0
      store.get(live.ref).futureValue.isDefined shouldBe true
      store.get(oldOrphan).futureValue.isDefined shouldBe true
      store.get(newOrphan).futureValue.isDefined shouldBe true
    }

    "reclaim aged orphans and stale temp debris on a confirmed sweep, keeping live + recent" in {
      val (store, root) = newStore()
      val bucket = createBucket("gc-del")
      val live = ObjectService(store, entityFor)
        .commit(
          bucket,
          ObjectName.unsafe("keep.txt"),
          meta,
          Source.single(ByteString("live")),
          None
        )
        .futureValue
      val oldOrphan = orphanBlob(store, root, bucket, "old-orphan-bytes", ageHours = 48)
      val newOrphan = orphanBlob(store, root, bucket, "recent", ageHours = 1)
      val tmpDir = root.resolve(bucket.value).resolve(".tmp")
      Files.createDirectories(tmpDir)
      val debris = tmpDir.resolve("half")
      Files.writeString(debris, "junk")
      Files.setLastModifiedTime(debris, FileTime.from(Instant.now().minus(Duration.ofHours(48))))

      val report = new BlobGc(store, entityFor, grace).sweep(delete = true).futureValue
      report.reclaimed shouldBe 1
      report.bytesReclaimed shouldBe "old-orphan-bytes".length.toLong
      report.tmpReclaimed shouldBe 1
      store.get(oldOrphan).futureValue shouldBe None // reclaimed
      store.get(newOrphan).futureValue.isDefined shouldBe true // recent spared
      store.get(live.ref).futureValue.isDefined shouldBe true // live kept
      Files.exists(debris) shouldBe false // tmp reclaimed
    }

    "survive a failed unlink — count it, do not throw" in {
      val (store, root) = newStore()
      val bucket = createBucket("gc-fail")
      orphanBlob(store, root, bucket, "doomed", ageHours = 48)
      val deleteFailing = new BlobStore:
        def put(b: BucketName, d: Source[ByteString, Any], e: Option[Checksums]) =
          store.put(b, d, e)
        def get(r: BlobRef) = store.get(r)
        def delete(r: BlobRef) = Future.failed(new RuntimeException("unlink boom"))
        def listBucketsOnDisk() = store.listBucketsOnDisk()
        def listStoredBlobs(b: BucketName) = store.listStoredBlobs(b)
        def listTempArtifacts(b: BucketName) = store.listTempArtifacts(b)
        def deleteTempArtifact(b: BucketName, id: String) = store.deleteTempArtifact(b, id)
      val report = new BlobGc(deleteFailing, entityFor, grace).sweep(delete = true).futureValue
      report.orphansFound shouldBe 1
      report.reclaimed shouldBe 0 // the unlink failed but the sweep completed
    }
  }
