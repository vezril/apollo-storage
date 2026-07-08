package apollostorage.projection

import apollostorage.domain.Event
import apollostorage.domain.Event.*
import org.apache.pekko.Done
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.projection.r2dbc.scaladsl.{R2dbcHandler, R2dbcSession}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Folds bucket journal events into the read model (design D21/D22). Idempotent: upserts and deletes
 * can be re-applied without visible effect, so at-least-once delivery is safe. The bucket name
 * comes from the persistence id (`bucket|<name>`, design D2/D26); object events carry only the
 * object key.
 *
 * It delegates to [[ReadModelRepository]] (its own connection) rather than writing through the
 * projection's `session`; the idempotent handler makes this observably equivalent to exactly-once
 * (design D21).
 */
final class BucketProjectionHandler(repo: ReadModelRepository)(using ec: ExecutionContext)
    extends R2dbcHandler[EventEnvelope[Event]]:

  def process(session: R2dbcSession, envelope: EventEnvelope[Event]): Future[Done] =
    val bucket = envelope.persistenceId.substring(envelope.persistenceId.indexOf('|') + 1)
    val applied = envelope.event match
      case e: BucketCreated => repo.upsertBucket(bucket, e.at)
      case _: BucketDeleted => repo.deleteBucket(bucket)
      case e: ObjectCommitted =>
        repo.upsertObject(
          bucket,
          e.name.value,
          e.generation.value,
          e.metadata.sizeBytes,
          e.metadata.contentType,
          e.checksums.crc32c,
          e.checksums.md5,
          e.at
        )
      case e: ObjectDeleted => repo.deleteObject(bucket, e.name.value)
    applied.map(_ => Done)
