package apollostorage.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ObjectNameSpec extends AnyWordSpec with Matchers:

  "ObjectName.from" should {

    "accept a nested key" in {
      ObjectName.from("photos/2026/07/dive-log.jpg").isRight shouldBe true
    }

    "reject path traversal inputs" in {
      Seq("../../etc/passwd", "a/../b", "/absolute").foreach { bad =>
        withClue(s"input=$bad: ") {
          val result = ObjectName.from(bad)
          result.isLeft shouldBe true
          result.left.map(_.getClass) shouldBe Left(classOf[DomainError.InvalidObjectName])
        }
      }
    }

    "reject a leading slash and backslashes" in {
      ObjectName.from("/x").isRight shouldBe false
      ObjectName.from("a\\b").isRight shouldBe false
    }

    "reject an empty name" in {
      ObjectName.from("").isRight shouldBe false
    }

    "reject a NUL character" in {
      ObjectName.from("a" + '\u0000' + "b").isRight shouldBe false
    }

    "measure length in UTF-8 bytes, not chars" in {
      // '€' is 3 UTF-8 bytes. 342 * 3 = 1026 bytes > 1024, but only 342 chars.
      val multibyte = "€" * 342
      multibyte.length should be <= ObjectName.MaxBytes
      ObjectName.from(multibyte).isRight shouldBe false
    }

    "accept a name exactly at the 1024-byte boundary and reject 1025" in {
      ObjectName.from("a" * 1024).isRight shouldBe true
      ObjectName.from("a" * 1025).isRight shouldBe false
    }
  }
