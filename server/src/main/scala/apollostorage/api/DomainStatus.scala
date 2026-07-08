package apollostorage.api

import apollostorage.blob.BlobStoreException
import apollostorage.domain.{DomainError, DomainException}
import io.grpc.Status
import org.apache.pekko.grpc.GrpcServiceException

/**
 * Central mapping of domain and blob-store failures to gRPC statuses (design D18), so handlers
 * never assemble status codes themselves.
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
    new GrpcServiceException(statusFor(error).withDescription(error.message))

  /** For failures surfaced through a `Future` (entity replies, blob store). */
  def fromThrowable(t: Throwable): GrpcServiceException =
    t match
      case e: GrpcServiceException => e
      case DomainException(error) => exceptionFor(error)
      case _: BlobStoreException.ChecksumMismatch =>
        new GrpcServiceException(Status.FAILED_PRECONDITION.withDescription(t.getMessage))
      case other =>
        new GrpcServiceException(Status.INTERNAL.withDescription(other.getMessage))
