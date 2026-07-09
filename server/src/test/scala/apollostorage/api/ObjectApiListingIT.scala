package apollostorage.api

import apollostorage.blob.{FileSystemBlobStore, ObjectService}
import apollostorage.config.PostgresConfig
import apollostorage.domain.BucketName
import apollostorage.grpc.*
import apollostorage.persistence.{BucketEntity, BucketSharding}
import apollostorage.projection.{BucketProjection, ReadModelRepository}
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.google.protobuf.ByteString as ProtoBytes
import com.typesafe.config.{Config, ConfigFactory}
import io.grpc.{Status, StatusRuntimeException}
import org.testcontainers.utility.DockerImageName
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.sql.DriverManager
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.io.Source as IoSource
import scala.util.Using

/**
 * End-to-end listing test: writes via the gRPC API, the projection folds them into the read model,
 * and `ListBuckets`/`ListObjects` return them (design D23/D24).
 */
final class ObjectApiListingIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures
    with Eventually
    with BeforeAndAfterAll:

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
    databaseName = "apollostorage",
    username = "apollostorage",
    password = "apollostorage"
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(300, Millis))

  private given Timeout = Timeout(10.seconds)
  private var testKit: ActorTestKit = scala.compiletime.uninitialized
  private var client: ObjectApiClient = scala.compiletime.uninitialized

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    val sql = Using.resource(IoSource.fromResource("ddl/create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    )(c =>
      Using.resource(c.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    )

    testKit = ActorTestKit("apollo-listing", config())
    given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
    given ec: scala.concurrent.ExecutionContext = testKit.system.executionContext
    val repo = new ReadModelRepository(pgConfig)
    val store =
      FileSystemBlobStore(java.nio.file.Files.createTempDirectory("apollo-listing-blobs"))(using
        testKit.system
      )
    val sharding = apollostorage.ClusterTestSupport.formSingleNode(testKit.system)
    val entityFor: BucketName => org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef[
      BucketEntity.Command
    ] = b => apollostorage.persistence.BucketSharding.entityRef(sharding, b)
    val impl = new ObjectApiImpl(
      ObjectService(store, entityFor)(using testKit.system, summon),
      store,
      entityFor,
      repo,
      TokenAuthenticator(apollostorage.config.AuthConfig(enabled = false, principals = Nil))
    )(using
      testKit.system,
      summon
    )
    testKit.spawn(ProjectionBehavior(BucketProjection(repo)(using testKit.system)))

    val handler = GrpcServer.handler(impl, HealthServiceImpl(() => true))(using testKit.system)
    val binding = Http()(testKit.system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
    client = ObjectApiClient(
      GrpcClientSettings
        .connectToServiceAt("127.0.0.1", binding.localAddress.getPort)(testKit.system)
        .withTls(false)
    )

  override def beforeStop(): Unit = if testKit != null then testKit.shutdownTestKit()

  private def pgConfig =
    PostgresConfig(
      container.host,
      container.mappedPort(5432),
      container.databaseName,
      container.username,
      container.password,
      5.seconds
    )

  private def config(): Config =
    ConfigFactory
      .parseString(s"""
        pekko.http.server.preview.enable-http2 = on
        pekko.persistence.r2dbc.connection-factory {
          host = "${container.host}"
          port = ${container.mappedPort(5432)}
          database = "${container.databaseName}"
          user = "${container.username}"
          password = "${container.password}"
        }
      """)
      .withFallback(apollostorage.ClusterTestSupport.clusterConfig)
      .withFallback(ConfigFactory.parseResources("persistence.conf"))
      .withFallback(ConfigFactory.parseResources("serialization.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()

  private def put(bucket: String, obj: String): Unit =
    val header = PutObjectRequest(
      PutObjectRequest.Payload.Header(
        PutHeader(bucket = bucket, `object` = obj, contentType = "text/plain")
      )
    )
    val chunk = PutObjectRequest(PutObjectRequest.Payload.Chunk(ProtoBytes.copyFromUtf8("data")))
    client.putObject(Source(List(header, chunk))).futureValue

  private def codeOf(t: Throwable): Status.Code = t match
    case e: StatusRuntimeException => e.getStatus.getCode
    case other => throw other

  "The listing API" should {

    "list buckets and objects by prefix once the projection catches up" in {
      client.createBucket(CreateBucketRequest("gallery")).futureValue
      put("gallery", "photos/a.jpg")
      put("gallery", "photos/b.jpg")
      put("gallery", "docs/x.txt")

      eventually {
        val objs = client.listObjects(ListObjectsRequest("gallery", "photos/", 10, "")).futureValue
        objs.objects.map(_.`object`) shouldBe Seq("photos/a.jpg", "photos/b.jpg")
        client.listBuckets(ListBucketsRequest(10, "")).futureValue.buckets should contain("gallery")
      }
    }

    "drop deleted objects from listings" in {
      client.createBucket(CreateBucketRequest("temp")).futureValue
      put("temp", "keep.txt")
      put("temp", "gone.txt")
      eventually {
        client
          .listObjects(ListObjectsRequest("temp", "", 10, ""))
          .futureValue
          .objects should have size 2
      }
      client.deleteObject(DeleteObjectRequest("temp", "gone.txt")).futureValue
      eventually {
        client
          .listObjects(ListObjectsRequest("temp", "", 10, ""))
          .futureValue
          .objects
          .map(_.`object`) shouldBe Seq("keep.txt")
      }
    }

    "paginate object listings" in {
      client.createBucket(CreateBucketRequest("paged")).futureValue
      put("paged", "k1")
      put("paged", "k2")
      put("paged", "k3")
      eventually {
        client
          .listObjects(ListObjectsRequest("paged", "", 10, ""))
          .futureValue
          .objects should have size 3
      }
      val p1 = client.listObjects(ListObjectsRequest("paged", "", 2, "")).futureValue
      p1.objects.map(_.`object`) shouldBe Seq("k1", "k2")
      p1.nextPageToken shouldBe "k2"
      val p2 = client.listObjects(ListObjectsRequest("paged", "", 2, p1.nextPageToken)).futureValue
      p2.objects.map(_.`object`) shouldBe Seq("k3")
      p2.nextPageToken shouldBe ""
    }

    "return NOT_FOUND when listing a missing bucket" in {
      codeOf(
        client.listObjects(ListObjectsRequest("nonexistent", "", 10, "")).failed.futureValue
      ) shouldBe Status.Code.NOT_FOUND
    }
  }
