// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.codegen

import com.digitalasset.codegen.types.Namespace
import com.digitalasset.daml.lf.{data, iface}
import iface.{Type => _, _}
import com.digitalasset.daml.lf.iface.reader.{Errors, Interface, InterfaceReader, InterfaceType}
import java.io._
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipFile

import scala.collection.{breakOut, immutable}
import com.digitalasset.codegen.dependencygraph._
import com.digitalasset.codegen.exception.PackageInterfaceException
import com.digitalasset.daml.lf.archive.{DarReader, DarReaderWithVersion, LanguageMajorVersion}
import com.digitalasset.daml.lf.data.ImmArray.ImmArraySeq
import com.digitalasset.daml.lf.data.Ref
import lf.{DefTemplateWithRecord, LFUtil, ScopedDataType}
import com.digitalasset.daml_lf.{DamlLf, DamlLf1}
import com.digitalasset.daml.lf.data.Ref._
import com.digitalasset.daml.lf.iface.reader.Errors.ErrorLoc
import scalaz._
import scalaz.std.tuple._
import scalaz.std.list._
import scalaz.std.set._
import scalaz.std.string._
import scalaz.syntax.bifunctor._
import scalaz.syntax.std.option._
import scalaz.syntax.bind._
import scalaz.syntax.traverse1._

import scala.util.Try

object CodeGen {

  val SupportedLanguageVersions: Set[LanguageMajorVersion] = Set(LanguageMajorVersion.V1)

  sealed abstract class SupportedFileType(fileExtension: String) extends Serializable with Product {
    def matchesFileExtension(f: File): Boolean = f.getName.endsWith(fileExtension)
  }
  final case object Dar extends SupportedFileType(".dar")
  final case object Dalf extends SupportedFileType(".dalf")

  def supportedFileType(f: File): String \/ SupportedFileType = {
    if (Dar.matchesFileExtension(f)) \/.right(Dar)
    else if (Dalf.matchesFileExtension(f)) \/.right(Dalf)
    else \/.left(s"Unsupported file extension: ${f.getAbsolutePath}")
  }

  sealed abstract class Mode extends Serializable with Product { self =>
    type Dialect <: Util { type Interface <: self.Interface }
    type InterfaceElement
    type Interface
    private[CodeGen] val Dialect: (String, Interface, File) => Dialect

    private[CodeGen] def decodeInterfaceFromStream(
        format: PackageFormat,
        bis: BufferedInputStream): String \/ InterfaceElement

    private[CodeGen] def combineInterfaces(
        leader: InterfaceElement,
        dependencies: Seq[InterfaceElement]): Interface

    private[CodeGen] def templateCount(interface: Interface): Int
  }

  case object Novel extends Mode {
    import reader.InterfaceReader
    type Dialect = LFUtil
    type InterfaceElement = reader.Interface
    type Interface = lf.EnvironmentInterface
    private[CodeGen] val Dialect = LFUtil.apply

    private[CodeGen] def decodeInterfaceFromFile(f: File): String \/ Seq[InterfaceElement] = {
      println(s"Codegen decoding file: ${f.getAbsolutePath: String}")
      supportedFileType(f).map {
        case Dar => ???

        case Dalf => ???
      }
    }

    private[CodeGen] override def decodeInterfaceFromStream(
        bis: BufferedInputStream): String \/ InterfaceElement =
      \/.fromTryCatchNonFatal {
        val (errors, out) = reader.Interface.read(DamlLf.Archive.parser().parseFrom(bis))
        println(
          s"Codegen decoded archive with Package ID: ${out.packageId.underlyingString: String}")
        if (!errors.empty)
          \/.left("Errors reading LF archive:\n" +: formatErrors(errors))
        else \/.right(out)
      }.leftMap(_.getLocalizedMessage).join

    private[CodeGen] def formatErrors(
        errors: Errors[ErrorLoc, InterfaceReader.InvalidDataTypeDefinition]): String =
      InterfaceReader.InterfaceReaderError.treeReport(errors).toString

    private[CodeGen] override def combineInterfaces(
        leader: InterfaceElement,
        dependencies: Seq[InterfaceElement]): Interface =
      lf.EnvironmentInterface fromReaderInterfaces (leader, dependencies: _*)

    private[CodeGen] override def templateCount(interface: Interface): Int = {
      interface.typeDecls.count {
        case (_, InterfaceType.Template(_, _)) => true
        case _ => false
      }
    }
  }

  val universe: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe
  import universe._

  import Util.{FilePlan, WriteParams, partitionEithers}

