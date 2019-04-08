// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf
import java.io._
import java.util.zip.ZipFile

import com.digitalasset.daml.lf.DarDalfReader.{DalfFile, DarFile, supportedFileType}
import com.digitalasset.daml.lf.archive.DarReader
import com.digitalasset.daml.lf.data.TryOps.Bracket.bracket

import scala.util.{Failure, Success, Try}

class DarDalfReader[A](parseDar: ZipFile => Try[Dar[A]], parseDalf: InputStream => Try[A]) {

  def readArchive(file: File): Try[Dar[A]] = supportedFileType(file).flatMap {
    case DarFile =>
      bracket(zipFile(file))(close).flatMap(parseDar)
    case DalfFile =>
      bracket(inputStream(file))(close).flatMap(parseDalf).map(Dar(_, List.empty))
  }

  private def zipFile(f: File): Try[ZipFile] = Try(new ZipFile(f))

  private def inputStream(f: File): Try[InputStream] =
    Try(new BufferedInputStream(new FileInputStream(f)))

  private def close(f: Closeable): Try[Unit] = Try(f.close())
}

object DarDalfReader {

  def apply[A](parseDalf: InputStream => Try[A]): DarDalfReader[A] =
    new DarDalfReader[A](parseDar(parseDalf), parseDalf)

  private def parseDar[A](parseDalf: InputStream => Try[A]): ZipFile => Try[Dar[A]] =
    DarReader(parseDalf).readArchive

  sealed abstract class SupportedFileType(fileExtension: String) extends Serializable with Product {
    def matchesFileExtension(f: File): Boolean = f.getName.endsWith(fileExtension)
  }
  final case object DarFile extends SupportedFileType(".dar")
  final case object DalfFile extends SupportedFileType(".dalf")

  def supportedFileType(f: File): Try[SupportedFileType] =
    if (DarFile.matchesFileExtension(f)) Success(DarFile)
    else if (DalfFile.matchesFileExtension(f)) Success(DalfFile)
    else Failure(UnsupportedFileExtension(f))

  case class UnsupportedFileExtension(file: File)
      extends RuntimeException(s"Unsupported file extension: ${file.getAbsolutePath}")
}
