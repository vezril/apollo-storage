package apollostorage.api

import apollostorage.blob.{FileSystemBlobStore, ObjectService}
import apollostorage.domain.{BucketName, Checksums}
import apollostorage.grpc.*
import apollostorage.persistence.BucketEntity
import com.google.protobuf.ByteString as ProtoBytes
import com.typesafe.config.ConfigFactory
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.grpc.{GrpcClientSettings, GrpcServiceException}
import org.apache.pekko.grpc.scaladsl.ServiceHandler
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * In-process gRPC round-trip tests for the object API (h2c). One harness covers bucket lifecycle,
 * streaming upload/download, head, and delete.
 */
final class ObjectApiSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("pekko.http.server.preview.enable-http2 = on")
        .withFallback(apollostorage.ClusterTestSupport.clusterConfig)
        .withFallback(
          org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.config
        )
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  private given Timeout = Timeout(10.seconds)

  private var root: Path = scala.compiletime.uninitialized
  private var client: ObjectApiClient = scala.compiletime.uninitialized

  override protected def beforeAll(): Unit =
    super.beforeAll()
    root = Files.createTempDirectory("apollo-api-blobs")
    val store = FileSystemBlobStore(root)
    val sharding = apollostorage.ClusterTestSupport.formSingleNode(system)
    val entityFor: BucketName => org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef[
      BucketEntity.Command
    ] = b => apollostorage.persistence.BucketSharding.entityRef(sharding, b)
    val objectService = ObjectService(store, entityFor)
    // Listing is covered by ObjectApiListingIT (needs Postgres); unused here.
    val readModel = new apollostorage.projection.ReadModelRepository(
      apollostorage.config.PostgresConfig("localhost", 1, "x", "x", "x", 1.second)
    )(using system.executionContext)
    val impl = new ObjectApiImpl(objectService, store, entityFor, readModel)

    val handler: HttpRequest => Future[HttpResponse] =
      ServiceHandler.concatOrNotFound(ObjectApiHandler.partial(impl))
    val binding = Http()(system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
    val port = binding.localAddress.getPort
    client = ObjectApiClient(
      GrpcClientSettings.connectToServiceAt("127.0.0.1", port)(system).withTls(false)
    )

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

  private def codeOf(t: Throwable): Status.Code = t match
    case e: StatusRuntimeException => e.getStatus.getCode
    case e: GrpcServiceException => e.status.getCode
    case other => throw other

  private def putSource(
      bucket: String,
      obj: String,
      contentType: String,
      bytes: Array[Byte],
      expected: Option[Checksums] = None
  ): Source[PutObjectRequest, org.apache.pekko.NotUsed] =
    val header = PutObjectRequest(
      PutObjectRequest.Payload.Header(
        PutHeader(
          bucket = bucket,
          `object` = obj,
          contentType = contentType,
          expectedCrc32C = expected.map(_.crc32c).getOrElse(""),
          expectedMd5 = expected.map(_.md5).getOrElse("")
        )
      )
    )
    val chunks = bytes
      .grouped(8)
      .map(b => PutObjectRequest(PutObjectRequest.Payload.Chunk(ProtoBytes.copyFrom(b))))
      .toList
    Source(header :: chunks)

  private def download(bucket: String, obj: String): (ObjectMetadata, Array[Byte]) =
    val messages = client.getObject(GetObjectRequest(bucket, obj)).runWith(Sink.seq).futureValue
    val payloads = messages.map(_.payload)
    val header = payloads
      .collectFirst { case GetObjectResponse.Payload.Header(h) => h }
      .getOrElse(fail("first message must be a header"))
    val bytes =
      payloads.collect { case GetObjectResponse.Payload.Chunk(b) => b.toByteArray }.flatten.toArray
    (header, bytes)

  // --- tests -----------------------------------------------------------------

  "Bucket lifecycle" should {
    "create then delete a bucket" in {
      client.createBucket(CreateBucketRequest("media")).futureValue.bucket shouldBe "media"
      client.deleteBucket(DeleteBucketRequest("media")).futureValue.bucket shouldBe "media"
    }

    "reject a duplicate create with ALREADY_EXISTS" in {
      client.createBucket(CreateBucketRequest("dup")).futureValue
      val ex = client.createBucket(CreateBucketRequest("dup")).failed.futureValue
      codeOf(ex) shouldBe Status.Code.ALREADY_EXISTS
    }

    "reject an invalid bucket name with INVALID_ARGUMENT" in {
      codeOf(client.createBucket(CreateBucketRequest("Invalid_Name")).failed.futureValue) shouldBe
        Status.Code.INVALID_ARGUMENT
    }
  }

  "Object upload/download" should {
    "round-trip a streamed payload with metadata" in {
      client.createBucket(CreateBucketRequest("photos")).futureValue
      val payload = ("scenic bytes " * 50).getBytes("UTF-8")
      val resp = client.putObject(putSource("photos", "a/b.jpg", "image/jpeg", payload)).futureValue
      resp.size shouldBe payload.length.toLong
      resp.generation shouldBe 1L
      resp.crc32C should not be empty

      val (header, bytes) = download("photos", "a/b.jpg")
      bytes shouldBe payload
      header.contentType shouldBe "image/jpeg"
      header.size shouldBe payload.length.toLong
      header.crc32C shouldBe resp.crc32C
    }

    "reject a checksum mismatch with FAILED_PRECONDITION and commit nothing" in {
      client.createBucket(CreateBucketRequest("verify")).futureValue
      val bad = Some(Checksums("deadbeef", "0" * 32))
      val ex = client
        .putObject(putSource("verify", "x.bin", "application/octet-stream", "data".getBytes, bad))
        .failed
        .futureValue
      codeOf(ex) shouldBe Status.Code.FAILED_PRECONDITION
      codeOf(client.headObject(HeadObjectRequest("verify", "x.bin")).failed.futureValue) shouldBe
        Status.Code.NOT_FOUND
    }

    "return NOT_FOUND for a missing object on get" in {
      client.createBucket(CreateBucketRequest("empty")).futureValue
      codeOf(
        client.getObject(GetObjectRequest("empty", "nope")).runWith(Sink.seq).failed.futureValue
      ) shouldBe
        Status.Code.NOT_FOUND
    }
  }

  "Head and delete" should {
    "head returns metadata without payload; delete makes it NOT_FOUND" in {
      client.createBucket(CreateBucketRequest("docs")).futureValue
      val payload = "hello".getBytes("UTF-8")
      client.putObject(putSource("docs", "greeting.txt", "text/plain", payload)).futureValue

      val head = client.headObject(HeadObjectRequest("docs", "greeting.txt")).futureValue
      head.size shouldBe payload.length.toLong
      head.generation shouldBe 1L

      client.deleteObject(DeleteObjectRequest("docs", "greeting.txt")).futureValue
      codeOf(
        client.headObject(HeadObjectRequest("docs", "greeting.txt")).failed.futureValue
      ) shouldBe
        Status.Code.NOT_FOUND
    }

    "deleting a missing object is NOT_FOUND" in {
      client.createBucket(CreateBucketRequest("nothing")).futureValue
      codeOf(
        client.deleteObject(DeleteObjectRequest("nothing", "ghost")).failed.futureValue
      ) shouldBe
        Status.Code.NOT_FOUND
    }
  }
