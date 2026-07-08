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
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef, Scheduler}
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/**
 * gRPC `ObjectApi` power-API implementation. Each RPC first authenticates the request metadata
 * (design D35), then delegates to `ObjectService`, the `BlobStore`, and the bucket entities,
 * mapping domain failures to gRPC statuses (design D18).
 */
final class ObjectApiImpl(
    objectService: ObjectService,
    blobStore: BlobStore,
    entityFor: BucketName => RecipientRef[BucketEntity.Command],
    readModel: apollostorage.projection.ReadModelRepository,
    authenticator: TokenAuthenticator
)(using system: ActorSystem[?], timeout: Timeout)
    extends ObjectApiPowerApi:

  private given ExecutionContext = system.executionContext
  private given Scheduler = system.scheduler

  // --- bucket lifecycle ------------------------------------------------------

  def createBucket(in: CreateBucketRequest, metadata: Metadata): Future[BucketResponse] =
    guarded(metadata) {
      bucketName(in.bucket).flatMap { bucket =>
        execute(bucket, CreateBucket(bucket, Instant.now())).map(_ => BucketResponse(bucket.value))
      }
    }

  def deleteBucket(in: DeleteBucketRequest, metadata: Metadata): Future[BucketResponse] =
    guarded(metadata) {
      bucketName(in.bucket).flatMap { bucket =>
        execute(bucket, DeleteBucket(bucket, Instant.now())).map(_ => BucketResponse(bucket.value))
      }
    }

  // --- object upload ---------------------------------------------------------

  def putObject(
      in: Source[PutObjectRequest, NotUsed],
      metadata: Metadata
  ): Future[PutObjectResponse] =
    guarded(metadata) {
      in.prefixAndTail(1).runWith(Sink.head).flatMap { case (prefix, tail) =>
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
    }

  // --- object download & metadata --------------------------------------------

  def getObject(in: GetObjectRequest, metadata: Metadata): Source[GetObjectResponse, NotUsed] =
    val stream = Future.unit.flatMap { _ =>
      authenticator.check(metadata)
      for
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
    }
    Source
      .futureSource(stream.recover { case t => Source.failed(DomainStatus.fromThrowable(t)) })
      .mapMaterializedValue(_ => NotUsed)

  def headObject(in: HeadObjectRequest, metadata: Metadata): Future[ObjectMetadata] =
    guarded(metadata) {
      for
        bucket <- bucketName(in.bucket)
        name <- objectName(in.`object`)
        entry <- lookup(bucket, name)
      yield metadataMessage(in.bucket, in.`object`, entry)
    }

  def deleteObject(in: DeleteObjectRequest, metadata: Metadata): Future[DeleteObjectResponse] =
    guarded(metadata) {
      for
        bucket <- bucketName(in.bucket)
        name <- objectName(in.`object`)
        _ <- objectService.delete(bucket, name)
      yield DeleteObjectResponse(in.bucket, in.`object`)
    }

  // --- listing (read model; eventually consistent) ---------------------------

  def listBuckets(in: ListBucketsRequest, metadata: Metadata): Future[ListBucketsResponse] =
    guarded(metadata) {
      readModel
        .listBuckets(pageSize(in.pageSize), in.pageToken)
        .map(p => ListBucketsResponse(p.items, p.nextPageToken))
    }

  def listObjects(in: ListObjectsRequest, metadata: Metadata): Future[ListObjectsResponse] =
    guarded(metadata) {
      bucketName(in.bucket).flatMap { bucket =>
        readModel.bucketExists(bucket.value).flatMap {
          case false => Future.failed(DomainStatus.exceptionFor(DomainError.BucketNotFound))
          case true =>
            readModel
              .listObjects(bucket.value, in.prefix, pageSize(in.pageSize), in.pageToken)
              .map(page => ListObjectsResponse(page.items.map(toEntry), page.nextPageToken))
        }
      }
    }

  // --- helpers ---------------------------------------------------------------

  /** Authenticate, then run the body; any failure maps to a gRPC status. */
  private def guarded[A](metadata: Metadata)(body: => Future[A]): Future[A] =
    Future.unit
      .flatMap { _ =>
        authenticator.check(metadata); body
      }
      .recoverWith(mapFailure)

  private def pageSize(requested: Int): Int =
    if requested <= 0 then 100 else math.min(requested, 1000)

  private def toEntry(row: apollostorage.projection.ObjectRow): apollostorage.grpc.ObjectEntry =
    apollostorage.grpc.ObjectEntry(
      `object` = row.key,
      generation = row.generation,
      size = row.size,
      contentType = row.contentType,
      crc32C = row.crc32c,
      md5 = row.md5
    )

  private def bucketName(raw: String): Future[BucketName] =
    BucketName.from(raw).fold(e => Future.failed(DomainStatus.exceptionFor(e)), Future.successful)

  private def objectName(raw: String): Future[ObjectName] =
    ObjectName.from(raw).fold(e => Future.failed(DomainStatus.exceptionFor(e)), Future.successful)

  private def execute(bucket: BucketName, command: apollostorage.domain.Command): Future[Done] =
    entityFor(bucket).askWithStatus[Done](replyTo => BucketEntity.Execute(command, replyTo))

  private def lookup(bucket: BucketName, name: ObjectName): Future[ObjectEntry] =
    entityFor(bucket)
      .ask[Option[ObjectEntry]](replyTo => BucketEntity.GetObject(name, replyTo))
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
