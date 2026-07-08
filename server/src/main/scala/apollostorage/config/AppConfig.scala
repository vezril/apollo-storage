package apollostorage.config

import com.typesafe.config.Config

import java.nio.file.Path
import scala.concurrent.duration.*

/** HTTP bind settings, resolved from HOCON with env-var overrides (design D8). */
final case class HttpConfig(host: String, port: Int)

/**
 * PostgreSQL journal connection settings, read from the r2dbc plugin config so environment
 * overrides (`POSTGRES_HOST`, …) are honored (design D8). Used by the startup readiness probe; the
 * plugin itself reads the same keys.
 */
final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    connectTimeout: FiniteDuration
)

object AppConfig:
  private val ConnectionFactory = "pekko.persistence.r2dbc.connection-factory"

  def http(config: Config): HttpConfig =
    HttpConfig(
      host = config.getString("apollostorage.http.host"),
      port = config.getInt("apollostorage.http.port")
    )

  /**
   * Root directory for object payloads (blob store), env-overridable via `BLOB_STORE_PATH` (design
   * D8/D14).
   */
  def blobRoot(config: Config): Path =
    Path.of(config.getString("apollostorage.blob.root"))

  /** gRPC bind port, env-overridable via `GRPC_PORT` (design D17). */
  def grpcPort(config: Config): Int =
    config.getInt("apollostorage.grpc.port")

  def postgres(config: Config): PostgresConfig =
    val cf = config.getConfig(ConnectionFactory)
    PostgresConfig(
      host = cf.getString("host"),
      port = cf.getInt("port"),
      database = cf.getString("database"),
      user = cf.getString("user"),
      password = cf.getString("password"),
      connectTimeout = cf.getDuration("connect-timeout").toMillis.millis
    )
