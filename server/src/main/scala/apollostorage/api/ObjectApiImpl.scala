package apollostorage.api

import apollostorage.blob.{BlobStore, ObjectService}
import apollostorage.config.Scope
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
import apollostorage.tracing.{CorrelationId, MdcPropagatingExecutionContext}
import com.google.protobuf.ByteString as ProtoBytes
import org.apache.pekko.NotUsed
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef, Scheduler}
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}
import org.slf4j.{LoggerFactory, MDC}

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

  // The propagating EC carries the request's `correlationId` (set in the MDC by `guarded`) across
  // every Future hop, so deep TRACE/DEBUG logs stay correlated (request-tracing capability).
  private given ExecutionContext = MdcPropagatingExecutionContext(system.executionContext)
  private given Scheduler = system.scheduler

  private val log = LoggerFactory.getLogger(getClass)

  // --- bucket lifecycle ------------------------------------------------------

  def createBucket(in: CreateBucketRequest, metadata: Metadata): Future[BucketResponse] =
    guarded(metadata, Scope.Write) {
      bucketName(in.bucket).flatMap { bucket =>
        execute(bucket, CreateBucket(bucket, Instant.now())).map(_ => BucketResponse(bucket.value))
      }
    }

  def deleteBucket(in: DeleteBucketRequest, metadata: Metadata): Future[BucketResponse] =
    guarded(metadata, Scope.Write) {
      bucketName(in.bucket).flatMap { bucket =>
        execute(bucket, DeleteBucket(bucket, Instant.now())).map(_ => BucketResponse(bucket.value))
      }
    }

  // --- object upload ---------------------------------------------------------

  def putObject(
      in: Source[PutObjectRequest, NotUsed],
      metadata: Metadata
  ): Future[PutObjectResponse] =
    guarded(metadata, Scope.Write) {
      in.prefixAndTail(1).runWith(Sink.head).flatMap { case (prefix, tail) =>
        prefix.headOption.map(_.payload) match
          case Some(PutObjectRequest.Payload.Header(header)) =>
            log.debug(s"put object ${header.bucket}/${header.`object`} (${header.contentType})")
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
            Future.failed(DomainStatus.invalidArgument("first PutObject message must be a header"))
      }
    }

  // --- object download & metadata --------------------------------------------

  def getObject(in: GetObjectRequest, metadata: Metadata): Source[GetObjectResponse, NotUsed] =
    // getObject returns a Source (not routed through `guarded`), so establish the correlation id in
    // the MDC here; the propagating EC captures it as the stream-building Future is scheduled, then
    // we clear it from this boundary thread so nothing leaks to the next request.
    MDC.put(CorrelationId.MdcKey, correlationId(metadata))
    val stream =
      try
        Future.unit.flatMap { _ =>
          authenticator.authorize(metadata, Scope.Read)
          log.debug(s"get object ${in.bucket}/${in.`object`}")
          for
            bucket <- bucketName(in.bucket)
            name <- objectName(in.`object`)
            entry <- lookup(bucket, name)
            opened <- blobStore.get(entry.blob)
          yield opened match
            case None =>
              Source.failed(DomainStatus.internal("blob missing on disk"))
            case Some(bytes) =>
              val header = GetObjectResponse(
                GetObjectResponse.Payload.Header(metadataMessage(in.bucket, in.`object`, entry))
              )
              Source
                .single(header)
                .concat(
                  bytes.map(b => GetObjectResponse(GetObjectResponse.Payload.Chunk(toProto(b))))
                )
        }
      finally MDC.remove(CorrelationId.MdcKey)
    Source
      .futureSource(stream.recover { case t => Source.failed(DomainStatus.fromThrowable(t)) })
      .mapMaterializedValue(_ => NotUsed)

  def headObject(in: HeadObjectRequest, metadata: Metadata): Future[ObjectMetadata] =
    guarded(metadata, Scope.Read) {
      for
        bucket <- bucketName(in.bucket)
        name <- objectName(in.`object`)
        entry <- lookup(bucket, name)
      yield metadataMessage(in.bucket, in.`object`, entry)
    }

  def deleteObject(in: DeleteObjectRequest, metadata: Metadata): Future[DeleteObjectResponse] =
    guarded(metadata, Scope.Write) {
      for
        bucket <- bucketName(in.bucket)
        name <- objectName(in.`object`)
        _ <- objectService.delete(bucket, name)
      yield DeleteObjectResponse(in.bucket, in.`object`)
    }

  // --- listing (read model; eventually consistent) ---------------------------

  def listBuckets(in: ListBucketsRequest, metadata: Metadata): Future[ListBucketsResponse] =
    guarded(metadata, Scope.Read) {
      readModel
        .listBuckets(pageSize(in.pageSize), in.pageToken)
        .map(p => ListBucketsResponse(p.items, p.nextPageToken))
    }

  def listObjects(in: ListObjectsRequest, metadata: Metadata): Future[ListObjectsResponse] =
    guarded(metadata, Scope.Read) {
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

  /**
   * Establish the request's correlation id in the MDC, authorize the required scope, then run the
   * body; any failure maps to a gRPC status. The id is set on this boundary thread (so the
   * propagating EC captures it for the body's async hops) and removed here afterward so it does not
   * leak to the next request handled on this thread.
   */
  private def guarded[A](metadata: Metadata, required: Scope)(body: => Future[A]): Future[A] =
    MDC.put(CorrelationId.MdcKey, correlationId(metadata))
    try
      Future.unit
        .flatMap { _ =>
          authenticator.authorize(metadata, required)
          log.debug(s"authorized request (required scope $required)")
          body
        }
        .recoverWith(mapFailure)
    finally MDC.remove(CorrelationId.MdcKey)

  /** The correlation id stamped onto the request by the boundary decorator, or a fresh fallback. */
  private def correlationId(metadata: Metadata): String =
    metadata.getText(CorrelationId.MetadataKey).getOrElse(CorrelationId.mint())

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
    log.trace(s"dispatch ${command.getClass.getSimpleName} to bucket ${bucket.value}")
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
