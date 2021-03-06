// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.tests.integration.ledger.api

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.digitalasset.api.util.TimeProvider
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.ledger.api.v1.command_service.SubmitAndWaitRequest
import com.digitalasset.ledger.api.v1.command_submission_service.SubmitRequest
import com.digitalasset.ledger.api.v1.completion.Completion
import com.digitalasset.ledger.api.v1.event.Event.Event.{Archived, Created}
import com.digitalasset.ledger.api.v1.event.{ArchivedEvent, CreatedEvent, Event, ExercisedEvent}
import com.digitalasset.ledger.api.v1.ledger_offset.LedgerOffset
import com.digitalasset.ledger.api.v1.testing.time_service.TimeServiceGrpc.TimeService
import com.digitalasset.ledger.api.v1.transaction.{Transaction, TransactionTree, TreeEvent}
import com.digitalasset.ledger.api.v1.transaction_filter.{Filters, TransactionFilter}
import com.digitalasset.ledger.api.v1.value.{Identifier, Record, RecordField, Value}
import com.digitalasset.ledger.client.services.testing.time.StaticTime
import com.digitalasset.ledger.client.services.transactions.TransactionClient
import com.google.protobuf.empty.Empty
import com.google.rpc.code.Code
import com.google.rpc.status.Status
import io.grpc.StatusRuntimeException
import org.scalatest.{Assertion, Inside, Matchers, OptionValues}

