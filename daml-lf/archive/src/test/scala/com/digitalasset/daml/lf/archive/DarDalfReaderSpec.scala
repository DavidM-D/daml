package com.digitalasset.daml.lf
import java.io.File

import org.scalacheck.Gen
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class DarDalfReaderSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {
  behavior of classOf[DarDalfReader[_]].getSimpleName

  it should "parse DARs and DALFs based on the file extention" in forAll(darOrDalfFileGen) { file =>
    ???
  }

  private val darOrDalfFileGen: Gen[File] = for {
    extention <- Gen.oneOf(".dar", ".dalf")
    name <- Gen.identifier
  } yield new File(s"$name.$extention")

}
