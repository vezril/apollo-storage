package apollostorage.domain

import org.scalatest.funsuite.AnyFunSuite

/** Deliberately failing test to prove CI's required check goes red and branch
  * protection blocks the merge. This branch/PR is a throwaway (never merged).
  */
final class CiRedCheckSpec extends AnyFunSuite:
  test("deliberately fails to verify failing tests block merge") {
    assert(1 == 2)
  }
