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
 * S3 backend against a LocalStack testcontainer (add-s3-backend-and-rest-api §3–§4): streaming
 * multipart put with checksum verification, streamed get, delete, and the list-based reconciliation
 * surface.
 *
 * ==Why LocalStack and not MinIO==
 *
 * This suite ran against `minio/minio` until MinIO withdrew its community images from Docker Hub
 * (the repository 404s; `quay.io/minio/minio` requires authentication). It was parked rather than
 * swapped, because the replacement decides what the suite still proves: these tests exercise
 * path-style addressing, streaming multipart put with checksum verification, and ETag/md5 — exactly
 * the edges where a mock diverges from a real implementation, and exactly what QuObjects has to
 * match in production. LocalStack reimplements S3 semantics rather than stubbing them, which is why
 * it was chosen over `adobe/s3mock` despite a slower start.
 *
 * The tag is pinned deliberately, and pinned *old* — see the comment on the image below. `:latest`
 * on a test container makes the suite silently time-dependent, and this suite has already been
 * broken once by an upstream image decision; a current LocalStack tag would break it a second time.
 */
final class S3BlobStoreIT
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with ForAllTestContainer:

  // LocalStack serves every API on one edge port. SERVICES=s3 keeps it from starting the rest,
  // which is most of its startup cost.
  private val EdgePort = 4566
  // LocalStack accepts any credentials; `test` is its own convention.
  private val AccessKey = "test"
  private val SecretKey = "test"

  override val container: GenericContainer = GenericContainer(
    // 4.0.3 and NOT a current tag: LocalStack's 2026.x images refuse to start without a paid
    // LOCALSTACK_AUTH_TOKEN ("License activation failed", exit 55). 4.0.3 predates that gate and
    // boots free — verified locally: health 200, `s3: available`, ListBuckets answers.
    //
    // This pin will rot. It is a 2024 build of a product that has since moved behind a licence, so
    // it will never see another update. That is tolerable for a test-only dependency but it is a
    // dead branch, not a resting place. If it ever breaks, `adobe/s3mock` is the fallback — faster
    // and maintained, at the cost of being a mock rather than a reimplementation.
    "localstack/localstack:4.0.3",
    exposedPorts = Seq(EdgePort),
    env = Map("SERVICES" -> "s3", "EAGER_SERVICE_LOADING" -> "1"),
    waitStrategy = Wait.forHttp("/_localstack/health").forPort(EdgePort).forStatusCode(200)
  )

  private def s3Config(bucketName: String) = S3Config(
    endpoint = s"http://localhost:${container.mappedPort(EdgePort)}",
    region = "us-east-1",
    bucket = bucketName,
    pathStyle = true,
    accessKey = AccessKey,
    secretKey = SecretKey,
    tlsInsecure = false,
    truststorePath = "",
    truststorePassword = ""
  )

  // LocalStack boots slower than MinIO did, and the 6 MiB multipart put is not instant either.
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(250, Millis))

  private var system: ActorSystem[Nothing] = scala.compiletime.uninitialized
  private var store: S3BlobStore = scala.compiletime.uninitialized

  override def afterStart(): Unit = // ForAllTestContainer hook: LocalStack's S3 is up
    system = ActorSystem(Behaviors.empty, "s3blobstore-it")
    store = S3BlobStore(s3Config("apollo-test"))(using system)
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
      val missing = S3BlobStore(s3Config("no-such-bucket"))(using system)
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
