package apollostorage.blob

/**
 * A metrics sink for the blob store (design D42), kept as a small interface so the store does not
 * depend on Prometheus directly. `MetricsRegistry` adapts to this; [[BlobMetrics.noop]] is used
 * when metrics are disabled or in tests that don't care.
 */
trait BlobMetrics:
  /** Record one completed operation (`put`/`get`/`delete`) with outcome and latency. */
  def observe(operation: String, outcome: String, seconds: Double): Unit

  /** Record moved bytes for a direction (`read` or `written`). */
  def addBytes(direction: String, n: Long): Unit

object BlobMetrics:
  val noop: BlobMetrics = new BlobMetrics:
    def observe(operation: String, outcome: String, seconds: Double): Unit = ()
    def addBytes(direction: String, n: Long): Unit = ()
