package apollostorage.domain

/**
 * The folded state of one bucket aggregate. A journal for a given bucket moves `Empty -> Active ->
 * Deleted`; `Deleted` is terminal (a tombstone that keeps only the name, so counters from a
 * previous life cannot be resurrected).
 */
enum BucketState:
  case Empty
  case Active(name: BucketName, objects: Map[ObjectName, ObjectEntry])
  case Deleted(name: BucketName)

object BucketState:
  val initial: BucketState = Empty

/** State of a single live object version within a bucket. */
final case class ObjectEntry(
    generation: Generation,
    metadata: ObjectMetadata,
    checksums: Checksums,
    blob: BlobRef
)
