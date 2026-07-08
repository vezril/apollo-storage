package apollostorage.metrics

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit coverage for the metrics registry: exposition rendering, JVM/build-info/readiness
 * collectors, and the gRPC/blob observation helpers (design D40/D43).
 */
final class MetricsRegistrySpec extends AnyWordSpec with Matchers:

  "MetricsRegistry" should {

    "render exposition text with build-info, readiness, and JVM metrics" in {
      val m = new MetricsRegistry("1.2.3", () => true)
      val out = m.scrape()
      out should include("apollostorage_build_info")
      out should include("""version="1.2.3"""")
      out should include("apollostorage_ready 1.0")
      out should include("jvm_") // DefaultExports registered
    }

    "reflect readiness changes in the ready gauge at scrape time" in {
      val ready = new AtomicBoolean(false)
      val m = new MetricsRegistry("v", () => ready.get())
      m.scrape() should include("apollostorage_ready 0.0")
      ready.set(true)
      m.scrape() should include("apollostorage_ready 1.0")
    }

    "record gRPC request counts and latency" in {
      val m = new MetricsRegistry("v", () => true)
      m.observeGrpc("CreateBucket", "OK", 0.01)
      m.observeGrpc("CreateBucket", "OK", 0.02)
      m.observeGrpc("GetObject", "NOT_FOUND", 0.005)
      val out = m.scrape()
      out should include(
        """apollostorage_grpc_requests_total{method="CreateBucket",status="OK",} 2.0"""
      )
      out should include(
        """apollostorage_grpc_requests_total{method="GetObject",status="NOT_FOUND",} 1.0"""
      )
      out should include("apollostorage_grpc_request_duration_seconds")
    }

    "record blob operations, outcomes, and bytes" in {
      val m = new MetricsRegistry("v", () => true)
      m.observeBlob("put", "success", 0.02)
      m.observeBlob("get", "failure", 0.01)
      m.addBytes("written", 1024)
      val out = m.scrape()
      out should include(
        """apollostorage_blob_operations_total{operation="put",outcome="success",} 1.0"""
      )
      out should include(
        """apollostorage_blob_operations_total{operation="get",outcome="failure",} 1.0"""
      )
      out should include("apollostorage_blob_operation_duration_seconds")
      out should include("""apollostorage_blob_bytes_total{direction="written",} 1024.0""")
    }
  }
