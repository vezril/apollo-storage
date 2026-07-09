package apollostorage.blob

import apollostorage.domain.*
import apollostorage.domain.Command.{CommitObject, DeleteObject}
import apollostorage.persistence.BucketEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef, Scheduler}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Result of a successful object commit. */
final case class CommitResult(ref: BlobRef, checksums: Checksums, size: Long)

/**
 * Orchestrates the object lifecycle across the blob store and the bucket aggregate (design D12): a
 * payload is made durable **before** its `ObjectCommitted` event; a deletion removes the payload
 * only **after** `ObjectDeleted` is persisted (best-effort — a failed blob delete leaves an orphan,
 * never a committed object missing its bytes).
 */
final class ObjectService(
    blobStore: BlobStore,
    entityFor: BucketName => RecipientRef[BucketEntity.Command]
)(using system: ActorSystem[?], timeout: Timeout):

  private given ExecutionContext = system.executionContext
  private given Scheduler = system.scheduler
  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Stream a payload to the blob store (verifying `expected` checksums if given), then commit the
   * metadata event. If the payload write fails, no command is sent and no event is persisted.
   */
  def commit(
      bucket: BucketName,
      name: ObjectName,
      metadata: ObjectMetadata,
      data: Source[ByteString, Any],
      expected: Option[Checksums]
  ): Future[CommitResult] =
    val entity = entityFor(bucket)
    // Capture the entry this commit will supersede, so its blob can be reclaimed (D55).
    entity.ask[Option[ObjectEntry]](replyTo => BucketEntity.GetObject(name, replyTo)).flatMap {
      prior =>
        blobStore.put(bucket, data, expected).flatMap { put =>
          // The blob store knows the true byte count; record it authoritatively.
          val command =
            CommitObject(
              name,
              metadata.copy(sizeBytes = put.size),
              put.checksums,
              put.ref,
              Instant.now()
            )
          entity
            .askWithStatus[Done](replyTo => BucketEntity.Execute(command, replyTo))
            .flatMap(_ =>
              reclaimSuperseded(prior, put.ref)
                .map(_ => CommitResult(put.ref, put.checksums, put.size))
            )
        }
    }

  /**
   * After an overwrite commits, delete the superseded generation's blob best-effort (design D55). A
   * failed reclaim is logged and leaves an orphan (caught later by blob-gc), never failing the
   * commit or touching the newly committed payload.
   */
  private def reclaimSuperseded(prior: Option[ObjectEntry], newRef: BlobRef): Future[Unit] =
    prior.map(_.blob).filter(_ != newRef) match
      case Some(oldRef) =>
        blobStore.delete(oldRef).map(_ => ()).recover { case NonFatal(e) =>
          log.warn(
            "orphaned superseded blob {} — reclaim after overwrite failed: {}",
            oldRef,
            e.getMessage
          )
        }
      case None => Future.unit

  /** Persist the deletion, then remove the payload best-effort. */
  def delete(bucket: BucketName, name: ObjectName): Future[Unit] =
    val entity = entityFor(bucket)
    entity.ask[Option[ObjectEntry]](replyTo => BucketEntity.GetObject(name, replyTo)).flatMap {
      maybeEntry =>
        entity
          .askWithStatus[Done](replyTo =>
            BucketEntity.Execute(DeleteObject(name, Instant.now()), replyTo)
          )
          .flatMap { _ =>
            maybeEntry match
              case Some(entry) =>
                blobStore.delete(entry.blob).map(_ => ()).recover { case NonFatal(e) =>
                  log.warn(
                    "orphaned blob {} — delete after ObjectDeleted failed: {}",
                    entry.blob,
                    e.getMessage
                  )
                }
              case None => Future.unit // Execute would have already failed if absent
          }
    }
