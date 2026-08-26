package apollostorage.http

import apollostorage.api.TokenAuthenticator
import apollostorage.config.{AuthConfig, Scope}
import apollostorage.domain.*
import apollostorage.projection.{ObjectRow, Page}
import org.apache.pekko.http.scaladsl.model.headers.{
  `Cache-Control`,
  ETag,
  EntityTag,
  `If-None-Match`,
  RawHeader
}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

/**
 * HTTP cache validation on the object API (add-http-caching). The point of the change is that a
 * revalidated read costs a metadata lookup instead of a blob fetch, so the tests assert not only
 * the status codes but that **no blob read happens on a 304** — a `304` that still fetched the
 * payload would pass every other assertion while saving nothing.
 */
final class ObjectCachingSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val Md5 = "d41d8cd98f00b204e9800998ecf8427e"
  private val OtherMd5 = "0cc175b9c0f1b6a831c399e269772661"

  private def entryWith(md5: String, generation: Long = 3L): ObjectEntry =
    ObjectEntry(
      Generation.unsafe(generation),
      ObjectMetadata("text/plain; charset=UTF-8", 5L),
      Checksums("aabbccdd", md5),
      BlobRef("photos/aa/xyz")
    )

  /**
   * Counts the calls that reach the blob store. `getObject` is the only operation that opens the
   * payload (`headObject` resolves from the read model alone), so its call count is exactly the
   * "did we touch the blob store?" signal this change is about.
   */
  final private class Recorder:
    val blobReads = new AtomicInteger(0)
    val metadataReads = new AtomicInteger(0)

  private def stub(entry: ObjectEntry, rec: Recorder) = new ObjectOperations:
    def createBucket(b: String) = Future.unit
    def deleteBucket(b: String) = Future.unit
    def listBuckets(ps: Int, pt: String) = Future.successful(Page(Seq("photos"), ""))
    def putObject(
        b: String,
        o: String,
        ct: String,
        e: Option[Checksums],
        d: Source[ByteString, Any]
    ) = Future.successful(entry)
    def getObject(b: String, o: String) =
      val _ = rec.blobReads.incrementAndGet()
      if o == "missing.txt" then Future.failed(DomainException(DomainError.ObjectNotFound))
      else Future.successful((entry, Source.single(ByteString("hello"))))
    def headObject(b: String, o: String) =
      val _ = rec.metadataReads.incrementAndGet()
      if o == "missing.txt" then Future.failed(DomainException(DomainError.ObjectNotFound))
      else Future.successful(entry)
    def deleteObject(b: String, o: String) = Future.unit
    def listObjects(b: String, prefix: String, ps: Int, pt: String) =
      Future.successful(Page(Seq(ObjectRow("a.txt", 1L, 5L, "text/plain", "aa", "bb")), ""))

  private def routesFor(entry: ObjectEntry, rec: Recorder): Route =
    ObjectRoutes(stub(entry, rec), TokenAuthenticator(AuthConfig(enabled = false, Nil)))

  private def routes(md5: String = Md5, generation: Long = 3L): Route =
    routesFor(entryWith(md5, generation), new Recorder)

  private val Obj = "/v1/buckets/photos/objects/hello.txt"

  "Object read validators" should {

    "return a strong ETag derived from the object's md5 on GET" in {
      Get(Obj) ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        val tag = header[ETag].getOrElse(fail("no ETag on the response"))
        tag.etag.tag shouldBe Md5
        tag.etag.weak shouldBe false
      }
    }

    "return the same ETag on HEAD as on GET" in {
      val get = Get(Obj) ~> routes() ~> check(header[ETag])
      val head = Head(Obj) ~> routes() ~> check(header[ETag])
      get shouldBe defined
      head shouldBe get
    }

    "change the ETag when the object is overwritten with different content" in {
      val before = Get(Obj) ~> routes(md5 = Md5) ~> check(header[ETag].map(_.etag.tag))
      val after = Get(Obj) ~> routes(md5 = OtherMd5) ~> check(header[ETag].map(_.etag.tag))
      before shouldBe defined
      before should not be after
    }

    "keep the ETag when an overwrite produces byte-identical content" in {
      // Same bytes, later generation: the client's copy is still current, so re-sending it would be
      // pointless. This is why the validator is the md5 and not the generation counter (design D1).
      val gen3 =
        Get(Obj) ~> routes(md5 = Md5, generation = 3L) ~> check(header[ETag].map(_.etag.tag))
      val gen9 =
        Get(Obj) ~> routes(md5 = Md5, generation = 9L) ~> check(header[ETag].map(_.etag.tag))
      gen3 shouldBe defined // guard: without this the assertion below passes on two Nones
      gen9 shouldBe gen3
    }
  }

  "Cache directives" should {

    "require revalidation and stay private on object reads" in {
      Get(Obj) ~> routes() ~> check {
        val cc = header[`Cache-Control`].getOrElse(fail("no Cache-Control"))
        val rendered = cc.value.toLowerCase
        rendered should include("no-cache")
        rendered should include("private")
      }
    }

    "never mark an object response immutable or fresh for a period" in {
      Get(Obj) ~> routes() ~> check {
        val rendered = header[`Cache-Control`].map(_.value.toLowerCase).getOrElse("")
        withClue(s"object URLs are mutable — directives were: $rendered ") {
          rendered should not include "immutable"
          rendered should not include "max-age"
        }
      }
    }

    "mark a not-found response non-storable" in {
      // A read that races object creation must not be remembered, or the object stays invisible.
      Get("/v1/buckets/photos/objects/missing.txt") ~> routes() ~> check {
        status shouldBe StatusCodes.NotFound
        val rendered = header[`Cache-Control`].map(_.value.toLowerCase).getOrElse("")
        rendered should include("no-store")
      }
    }
  }

  "Conditional reads" should {

    "answer 304 with no body when the validator matches" in {
      val rec = new Recorder
      Get(Obj).withHeaders(`If-None-Match`(EntityTag(Md5))) ~>
        routesFor(entryWith(Md5), rec) ~> check {
          status shouldBe StatusCodes.NotModified
          responseAs[String] shouldBe ""
        }
    }

    "perform NO blob read when answering 304" in {
      val rec = new Recorder
      Get(Obj).withHeaders(`If-None-Match`(EntityTag(Md5))) ~>
        routesFor(entryWith(Md5), rec) ~> check {
          status shouldBe StatusCodes.NotModified
          withClue(
            "a 304 that still opened the payload saves nothing — that is the whole point: "
          ) {
            rec.blobReads.get() shouldBe 0
          }
          rec.metadataReads.get() should be > 0
        }
    }

    "answer 304 on a conditional HEAD" in {
      Head(Obj).withHeaders(`If-None-Match`(EntityTag(Md5))) ~> routes() ~> check {
        status shouldBe StatusCodes.NotModified
      }
    }

    "serve the object normally when the validator is stale" in {
      val rec = new Recorder
      Get(Obj).withHeaders(`If-None-Match`(EntityTag(OtherMd5))) ~>
        routesFor(entryWith(Md5), rec) ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String] shouldBe "hello"
          header[ETag].map(_.etag.tag) shouldBe Some(Md5)
          rec.blobReads.get() shouldBe 1
        }
    }

    "return 404 rather than 304 for a missing object" in {
      Get("/v1/buckets/photos/objects/missing.txt")
        .withHeaders(`If-None-Match`(EntityTag(Md5))) ~> routes() ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "leave an unconditional read untouched" in {
      val rec = new Recorder
      Get(Obj) ~> routesFor(entryWith(Md5), rec) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "hello"
        header("X-Apollo-Generation").map(_.value) shouldBe Some("3")
        header("X-Apollo-Md5").map(_.value) shouldBe Some(Md5)
        rec.blobReads.get() shouldBe 1
      }
    }

    "serve normally when If-None-Match is a wildcard mismatch or unparseable" in {
      // A malformed validator must not fail the request; it simply does not match.
      Get(Obj).withHeaders(RawHeader("If-None-Match", "not a valid etag")) ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "hello"
      }
    }
  }
