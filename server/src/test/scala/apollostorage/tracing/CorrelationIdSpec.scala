package apollostorage.tracing

import org.apache.pekko.grpc.scaladsl.MetadataBuilder
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit coverage for correlation-id minting and the round-trip through gRPC metadata
 * (request-tracing capability). IDs must be unique, non-empty, and log-friendly; the id stamped
 * onto request metadata must read back under the canonical key.
 */
final class CorrelationIdSpec extends AnyWordSpec with Matchers:

  "CorrelationId.mint" should {

    "produce a non-empty, log-friendly token" in {
      val id = CorrelationId.mint()
      id should not be empty
      id should fullyMatch regex "[0-9a-z]+"
    }

    "produce a distinct value each call" in {
      val ids = Vector.fill(1000)(CorrelationId.mint())
      ids.distinct.size shouldBe ids.size
    }
  }

  "CorrelationId names" should {
    "use the canonical MDC / header / metadata keys" in {
      CorrelationId.MdcKey shouldBe "correlationId"
      CorrelationId.HttpHeader shouldBe "X-Correlation-Id"
      CorrelationId.MetadataKey shouldBe "x-correlation-id" // HTTP/2 keys must be lower-case
    }

    "round-trip through gRPC metadata under the metadata key" in {
      val id = CorrelationId.mint()
      val md = new MetadataBuilder().addText(CorrelationId.MetadataKey, id).build()
      md.getText(CorrelationId.MetadataKey) shouldBe Some(id)
    }
  }
