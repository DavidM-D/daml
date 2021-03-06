// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.codegen

import com.digitalasset.daml.lf.data.ImmArray
import com.digitalasset.daml.lf.data.ImmArray.ImmArraySeq
import com.digitalasset.daml.lf.data.Ref.{DottedName, QualifiedName, SimpleString}
import com.digitalasset.daml.lf.iface.reader.{Interface, InterfaceType}
import com.digitalasset.daml.lf.iface.{DefDataType, Record, Variant}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.ArrayBuffer

class InterfaceTreeSpec extends FlatSpec with Matchers {

  behavior of "InterfaceTree.bfs"

  it should "traverse an empty tree" in {
    val interfaceTree =
      InterfaceTree(Map.empty, Interface(SimpleString.assertFromString("packageid"), Map.empty))
    interfaceTree.bfs(0)((x, _) => x + 1) shouldEqual 0
  }

  it should "traverse a tree with n elements in bfs order" in {
    val qualifiedName1 = QualifiedName(DottedName(ImmArray("foo")), DottedName(ImmArray("bar")))
    val record1 = InterfaceType.Normal(DefDataType(ImmArraySeq(), Record(ImmArraySeq())))
    val qualifiedName2 =
      QualifiedName(DottedName(ImmArray("foo")), DottedName(ImmArray("bar", "baz")))
    val variant1 = InterfaceType.Normal(DefDataType(ImmArraySeq(), Variant(ImmArraySeq())))
    val qualifiedName3 = QualifiedName(DottedName(ImmArray("foo")), DottedName(ImmArray("qux")))
    val record2 = InterfaceType.Normal(DefDataType(ImmArraySeq(), Record(ImmArraySeq())))
    val typeDecls =
      Map(qualifiedName1 -> record1, qualifiedName2 -> variant1, qualifiedName3 -> record2)
    val interface = new Interface(SimpleString.assertFromString("packageId2"), typeDecls)
    val tree = InterfaceTree.fromInterface(interface)
    val result = tree.bfs(ArrayBuffer.empty[InterfaceType])((ab, n) =>
      n match {
        case ModuleWithContext(interface @ _, modulesLineage @ _, name @ _, module @ _) => ab
        case TypeWithContext(interface @ _, modulesLineage @ _, typesLineage @ _, name @ _, typ) =>
          ab += typ.typ
    })
    result should contain theSameElementsInOrderAs Seq(record1, record2, variant1)
  }
}
