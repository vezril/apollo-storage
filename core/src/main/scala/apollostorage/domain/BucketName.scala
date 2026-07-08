package apollostorage.domain

/**
 * A validated bucket name. Constructible only via [[BucketName.from]] which enforces GCS-inspired
 * rules (see domain-model spec):
 *   - 3–63 characters
 *   - lowercase letters, digits, hyphens only
 *   - must start and end with a letter or digit
 */
final case class BucketName private (value: String):
  override def toString: String = value

object BucketName:
  val MinLength = 3
  val MaxLength = 63

  private val Pattern = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$".r

  def from(input: String): Either[DomainError, BucketName] =
    val len = input.length
    if len < MinLength || len > MaxLength then
      Left(
        DomainError.InvalidBucketName(
          s"length must be $MinLength–$MaxLength characters, was $len"
        )
      )
    else if input != input.toLowerCase then Left(DomainError.InvalidBucketName("must be lowercase"))
    else if Pattern.matches(input) then Right(BucketName(input))
    else
      Left(
        DomainError.InvalidBucketName(
          "only lowercase letters, digits, and hyphens; must start and end with a letter or digit"
        )
      )

  /**
   * Escape hatch for trusted sources (e.g. recovery from a persisted event whose value was
   * validated when first created). Not for untrusted input.
   */
  def unsafe(value: String): BucketName = BucketName(value)
