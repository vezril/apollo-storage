package apollostorage.http

import apollostorage.api.TokenAuthenticator
import apollostorage.config.{AuthConfig, Principal, Scope}
import apollostorage.domain.*
import apollostorage.projection.{ObjectRow, Page}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

/**
 * REST adapter coverage (add-s3-backend-and-rest-api §6–§8): HTTP translation over a stub
 * `ObjectOperations` — status codes, streaming bodies, metadata-as-headers, JSON, scoped auth, and
 * the error envelope. The core behaviour it delegates to is covered by the gRPC + domain tests.
 */
final class ObjectRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val entry = ObjectEntry(
    Generation.unsafe(3),
    ObjectMetadata("text/plain; charset=UTF-8", 5L),
    Checksums("aabbccdd", "d41d8cd98f00b204e9800998ecf8427e"),
    BlobRef("photos/aa/xyz")
  )

  private val stub = new ObjectOperations:
    def createBucket(b: String) = Future.unit
    def deleteBucket(b: String) = Future.unit
    def listBuckets(ps: Int, pt: String) = Future.successful(Page(Seq("photos", "docs"), ""))
    def putObject(
        b: String,
        o: String,
        ct: String,
        e: Option[Checksums],
        d: Source[ByteString, Any]
    ) =
      Future.successful(entry)
    def getObject(b: String, o: String) =
      if o == "missing.txt" then Future.failed(DomainException(DomainError.ObjectNotFound))
      else Future.successful((entry, Source.single(ByteString("hello"))))
    def headObject(b: String, o: String) =
      if o == "missing.txt" then Future.failed(DomainException(DomainError.ObjectNotFound))
      else Future.successful(entry)
    def deleteObject(b: String, o: String) = Future.unit
    def listObjects(b: String, prefix: String, ps: Int, pt: String) =
      Future.successful(Page(Seq(ObjectRow("a.txt", 1L, 5L, "text/plain", "aa", "bb")), "next"))

  private def routes(authEnabled: Boolean = false) =
    val cfg =
      if authEnabled then
        AuthConfig(
          enabled = true,
          Seq(Principal("rtok", Scope.Read), Principal("wtok", Scope.Write))
        )
      else AuthConfig(enabled = false, Nil)
    ObjectRoutes(stub, TokenAuthenticator(cfg))

  "REST bucket routes" should {
    "list buckets as JSON" in {
      Get("/v1/buckets") ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"photos\"")
      }
    }
    "create a bucket → 201" in {
      Put("/v1/buckets/photos") ~> routes() ~> check {
        status shouldBe StatusCodes.Created
        responseAs[String] should include("\"bucket\":\"photos\"")
      }
    }
    "delete a bucket → 204" in {
      Delete("/v1/buckets/photos") ~> routes() ~> check(status shouldBe StatusCodes.NoContent)
    }
  }

  "REST object routes" should {
    "store a raw body → 201 with metadata headers" in {
      Put(
        "/v1/buckets/photos/objects/hello.txt",
        HttpEntity(ContentTypes.`text/plain(UTF-8)`, "hello")
      ) ~>
        routes() ~> check {
          status shouldBe StatusCodes.Created
          header("X-Apollo-Generation").map(_.value) shouldBe Some("3")
          header("X-Apollo-Crc32c").map(_.value) shouldBe Some("aabbccdd")
        }
    }
    "stream an object body back with metadata headers" in {
      Get("/v1/buckets/photos/objects/hello.txt") ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "hello"
        header("X-Apollo-Size").map(_.value) shouldBe Some("5")
      }
    }
    "return metadata headers and no body on HEAD" in {
      Head("/v1/buckets/photos/objects/hello.txt") ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        header("X-Apollo-Md5").map(_.value) shouldBe Some("d41d8cd98f00b204e9800998ecf8427e")
        responseAs[String] shouldBe ""
      }
    }
    "delete an object → 204" in {
      Delete("/v1/buckets/photos/objects/hello.txt") ~> routes() ~> check {
        status shouldBe StatusCodes.NoContent
      }
    }
    "list objects by prefix as JSON with a page token" in {
      Get("/v1/buckets/photos/objects?prefix=a") ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"object\":\"a.txt\"")
        responseAs[String] should include("\"nextPageToken\":\"next\"")
      }
    }
  }

  "REST errors" should {
    "map a not-found to 404 with a JSON envelope carrying a correlationId field" in {
      Get("/v1/buckets/photos/objects/missing.txt") ~> routes() ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[String] should include("\"code\":\"OBJECT_NOT_FOUND\"")
        responseAs[String] should include("\"correlationId\"")
      }
    }
  }

  "REST scoped auth" should {
    "reject a missing token with 401 and a read token on a write with 403" in {
      Put("/v1/buckets/photos") ~> routes(authEnabled = true) ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
      Put("/v1/buckets/photos").withHeaders(RawHeader("authorization", "Bearer rtok")) ~>
        routes(authEnabled = true) ~> check(status shouldBe StatusCodes.Forbidden)
      Put("/v1/buckets/photos").withHeaders(RawHeader("authorization", "Bearer wtok")) ~>
        routes(authEnabled = true) ~> check(status shouldBe StatusCodes.Created)
      Get("/v1/buckets").withHeaders(RawHeader("authorization", "Bearer rtok")) ~>
        routes(authEnabled = true) ~> check(status shouldBe StatusCodes.OK)
    }
  }
