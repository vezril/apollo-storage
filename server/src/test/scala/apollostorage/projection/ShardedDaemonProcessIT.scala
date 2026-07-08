package apollostorage.projection

import apollostorage.ClusterTestSupport
import apollostorage.config.PostgresConfig
import apollostorage.domain.*
import apollostorage.domain.Command.*
import apollostorage.persistence.{BucketEntity, BucketSharding}
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import org.testcontainers.utility.DockerImageName
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, ShardedDaemonProcess}
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.sql.DriverManager
import java.time.Instant
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * The projection distributed via ShardedDaemonProcess (design D30): N slice-range instances
 * together cover all slices, so events across many buckets all land in the read model. Single-node
 * cluster here; true multi-node rebalance is design-verified + deployment-smoke-verified.
 */
final class ShardedDaemonProcessIT
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
  private var repo: ReadModelRepository = scala.compiletime.uninitialized
  private var sharding: ClusterSharding = scala.compiletime.uninitialized

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val meta = ObjectMetadata("text/plain", 3L)
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

    testKit = ActorTestKit("apollo-sdp", config())
    given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
    repo = new ReadModelRepository(pgConfig)(using testKit.system.executionContext)
    sharding = ClusterTestSupport.formSingleNode(testKit.system)

    val ranges = BucketProjection.sliceRanges(4)
    ShardedDaemonProcess(testKit.system).init[ProjectionBehavior.Command](
      name = "bucket-projection",
      numberOfInstances = ranges.size,
      behaviorFactory = i => ProjectionBehavior(BucketProjection.forRange(repo, ranges(i))),
      stopMessage = ProjectionBehavior.Stop
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
        pekko.persistence.r2dbc.connection-factory {
          host = "${container.host}"
          port = ${container.mappedPort(5432)}
          database = "${container.databaseName}"
          user = "${container.username}"
          password = "${container.password}"
        }
      """)
      .withFallback(ClusterTestSupport.clusterConfig)
      .withFallback(ConfigFactory.parseResources("persistence.conf"))
      .withFallback(ConfigFactory.parseResources("serialization.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()

  private def commit(bucketName: String): Unit =
    val bucket = BucketName.unsafe(bucketName)
    val ref = BucketSharding.entityRef(sharding, bucket)
    ref.askWithStatus[Done](rt => BucketEntity.Execute(CreateBucket(bucket, now), rt)).futureValue
    ref
      .askWithStatus[Done](rt =>
        BucketEntity.Execute(CommitObject(ObjectName.unsafe("obj"), meta, sums, blob, now), rt)
      )
      .futureValue

  "The ShardedDaemonProcess projection" should {
    "cover all slices so events across many buckets land in the read model" in {
      val buckets = Seq("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
      buckets.foreach(commit)
      eventually {
        buckets.foreach { b =>
          repo.listObjects(b, "", 10, "").futureValue.items.map(_.key) shouldBe Seq("obj")
        }
      }
    }
  }
