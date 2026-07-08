package apollostorage.persistence

import apollostorage.domain.*
import apollostorage.domain.Command.*
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import org.testcontainers.utility.DockerImageName
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.pattern.StatusReply
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.sql.DriverManager
import java.time.Instant
import scala.io.Source
import scala.util.Using

/**
 * Integration test: domain events round-trip through a real PostgreSQL journal (testcontainers)
 * with correct sequence numbers, and state recovers after an entity restart (event-persistence
 * spec, design D1).
 */
final class BucketPersistenceIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures
    with BeforeAndAfterAll:

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
    databaseName = "apollostorage",
    username = "apollostorage",
    password = "apollostorage"
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  private var testKit: ActorTestKit = scala.compiletime.uninitialized

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val meta = ObjectMetadata("text/plain", 3L)
  private val sums = Checksums("crc", "md5")
  private val blob = BlobRef("blob://a")

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver") // ensure JDBC driver registered
    applySchema()
    testKit = ActorTestKit("apollo-it", config())

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit()

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

  private def applySchema(): Unit =
    val sql = Using.resource(Source.fromResource("ddl/create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      // The Postgres simple-query protocol runs a whole multi-statement script
      // (comments and all) in one execute — no fragile client-side ';' splitting.
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    }

  private def seqNrs(persistenceId: String): Seq[Long] =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      val rs = conn
        .createStatement()
        .executeQuery(
          s"SELECT seq_nr FROM event_journal WHERE persistence_id = '$persistenceId' ORDER BY seq_nr"
        )
      Iterator.continually(rs).takeWhile(_.next()).map(_.getLong("seq_nr")).toList
    }

  private def send(bucket: BucketName, cmd: DomainCommandAlias): StatusReply[Done] =
    val ref = testKit.spawn(BucketEntity(bucket))
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! BucketEntity.Command(cmd, probe.ref)
    val reply = probe.receiveMessage()
    testKit.stop(ref)
    reply

  private type DomainCommandAlias = apollostorage.domain.Command

  "Events round-trip through real Postgres" should {
    "persist CreateBucket then CommitObject with sequence numbers 1 and 2" in {
      val bucket = BucketName.unsafe("media")
      send(bucket, CreateBucket(bucket, now)).isSuccess shouldBe true
      send(
        bucket,
        CommitObject(ObjectName.unsafe("a.txt"), meta, sums, blob, now)
      ).isSuccess shouldBe true
      seqNrs("bucket|media") shouldBe Seq(1L, 2L)
    }
  }

  "Crash recovery" should {
    "reconstruct generation counters so the next commit is generation 3" in {
      val bucket = BucketName.unsafe("gens")
      val obj = ObjectName.unsafe("a")
      send(bucket, CreateBucket(bucket, now)).isSuccess shouldBe true
      send(bucket, CommitObject(obj, meta, sums, blob, now)).isSuccess shouldBe true
      send(bucket, CommitObject(obj, meta, sums, blob, now)).isSuccess shouldBe true

      // Fresh entity instance recovers from the journal; the next commit is gen 3.
      val ref = testKit.spawn(BucketEntity(bucket))
      val probe = testKit.createTestProbe[StatusReply[Done]]()
      ref ! BucketEntity.Command(CommitObject(obj, meta, sums, blob, now), probe.ref)
      probe.receiveMessage().isSuccess shouldBe true
      testKit.stop(ref)

      seqNrs("bucket|gens") shouldBe Seq(1L, 2L, 3L, 4L) // create + 3 commits
    }

    "keep a deleted bucket deleted after replay" in {
      val bucket = BucketName.unsafe("goner")
      send(bucket, CreateBucket(bucket, now)).isSuccess shouldBe true
      send(bucket, DeleteBucket(bucket, now)).isSuccess shouldBe true
      // After recovery, any object command is rejected (deletion not resurrected).
      val reply = send(bucket, CommitObject(ObjectName.unsafe("x"), meta, sums, blob, now))
      reply.isError shouldBe true
      reply.getError.getMessage should include("does not exist")
    }
  }
