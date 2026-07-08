package apollostorage.api

import apollostorage.blob.BlobStoreException
import apollostorage.domain.{Checksums, DomainError, DomainException}
import io.grpc.Status
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class DomainStatusSpec extends AnyWordSpec with Matchers:

  "DomainStatus" should {

    "map domain errors to gRPC status codes" in {
      DomainStatus.statusFor(DomainError.BucketNotFound).getCode shouldBe Status.Code.NOT_FOUND
      DomainStatus.statusFor(DomainError.ObjectNotFound).getCode shouldBe Status.Code.NOT_FOUND
      DomainStatus
        .statusFor(DomainError.BucketAlreadyExists)
        .getCode shouldBe Status.Code.ALREADY_EXISTS
      DomainStatus
        .statusFor(DomainError.InvalidBucketName("bad"))
        .getCode shouldBe Status.Code.INVALID_ARGUMENT
      DomainStatus
        .statusFor(DomainError.InvalidObjectName("bad"))
        .getCode shouldBe Status.Code.INVALID_ARGUMENT
    }

    "unwrap a DomainException from a Future failure" in {
      val ex = DomainStatus.fromThrowable(DomainException(DomainError.BucketNotFound))
      ex.status.getCode shouldBe Status.Code.NOT_FOUND
    }

    "map a checksum mismatch to FAILED_PRECONDITION" in {
      val mismatch = BlobStoreException.ChecksumMismatch(Checksums("a", "b"), Checksums("c", "d"))
      DomainStatus.fromThrowable(mismatch).status.getCode shouldBe Status.Code.FAILED_PRECONDITION
    }

    "map an unexpected failure to INTERNAL" in {
      DomainStatus
        .fromThrowable(new RuntimeException("boom"))
        .status
        .getCode shouldBe Status.Code.INTERNAL
    }
  }
