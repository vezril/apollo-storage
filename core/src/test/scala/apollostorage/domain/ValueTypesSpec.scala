package apollostorage.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ValueTypesSpec extends AnyWordSpec with Matchers:

  "Generation" should {
    "start at 1 and increase monotonically" in {
      Generation.first.value shouldBe 1L
      Generation.first.next.value shouldBe 2L
      Generation.first.next.next.value shouldBe 3L
    }

    "be ordered by value" in {
      Generation.first should be < Generation.first.next
      Seq(Generation.unsafe(3), Generation.first, Generation.unsafe(2)).sorted
        .map(_.value) shouldBe Seq(1L, 2L, 3L)
    }
  }

  "Checksums" should {
    "carry crc32c and md5 and compare by value" in {
      val a = Checksums("crc-x", "md5-y")
      val b = Checksums("crc-x", "md5-y")
      a shouldBe b
      a.crc32c shouldBe "crc-x"
      a.md5 shouldBe "md5-y"
    }
  }

  "ObjectMetadata" should {
    "have value equality and be immutable" in {
      val m1 = ObjectMetadata("image/jpeg", 1024L, Map("k" -> "v"))
      val m2 = ObjectMetadata("image/jpeg", 1024L, Map("k" -> "v"))
      m1 shouldBe m2
      // copy produces a new instance; the original is unchanged.
      val m3 = m1.copy(sizeBytes = 2048L)
      m3.sizeBytes shouldBe 2048L
      m1.sizeBytes shouldBe 1024L
    }
  }
