package apollostorage.projection

import apollostorage.config.PostgresConfig
import apollostorage.domain.*
import apollostorage.domain.Command.*
import apollostorage.persistence.BucketEntity
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.projection.ProjectionBehavior
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.time.Instant
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * Integration test (testcontainers): the projection folds journal events into the read model, and a
 * restarted projection catches up without duplicating rows.
 */
final class BucketProjectionIT
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
    PatienceConfig(timeout = Span(15, Seconds), interval = Span(200, Millis))

  private var testKit: ActorTestKit = scala.compiletime.uninitialized
  private var repo: ReadModelRepository = scala.compiletime.uninitialized

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val meta = ObjectMetadata("text/plain", 10L)
  private val sums = Checksums("crc", "md5")
  private val blob = BlobRef("blob://x")

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    val sql = Using.resource(Source.fromResource("ddl/create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    )(c =>
      Using.resource(c.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    )
    testKit = ActorTestKit("apollo-proj", config())
    repo = new ReadModelRepository(pgConfig)(using testKit.system.executionContext)

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit() // scalafix:ok DisableSyntax

  private def pgConfig = PostgresConfig(
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
        pekko.actor.provider = "local"
        pekko.persistence.r2dbc.connection-factory {
          host = "${container.host}"
          port = ${container.mappedPort(5432)}
          database = "${container.databaseName}"
          user = "${container.username}"
          password = "${container.password}"
        }
      """)
      .withFallback(ConfigFactory.parseResources("persistence.conf"))
      .withFallback(ConfigFactory.parseResources("serialization.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()

  private def send(bucket: BucketName, cmd: apollostorage.domain.Command): Unit =
    val ref = testKit.spawn(BucketEntity(bucket))
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! BucketEntity.Execute(cmd, probe.ref)
    probe.receiveMessage(10.seconds).isSuccess shouldBe true
    testKit.stop(ref)

  "The bucket projection" should {

    "fold events into the read model" in {
      val bucket = BucketName.unsafe("gallery")
      val obj = ObjectName.unsafe("photos/a.jpg")
      send(bucket, CreateBucket(bucket, now))
      send(bucket, CommitObject(obj, meta, sums, blob, now))

      val projection =
        testKit.spawn(ProjectionBehavior(BucketProjection(repo)(using testKit.system)))

      eventually {
        repo.bucketExists("gallery").futureValue shouldBe true
        val objs = repo.listObjects("gallery", "photos/", 10, "").futureValue.items
        objs.map(_.key) shouldBe Seq("photos/a.jpg")
        objs.head.generation shouldBe 1L
      }

      // recommit → generation 2 in place
      send(bucket, CommitObject(obj, meta, sums, blob, now))
      eventually {
        repo.listObjects("gallery", "photos/", 10, "").futureValue.items.head.generation shouldBe 2L
      }

      // delete object then bucket
      send(bucket, DeleteObject(obj, now))
      eventually {
        repo.listObjects("gallery", "photos/", 10, "").futureValue.items shouldBe empty
      }
      send(bucket, DeleteBucket(bucket, now))
      eventually {
        repo.bucketExists("gallery").futureValue shouldBe false
      }

      testKit.stop(projection)
    }

    "resume after a restart without duplicating rows" in {
      val bucket = BucketName.unsafe("resume")
      send(bucket, CreateBucket(bucket, now))
      send(bucket, CommitObject(ObjectName.unsafe("a"), meta, sums, blob, now))

      val p1 = testKit.spawn(ProjectionBehavior(BucketProjection(repo)(using testKit.system)))
      eventually {
        repo.listObjects("resume", "", 10, "").futureValue.items.map(_.key) shouldBe Seq("a")
      }
      testKit.stop(p1)

      // more events while stopped
      send(bucket, CommitObject(ObjectName.unsafe("b"), meta, sums, blob, now))

      val p2 = testKit.spawn(ProjectionBehavior(BucketProjection(repo)(using testKit.system)))
      eventually {
        repo.listObjects("resume", "", 10, "").futureValue.items.map(_.key) shouldBe Seq("a", "b")
      }
      testKit.stop(p2)
    }
  }
