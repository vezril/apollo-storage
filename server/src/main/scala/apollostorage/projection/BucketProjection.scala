package apollostorage.projection

import apollostorage.domain.Event
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcProjection
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.projection.{Projection, ProjectionId}

import scala.collection.immutable

/**
 * Builds the read-model projection(s). A projection covers a slice range of the journal
 * (`eventsBySlices`, entity type `bucket`, design D26); the runtime distributes N of them across
 * the cluster via ShardedDaemonProcess (design D30), while tests use the single full-range
 * convenience.
 */
object BucketProjection:

  val EntityType = "bucket"

  /** Split the 1024 slices into `numberOfInstances` contiguous ranges. */
  def sliceRanges(numberOfInstances: Int)(using system: ActorSystem[?]): immutable.Seq[Range] =
    EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, numberOfInstances)

  /** A projection over one slice range, folding events into the read model. */
  def forRange(repo: ReadModelRepository, sliceRange: Range)(using
      system: ActorSystem[?]
  ): Projection[EventEnvelope[Event]] =
    val sourceProvider
        : SourceProvider[org.apache.pekko.persistence.query.Offset, EventEnvelope[Event]] =
      EventSourcedProvider.eventsBySlices[Event](
        system,
        R2dbcReadJournal.Identifier,
        EntityType,
        sliceRange.min,
        sliceRange.max
      )
    R2dbcProjection.atLeastOnce(
      projectionId = ProjectionId("bucket-read-model", s"${sliceRange.min}-${sliceRange.max}"),
      settings = None,
      sourceProvider = sourceProvider,
      handler = () => new BucketProjectionHandler(repo)(using system.executionContext)
    )(system)

  /** Single full-range projection (all 1024 slices) — used by tests. */
  def apply(repo: ReadModelRepository)(using
      system: ActorSystem[?]
  ): Projection[EventEnvelope[Event]] =
    forRange(repo, 0 to 1023)
