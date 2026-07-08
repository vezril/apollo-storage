package apollostorage.domain

/**
 * Per-object version counter. Starts at 1 for a new object and increases monotonically on every
 * commit. Constructed only via [[Generation.first]] and [[Generation.next]] so the monotonic
 * invariant cannot be violated by callers.
 */
final case class Generation private (value: Long) extends Ordered[Generation]:
  def next: Generation = Generation(value + 1)
  def compare(that: Generation): Int = java.lang.Long.compare(value, that.value)
  override def toString: String = value.toString

object Generation:
  val first: Generation = Generation(1)

  /** Rehydrate a generation from a persisted event (trusted source). */
  def unsafe(value: Long): Generation = Generation(value)

/** Content integrity checksums carried by an object commit. */
final case class Checksums(crc32c: String, md5: String)

/**
 * Reference to the object's bytes in the (future) blob store. Payloads never enter the journal
 * (design D3); the event only records where they live.
 */
final case class BlobRef(value: String)

/**
 * Immutable descriptor of a committed object version. Excludes checksums and blob reference, which
 * the commit carries separately, so this stays focused on user-facing metadata. Case class ⇒
 * structural equality + immutability.
 */
final case class ObjectMetadata(
    contentType: String,
    sizeBytes: Long,
    custom: Map[String, String] = Map.empty
)
