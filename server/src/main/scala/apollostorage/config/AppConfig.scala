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

/** TLS settings for the gRPC surface (design D34). */
final case class TlsConfig(enabled: Boolean, keystorePath: String, keystorePassword: String)

/** Bearer-token auth settings (design D35/D39); tokens are secrets. */
final case class AuthConfig(enabled: Boolean, tokens: Seq[String])

/** Prometheus metrics settings (design D40/D44); on by default, disableable. */
final case class MetricsConfig(enabled: Boolean)

/** Blob-gc settings (design D53/D56): the admin sweep, off by default, and the grace period. */
final case class BlobGcConfig(enabled: Boolean, grace: FiniteDuration)

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

  def tls(config: Config): TlsConfig =
    TlsConfig(
      enabled = config.getBoolean("apollostorage.tls.enabled"),
      keystorePath = config.getString("apollostorage.tls.keystore-path"),
      keystorePassword = config.getString("apollostorage.tls.keystore-password")
    )

  def auth(config: Config): AuthConfig =
    AuthConfig(
      enabled = config.getBoolean("apollostorage.auth.enabled"),
      tokens = config
        .getString("apollostorage.auth.tokens")
        .split(",")
        .map(_.trim)
        .filter(_.nonEmpty)
        .toSeq
    )

  def metrics(config: Config): MetricsConfig =
    MetricsConfig(enabled = config.getBoolean("apollostorage.metrics.enabled"))

  def blobGc(config: Config): BlobGcConfig =
    BlobGcConfig(
      enabled = config.getBoolean("apollostorage.blob-gc.enabled"),
      grace = config.getDuration("apollostorage.blob-gc.grace").toMillis.millis
    )

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
