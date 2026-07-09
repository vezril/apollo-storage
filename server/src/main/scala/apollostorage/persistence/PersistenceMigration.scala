package apollostorage.persistence

import apollostorage.config.PostgresConfig
import io.r2dbc.spi.{Connection, ConnectionFactories, ConnectionFactoryOptions, Result}
import org.slf4j.LoggerFactory
import reactor.core.publisher.{Flux, Mono}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Applies Apollo's bundled schema at startup (design D62-D66). The DDL
 * (`ddl/create_tables_postgres.sql`) is idempotent (`CREATE ... IF NOT EXISTS`), so running it is a
 * no-op after first boot. Statements are split on `;` and executed sequentially over a short-lived
 * r2dbc connection (the same driver path as [[PersistenceReadiness]]); a failure propagates so the
 * caller can fail fast.
 */
object PersistenceMigration:
  private val log = LoggerFactory.getLogger(getClass)
  private val DdlResource = "ddl/create_tables_postgres.sql"

  def run(cfg: PostgresConfig)(using ec: ExecutionContext): Future[Unit] =
    val statements = loadStatements()
    val factory = ConnectionFactories.get(
      ConnectionFactoryOptions
        .builder()
        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
        .option(ConnectionFactoryOptions.HOST, cfg.host)
        .option(ConnectionFactoryOptions.PORT, Integer.valueOf(cfg.port))
        .option(ConnectionFactoryOptions.DATABASE, cfg.database)
        .option(ConnectionFactoryOptions.USER, cfg.user)
        .option(ConnectionFactoryOptions.PASSWORD, cfg.password)
        .option(
          ConnectionFactoryOptions.CONNECT_TIMEOUT,
          java.time.Duration.ofMillis(cfg.connectTimeout.toMillis)
        )
        .build()
    )

    val migrate = Mono.usingWhen[Void, Connection](
      Mono.from[Connection](factory.create()),
      (conn: Connection) =>
        Flux
          .fromIterable(statements.asJava)
          .concatMap(stmt =>
            // Consume getRowsUpdated so the statement fully executes (draining the Result;
            // Mono.from would cancel after the first element without running it to completion).
            Flux
              .from[Result](conn.createStatement(stmt).execute())
              .concatMap((r: Result) => r.getRowsUpdated())
          )
          .`then`(),
      (conn: Connection) => conn.close()
    )
    toFuture(migrate).map { _ =>
      log.info("Applied database schema ({} statements) — self-migration complete", statements.size)
    }

  /**
   * DDL statements from the classpath (design D63). Comments are stripped **before** splitting on
   * `;` so a `;` inside a comment can't break statement boundaries; only a `;` inside a string
   * literal would (none in this schema, and none is expected in idempotent DDL).
   */
  private def loadStatements(): Seq[String] =
    val raw = Using.resource(scala.io.Source.fromResource(DdlResource))(_.mkString)
    val withoutComments = raw.linesIterator.map(_.replaceFirst("--.*$", "")).mkString("\n")
    withoutComments.split(";").map(_.trim).filter(_.nonEmpty).toSeq

  private def toFuture[A](mono: Mono[A]): Future[A] =
    val promise = Promise[A]()
    mono.subscribe(
      (value: A) => { promise.trySuccess(value); () },
      (err: Throwable) => { promise.tryFailure(err); () },
      () => { promise.trySuccess(null.asInstanceOf[A]); () }
    )
    promise.future
