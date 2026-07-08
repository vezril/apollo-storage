package apollostorage.projection

import apollostorage.config.PostgresConfig
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.testcontainers.utility.DockerImageName
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.sql.DriverManager
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * Testcontainers verification of the read-model repository: idempotent upserts/deletes and
 * keyset-paginated prefix listing.
 */
final class ReadModelRepositoryIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures:

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
    databaseName = "apollostorage",
    username = "apollostorage",
    password = "apollostorage"
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  private lazy val repo = new ReadModelRepository(
    PostgresConfig(
      host = container.host,
      port = container.mappedPort(5432),
      database = container.databaseName,
      user = container.username,
      password = container.password,
      connectTimeout = 5.seconds
    )
  )

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    val sql = Using.resource(Source.fromResource("ddl/create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    )(conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    )

  private val now = Instant.parse("2026-07-08T00:00:00Z")

  "ReadModelRepository" should {

    "upsert and cascade-delete buckets and objects" in {
      repo.upsertBucket("media", now).futureValue
      repo.bucketExists("media").futureValue shouldBe true
      repo.bucketExists("absent").futureValue shouldBe false

      repo.upsertObject("media", "photos/a.jpg", 1, 100, "image/jpeg", "c1", "m1", now).futureValue
      repo.upsertObject("media", "photos/b.jpg", 1, 200, "image/jpeg", "c2", "m2", now).futureValue
      repo.upsertObject("media", "docs/x.txt", 1, 5, "text/plain", "c3", "m3", now).futureValue

      // prefix filter + ordering
      val photos = repo.listObjects("media", "photos/", 10, "").futureValue
      photos.items.map(_.key) shouldBe Seq("photos/a.jpg", "photos/b.jpg")

      // recommit updates the row in place (one row, new generation)
      repo
        .upsertObject("media", "photos/a.jpg", 2, 150, "image/jpeg", "c1b", "m1b", now)
        .futureValue
      val a = repo.listObjects("media", "photos/a.jpg", 10, "").futureValue.items.head
      a.generation shouldBe 2L
      a.size shouldBe 150L

      // delete one object
      repo.deleteObject("media", "photos/a.jpg").futureValue
      repo.listObjects("media", "photos/", 10, "").futureValue.items.map(_.key) shouldBe
        Seq("photos/b.jpg")

      // delete bucket cascades objects
      repo.deleteBucket("media").futureValue
      repo.bucketExists("media").futureValue shouldBe false
      repo.listObjects("media", "", 10, "").futureValue.items shouldBe empty
    }

    "keyset-paginate bucket listing" in {
      Seq("aa", "bb", "cc").foreach(b => repo.upsertBucket(b, now).futureValue)
      val page1 = repo.listBuckets(2, "").futureValue
      page1.items shouldBe Seq("aa", "bb")
      page1.nextPageToken shouldBe "bb"
      val page2 = repo.listBuckets(2, page1.nextPageToken).futureValue
      page2.items shouldBe Seq("cc")
      page2.nextPageToken shouldBe ""
    }
  }
