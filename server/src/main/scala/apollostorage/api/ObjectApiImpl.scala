package apollostorage.api

import apollostorage.blob.{BlobStore, ObjectService}
import apollostorage.domain.Command.{CreateBucket, DeleteBucket}
import apollostorage.domain.{
  BucketName,
  Checksums,
  DomainError,
  ObjectEntry,
  ObjectMetadata as DomainMetadata,
  ObjectName
}
import apollostorage.grpc.*
import apollostorage.persistence.BucketEntity
import com.google.protobuf.ByteString as ProtoBytes
import io.grpc.Status
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/**
 * gRPC `ObjectApi` implementation. A thin adapter that validates requests, maps domain failures to
 * gRPC statuses (design D18), and delegates to `ObjectService`, the `BlobStore`, and the bucket
 * entities.
 */
final class ObjectApiImpl(
    objectService: ObjectService,
    blobStore: BlobStore,
    entityFor: BucketName => Future[ActorRef[BucketEntity.Command]]
)(using system: ActorSystem[?], timeout: Timeout)
    extends ObjectApi:

  private given ExecutionContext = system.executionContext
  private given Scheduler = system.scheduler

  // --- bucket lifecycle ------------------------------------------------------

  def createBucket(in: CreateBucketRequest): Future[BucketResponse] =
    bucketName(in.bucket)
      .flatMap { bucket =>
        execute(bucket, CreateBucket(bucket, Instant.now())).map(_ => BucketResponse(bucket.value))
      }
      .recoverWith(mapFailure)

  def deleteBucket(in: DeleteBucketRequest): Future[BucketResponse] =
    bucketName(in.bucket)
      .flatMap { bucket =>
        execute(bucket, DeleteBucket(bucket, Instant.now())).map(_ => BucketResponse(bucket.value))
      }
      .recoverWith(mapFailure)

  // --- object upload ---------------------------------------------------------

  def putObject(in: Source[PutObjectRequest, NotUsed]): Future[PutObjectResponse] =
    in.prefixAndTail(1)
      .runWith(Sink.head)
      .flatMap { case (prefix, tail) =>
        prefix.headOption.map(_.payload) match
          case Some(PutObjectRequest.Payload.Header(header)) =>
            val chunks = tail.map(_.payload).collect { case PutObjectRequest.Payload.Chunk(b) =>
              ByteString.fromArrayUnsafe(b.toByteArray)
            }
            for
              bucket <- bucketName(header.bucket)
              name <- objectName(header.`object`)
              result <- objectService.commit(
                bucket,
                name,
                DomainMetadata(header.contentType, 0L),
                chunks,
                expectedChecksums(header)
              )
              // Re-read the committed entry to report the assigned generation.
              entry <- lookup(bucket, name)
            yield PutObjectResponse(
              generation = entry.generation.value,
              crc32C = result.checksums.crc32c,
              md5 = result.checksums.md5,
              size = result.size
            )
          case _ =>
            Future.failed(
              new GrpcServiceException(
                Status.INVALID_ARGUMENT.withDescription("first PutObject message must be a header")
              )
            )
      }
      .recoverWith(mapFailure)

  // --- object download & metadata --------------------------------------------

  def getObject(in: GetObjectRequest): Source[GetObjectResponse, NotUsed] =
    val stream = for
      bucket <- bucketName(in.bucket)
      name <- objectName(in.`object`)
      entry <- lookup(bucket, name)
      opened <- blobStore.get(entry.blob)
    yield opened match
      case None =>
        Source.failed(
          new GrpcServiceException(Status.INTERNAL.withDescription("blob missing on disk"))
        )
      case Some(bytes) =>
        val header = GetObjectResponse(
          GetObjectResponse.Payload.Header(metadataMessage(in.bucket, in.`object`, entry))
        )
        Source
          .single(header)
          .concat(bytes.map(b => GetObjectResponse(GetObjectResponse.Payload.Chunk(toProto(b)))))
    Source
      .futureSource(stream.recover { case t => Source.failed(DomainStatus.fromThrowable(t)) })
      .mapMaterializedValue(_ => NotUsed)

  def headObject(in: HeadObjectRequest): Future[ObjectMetadata] =
    (for
      bucket <- bucketName(in.bucket)
      name <- objectName(in.`object`)
      entry <- lookup(bucket, name)
    yield metadataMessage(in.bucket, in.`object`, entry)).recoverWith(mapFailure)

  def deleteObject(in: DeleteObjectRequest): Future[DeleteObjectResponse] =
    (for
      bucket <- bucketName(in.bucket)
      name <- objectName(in.`object`)
      _ <- objectService.delete(bucket, name)
    yield DeleteObjectResponse(in.bucket, in.`object`)).recoverWith(mapFailure)

  // --- helpers ---------------------------------------------------------------

  private def bucketName(raw: String): Future[BucketName] =
    BucketName.from(raw).fold(e => Future.failed(DomainStatus.exceptionFor(e)), Future.successful)

  private def objectName(raw: String): Future[ObjectName] =
    ObjectName.from(raw).fold(e => Future.failed(DomainStatus.exceptionFor(e)), Future.successful)

  private def execute(bucket: BucketName, command: apollostorage.domain.Command): Future[Any] =
    entityFor(bucket).flatMap(_.askWithStatus(replyTo => BucketEntity.Execute(command, replyTo)))

  private def lookup(bucket: BucketName, name: ObjectName): Future[ObjectEntry] =
    entityFor(bucket)
      .flatMap(_.ask[Option[ObjectEntry]](replyTo => BucketEntity.GetObject(name, replyTo)))
      .flatMap {
        case Some(entry) => Future.successful(entry)
        case None => Future.failed(DomainStatus.exceptionFor(DomainError.ObjectNotFound))
      }

  private def expectedChecksums(header: PutHeader): Option[Checksums] =
    if header.expectedCrc32C.nonEmpty && header.expectedMd5.nonEmpty then
      Some(Checksums(header.expectedCrc32C, header.expectedMd5))
    else None

  private def metadataMessage(bucket: String, obj: String, entry: ObjectEntry): ObjectMetadata =
    ObjectMetadata(
      bucket = bucket,
      `object` = obj,
      contentType = entry.metadata.contentType,
      size = entry.metadata.sizeBytes,
      crc32C = entry.checksums.crc32c,
      md5 = entry.checksums.md5,
      generation = entry.generation.value
    )

  private def toProto(b: ByteString): ProtoBytes = ProtoBytes.copyFrom(b.toArray)

  private def mapFailure[A]: PartialFunction[Throwable, Future[A]] = { case t =>
    Future.failed(DomainStatus.fromThrowable(t))
  }
