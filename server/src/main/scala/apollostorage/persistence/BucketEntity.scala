package apollostorage.persistence

import apollostorage.domain.{BucketDomain, BucketName, BucketState, Event, Command as DomainCommand}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

/**
 * `BucketEntity` wraps the pure domain transitions in an `EventSourcedBehavior` (event-persistence
 * spec). It persists domain events and replies with a `StatusReply` that mirrors the domain's
 * `Either`: success ⇒ ack, rejection ⇒ error carrying the `DomainError` message.
 *
 * The persistence ID is the frozen contract `bucket|<name>` (design D2) — changing it breaks
 * recovery of all existing journals.
 */
object BucketEntity:

  val EntityPrefix = "bucket"

  /**
   * Envelope carrying a validated domain command plus where to reply. Commands are transient (never
   * persisted), so they are not CBOR-serialized.
   */
  final case class Command(domain: DomainCommand, replyTo: ActorRef[StatusReply[Done]])

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
    BucketDomain.decide(state, command.domain) match
      case Right(events) =>
        Effect.persist(events).thenReply(command.replyTo)(_ => StatusReply.ack())
      case Left(error) =>
        Effect.reply(command.replyTo)(StatusReply.error(error.message))
