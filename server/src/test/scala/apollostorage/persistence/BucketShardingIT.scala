package apollostorage.persistence

import apollostorage.domain.*
import apollostorage.domain.Command.*
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.apache.pekko.util.Timeout
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
 * Verifies Cluster Sharding for bucket entities in a single-node cluster (design D29): commands
 * route to one entity, the persistence id stays `bucket|<name>`, and generation counters work
 * end-to-end through the real journal.
 */
final class BucketShardingIT
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

  private given Timeout = Timeout(10.seconds)
  private var testKit: ActorTestKit = scala.compiletime.uninitialized
  private var sharding: ClusterSharding = scala.compiletime.uninitialized

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val meta = ObjectMetadata("text/plain", 3L)
  private val sums = Checksums("crc", "md5")
  private val blob = BlobRef("blob://a")

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

    testKit = ActorTestKit("apollo-sharding", config())
    val cluster = Cluster(testKit.system)
    cluster.manager ! Join(cluster.selfMember.address)
    eventually(cluster.selfMember.status shouldBe MemberStatus.Up)
    sharding = BucketSharding.init(testKit.system)

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit() // scalafix:ok DisableSyntax

  private def config(): Config =
    ConfigFactory
      .parseString(s"""
        pekko.actor.provider = "cluster"
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = 0
        pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
        pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
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

  private def run(bucket: BucketName, cmd: apollostorage.domain.Command): Done =
    val ref = BucketSharding.entityRef(sharding, bucket)
    ref.askWithStatus[Done](replyTo => BucketEntity.Execute(cmd, replyTo)).futureValue

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

  "Cluster-sharded bucket entities" should {
    "route to one entity with persistence id bucket|<name> and increment generation" in {
      val bucket = BucketName.unsafe("media")
      val obj = ObjectName.unsafe("a.txt")
      run(bucket, CreateBucket(bucket, now)) shouldBe Done
      run(bucket, CommitObject(obj, meta, sums, blob, now)) shouldBe Done
      run(bucket, CommitObject(obj, meta, sums, blob, now)) shouldBe Done

      seqNrs("bucket|media") shouldBe Seq(1L, 2L, 3L)

      val entry = BucketSharding
        .entityRef(sharding, bucket)
        .ask[Option[ObjectEntry]](replyTo => BucketEntity.GetObject(obj, replyTo))
        .futureValue
      entry.map(_.generation) shouldBe Some(Generation.unsafe(2))
    }
  }
