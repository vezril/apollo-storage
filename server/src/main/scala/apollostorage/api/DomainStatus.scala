package apollostorage.api

import apollostorage.blob.BlobStoreException
import apollostorage.domain.{DomainError, DomainException}
import apollostorage.tracing.CorrelationId
import io.grpc.Status
import org.apache.pekko.grpc.GrpcServiceException
import org.slf4j.MDC

/**
 * Central mapping of domain and blob-store failures to gRPC statuses (design D18), so handlers
 * never assemble status codes themselves. Every status built here is annotated with the request's
 * correlation id — `(cid=<id>)` appended to the description — so a failing call surfaces its id in
 * the error message itself (request-tracing capability), not only in the response metadata.
 */
object DomainStatus:

  def statusFor(error: DomainError): Status =
    error match
      case DomainError.BucketNotFound => Status.NOT_FOUND
      case DomainError.ObjectNotFound => Status.NOT_FOUND
      case DomainError.BucketAlreadyExists => Status.ALREADY_EXISTS
      case _: DomainError.InvalidBucketName => Status.INVALID_ARGUMENT
      case _: DomainError.InvalidObjectName => Status.INVALID_ARGUMENT

  /** For validation errors caught at the edge (typed `DomainError` in hand). */
  def exceptionFor(error: DomainError): GrpcServiceException =
    new GrpcServiceException(withCid(statusFor(error).withDescription(error.message)))

  /** A hand-built INVALID_ARGUMENT (edge validation not expressed as a `DomainError`). */
  def invalidArgument(message: String): GrpcServiceException =
    new GrpcServiceException(withCid(Status.INVALID_ARGUMENT.withDescription(message)))

  /** A hand-built INTERNAL failure. */
  def internal(message: String): GrpcServiceException =
    new GrpcServiceException(withCid(Status.INTERNAL.withDescription(message)))

  /** For failures surfaced through a `Future` (entity replies, blob store). */
  def fromThrowable(t: Throwable): GrpcServiceException =
    t match
      case e: GrpcServiceException => e
      case DomainException(error) => exceptionFor(error)
      case _: BlobStoreException.ChecksumMismatch =>
        new GrpcServiceException(withCid(Status.FAILED_PRECONDITION.withDescription(t.getMessage)))
      case other =>
        new GrpcServiceException(withCid(Status.INTERNAL.withDescription(other.getMessage)))

  /** Append `(cid=<id>)` to the status description when a correlation id is in scope (MDC). */
  private def withCid(status: Status): Status =
    Option(MDC.get(CorrelationId.MdcKey)) match
      case Some(cid) =>
        val desc = Option(status.getDescription).getOrElse("")
        status.withDescription(s"$desc (cid=$cid)".trim)
      case None => status
