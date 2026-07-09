package apollostorage.persistence

import apollostorage.domain.{
  BlobRef,
  BucketDomain,
  BucketName,
  BucketState,
  DomainException,
  Event,
  ObjectEntry,
  ObjectName,
  Command as DomainCommand
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

/**
 * `BucketEntity` wraps the pure domain transitions in an `EventSourcedBehavior` (event-persistence
 * spec). `Execute` runs a validated domain command and persists any resulting events, replying with
 * a `StatusReply` that mirrors the domain's `Either`. `GetObject` is a read-only query (persists
 * nothing) used by the object service to learn a stored payload's `BlobRef`.
 *
 * The persistence ID is the frozen contract `bucket|<name>` (design D2).
 */
object BucketEntity:

  val EntityPrefix = "bucket"

  sealed trait Command

  /** Run a validated domain command; persist events and reply success/rejection. */
  final case class Execute(command: DomainCommand, replyTo: ActorRef[StatusReply[Done]])
      extends Command

  /** Read-only lookup of a live object's current entry (no event persisted). */
  final case class GetObject(name: ObjectName, replyTo: ActorRef[Option[ObjectEntry]])
      extends Command

  /**
   * Read-only query of the `BlobRef`s of all currently-live objects (one per name, the current
   * generation), used by blob-gc reconciliation to assemble the authoritative live set. Persists
   * nothing.
   */
  final case class GetLiveBlobRefs(replyTo: ActorRef[Set[BlobRef]]) extends Command

  def persistenceId(bucket: String): PersistenceId =
    PersistenceId.ofUniqueId(s"$EntityPrefix|$bucket")

  def apply(bucket: BucketName): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BucketState](
      persistenceId = persistenceId(bucket.value),
      emptyState = BucketState.initial,
      commandHandler = handleCommand,
      eventHandler = BucketDomain.evolve
    )

  private def handleCommand(state: BucketState, command: Command): Effect[Event, BucketState] =
    command match
      case Execute(domain, replyTo) =>
        BucketDomain.decide(state, domain) match
          case Right(events) =>
            Effect.persist(events).thenReply(replyTo)(_ => StatusReply.ack())
          case Left(error) =>
            Effect.reply(replyTo)(StatusReply.error(DomainException(error)))

      case GetObject(name, replyTo) =>
        val entry = state match
          case BucketState.Active(_, objects) => objects.get(name)
          case _ => None
        Effect.reply(replyTo)(entry)

      case GetLiveBlobRefs(replyTo) =>
        val refs = state match
          case BucketState.Active(_, objects) => objects.values.map(_.blob).toSet
          case _ => Set.empty[BlobRef]
        Effect.reply(replyTo)(refs)
