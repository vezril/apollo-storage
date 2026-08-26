package apollostorage.http

import apollostorage.api.{AuthOutcome, TokenAuthenticator}
import apollostorage.blob.BlobStoreException
import apollostorage.config.Scope
import apollostorage.domain.{Checksums, DomainError, DomainException, ObjectEntry}
import apollostorage.projection.ObjectRow
import apollostorage.tracing.CorrelationId
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{
  `Cache-Control`,
  CacheDirectives,
  ETag,
  EntityTag,
  `If-None-Match`,
  RawHeader
}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import org.slf4j.MDC
import spray.json.DefaultJsonProtocol.*
import spray.json.{
  DeserializationException,
  JsArray,
  JsNumber,
  JsObject,
  JsString,
  JsValue,
  RootJsonFormat
}

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * Plain RESTful object/bucket API at `/v1/...` (add-s3-backend-and-rest-api). A thin second adapter
 * over [[ObjectOperations]] — the same core the gRPC surface uses. Uploads/downloads stream the raw
 * HTTP entity; object metadata rides as `X-Apollo-*` response headers; bucket/list ops return JSON;
 * scoped bearer auth reuses [[TokenAuthenticator]]. Errors become a JSON envelope carrying the
 * request's correlation id.
 */
object ObjectRoutes extends SprayJsonSupport:

  final case class BucketJson(bucket: String)
  final case class BucketListJson(buckets: Seq[String], nextPageToken: String)
  final case class ObjectListJson(objects: Seq[JsValue], nextPageToken: String)
  final case class ErrorJson(code: String, message: String, correlationId: String)

  private given RootJsonFormat[BucketJson] = jsonFormat1(BucketJson.apply)
  private given RootJsonFormat[BucketListJson] = jsonFormat2(BucketListJson.apply)
  private given RootJsonFormat[ErrorJson] = jsonFormat3(ErrorJson.apply)
  // Objects are pre-rendered JsValues (custom keys incl. "object"), so write them directly.
  private given RootJsonFormat[ObjectListJson] with
    def write(o: ObjectListJson): JsValue =
      JsObject(
        "objects" -> JsArray(o.objects.toVector),
        "nextPageToken" -> JsString(o.nextPageToken)
      )
    def read(v: JsValue): ObjectListJson = throw DeserializationException("response-only")

  def apply(ops: ObjectOperations, authenticator: TokenAuthenticator): Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("v1" / "buckets") {
        concat(
          pathEndOrSingleSlash {
            get {
              authorized(Scope.Read, authenticator) {
                parameters("pageSize".as[Int] ? 0, "pageToken" ? "") { (ps, pt) =>
                  onSuccess(ops.listBuckets(ps, pt)) { page =>
                    complete(BucketListJson(page.items, page.nextPageToken))
                  }
                }
              }
            }
          },
          pathPrefix(Segment) { bucket =>
            concat(
              objectRoutes(ops, authenticator, bucket),
              bucketRoutes(ops, authenticator, bucket)
            )
          }
        )
      }
    }

  private def bucketRoutes(ops: ObjectOperations, auth: TokenAuthenticator, bucket: String): Route =
    pathEnd {
      concat(
        put {
          authorized(Scope.Write, auth) {
            onComplete(ops.createBucket(bucket)) {
              case Success(_) => complete(StatusCodes.Created, BucketJson(bucket))
              case Failure(e) => throw e // mapped by exceptionHandler
            }
          }
        },
        delete {
          authorized(Scope.Write, auth) {
            onComplete(ops.deleteBucket(bucket)) {
              case Success(_) => complete(StatusCodes.NoContent)
              case Failure(e) => throw e
            }
          }
        }
      )
    }

  private def objectRoutes(ops: ObjectOperations, auth: TokenAuthenticator, bucket: String): Route =
    pathPrefix("objects") {
      concat(
        pathEndOrSingleSlash {
          get {
            authorized(Scope.Read, auth) {
              parameters("prefix" ? "", "pageSize".as[Int] ? 0, "pageToken" ? "") {
                (prefix, ps, pt) =>
                  onSuccess(ops.listObjects(bucket, prefix, ps, pt)) { page =>
                    complete(ObjectListJson(page.items.map(objectRowJson), page.nextPageToken))
                  }
              }
            }
          }
        },
        path(Remaining) { obj =>
          concat(
            put {
              authorized(Scope.Write, auth) {
                optionalHeaderValueByName("X-Apollo-Crc32c") { crc =>
                  optionalHeaderValueByName("X-Apollo-Md5") { md5 =>
                    extractRequest { req =>
                      val expected = (crc, md5) match
                        case (Some(c), Some(m)) => Some(Checksums(c, m))
                        case _ => None
                      val ct = req.entity.contentType.toString
                      onSuccess(ops.putObject(bucket, obj, ct, expected, req.entity.dataBytes)) {
                        entry =>
                          respondWithHeaders(metaHeaders(entry)) {
                            complete(StatusCodes.Created, entryJson(obj, entry))
                          }
                      }
                    }
                  }
                }
              }
            },
            get {
              authorized(Scope.Read, auth) {
                optionalHeaderValueByType(`If-None-Match`) { ifNoneMatch =>
                  revalidate(ops, bucket, obj, ifNoneMatch) {
                    onSuccess(ops.getObject(bucket, obj)) { case (entry, source) =>
                      respondWithHeaders(readHeaders(entry)) {
                        val ct = contentTypeOf(entry)
                        val size = entry.metadata.sizeBytes
                        val entity =
                          if size > 0 then HttpEntity.Default(ct, size, source)
                          else HttpEntity.empty(ct)
                        complete(HttpResponse(entity = entity))
                      }
                    }
                  }
                }
              }
            },
            head {
              authorized(Scope.Read, auth) {
                optionalHeaderValueByType(`If-None-Match`) { ifNoneMatch =>
                  revalidate(ops, bucket, obj, ifNoneMatch) {
                    onSuccess(ops.headObject(bucket, obj)) { entry =>
                      respondWithHeaders(readHeaders(entry)) {
                        complete(
                          HttpResponse(
                            StatusCodes.OK,
                            entity = HttpEntity.empty(contentTypeOf(entry))
                          )
                        )
                      }
                    }
                  }
                }
              }
            },
            delete {
              authorized(Scope.Write, auth) {
                onComplete(ops.deleteObject(bucket, obj)) {
                  case Success(_) => complete(StatusCodes.NoContent)
                  case Failure(e) => throw e
                }
              }
            }
          )
        }
      )
    }

  /** Reuse the scoped-token check; short-circuit 401/403 like the admin route. */
  private def authorized(required: Scope, authenticator: TokenAuthenticator)(
      inner: => Route
  ): Route =
    optionalHeaderValueByName("authorization") { header =>
      authenticator.authorizeHttp(header, required) match
        case AuthOutcome.Ok => inner
        case AuthOutcome.Unauthenticated => complete(StatusCodes.Unauthorized)
        case AuthOutcome.Forbidden => complete(StatusCodes.Forbidden)
    }

  /**
   * Cache directives for an object read. Apollo object paths are **mutable** — an overwrite
   * increments the generation at the same path — so a read may be stored but MUST be revalidated
   * before reuse. Never `immutable`, never a positive `max-age`: either would let a client serve
   * superseded bytes. `private` because object reads are authenticated when auth is enabled, and a
   * shared cache must not retain one identity's payload for another (design D2).
   */
  private val objectCacheControl: HttpHeader =
    `Cache-Control`(CacheDirectives.`private`(), CacheDirectives.`no-cache`)

  /**
   * Errors are never storable. The case that matters: a client reads an object while it is still
   * being written, gets a 404, and — if that response were remembered — would never see the object
   * appear (design D4).
   */
  private val noStore: HttpHeader = `Cache-Control`(CacheDirectives.`no-store`)

  /**
   * The validator is the object's md5: an ETag identifies a *representation*, not a version
   * counter. An overwrite with byte-identical content bumps the generation but leaves the bytes —
   * and the client's copy — current, so the md5 correctly yields a 304 there where a
   * generation-derived tag would force a pointless re-transfer (design D1). Strong, because it is
   * computed over the exact bytes.
   */
  private def validatorOf(entry: ObjectEntry): EntityTag = EntityTag(entry.checksums.md5)

  private def readHeaders(entry: ObjectEntry): List[HttpHeader] =
    ETag(validatorOf(entry)) :: objectCacheControl :: metaHeaders(entry)

  /**
   * Short-circuit a conditional read. Resolving metadata via `headObject` touches only the read
   * model, so a matching validator answers 304 without ever opening the blob store — which is the
   * entire saving, since `BlobStore.get` costs an S3 round-trip before any byte flows.
   *
   * The lookup happens **only when the caller supplies a validator**; an unconditional read runs
   * `inner` directly and is byte-for-byte the path it took before this existed (design D3).
   */
  private def revalidate(
      ops: ObjectOperations,
      bucket: String,
      obj: String,
      ifNoneMatch: Option[`If-None-Match`]
  )(inner: => Route): Route =
    ifNoneMatch match
      case None => inner
      case Some(condition) =>
        onSuccess(ops.headObject(bucket, obj)) { entry =>
          // Weak comparison per RFC 9110 for If-None-Match.
          if EntityTag.matchesRange(validatorOf(entry), condition.m, weakComparison = true) then
            respondWithHeaders(readHeaders(entry)) {
              complete(HttpResponse(StatusCodes.NotModified))
            }
          else inner
        }

  private def metaHeaders(entry: ObjectEntry): List[HttpHeader] =
    List(
      RawHeader("X-Apollo-Generation", entry.generation.value.toString),
      RawHeader("X-Apollo-Size", entry.metadata.sizeBytes.toString),
      RawHeader("X-Apollo-Crc32c", entry.checksums.crc32c),
      RawHeader("X-Apollo-Md5", entry.checksums.md5)
    )

  private def contentTypeOf(entry: ObjectEntry): ContentType =
    ContentType.parse(entry.metadata.contentType).getOrElse(ContentTypes.`application/octet-stream`)

  private def entryJson(obj: String, entry: ObjectEntry): JsValue =
    JsObject(
      "object" -> JsString(obj),
      "generation" -> JsNumber(entry.generation.value),
      "size" -> JsNumber(entry.metadata.sizeBytes),
      "contentType" -> JsString(entry.metadata.contentType),
      "crc32c" -> JsString(entry.checksums.crc32c),
      "md5" -> JsString(entry.checksums.md5)
    )

  private def objectRowJson(row: ObjectRow): JsValue =
    JsObject(
      "object" -> JsString(row.key),
      "generation" -> JsNumber(row.generation),
      "size" -> JsNumber(row.size),
      "contentType" -> JsString(row.contentType),
      "crc32c" -> JsString(row.crc32c),
      "md5" -> JsString(row.md5)
    )

  private def exceptionHandler: ExceptionHandler = ExceptionHandler {
    case DomainException(err) =>
      respondWithHeader(noStore) {
        complete(statusFor(err), ErrorJson(codeFor(err), err.message, cid()))
      }
    case e: BlobStoreException.ChecksumMismatch =>
      respondWithHeader(noStore) {
        complete(
          StatusCodes.PreconditionFailed,
          ErrorJson("CHECKSUM_MISMATCH", e.getMessage, cid())
        )
      }
    case NonFatal(e) =>
      respondWithHeader(noStore) {
        complete(
          StatusCodes.InternalServerError,
          ErrorJson("INTERNAL", Option(e.getMessage).getOrElse("internal error"), cid())
        )
      }
  }

  private def statusFor(error: DomainError): StatusCode = error match
    case DomainError.BucketNotFound | DomainError.ObjectNotFound => StatusCodes.NotFound
    case DomainError.BucketAlreadyExists => StatusCodes.Conflict
    case _: DomainError.InvalidBucketName | _: DomainError.InvalidObjectName =>
      StatusCodes.BadRequest

  private def codeFor(error: DomainError): String = error match
    case DomainError.BucketNotFound => "BUCKET_NOT_FOUND"
    case DomainError.ObjectNotFound => "OBJECT_NOT_FOUND"
    case DomainError.BucketAlreadyExists => "BUCKET_ALREADY_EXISTS"
    case _: DomainError.InvalidBucketName => "INVALID_BUCKET_NAME"
    case _: DomainError.InvalidObjectName => "INVALID_OBJECT_NAME"

  private def cid(): String = Option(MDC.get(CorrelationId.MdcKey)).getOrElse("")
