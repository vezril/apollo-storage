package apollostorage.domain

import apollostorage.domain.BucketState.*
import apollostorage.domain.Command.*
import apollostorage.domain.Event.*

/**
 * Pure state-transition logic for the bucket aggregate. Two total functions:
 *
 *   - [[decide]] : `(State, Command) => Either[DomainError, Seq[Event]]` — validates a command
 *     against current state, producing events (or an error and zero events). No side effects, no
 *     clock reads.
 *   - [[evolve]] : `(State, Event) => State` — applies a fact to fold state.
 *
 * Invariants enforced: no operations on non-existent or deleted buckets, no duplicate bucket
 * creation, per-object generations start at 1 and increase monotonically per commit, no deletion of
 * non-existent objects.
 */
object BucketDomain:

  def decide(state: BucketState, command: Command): Either[DomainError, Seq[Event]] =
    state match
      case Empty =>
        command match
          case CreateBucket(name, at) => Right(Seq(BucketCreated(name, at)))
          case _ => Left(DomainError.BucketNotFound)

      case Deleted(_) =>
        // Tombstone: no operations on a deleted bucket, including recreation.
        Left(DomainError.BucketNotFound)

      case Active(name, objects) =>
        command match
          case _: CreateBucket =>
            Left(DomainError.BucketAlreadyExists)

          case DeleteBucket(_, at) =>
            Right(Seq(BucketDeleted(name, at)))

          case CommitObject(obj, metadata, checksums, blob, at) =>
            val generation = objects.get(obj) match
              case Some(entry) => entry.generation.next
              case None => Generation.first
            Right(Seq(ObjectCommitted(obj, generation, metadata, checksums, blob, at)))

          case DeleteObject(obj, at) =>
            if objects.contains(obj) then Right(Seq(ObjectDeleted(obj, at)))
            else Left(DomainError.ObjectNotFound)

  def evolve(state: BucketState, event: Event): BucketState =
    (state, event) match
      case (Empty, BucketCreated(name, _)) =>
        Active(name, Map.empty)

      case (
            Active(name, objects),
            ObjectCommitted(obj, generation, metadata, checksums, blob, _)
          ) =>
        Active(name, objects.updated(obj, ObjectEntry(generation, metadata, checksums, blob)))

      case (Active(name, objects), ObjectDeleted(obj, _)) =>
        Active(name, objects - obj)

      case (Active(name, _), BucketDeleted(_, _)) =>
        Deleted(name)

      // No other (state, event) pair is producible by `decide`; keep total.
      case (current, _) => current

  /** Convenience for tests/replay: fold a full event list from the initial state. */
  def replay(events: Seq[Event]): BucketState =
    events.foldLeft(BucketState.initial)(evolve)
