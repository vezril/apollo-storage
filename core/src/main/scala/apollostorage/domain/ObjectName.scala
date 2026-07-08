package apollostorage.domain

import java.nio.charset.StandardCharsets

/**
 * A validated, traversal-safe object key. Constructible only via [[ObjectName.from]] (see
 * domain-model spec):
 *   - 1–1024 bytes when UTF-8 encoded (measured in bytes, not chars)
 *   - no NUL character
 *   - cannot escape the bucket directory on a filesystem backend: no `.` or `..` path segments, no
 *     leading `/`, no backslashes
 *
 * Nested keys separated by `/` are allowed (e.g. `photos/2026/07/x.jpg`).
 */
final case class ObjectName private (value: String):
  override def toString: String = value

object ObjectName:
  val MaxBytes = 1024

  def from(input: String): Either[DomainError, ObjectName] =
    val bytes = input.getBytes(StandardCharsets.UTF_8).length
    if input.isEmpty then Left(invalid("must not be empty"))
    else if bytes > MaxBytes then Left(invalid(s"must be 1-$MaxBytes UTF-8 bytes, was $bytes"))
    else if input.exists(_ == '\u0000') then Left(invalid("must not contain NUL"))
    else if input.contains('\\') then Left(invalid("must not contain backslashes"))
    else if input.startsWith("/") then Left(invalid("must not start with '/'"))
    else if hasUnsafeSegment(input) then Left(invalid("must not contain '.' or '..' path segments"))
    else Right(ObjectName(input))

  private def hasUnsafeSegment(input: String): Boolean =
    input.split('/').exists(seg => seg == "." || seg == "..")

  private def invalid(reason: String): DomainError =
    DomainError.InvalidObjectName(reason)

  def unsafe(value: String): ObjectName = ObjectName(value)
