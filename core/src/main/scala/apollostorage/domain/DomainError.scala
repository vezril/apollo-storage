package apollostorage.domain

/**
 * Typed domain failures. Smart constructors and state transitions return these via `Either`; the
 * domain never throws for expected invalid input.
 */
sealed trait DomainError:
  /** Human-readable reason naming the violated rule. */
  def message: String

object DomainError:
  final case class InvalidBucketName(message: String) extends DomainError
  final case class InvalidObjectName(message: String) extends DomainError

  case object BucketAlreadyExists extends DomainError:
    val message = "bucket already exists"

  case object BucketNotFound extends DomainError:
    val message = "bucket does not exist (never created or deleted)"

  case object ObjectNotFound extends DomainError:
    val message = "object does not exist in this bucket"
