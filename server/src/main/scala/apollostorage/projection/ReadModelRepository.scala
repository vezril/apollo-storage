package apollostorage.projection

import apollostorage.config.PostgresConfig
import io.r2dbc.spi.{
  Connection,
  ConnectionFactories,
  ConnectionFactory,
  ConnectionFactoryOptions,
  Row,
  Statement
}
import reactor.core.publisher.{Flux, Mono}

import java.time.{Instant, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/** One entry in the object read model. */
final case class ObjectRow(
    key: String,
    generation: Long,
    size: Long,
    contentType: String,
    crc32c: String,
    md5: String
)

/** A page of results plus the keyset token to continue from (empty when exhausted). */
final case class Page[A](items: Seq[A], nextPageToken: String)

/**
 * Read model over PostgreSQL (design D22/D23): idempotent upserts/deletes applied by the projection
 * handler, and keyset-paginated prefix queries for the listing API. Uses its own r2dbc connections
 * (short-lived) so it does not depend on the persistence plugin's pool.
 */
final class ReadModelRepository(cfg: PostgresConfig)(using ec: ExecutionContext):

  private val factory: ConnectionFactory =
    ConnectionFactories.get(
      ConnectionFactoryOptions
        .builder()
        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
        .option(ConnectionFactoryOptions.HOST, cfg.host)
        .option(ConnectionFactoryOptions.PORT, Integer.valueOf(cfg.port))
        .option(ConnectionFactoryOptions.DATABASE, cfg.database)
        .option(ConnectionFactoryOptions.USER, cfg.user)
        .option(ConnectionFactoryOptions.PASSWORD, cfg.password)
        .build()
    )

  // --- writes (idempotent; called by the projection handler) -----------------

  def upsertBucket(bucket: String, createdAt: Instant): Future[Unit] =
    update(
      "INSERT INTO bucket_index (bucket, created_at) VALUES ($1, $2) ON CONFLICT (bucket) DO NOTHING",
      _.bind(0, bucket).bind(1, createdAt.atOffset(ZoneOffset.UTC))
    ).map(_ => ())

  def deleteBucket(bucket: String): Future[Unit] =
    update("DELETE FROM object_index WHERE bucket = $1", _.bind(0, bucket))
      .flatMap(_ => update("DELETE FROM bucket_index WHERE bucket = $1", _.bind(0, bucket)))
      .map(_ => ())

  def upsertObject(
      bucket: String,
      key: String,
      generation: Long,
      size: Long,
      contentType: String,
      crc32c: String,
      md5: String,
      updatedAt: Instant
  ): Future[Unit] =
    update(
      """INSERT INTO object_index
        |  (bucket, object_key, generation, size_bytes, content_type, crc32c, md5, updated_at)
        |VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
        |ON CONFLICT (bucket, object_key) DO UPDATE SET
        |  generation = EXCLUDED.generation, size_bytes = EXCLUDED.size_bytes,
        |  content_type = EXCLUDED.content_type, crc32c = EXCLUDED.crc32c,
        |  md5 = EXCLUDED.md5, updated_at = EXCLUDED.updated_at""".stripMargin,
      _.bind(0, bucket)
        .bind(1, key)
        .bind(2, java.lang.Long.valueOf(generation))
        .bind(3, java.lang.Long.valueOf(size))
        .bind(4, contentType)
        .bind(5, crc32c)
        .bind(6, md5)
        .bind(7, updatedAt.atOffset(ZoneOffset.UTC))
    ).map(_ => ())

  def deleteObject(bucket: String, key: String): Future[Unit] =
    update(
      "DELETE FROM object_index WHERE bucket = $1 AND object_key = $2",
      _.bind(0, bucket).bind(1, key)
    ).map(_ => ())

  // --- reads (listing API) ---------------------------------------------------

  def bucketExists(bucket: String): Future[Boolean] =
    query("SELECT bucket FROM bucket_index WHERE bucket = $1", _.bind(0, bucket))(_ => ())
      .map(_.nonEmpty)

  def listBuckets(pageSize: Int, pageToken: String): Future[Page[String]] =
    query(
      "SELECT bucket FROM bucket_index WHERE bucket > $1 ORDER BY bucket LIMIT $2",
      _.bind(0, pageToken).bind(1, Integer.valueOf(pageSize))
    )(row => row.get("bucket", classOf[String]))
      .map(rows => Page(rows, if rows.size == pageSize then rows.last else ""))

  def listObjects(
      bucket: String,
      prefix: String,
      pageSize: Int,
      pageToken: String
  ): Future[Page[ObjectRow]] =
    query(
      """SELECT object_key, generation, size_bytes, content_type, crc32c, md5
        |FROM object_index
        |WHERE bucket = $1 AND object_key LIKE $2 ESCAPE '\' AND object_key > $3
        |ORDER BY object_key LIMIT $4""".stripMargin,
      _.bind(0, bucket)
        .bind(1, escapeLike(prefix) + "%")
        .bind(2, pageToken)
        .bind(3, Integer.valueOf(pageSize))
    ) { row =>
      ObjectRow(
        key = row.get("object_key", classOf[String]),
        generation = row.get("generation", classOf[java.lang.Long]).longValue,
        size = row.get("size_bytes", classOf[java.lang.Long]).longValue,
        contentType = row.get("content_type", classOf[String]),
        crc32c = row.get("crc32c", classOf[String]),
        md5 = row.get("md5", classOf[String])
      )
    }.map(rows => Page(rows, if rows.size == pageSize then rows.last.key else ""))

  private def escapeLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  // --- r2dbc plumbing --------------------------------------------------------

  private def update(sql: String, bind: Statement => Statement): Future[Long] =
    withConnection { conn =>
      Mono
        .from(bind(conn.createStatement(sql)).execute())
        .flatMap(result => Mono.from(result.getRowsUpdated))
        .map(_.longValue)
    }

  private def query[A](sql: String, bind: Statement => Statement)(map: Row => A): Future[Seq[A]] =
    withConnection { conn =>
      Flux
        .from(bind(conn.createStatement(sql)).execute())
        .flatMap(result => result.map((row, _) => map(row)))
        .collectList()
        .map(_.asScala.toVector)
    }

  private def withConnection[A](use: Connection => Mono[A]): Future[A] =
    toFuture(
      Mono.usingWhen[A, Connection](
        Mono.from[Connection](factory.create()),
        (conn: Connection) => use(conn),
        (conn: Connection) => conn.close()
      )
    )

  private def toFuture[A](mono: Mono[A]): Future[A] =
    val promise = Promise[A]()
    mono.subscribe(
      (value: A) => { promise.trySuccess(value); () },
      (err: Throwable) => { promise.tryFailure(err); () },
      () => { promise.trySuccess(null.asInstanceOf[A]); () }
    )
    promise.future
