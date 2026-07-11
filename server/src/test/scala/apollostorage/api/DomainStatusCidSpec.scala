package apollostorage.api

import apollostorage.domain.DomainError
import apollostorage.tracing.CorrelationId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.MDC

/**
 * The correlation id is embedded in gRPC error descriptions (request-tracing capability): when an
 * id is in scope (MDC) it is appended as `(cid=<id>)`, so a failing call surfaces the id in the
 * error message itself; when no id is in scope the description is unchanged.
 */
final class DomainStatusCidSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  override def afterEach(): Unit = MDC.remove(CorrelationId.MdcKey)

  "DomainStatus" should {

    "append (cid=…) to a validation error when an id is in scope" in {
      MDC.put(CorrelationId.MdcKey, "trace99")
      val ex = DomainStatus.exceptionFor(DomainError.BucketNotFound)
      ex.status.getDescription should include("(cid=trace99)")
    }

    "append (cid=…) to a failure mapped from a Throwable" in {
      MDC.put(CorrelationId.MdcKey, "trace77")
      val ex = DomainStatus.fromThrowable(new RuntimeException("kaboom"))
      ex.status.getDescription should include("kaboom")
      ex.status.getDescription should include("(cid=trace77)")
    }

    "append (cid=…) to the hand-built helpers" in {
      MDC.put(CorrelationId.MdcKey, "trace11")
      DomainStatus.invalidArgument("bad").status.getDescription should endWith("(cid=trace11)")
      DomainStatus.internal("oops").status.getDescription should endWith("(cid=trace11)")
    }

    "leave the description unchanged when no id is in scope" in {
      val ex = DomainStatus.invalidArgument("plain")
      ex.status.getDescription shouldBe "plain"
    }
  }
