package apollostorage.api

import apollostorage.tracing.CorrelationId
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.core.read.ListAppender
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * Unit coverage for the gRPC tracing decorator (request-tracing capability): it stamps the request
 * with a correlation id, echoes `x-correlation-id` on both success and trailers-only error
 * responses, and access-logs entry + completion with the id in the MDC.
 */
final class GrpcTracingSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers:

  private given scala.concurrent.ExecutionContext = system.executionContext

  private def cidOf(resp: HttpResponse): Option[String] =
    resp.headers.find(_.lowercaseName == CorrelationId.MetadataKey).map(_.value)

  private val path = "/apollostorage.grpc.ObjectApi/CreateBucket"

  "GrpcTracing.instrument" should {

    "add x-correlation-id to a successful response" in {
      val wrapped = GrpcTracing.instrument(_ => Future.successful(HttpResponse(StatusCodes.OK)))
      val resp = wrapped(HttpRequest(uri = path)).futureValue
      cidOf(resp).map(_.length) shouldBe Some(12)
    }

    "add x-correlation-id to a trailers-only error response" in {
      // A gRPC error arrives as HTTP 200 with grpc-status in the headers, not a failed Future.
      val err = HttpResponse(StatusCodes.OK).withHeaders(RawHeader("grpc-status", "16"))
      val wrapped = GrpcTracing.instrument(_ => Future.successful(err))
      cidOf(wrapped(HttpRequest(uri = path)).futureValue) should not be empty
    }

    "stamp the request metadata so the handler can read the id" in {
      @volatile var seen: Option[String] = None
      val wrapped = GrpcTracing.instrument { req =>
        seen = req.headers.find(_.lowercaseName == CorrelationId.MetadataKey).map(_.value)
        Future.successful(HttpResponse(StatusCodes.OK))
      }
      val resp = wrapped(HttpRequest(uri = path)).futureValue
      seen shouldBe cidOf(resp) // the stamped request id equals the echoed response id
    }

    "access-log entry and completion with the id in the MDC" in {
      val (appender, logger) = attachListAppender("apollostorage.api.access")
      try
        val wrapped = GrpcTracing.instrument(_ => Future.successful(HttpResponse(StatusCodes.OK)))
        val resp = wrapped(HttpRequest(uri = path)).futureValue
        val id = cidOf(resp).value
        val events = appender.list.asScala.toList
        events.size shouldBe 2 // one on entry, one on completion
        events.foreach(_.getMDCPropertyMap.get(CorrelationId.MdcKey) shouldBe id)
        val completion = events.last.getFormattedMessage
        completion should include("CreateBucket")
        completion should include("OK")
      finally { val _ = logger.detachAppender(appender) }
    }
  }

  private def attachListAppender(name: String): (ListAppender[ILoggingEvent], LogbackLogger) =
    val logger = LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    (appender, logger)
