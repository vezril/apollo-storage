package apollostorage.blob

import apollostorage.config.S3Config
import apollostorage.domain.{BucketName, Checksums}
import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

/**
 * S3 backend against a MinIO testcontainer (add-s3-backend-and-rest-api §3–§4): streaming multipart
 * put with checksum verification, streamed get, delete, and the list-based reconciliation surface.
 */
final class S3BlobStoreIT
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with ForAllTestContainer:

  override val container: GenericContainer = GenericContainer(
    "minio/minio:latest",
    exposedPorts = Seq(9000),
    env = Map("MINIO_ROOT_USER" -> "minioadmin", "MINIO_ROOT_PASSWORD" -> "minioadmin"),
    command = Seq("server", "/data"),
    waitStrategy = Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200)
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(200, Millis))

  private var system: ActorSystem[Nothing] = scala.compiletime.uninitialized
  private var store: S3BlobStore = scala.compiletime.uninitialized

  override def afterStart(): Unit = // ForAllTestContainer hook: MinIO is up
    system = ActorSystem(Behaviors.empty, "s3blobstore-it")
    val cfg = S3Config(
      endpoint = s"http://localhost:${container.mappedPort(9000)}",
      region = "us-east-1",
      bucket = "apollo-test",
      pathStyle = true,
      accessKey = "minioadmin",
      secretKey = "minioadmin",
      tlsInsecure = false,
      truststorePath = "",
      truststorePassword = ""
    )
    store = S3BlobStore(cfg)(using system)
    store.ensureBucket().futureValue
    ()

  override def beforeStop(): Unit =
    if system != null then system.terminate() // scalafix:ok DisableSyntax

  private val bucket = BucketName.unsafe("photos")
  private def src(bytes: Array[Byte]) = Source.single(ByteString(bytes))
  private def bigPayload = Array.fill(6 * 1024 * 1024)('a'.toByte) // >5 MiB → real multipart

  "S3BlobStore" should {

    "round-trip a multipart upload and report size + checksums" in {
      val put = store.put(bucket, src(bigPayload), None).futureValue
      put.size shouldBe bigPayload.length.toLong
      put.checksums.crc32c should not be empty
      put.checksums.md5 should not be empty
      val read = store.get(put.ref).futureValue
      read.isDefined shouldBe true
      val n = read.get
        .runFold(0L)((acc, bs) => acc + bs.length)(Materializer(system))
        .futureValue
      n shouldBe bigPayload.length.toLong
    }

    "fail a checksum-mismatched put leaving nothing committed" in {
      val before = store.listStoredBlobs(bucket).futureValue.size
      val wrong = Some(Checksums("00000000", "0" * 32))
      store.put(bucket, src("hello".getBytes), wrong).failed.futureValue shouldBe a[
        BlobStoreException.ChecksumMismatch
      ]
      store.listStoredBlobs(bucket).futureValue.size shouldBe before // no new object
    }

    "return None for an absent reference" in {
      store.get(apollostorage.domain.BlobRef("photos/zz/does-not-exist")).futureValue shouldBe None
    }

    "delete a stored payload" in {
      val put = store.put(bucket, src("bye".getBytes), None).futureValue
      store.delete(put.ref).futureValue shouldBe true
      store.get(put.ref).futureValue shouldBe None
    }

    "enumerate stored payloads with size and last-modified, and the bucket" in {
      val put = store.put(bucket, src("data".getBytes), None).futureValue
      val stored = store.listStoredBlobs(bucket).futureValue
      stored.map(_.ref) should contain(put.ref)
      stored.find(_.ref == put.ref).get.sizeBytes shouldBe 4L
      store.listBucketsOnDisk().futureValue should contain(bucket)
    }

    "pass readiness for the existing bucket and fail for a missing one" in {
      store.checkReadiness().futureValue // existing bucket → ok
      val missing = S3BlobStore(
        S3Config(
          endpoint = s"http://localhost:${container.mappedPort(9000)}",
          region = "us-east-1",
          bucket = "no-such-bucket",
          pathStyle = true,
          accessKey = "minioadmin",
          secretKey = "minioadmin",
          tlsInsecure = false,
          truststorePath = "",
          truststorePassword = ""
        )
      )(using system)
      missing.checkReadiness().failed.futureValue // unreachable/missing bucket ⇒ fails fast
    }

    "list temp artifacts (no debris after clean uploads) and reject a malformed id" in {
      store.listTempArtifacts(bucket).futureValue shouldBe empty
      store
        .deleteTempArtifact(bucket, "no-pipe-separator")
        .failed
        .futureValue shouldBe a[BlobStoreException.InvalidReference]
    }
  }
