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

/** Operation scope a token grants (design D57). `Write` is a superset of `Read`. */
enum Scope:
  case Read, Write

  /** Does a token with this scope satisfy an operation that requires `required`? */
  def satisfies(required: Scope): Boolean = this match
    case Scope.Write => true // write can read and write
    case Scope.Read => required == Scope.Read

object Scope:
  def parse(s: String): Option[Scope] = s.trim.toLowerCase match
    case "read" => Some(Read)
    case "write" => Some(Write)
    case _ => None

/** A validated caller: a bearer token and the scope it grants (design D57/D59). */
final case class Principal(token: String, scope: Scope)

/** Bearer-token auth settings (design D35/D39/D57); tokens are secrets, each with a scope. */
final case class AuthConfig(enabled: Boolean, principals: Seq[Principal])

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
    // Legacy flat tokens are full-access (write) for back-compat (design D58).
    val legacy = splitCsv(config.getString("apollostorage.auth.tokens"))
      .map(Principal(_, Scope.Write))
    // Scoped principals: comma-separated `token:scope`. Fail fast on a malformed entry (D61).
    val scoped = splitCsv(config.getString("apollostorage.auth.principals")).map { entry =>
      entry.split(":", -1) match
        case Array(token, scopeStr) if token.nonEmpty =>
          Scope
            .parse(scopeStr)
            .map(Principal(token, _))
            .getOrElse(
              throw new IllegalStateException(
                s"invalid auth scope in principal '$entry' (use read|write)"
              )
            )
        case _ =>
          throw new IllegalStateException(
            s"malformed auth principal '$entry' (expected token:scope, and a token may not contain ':')"
          )
    }
    AuthConfig(
      enabled = config.getBoolean("apollostorage.auth.enabled"),
      principals = legacy ++ scoped
    )

  private def splitCsv(raw: String): Seq[String] =
    raw.split(",").map(_.trim).filter(_.nonEmpty).toSeq

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
