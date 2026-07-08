package apollostorage.metrics

import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{Collector, CollectorRegistry, Counter, GaugeMetricFamily, Histogram}

import java.io.StringWriter

/**
 * Owns the application `CollectorRegistry` and the metrics ApolloStorage exposes (design D40/D43):
 * gRPC request counts/latency, blob-store operations/latency/bytes, JVM process metrics (via
 * `DefaultExports`), a build-info gauge, and a readiness gauge that reads the shared readiness flag
 * at scrape time.
 *
 * Instrumentation call sites use the semantic `observe*`/`addBytes` helpers so no Prometheus types
 * leak past this boundary. Label values are drawn only from closed sets (RPC method names, fixed
 * operation/outcome/direction values) — never bucket or object names — bounding cardinality and
 * avoiding information leakage.
 */
final class MetricsRegistry(version: String, readiness: () => Boolean):

  val registry: CollectorRegistry = new CollectorRegistry(true)

  private val grpcRequests: Counter = Counter
    .build()
    .name("apollostorage_grpc_requests_total")
    .help("gRPC requests by method and outcome status.")
    .labelNames("method", "status")
    .register(registry)

  private val grpcDuration: Histogram = Histogram
    .build()
    .name("apollostorage_grpc_request_duration_seconds")
    .help("gRPC request latency in seconds by method.")
    .labelNames("method")
    .register(registry)

  private val blobOps: Counter = Counter
    .build()
    .name("apollostorage_blob_operations_total")
    .help("Blob-store operations by operation and outcome.")
    .labelNames("operation", "outcome")
    .register(registry)

  private val blobDuration: Histogram = Histogram
    .build()
    .name("apollostorage_blob_operation_duration_seconds")
    .help("Blob-store operation latency in seconds by operation.")
    .labelNames("operation")
    .register(registry)

  private val blobBytes: Counter = Counter
    .build()
    .name("apollostorage_blob_bytes_total")
    .help("Bytes moved by the blob store by direction (read/written).")
    .labelNames("direction")
    .register(registry)

  // Build-info: a constant 1-valued gauge carrying the version as a label so Grafana can
  // correlate behaviour with a deployed version.
  locally {
    val buildInfo = io.prometheus.client.Gauge
      .build()
      .name("apollostorage_build_info")
      .help("Build information; value is always 1, version carried as a label.")
      .labelNames("version")
      .register(registry)
    buildInfo.labels(version).set(1.0)
  }

  // Readiness: a custom collector so the value always reflects the shared flag at scrape
  // time rather than the last write.
  registry.register(
    new Collector:
      def collect(): java.util.List[Collector.MetricFamilySamples] =
        java.util.Collections.singletonList(
          new GaugeMetricFamily(
            "apollostorage_ready",
            "1 when the service is ready to serve, else 0.",
            if readiness() then 1.0 else 0.0
          )
        )
  )

  DefaultExports.register(registry)

  /** Record one completed gRPC request. */
  def observeGrpc(method: String, status: String, seconds: Double): Unit =
    grpcRequests.labels(method, status).inc()
    grpcDuration.labels(method).observe(seconds)

  /** Record one completed blob-store operation. */
  def observeBlob(operation: String, outcome: String, seconds: Double): Unit =
    blobOps.labels(operation, outcome).inc()
    blobDuration.labels(operation).observe(seconds)

  /** Add moved bytes for a direction (`read` or `written`). */
  def addBytes(direction: String, n: Long): Unit =
    if n > 0 then blobBytes.labels(direction).inc(n.toDouble)

  /** Render the registry in Prometheus text exposition format (version 0.0.4). */
  def scrape(): String =
    val writer = new StringWriter()
    TextFormat.write004(writer, registry.metricFamilySamples())
    writer.toString

/** The content type of the text exposition format returned by [[MetricsRegistry.scrape]]. */
object MetricsRegistry:
  val ContentType: String = TextFormat.CONTENT_TYPE_004