  /*
   * Given an DAML package (in dalf format), a package name and an output
   * directory, this function writes a bunch of generated .scala files
   * to 'outputDir' that mirror the namespace of the DAML package.
   *
   * This function throws exception when an unexpected error happens. Unexpected errors are:
   * - input file not found or not readable
   * - package interface extraction failed
   */
  @throws[FileNotFoundException](cause = "input file not found")
  @throws[SecurityException](cause = "input file not readable")
  @throws[PackageInterfaceException](
    cause = "either decoding a package from a file or extracting" +
      " the package interface failed")
  def generateCode(
      dalfFile: File,
      otherDalfInputs: Seq[URL],
      packageName: String,
      outputDir: File,
      mode: Mode): Unit = {
    val errorOrRun = for {
      interface <- decodePackageFromFile(dalfFile, mode)
      dependencies <- decodePackagesFromURLs(otherDalfInputs, mode)
      combined = mode.combineInterfaces(interface, dependencies)
    } yield packageInterfaceToScalaCode(mode.Dialect(packageName, combined, outputDir))

    errorOrRun fold (e => throw PackageInterfaceException(e), identity)
  }

  type PayloadWithVersion = ((PackageId, DamlLf.ArchivePayload), LanguageMajorVersion)

  def readInterfaces(darFile: File) = {
    for {
      zipFile <- Try(new ZipFile(darFile)) // TODO(Leo): use bracket
      payloads <- DarReaderWithVersion.readArchive(zipFile)
      _ <- Try(zipFile.close())
      (supported, unsupported) <- Try(splitPayloads(SupportedLanguageVersions)(payloads))
      _ = reportUnsupportedPayloads(unsupported)
      packages = supported.map(getPackage)
      interfaces <- readInterfaces(packages)
    } yield interfaces
  }

  private def splitPayloads(supported: Set[LanguageMajorVersion])(
      payloads: List[PayloadWithVersion]): (List[PayloadWithVersion], List[PayloadWithVersion]) =
    payloads.partition { case ((_, _), v) => supported.contains(v) }

  private def reportUnsupportedPayloads(payloads: List[PayloadWithVersion]): Unit = {
    payloads.foreach {
      case ((packageId, _), version) =>
        println(
          s"WARNING: Skipping unsupported ArchivePayload, packageId: ${packageId.underlyingString: String}, version: ${version.toString}")
    }
  }

  private def getPackage(payloadWithVersion: PayloadWithVersion): (PackageId, DamlLf1.Package) = {
    val ((packageId, payload), _) = payloadWithVersion
    (packageId, payload.getDamlLf1)
  }

  private def readInterfaces(lfPackages: List[(PackageId, DamlLf1.Package)])
    : Seq[(Errors[ErrorLoc, InterfaceReader.InvalidDataTypeDefinition], Interface)] =
    lfPackages.map {
      case (packageId, pkg) => reader.Interface.read(packageId, pkg)
    }

  private def decodePackagesFromURLs(
      urls: Seq[URL],
      mode: Mode): String \/ Seq[mode.InterfaceElement] =
    urls
      .map { url =>
        val is = url.openStream()
        try mode.decodeInterfaceFromStream(new BufferedInputStream(is))
        finally is.close()
      }
      .toList
      .sequenceU

  private def packageInterfaceToScalaCode(util: Util): Unit = {
    val interface = util.iface

    val orderedDependencies
      : OrderedDependencies[Identifier, TypeDeclOrTemplateWrapper[util.TemplateInterface]] =
      util.orderedDependencies(interface)
    val (supportedTemplateIds, typeDeclsToGenerate): (
        Map[Identifier, util.TemplateInterface],
        Vector[ScopedDataType.FWT]) = {

      /* Here we collect templates and the
       * [[TypeDecl]]s without generating code for them.
       */
      val templateIdOrTypeDecls
        : Vector[(Identifier, util.TemplateInterface) Either ScopedDataType.FWT] =
        orderedDependencies.deps.flatMap {
          case (templateId, Node(TypeDeclWrapper(typeDecl), _, _)) =>
            Seq(Right(ScopedDataType fromDefDataType (templateId, typeDecl)))
          case (templateId, Node(TemplateWrapper(templateInterface), _, _)) =>
            Seq(Left((templateId, templateInterface)))
        }

      partitionEithers(templateIdOrTypeDecls).leftMap(_.toMap)
    }

    // Each record/variant has Scala code generated for it individually, unless their names are related
    writeTemplatesAndTypes(util)(WriteParams(supportedTemplateIds, typeDeclsToGenerate))

    println("Scala Codegen result:")
    println(s"Number of generated templates: ${supportedTemplateIds.size}")
    println(
      s"Number of not generated templates: ${util.mode.templateCount(interface) - supportedTemplateIds.size}")
    println(s"Details: ${orderedDependencies.errors.map(_.msg).mkString("\n")}")
  }

