package apollostorage.http

import apollostorage.blob.{BlobStore, ObjectService}
import apollostorage.domain.*
import apollostorage.domain.Command.{CreateBucket, DeleteBucket}
import apollostorage.persistence.BucketEntity
import apollostorage.projection.{ObjectRow, Page, ReadModelRepository}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef, Scheduler}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/**
 * The object/bucket operations shared by the API adapters (add-s3-backend-and-rest-api). The REST
 * routes are a thin translation over this, going through the very same `ObjectService`, bucket
 * entities, `BlobStore`, and read model as the gRPC surface — so behaviour, generations, checksums,
 * and event-sourced effects match. Failures are domain-typed (`DomainException`) so each adapter
 * maps them to its own status vocabulary.
 */
trait ObjectOperations:
  def createBucket(bucketRaw: String): Future[Unit]
  def deleteBucket(bucketRaw: String): Future[Unit]
  def listBuckets(pageSize: Int, pageToken: String): Future[Page[String]]
  def putObject(
      bucketRaw: String,
      objectRaw: String,
      contentType: String,
      expected: Option[Checksums],
      data: Source[ByteString, Any]
  ): Future[ObjectEntry]
  def getObject(
      bucketRaw: String,
      objectRaw: String
  ): Future[(ObjectEntry, Source[ByteString, Any])]
  def headObject(bucketRaw: String, objectRaw: String): Future[ObjectEntry]
  def deleteObject(bucketRaw: String, objectRaw: String): Future[Unit]
  def listObjects(
      bucketRaw: String,
      prefix: String,
      pageSize: Int,
      pageToken: String
  ): Future[Page[ObjectRow]]

/** Live wiring over the application core (identical to what the gRPC handler drives). */
final class LiveObjectOperations(
    objectService: ObjectService,
    blobStore: BlobStore,
    entityFor: BucketName => RecipientRef[BucketEntity.Command],
    readModel: ReadModelRepository
)(using system: ActorSystem[?], timeout: Timeout)
    extends ObjectOperations:

  private given ExecutionContext = system.executionContext
  private given Scheduler = system.scheduler

  def createBucket(bucketRaw: String): Future[Unit] =
    bucketName(bucketRaw).flatMap(b => execute(b, CreateBucket(b, Instant.now())).map(_ => ()))

  def deleteBucket(bucketRaw: String): Future[Unit] =
    bucketName(bucketRaw).flatMap(b => execute(b, DeleteBucket(b, Instant.now())).map(_ => ()))

  def listBuckets(pageSize: Int, pageToken: String): Future[Page[String]] =
    readModel.listBuckets(clampPageSize(pageSize), pageToken)

  def putObject(
      bucketRaw: String,
      objectRaw: String,
      contentType: String,
      expected: Option[Checksums],
      data: Source[ByteString, Any]
  ): Future[ObjectEntry] =
    for
      bucket <- bucketName(bucketRaw)
      name <- objectName(objectRaw)
      _ <- objectService.commit(bucket, name, ObjectMetadata(contentType, 0L), data, expected)
      entry <- lookup(bucket, name)
    yield entry

  def getObject(
      bucketRaw: String,
      objectRaw: String
  ): Future[(ObjectEntry, Source[ByteString, Any])] =
    for
      bucket <- bucketName(bucketRaw)
      name <- objectName(objectRaw)
      entry <- lookup(bucket, name)
      opened <- blobStore.get(entry.blob)
      source <- opened match
        case Some(s) => Future.successful(s)
        case None => Future.failed(new IllegalStateException("blob missing on disk"))
    yield (entry, source)

  def headObject(bucketRaw: String, objectRaw: String): Future[ObjectEntry] =
    for
      bucket <- bucketName(bucketRaw)
      name <- objectName(objectRaw)
      entry <- lookup(bucket, name)
    yield entry

  def deleteObject(bucketRaw: String, objectRaw: String): Future[Unit] =
    for
      bucket <- bucketName(bucketRaw)
      name <- objectName(objectRaw)
      _ <- objectService.delete(bucket, name)
    yield ()

  def listObjects(
      bucketRaw: String,
      prefix: String,
      pageSize: Int,
      pageToken: String
  ): Future[Page[ObjectRow]] =
    bucketName(bucketRaw).flatMap { b =>
      readModel.bucketExists(b.value).flatMap {
        case false => Future.failed(DomainException(DomainError.BucketNotFound))
        case true => readModel.listObjects(b.value, prefix, clampPageSize(pageSize), pageToken)
      }
    }

  private def bucketName(raw: String): Future[BucketName] =
    BucketName.from(raw).fold(e => Future.failed(DomainException(e)), Future.successful)

  private def objectName(raw: String): Future[ObjectName] =
    ObjectName.from(raw).fold(e => Future.failed(DomainException(e)), Future.successful)

  private def execute(bucket: BucketName, command: Command): Future[Done] =
    entityFor(bucket).askWithStatus[Done](replyTo => BucketEntity.Execute(command, replyTo))

  private def lookup(bucket: BucketName, name: ObjectName): Future[ObjectEntry] =
    entityFor(bucket)
      .ask[Option[ObjectEntry]](replyTo => BucketEntity.GetObject(name, replyTo))
      .flatMap {
        case Some(entry) => Future.successful(entry)
        case None => Future.failed(DomainException(DomainError.ObjectNotFound))
      }

  private def clampPageSize(requested: Int): Int =
    if requested <= 0 then 100 else math.min(requested, 1000)