import scala.collection.{breakOut, immutable}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.implicitConversions

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class LedgerTestingHelpers(
    submitCommand: SubmitRequest => Future[Completion],
    transactionClient: TransactionClient)(implicit mat: ActorMaterializer)
    extends Matchers
    with FutureTimeouts
    with Inside
    with OptionValues {

  implicit private val ec: ExecutionContextExecutor = mat.executionContext

  implicit def submitAndWait2SubmitReq(sw: SubmitAndWaitRequest): SubmitRequest =
    SubmitRequest(sw.commands, sw.traceContext)

  val submitSuccessfully: SubmitRequest => Future[Assertion] =
    submitCommand.andThen(_.map(assertCompletionIsSuccessful))

  def assertCompletionIsSuccessful(completion: Completion): Assertion = {
    inside(completion) { case c => c.getStatus should have('code (0)) }
  }

  def getAllContracts(parties: Seq[String]) = TransactionFilter(parties.map(_ -> Filters()).toMap)

  def assertCommandFailsWithCode(
      submitRequest: SubmitRequest,
      expectedErrorCode: Code,
      expectedMessageSubString: String,
      ignoreCase: Boolean = false): Future[Assertion] = {
    for {
      completion <- submitCommand(submitRequest)
    } yield {
      completion.getStatus should have('code (expectedErrorCode.value))
      if (ignoreCase)
        completion.getStatus.message.toLowerCase() should include(
          expectedMessageSubString.toLowerCase())
      else completion.getStatus.message should include(expectedMessageSubString)
    }
  }

  def submitAndListenForSingleResultOfCommand(
      submitRequest: SubmitRequest,
      transactionFilter: TransactionFilter,
      verbose: Boolean = false): Future[Transaction] = {
    for {
      (txEndOffset, transactionId) <- submitAndReturnOffsetAndTransactionId(submitRequest)
      tx <- expectTransaction(transactionFilter, transactionId, txEndOffset, verbose)
    } yield {
      tx
    }
  }

  def expectTransaction(
      transactionFilter: TransactionFilter,
      transactionId: String,
      txEndOffset: LedgerOffset,
      verbose: Boolean) = {
    for {
      tx <- transactionClient
        .getTransactions(
          txEndOffset,
          None,
          transactionFilter,
          verbose
        )
        .filter(_.transactionId == transactionId)
        .take(1)
        .runWith(Sink.head)
    } yield {
      tx
    }
  }

  def submitAndVerifyFilterCantSeeResultOf(
      submitRequest: SubmitRequest,
      transactionFilter: TransactionFilter): Future[Assertion] = {
    for {
      (txEndOffset, transactionId) <- submitAndReturnOffsetAndTransactionId(submitRequest)
      tx <- transactionClient
        .getTransactions(
          txEndOffset,
          None,
          transactionFilter
        )
        .filter(_.transactionId == transactionId)
        .take(1)
        .takeWithin(3.seconds)
        .runWith(Sink.headOption)
    } yield {
      tx shouldBe empty
    }
  }

  /** Submit the command, then listen as the submitter for the transactionID, such that test code can run assertions
    * on the presence and contents of that transaction as other parties.
    */
  def submitAndReturnOffsetAndTransactionId(
      submitRequest: SubmitRequest): Future[(LedgerOffset, String)] =
    for {
      offsetBeforeSubmission <- timeout(
        submitSuccessfullyAndReturnOffset(submitRequest),
        s"Submitting command ${submitRequest.getCommands.commandId} as party ${submitRequest.getCommands.party}"
      )(mat.system)
      transactionId <- timeout(
        transactionClient
          .getTransactions(
            offsetBeforeSubmission,
            None,
            TransactionFilter(Map(submitRequest.getCommands.party -> Filters.defaultInstance)))
          .filter(_.commandId == submitRequest.getCommands.commandId)
          .map(_.transactionId)
          .runWith(Sink.head),
        s"Reading result of command ${submitRequest.getCommands.commandId} as submitter ${submitRequest.getCommands.commandId}"
      )(mat.system)
    } yield offsetBeforeSubmission -> transactionId

  def submitAndListenForSingleTreeResultOfCommand(
      command: SubmitRequest,
      transactionFilter: TransactionFilter,
      filterCid: Boolean = true,
      verbose: Boolean = false): Future[TransactionTree] = {
    submitAndListenForAllResultsOfCommand(command, transactionFilter, filterCid, verbose).map {
      transactions =>
        transactions._2 should have length 1
        transactions._2.headOption.value
    }
  }

  def submitAndListenForResultsOfCommand(
      submitRequest: SubmitRequest,
      transactionFilter: TransactionFilter,
      filterCid: Boolean = true,
      verbose: Boolean = false): Future[immutable.Seq[Transaction]] = {
    val commandId = submitRequest.getCommands.commandId
    for {
      txEndOffset <- timeout(
        submitSuccessfullyAndReturnOffset(submitRequest),
        s"Submitting command ${submitRequest.getCommands.commandId} as party ${submitRequest.getCommands.party}"
      )(mat.system)
      transactions <- timeout(
        listenForResultOfCommand(
          transactionFilter,
          if (filterCid) Some(commandId) else None,
          txEndOffset,
          verbose),
        s"Reading result of command ${submitRequest.getCommands.commandId} as submitter ${submitRequest.getCommands.commandId}"
      )(mat.system)
    } yield {
      transactions
    }
  }

  def submitAndListenForAllResultsOfCommand(
      submitRequest: SubmitRequest,
      transactionFilter: TransactionFilter,
      filterCid: Boolean = true,
      verbose: Boolean = false)
    : Future[(immutable.Seq[Transaction], immutable.Seq[TransactionTree])] = {
    val commandId = submitRequest.getCommands.commandId
    for {
      txEndOffset <- submitSuccessfullyAndReturnOffset(submitRequest)
      transactionTrees <- timeout(
        listenForTreeResultOfCommand(
          transactionFilter,
          if (filterCid) Some(commandId) else None,
          txEndOffset,
          verbose),
        s"Reading tree result of command ${submitRequest.getCommands.commandId} as submitter ${submitRequest.getCommands.commandId}"
      )(mat.system)
      transactions <- timeout(
        listenForResultOfCommand(
          transactionFilter,
          if (filterCid) Some(commandId) else None,
          txEndOffset,
          verbose),
        s"Reading result of command ${submitRequest.getCommands.commandId} as submitter ${submitRequest.getCommands.commandId}"
      )(mat.system)
    } yield {
      (transactions, transactionTrees)
    }
  }

  def listenForResultOfCommand(
      transactionFilter: TransactionFilter,
      commandId: Option[String],
      txEndOffset: LedgerOffset,
      verbose: Boolean = false): Future[immutable.Seq[Transaction]] = {

    transactionClient
      .getTransactions(
        txEndOffset,
        None,
        transactionFilter,
        verbose
      )
      .filter(x => commandId.fold(true)(cid => x.commandId == cid))
      .take(1)
      .takeWithin(15.seconds)
      .runWith(Sink.seq)
  }

  def listenForTreeResultOfCommand(
      transactionFilter: TransactionFilter,
      commandId: Option[String],
      txEndOffset: LedgerOffset,
      verbose: Boolean = false): Future[immutable.Seq[TransactionTree]] = {
    transactionClient
      .getTransactionTrees(
        txEndOffset,
        None,
        transactionFilter,
        verbose
      )
      .filter(x => commandId.fold(true)(cid => x.commandId == cid))
      .take(1)
      .takeWithin(3.seconds)
      .runWith(Sink.seq)
  }

  def createdEventsIn(transaction: Transaction): Seq[CreatedEvent] =
    transaction.events.map(_.event).collect {
      case Created(createdEvent) =>
        createdEvent
    }

  //TODO this class contains a lot of duplicate code.
  def topLevelExercisedIn(transaction: TransactionTree): Seq[ExercisedEvent] =
    exercisedEventsInNodes(transaction.rootEventIds.map(evId => transaction.eventsById(evId)))

  def createdEventsInNodes(nodes: Seq[Event]): Seq[CreatedEvent] =
    nodes.map(_.event).collect {
      case Created(createdEvent) => createdEvent
    }

  def createdEventsInTreeNodes(nodes: Seq[TreeEvent]): Seq[CreatedEvent] =
    nodes.map(_.kind).collect {
      case TreeEvent.Kind.Created(createdEvent) => createdEvent
    }

  def exercisedEventsInNodes(nodes: Iterable[TreeEvent]): Seq[ExercisedEvent] =
    nodes
      .map(_.kind)
      .collect {
        case TreeEvent.Kind.Exercised(exercisedEvent) => exercisedEvent
      }(breakOut)

  def archivedEventsIn(transaction: Transaction): Seq[ArchivedEvent] =
    transaction.events.map(_.event).collect {
      case Archived(archivedEvent) => archivedEvent
    }

  /**
    * @return A LedgerOffset before the result transaction.
    */
  def submitSuccessfullyAndReturnOffset(submitRequest: SubmitRequest): Future[LedgerOffset] = {
    for {
      txEndOffset <- transactionClient.getLedgerEnd.map(_.getOffset)
      _ <- submitSuccessfully(submitRequest)
    } yield txEndOffset
  }

  def findCreatedEventIn(
      contractCreationTx: Transaction,
      templateToLookFor: Identifier): CreatedEvent = {
    // for helpful scalatest error message
    createdEventsIn(contractCreationTx).flatMap(_.templateId.toList) should contain(
      templateToLookFor)
    createdEventsIn(contractCreationTx).find(_.templateId.contains(templateToLookFor)).value
  }

  def getHead[T](elements: Iterable[T]): T = {
    elements should have size 1
    elements.headOption.value
  }

  def getField(record: Record, fieldName: String): Value = {
    val presentFieldNames: Set[String] = record.fields.map(_.label)(breakOut)
    presentFieldNames should contain(fieldName) // This check is duplicate, but offers better scalatest error message

    getHead(record.fields.collect {
      case RecordField(`fieldName`, fieldValue) => fieldValue
    }).value
  }

  def recordWithArgument(original: Record, fieldToInclude: RecordField): Record = {
    original.update(_.fields.modify(recordFieldsWithArgument(_, fieldToInclude)))
  }
  def recordFieldsWithArgument(
      originalFields: Seq[RecordField],
      fieldToInclude: RecordField): Seq[RecordField] = {
    var replacedAnElement: Boolean = false
    val updated = originalFields.map { original =>
      if (original.label == fieldToInclude.label) {
        replacedAnElement = true
        fieldToInclude
      } else {
        original
      }
    }
    if (replacedAnElement) updated else originalFields :+ fieldToInclude

  }

}

object LedgerTestingHelpers extends OptionValues {

  def sync(
      submitCommand: SubmitAndWaitRequest => Future[Empty],
      transactionClient: TransactionClient)(
      implicit ec: ExecutionContext,
      mat: ActorMaterializer): LedgerTestingHelpers =
    async(helper(submitCommand), transactionClient)

  def asyncFromTimeService(
      timeService: TimeService,
      submit: TimeProvider => SubmitRequest => Future[Completion],
      transactionClient: TransactionClient)(
      implicit ec: ExecutionContext,
      mat: ActorMaterializer,
      esf: ExecutionSequencerFactory): LedgerTestingHelpers = {
    val submitCommand: SubmitRequest => Future[Completion] = submitRequest => {
      for {
        st <- StaticTime.updatedVia(timeService, submitRequest.getCommands.ledgerId)
        res <- submit(st)(submitRequest)
      } yield res
    }
    async(submitCommand, transactionClient)
  }

  def async(
      submitCommand: SubmitRequest => Future[Completion],
      transactionClient: TransactionClient)(
      implicit ec: ExecutionContext,
      mat: ActorMaterializer): LedgerTestingHelpers =
    new LedgerTestingHelpers(submitCommand, transactionClient)

  def emptyToCompletion(commandId: String, emptyF: Future[Empty])(
      implicit ec: ExecutionContext): Future[Completion] =
    emptyF
      .map(_ => Completion(commandId, Some(Status(io.grpc.Status.OK.getCode.value(), ""))))
      .recover {
        case sre: StatusRuntimeException =>
          Completion(
            commandId,
            Some(Status(sre.getStatus.getCode.value(), sre.getStatus.getDescription)))
      }

  private def helper(submitCommand: SubmitAndWaitRequest => Future[Empty])(
      implicit ec: ExecutionContext) = { req: SubmitRequest =>
    emptyToCompletion(
      req.commands.value.commandId,
      submitCommand(SubmitAndWaitRequest(req.commands, req.traceContext)))
  }

}
