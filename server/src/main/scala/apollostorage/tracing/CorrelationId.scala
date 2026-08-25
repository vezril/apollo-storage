package apollostorage.tracing

import java.security.SecureRandom

/**
 * A per-request correlation ID (request-tracing capability). The server ALWAYS mints its own — no
 * client-supplied value is trusted or echoed — so there is no log-injection surface. The id is put
 * in the SLF4J MDC under [[MdcKey]]; the structured-logging encoder then promotes it to a top-level
 * JSON field, so every log line for the request carries it. It is returned to the caller as the
 * HTTP header [[HttpHeader]] and the gRPC metadata / HTTP-2 header [[MetadataKey]].
 */
object CorrelationId:

  /** MDC key → a top-level field on every JSON log line via the Logstash encoder. */
  val MdcKey = "correlationId"

  /** HTTP response header carrying the id back to the caller. */
  val HttpHeader = "X-Correlation-Id"

  /**
   * gRPC metadata / HTTP-2 header (must be lower-case) carrying the id on requests and responses.
   */
  val MetadataKey = "x-correlation-id"

  private val Rng = SecureRandom()
  private val Alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
  private val Length = 12

  /** A fresh, short, URL-safe token. Uniqueness and log-friendliness are all that matter. */
  def mint(): String =
    LazyList.continually(Alphabet(Rng.nextInt(Alphabet.length))).take(Length).mkString