  private[codegen] def produceTemplateAndTypeFilesLF(
      wp: WriteParams[DefTemplateWithRecord.FWT],
      util: lf.LFUtil): TraversableOnce[FilePlan] = {
    import wp._

    // New prep steps for LF codegen
    // 1. collect records, search variants and splat/filter
    val (unassociatedRecords, splattedVariants) = splatVariants(recordsAndVariants)

    // 2. put templates/types into single Namespace.fromHierarchy
    val treeified = Namespace.fromHierarchy {
      def widenDDT[R, V](iddt: Iterable[ScopedDataType.DT[R, V]]) = iddt
      val ntdRights =
        (widenDDT(unassociatedRecords.map {
          case ((q, tp), rec) => ScopedDataType(q, ImmArraySeq(tp: _*), rec)
        }) ++ splattedVariants)
          .map(sdt => (sdt.name, \/-(sdt)))
      val tmplLefts = supportedTemplateIds.transform((_, v) => -\/(v))
      (ntdRights ++ tmplLefts) map {
        case (ddtIdent @ Identifier(_, qualName), body) =>
          (qualName.module.segments.toList ++ qualName.name.segments.toList, (ddtIdent, body))
      }
    }

    // fold up the tree to discover the hierarchy's roots, each of which produces a file
    val (treeErrors, topFiles) = lf.HierarchicalOutput.discoverFiles(treeified, util)
    val filePlans = topFiles.map { case (fil, trees) => \/-((None, fil, trees)) } ++ treeErrors
      .map(-\/(_))

    // Finally we generate the "event decoder" and "package ID source"
    val specials =
      Seq(
        lf.EventDecoderGen.generate(util, supportedTemplateIds.keySet),
        lf.PackageIDsGen.generate(util))

    val specialPlans = specials map { case (fp, t) => \/-((None, fp, t)) }

    filePlans ++ specialPlans
  }

  type LHSIndexedRecords[+RF] = Map[(Identifier, List[String]), Record[RF]]

  private[this] def splitNTDs[RF, VF](recordsAndVariants: Iterable[ScopedDataType.DT[RF, VF]])
    : (LHSIndexedRecords[RF], List[ScopedDataType[Variant[VF]]]) =
    partitionEithers(recordsAndVariants map {
      case sdt @ ScopedDataType(qualName, typeVars, ddt) =>
        ddt match {
          case r: Record[RF] => Left(((qualName, typeVars.toList), r))
          case v: Variant[VF] => Right(sdt copy (dataType = v))
        }
    })(breakOut, breakOut)

  /** Replace every VT that refers to some apparently-nominalized record
    * type in the argument list with the fields of that record, and drop
    * those records that arose from nominalization.
    *
    * The nature of each variant data constructor ("field") can be
    * figured by examining the _2: left means splatted, right means
    * unchanged.
    */
  private[this] def splatVariants[RF, VN <: String, VT <: iface.Type](
      recordsAndVariants: Iterable[ScopedDataType.DT[RF, (VN, VT)]])
    : (LHSIndexedRecords[RF], List[ScopedDataType[Variant[(VN, List[RF] \/ VT)]]]) = {

    val (recordMap, variants) = splitNTDs(recordsAndVariants)

    val noDeletion = Set.empty[(Identifier, List[String])]
    // both traverseU can change to traverse with -Ypartial-unification
    // or Scala 2.13
    val (deletedRecords, newVariants) =
      variants.traverseU {
        case vsdt @ ScopedDataType(Identifier(packageId, qualName), vTypeVars, _) =>
          val typeVarDelegate = Util simplyDelegates vTypeVars
          vsdt.traverseU {
            _.traverseU {
              case (vn, vt) =>
                val syntheticRecord = Identifier(
                  packageId,
                  qualName copy (name =
                    DottedName.assertFromSegments(qualName.name.segments.slowSnoc(vn).toSeq)))
                val key = (syntheticRecord, vTypeVars.toList)
                typeVarDelegate(vt)
                  .filter((_: Identifier) == syntheticRecord)
                  .flatMap(_ => recordMap get key)
                  .cata(nr => (Set(key), (vn, -\/(nr.fields.toList))), (noDeletion, (vn, \/-(vt))))
            }
          }
      }

    (recordMap -- deletedRecords, newVariants)
  }

  private[this] def writeTemplatesAndTypes(util: Util)(
      wp: WriteParams[util.TemplateInterface]): Unit = {
    util.templateAndTypeFiles(wp) foreach {
      case -\/(msg) => println(msg)
      case \/-((msg, filePath, trees)) =>
        msg foreach (println(_))
        writeCode(filePath, trees)
    }
  }

  private def writeCode(filePath: File, trees: Iterable[Tree]): Unit =
    if (trees.nonEmpty) {
      filePath.getParentFile.mkdirs()
      val writer = new PrintWriter(filePath)
      try {
        writer.println(Util.autoGenerationHeader)
        trees.foreach(tree => writer.println(showCode(tree)))
      } finally {
        writer.close()
      }
    } else {
      println(s"WARNING: nothing to generate, empty trees passed, file: $filePath")
    }
}
