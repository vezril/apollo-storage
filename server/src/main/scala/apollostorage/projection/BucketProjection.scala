package apollostorage.projection

import apollostorage.domain.Event
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcProjection
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.projection.{Projection, ProjectionId}

/**
 * Builds the single at-least-once projection over all slices (design D21/D25): `eventsBySlices`
 * from the r2dbc read journal, entity type `bucket` (D26), folding into the read model via
 * [[BucketProjectionHandler]].
 */
object BucketProjection:

  val EntityType = "bucket"

  def apply(repo: ReadModelRepository)(using
      system: ActorSystem[?]
  ): Projection[EventEnvelope[Event]] =
    val sourceProvider
        : SourceProvider[org.apache.pekko.persistence.query.Offset, EventEnvelope[Event]] =
      EventSourcedProvider.eventsBySlices[Event](
        system,
        R2dbcReadJournal.Identifier,
        EntityType,
        minSlice = 0,
        maxSlice = 1023 // default number of slices is 1024
      )

    R2dbcProjection.atLeastOnce(
      projectionId = ProjectionId("bucket-read-model", "0-1023"),
      settings = None,
      sourceProvider = sourceProvider,
      handler = () => new BucketProjectionHandler(repo)(using system.executionContext)
    )(system)
