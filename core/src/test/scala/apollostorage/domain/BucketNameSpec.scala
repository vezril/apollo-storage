package apollostorage.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

final class BucketNameSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks:

  "BucketName.from" should {

    "accept a valid name" in {
      BucketName.from("media-archive-01") shouldBe Right(BucketName.unsafe("media-archive-01"))
    }

    "reject uppercase with a rule-naming message" in {
      val result = BucketName.from("MediaArchive")
      result.isLeft shouldBe true
      val Left(err) = result: @unchecked
      err shouldBe a[DomainError.InvalidBucketName]
      err.message should include("lowercase")
    }

    "enforce boundary lengths 2/3/63/64" in {
      def name(len: Int): String = "a" + "b" * (len - 1)
      BucketName.from(name(2)).isRight shouldBe false
      BucketName.from(name(3)).isRight shouldBe true
      BucketName.from(name(63)).isRight shouldBe true
      BucketName.from(name(64)).isRight shouldBe false
    }

    "reject a name that does not start or end with a letter or digit" in {
      BucketName.from("-abc").isRight shouldBe false
      BucketName.from("abc-").isRight shouldBe false
    }

    "reject names with illegal characters" in {
      BucketName.from("a_b.c").isRight shouldBe false
      BucketName.from("a b c").isRight shouldBe false
    }

    "accept any generated well-formed name (property)" in {
      val alnum = Gen.oneOf(('a' to 'z') ++ ('0' to '9'))
      // Build a name of length 3..63: alnum endpoints, interior of alnum-or-hyphen.
      val wellFormed = for
        len <- Gen.choose(3, 63)
        first <- alnum
        last <- alnum
        middle <- Gen.listOfN(len - 2, Gen.frequency(9 -> alnum, 1 -> Gen.const('-')))
      yield (first +: middle :+ last).mkString
      forAll(wellFormed) { s =>
        BucketName.from(s) shouldBe Right(BucketName.unsafe(s))
      }
    }
  }
