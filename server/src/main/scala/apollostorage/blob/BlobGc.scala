package apollostorage.blob

import apollostorage.domain.{BlobReconciliation, BlobRef, BucketName}
import apollostorage.persistence.BucketEntity
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Outcome of a blob-gc sweep (design D54). Counts are aggregate across all buckets. */
final case class GcReport(
    bucketsScanned: Int,
    bucketsSkipped: Int,
    blobsScanned: Int,
    liveBlobs: Int,
    orphansFound: Int,
    bytesOrphaned: Long,
    reclaimed: Int,
    bytesReclaimed: Long,
    tmpFound: Int,
    tmpReclaimed: Int,
    dryRun: Boolean
):
  def merge(o: GcReport): GcReport = GcReport(
    bucketsScanned + o.bucketsScanned,
    bucketsSkipped + o.bucketsSkipped,
    blobsScanned + o.blobsScanned,
    liveBlobs + o.liveBlobs,
    orphansFound + o.orphansFound,
    bytesOrphaned + o.bytesOrphaned,
    reclaimed + o.reclaimed,
    bytesReclaimed + o.bytesReclaimed,
    tmpFound + o.tmpFound,
    tmpReclaimed + o.tmpReclaimed,
    dryRun && o.dryRun
  )

object GcReport:
  def empty(dryRun: Boolean): GcReport = GcReport(0, 0, 0, 0, 0, 0L, 0, 0L, 0, 0, dryRun)

/**
 * Reconciles the blob store against live object state and reclaims orphaned payloads (design
 * D50–D54). For each bucket that holds payloads on disk it asks the entity for its live `BlobRef`s
 * (strongly consistent), applies the pure grace-period policy, and — only when `delete` is set —
 * removes orphans and stale `.tmp` debris best-effort. Dry-run (the default) computes and reports
 * without deleting. A bucket whose live set can't be fetched is skipped, never swept blind.
 */
final class BlobGc(
    blobStore: BlobStore,
    entityFor: BucketName => RecipientRef[BucketEntity.Command],
    grace: Duration
)(using system: ActorSystem[?], timeout: Timeout):

  private given ExecutionContext = system.executionContext
  private given Scheduler = system.scheduler
  private val log = LoggerFactory.getLogger(getClass)

  /** Run a sweep. `delete = false` (default) is a dry run: it reports but reclaims nothing. */
  def sweep(delete: Boolean = false, now: Instant = Instant.now()): Future[GcReport] =
    blobStore.listBucketsOnDisk().flatMap { buckets =>
      Future
        .traverse(buckets)(b => sweepBucket(b, delete, now))
        .map(_.foldLeft(GcReport.empty(dryRun = !delete))(_ merge _))
    }

  private def sweepBucket(bucket: BucketName, delete: Boolean, now: Instant): Future[GcReport] =
    val liveF =
      entityFor(bucket).ask[Set[BlobRef]](replyTo => BucketEntity.GetLiveBlobRefs(replyTo))
    (for
      live <- liveF
      stored <- blobStore.listStoredBlobs(bucket)
      temps <- blobStore.listTempArtifacts(bucket)
    yield
      val orphanRefs =
        BlobReconciliation.orphans(stored.map(s => s.ref -> s.modifiedAt), live, now, grace).toSet
      val orphanBlobs = stored.filter(s => orphanRefs.contains(s.ref))
      val reclaimableTmp =
        temps.filter(t => BlobReconciliation.isReclaimable(t.modifiedAt, now, grace))
      (live, stored, orphanBlobs, reclaimableTmp)
    ).flatMap { case (live, stored, orphanBlobs, reclaimableTmp) =>
      val reclaimBlobsF =
        if !delete then Future.successful(Vector.empty[StoredBlob])
        else
          Future
            .traverse(orphanBlobs)(b =>
              blobStore.delete(b.ref).map(ok => Option.when(ok)(b)).recover { case NonFatal(e) =>
                log.warn("blob-gc: failed to reclaim orphan {}: {}", b.ref, e.getMessage); None
              }
            )
            .map(_.flatten)
      val reclaimTmpF =
        if !delete then Future.successful(0)
        else
          Future
            .traverse(reclaimableTmp)(t =>
              blobStore.deleteTempArtifact(bucket, t.id).recover { case NonFatal(e) =>
                log.warn("blob-gc: failed to reclaim tmp {}: {}", t.id, e.getMessage); false
              }
            )
            .map(_.count(identity))
      for
        reclaimedBlobs <- reclaimBlobsF
        reclaimedTmp <- reclaimTmpF
      yield GcReport(
        bucketsScanned = 1,
        bucketsSkipped = 0,
        blobsScanned = stored.size,
        liveBlobs = live.size,
        orphansFound = orphanBlobs.size,
        bytesOrphaned = orphanBlobs.map(_.sizeBytes).sum,
        reclaimed = reclaimedBlobs.size,
        bytesReclaimed = reclaimedBlobs.map(_.sizeBytes).sum,
        tmpFound = reclaimableTmp.size,
        tmpReclaimed = reclaimedTmp,
        dryRun = !delete
      )
    }.recover { case NonFatal(e) =>
      log.warn(
        "blob-gc: skipping bucket {} — could not assemble live set: {}",
        bucket.value,
        e.getMessage
      )
      GcReport.empty(dryRun = !delete).copy(bucketsSkipped = 1)
    }
