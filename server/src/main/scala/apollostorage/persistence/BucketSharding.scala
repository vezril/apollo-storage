package apollostorage.persistence

import apollostorage.domain.BucketName
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityRef,
  EntityTypeKey
}

/**
 * Cluster Sharding for bucket entities (design D29): each bucket is a cluster-wide singleton,
 * addressed by `EntityRef`. The entity type key name is `bucket`, so the sharded persistence id is
 * `bucket|<name>` — the frozen contract (design D2) is preserved without any change to
 * `BucketEntity`.
 */
object BucketSharding:

  val TypeKey: EntityTypeKey[BucketEntity.Command] =
    EntityTypeKey[BucketEntity.Command](BucketEntity.EntityPrefix)

  /** Initialize sharding on the cluster and return the handle. */
  def init(system: ActorSystem[?]): ClusterSharding =
    val sharding = ClusterSharding(system)
    val _ = sharding.init(Entity(TypeKey)(ctx => BucketEntity(BucketName.unsafe(ctx.entityId))))
    sharding

  def entityRef(sharding: ClusterSharding, bucket: BucketName): EntityRef[BucketEntity.Command] =
    sharding.entityRefFor(TypeKey, bucket.value)
