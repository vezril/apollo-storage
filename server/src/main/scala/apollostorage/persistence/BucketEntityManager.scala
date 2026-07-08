package apollostorage.persistence

import apollostorage.domain.BucketName
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.collection.mutable

/**
 * Single-node registry that spawns and caches one `BucketEntity` per bucket on demand. Spawning
 * happens on this actor's own thread, so lookups are safe to request concurrently (via `ask`) from
 * the object service's futures.
 *
 * This is the v1 stand-in for cluster sharding (design D2); when a bucket is decomposed or the
 * cluster grows, this manager is replaced by sharding without changing the entity or its journal.
 */
object BucketEntityManager:

  sealed trait Command
  final case class GetEntity(
      bucket: BucketName,
      replyTo: ActorRef[ActorRef[BucketEntity.Command]]
  ) extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      val entities = mutable.Map.empty[String, ActorRef[BucketEntity.Command]]
      Behaviors.receiveMessage { case GetEntity(bucket, replyTo) =>
        val entity = entities.getOrElseUpdate(
          bucket.value,
          context.spawn(BucketEntity(bucket), s"bucket-${bucket.value}")
        )
        replyTo ! entity
        Behaviors.same
      }
    }
