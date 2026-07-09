package apollostorage.persistence

import apollostorage.config.PostgresConfig
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import scala.concurrent.duration.*
import scala.util.Using

/**
 * Self-migration against a fresh database (design D62/D63): running the migrator creates the
 * schema, and a second run is a no-op. The container's DDL is NOT pre-applied.
 */
final class PersistenceMigrationIT
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
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(200, Millis))

  private given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  private def pgConfig = PostgresConfig(
    container.host,
    container.mappedPort(5432),
    container.databaseName,
    container.username,
    container.password,
    5.seconds
  )

  private def tableExists(name: String): Boolean =
    val _ = Class.forName("org.postgresql.Driver")
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { c =>
      Using.resource(c.createStatement()) { st =>
        Using.resource(st.executeQuery(s"SELECT to_regclass('public.$name')")) { rs =>
          rs.next() && { rs.getString(1); !rs.wasNull() }
        }
      }
    }

  "PersistenceMigration" should {

    "create the schema on a fresh database, then be a no-op on re-run" in {
      tableExists("bucket_index") shouldBe false // fresh DB, nothing pre-applied

      PersistenceMigration.run(pgConfig).futureValue
      tableExists("event_journal") shouldBe true
      tableExists("projection_timestamp_offset_store") shouldBe true
      tableExists("bucket_index") shouldBe true
      tableExists("object_index") shouldBe true

      // Idempotent: a second run completes without error and leaves the schema in place.
      PersistenceMigration.run(pgConfig).futureValue
      tableExists("bucket_index") shouldBe true
    }
  }
