package com.digitalasset.platform.apitesting

/*
import java.time.Duration
import java.util.UUID

import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import com.digitalasset.api.util.TimeProvider
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.ledger.api.testing.utils.{AkkaBeforeAndAfterAll, SuiteResourceManagementAroundEach, MockMessages => M}
import com.digitalasset.ledger.api.v1.command_completion_service.CommandCompletionServiceGrpc
import com.digitalasset.ledger.api.v1.command_submission_service.{CommandSubmissionServiceGrpc, SubmitRequest}
import com.digitalasset.ledger.api.v1.commands.Command.Command.{Create, Exercise}
import com.digitalasset.ledger.api.v1.commands.{Command, CreateCommand, ExerciseCommand}
import com.digitalasset.ledger.api.v1.completion.Completion
import com.digitalasset.ledger.api.v1.event.Event.Event.{Archived, Created}
import com.digitalasset.ledger.api.v1.event.{ArchivedEvent, CreatedEvent, Event}
import com.digitalasset.ledger.api.v1.ledger_offset.LedgerOffset
import com.digitalasset.ledger.api.v1.transaction.{Transaction, TransactionTree}
import com.digitalasset.ledger.api.v1.transaction_filter._
import com.digitalasset.ledger.api.v1.transaction_service.TransactionServiceGrpc
import com.digitalasset.ledger.api.v1.value.Value.Sum
import com.digitalasset.ledger.api.v1.value.Value.Sum.{Bool, ContractId, Text, Timestamp}
import com.digitalasset.ledger.api.v1.value.{Identifier, Optional, Record, RecordField, Value, Variant}
import com.digitalasset.ledger.client.configuration.CommandClientConfiguration
import com.digitalasset.ledger.client.services.commands.{CommandClient, CompletionStreamElement}
import com.digitalasset.ledger.client.services.transactions.TransactionClient
import com.digitalasset.platform.participant.util.ValueConversions._
import com.google.protobuf.empty.Empty
import com.google.rpc.code.Code
import io.grpc.Channel
import org.scalatest._
import org.scalatest.concurrent.{AsyncTimeLimitedTests, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import com.digitalasset.ledger.api.testing.utils.{MockMessages => M}
import org.scalatest.Inside._

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
 */

import akka.stream.scaladsl.Sink
import com.digitalasset.ledger.api.testing.utils.{AkkaBeforeAndAfterAll, SuiteResourceManagementAroundEach, MockMessages => M}
import com.digitalasset.ledger.api.v1.command_submission_service.SubmitRequest
import com.digitalasset.ledger.api.v1.commands.Command.Command.Create
import com.digitalasset.ledger.api.v1.commands.{Command, CreateCommand, ExerciseCommand}
import com.digitalasset.ledger.api.v1.completion.Completion
import com.digitalasset.ledger.api.v1.event.{CreatedEvent, Event, ExercisedEvent}
import com.digitalasset.ledger.api.v1.event.Event.Event.Created
import com.digitalasset.ledger.api.v1.ledger_offset.LedgerOffset
import com.digitalasset.ledger.api.v1.transaction.{Transaction, TransactionTree, TreeEvent}
import com.digitalasset.ledger.api.v1.transaction_filter.{Filters, TransactionFilter}
import com.digitalasset.ledger.api.v1.value.Value.Sum
import com.digitalasset.ledger.api.v1.value.Value.Sum.{Bool, Text, Timestamp}
import com.digitalasset.ledger.api.v1.value.{Identifier, Optional, Record, RecordField, Value, Variant}
import com.digitalasset.ledger.client.services.commands.CompletionStreamElement
import com.digitalasset.platform.participant.util.ValueConversions._
import com.google.rpc.code.Code
import org.scalatest._
import org.scalatest.concurrent.{AsyncTimeLimitedTests, ScalaFutures}
import org.scalatest.Inside._

import scala.collection.{breakOut, immutable}
import scala.concurrent.duration._
import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.warts.Any"))
abstract class CommandTransactionChecks(instanceId: String)
    extends AsyncWordSpec
        with AkkaBeforeAndAfterAll
        with MultiLedgerFixture
        with SuiteResourceManagementAroundEach
        with ScalaFutures
        with AsyncTimeLimitedTests
        with Matchers
        with OptionValues
        with TestTemplateIds {

  // The huge command tests takes its sweet time
  /*
  override def timeLimit: Span = 60.seconds
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(Span(60L, Seconds))
  */

  protected def submitCommand(ctx: LedgerContext, req: SubmitRequest): Future[Completion]

  override protected def config: Config = Config.default

  private lazy val dummyTemplates =
    List(templateIds.dummy, templateIds.dummyFactory, templateIds.dummyWithParam)
  private val operator = "operator"
  private val receiver = "receiver"
  private val giver = "giver"
  private val observers = List("observer1", "observer2")

  private val integerListRecordLabel = "integerList"

  private val paramShowcaseArgs: Record = {
    val variantId = None
    val variant = Value(Value.Sum.Variant(Variant(variantId, "SomeInteger", 1.asInt64)))
    val nestedVariant = Vector("value" -> variant).asRecordValue
    val integerList = Vector(1, 2).map(_.toLong.asInt64).asList
    Record(
      Some(templateIds.parameterShowcase),
      Vector(
        RecordField("operator", "party".asParty),
        RecordField("integer", 1.asInt64),
        RecordField("decimal", "1.1".asDecimal),
        RecordField("text", Value(Text("text"))),
        RecordField("bool", Value(Bool(true))),
        RecordField("time", Value(Timestamp(0))),
        RecordField("nestedOptionalInteger", nestedVariant),
        RecordField(integerListRecordLabel, integerList),
        RecordField(
          "optionalText",
          Value(Value.Sum.Optional(Optional(Some(Value(Text("present")))))))
      )
    )
  }

  def submitSuccessfully(ctx: LedgerContext, req: SubmitRequest): Future[Assertion] =
    submitCommand(ctx, req).map(assertCompletionIsSuccessful)

  def assertCompletionIsSuccessful(completion: Completion): Assertion = {
    inside(completion) {
      case c => c.getStatus should have('code (0))
    }
  }

  s"Command and Transaction Services ($instanceId)" when {
    "reading completions" should {
      "return the completion of submitted commands for the submitting application" in allFixtures {
        ctx =>
          val commandId = cid("Submitting application sees this")
          val request = createCommandWithId(ctx, commandId)
          for {
            commandClient <- ctx.commandClient()
            offset <- commandClient.getCompletionEnd.map(_.getOffset)
            _ <- submitSuccessfully(ctx, request)
            completionAfterCheckpoint <- listenForCompletionAsApplication(
              ctx,
              M.applicationId,
              request.getCommands.party,
              offset,
              commandId)
          } yield {
            completionAfterCheckpoint.value.status.value should have('code (0))
          }
      }

      "not expose completions of submitted commands to other applications" in allFixtures { ctx =>
        val commandId = cid("The other application does not see this")
        val request = createCommandWithId(ctx, commandId)
        for {
          commandClient <- ctx.commandClient()
          offset <- commandClient.getCompletionEnd.map(_.getOffset)
          _ <- submitSuccessfully(ctx, request)
          completionsAfterCheckpoint <- listenForCompletionAsApplication(
            ctx,
            "anotherApplication",
            request.getCommands.party,
            offset,
            commandId)
        } yield {
          completionsAfterCheckpoint shouldBe empty
        }
      }

      "not expose completions of submitted commands to the application if it down't include the submitting party" in allFixtures {
        ctx =>
          val commandId =
            cid("The application should subscribe with the submitting party to see this")
          val request = createCommandWithId(ctx, commandId)
          for {
            commandClient <- ctx.commandClient()
            offset <- commandClient.getCompletionEnd.map(_.getOffset)
            _ <- submitSuccessfully(ctx, request)
            completionsAfterCheckpoint <- listenForCompletionAsApplication(
              ctx,
              request.getCommands.applicationId,
              "not " + request.getCommands.party,
              offset,
              commandId)
          } yield {
            completionsAfterCheckpoint shouldBe empty
          }
      }
    }

    "interacting with the ledger" should {
      //TODO: this is a quick copy of the test above to have a test case for DEL-3062. we need to clean this up. see: DEL-3097
      "expose contract Ids that are ready to be used for exercising choices using GetTransactionTrees" in allFixtures {
        ctx =>
          // note that the submitting party is not a stakeholder in any event,
          // so this test relies on the sandbox exposing the transactions to the
          // submitter.
          val factoryCreation = cid("Creating factory (Trees)")
          val exercisingChoice = cid("Exercising choice on factory (Trees)")
          for {
            factoryContractId <- findCreatedEventInResultOf(
              ctx,
              factoryCreation,
              templateIds.dummyFactory)
            transaction <- submitAndListenForSingleTreeResultOfCommand(
              ctx,
              requestToCallExerciseWithId(ctx, factoryContractId.contractId, exercisingChoice),
              getAllContracts)
          } yield {
            val exercisedEvent = topLevelExercisedIn(transaction).head
            val creates =
              createdEventsInTreeNodes(exercisedEvent.childEventIds.map(transaction.eventsById))
            exercisedEvent.contractId shouldEqual factoryContractId.contractId
            exercisedEvent.consuming shouldBe true
            creates should have length 2
          }
      }

      // An equivalent of this tests the non-tree api in TransactionIT
      "accept all kinds of arguments in choices (Choice1, different args)" in allFixtures { ctx =>
        val newArgs =
          recordWithArgument(
            paramShowcaseArgs,
            RecordField("decimal", Some(Value(Value.Sum.Decimal("37.0")))))
        verifyParamShowcaseChoice(
          ctx,
          "Choice1", // choice name
          "different args",
          paramShowcaseArgumentsToChoice1Argument(newArgs), // submitted choice args
          // Daml-lf-engine integration works with non-verbose setting,
          // because we do not send the verbose flag in the request
          newArgs
        ) // expected args
      }

      // scalafmt cannot deal with this function
      // format: off
      "accept all kinds of arguments in choices (Choice2)" in allFixtures { ctx =>
        val newArgs =
          recordWithArgument(
            paramShowcaseArgs,
            RecordField("integer", Some(Value(Value.Sum.Int64(74)))))
        verifyParamShowcaseChoice(
          ctx,
          "Choice2", // choice name
          "changing 'integer'",
          // submitted choice args
          Value(
            Value.Sum.Record(
              Record(fields = List(RecordField("newInteger", Some(Value(Value.Sum.Int64(74)))))))),
          newArgs
        ) // expected args
      }

      // TODO An equivalent of this in TransactionIT tests this with a lower number of commands.
      // This can be removed once we can raise the command limit there.
      "accept huge submissions with a large number of commands" in allFixtures { ctx =>
        val commandId = cid("Huge composite command")
        val originalCommand = createCommandWithId(ctx, commandId)
        val targetNumberOfSubCommands = 15000 // That's around the maximum gRPC input size
        val superSizedCommand =
          originalCommand.update(_.commands.update(_.commands.modify(original =>
            List.fill(targetNumberOfSubCommands)(original.head))))
        submitAndListenForSingleResultOfCommand(ctx, superSizedCommand, getAllContracts) map {
          tx =>
            tx.events.size shouldEqual targetNumberOfSubCommands
        }
      }

      "run callable payout and return the right events" in allFixtures { ctx =>
        val commandId = cid("callable payout command")
        val arg = Record(
          Some(templateIds.callablePayout),
          List(
            RecordField("giver", Some(Value(Value.Sum.Party(giver)))),
            RecordField("receiver", Some(Value(Value.Sum.Party(receiver))))
          )
        )

        val earg = Record(
          Some(templateIds.callablePayoutTransfer),
          List(
            RecordField("newReceiver", Some(Value(Value.Sum.Party("newReceiver"))))
          ))

        // create the contract with giver listen for the event with receiver
        val createF: Future[CreatedEvent] =
          simpleCreateWithListener(ctx, commandId, giver, receiver, templateIds.callablePayout, arg)

        val exercise = (party: String) =>
          (contractId: String) =>
            transactionsFromSimpleExercise(
              ctx,
              commandId + "exe",
              party,
              templateIds.callablePayout,
              contractId,
              "Transfer",
              Value(Value.Sum.Record(earg)))

        for {
          cr <- createF
          ex <- exercise(receiver)(cr.contractId)
        } yield {
          val es: Vector[Event] = ex.flatMap(_.events).toVector
          val events = es :+ Event(Created(cr))
          events.size shouldEqual 2
          val creates = events.flatMap(e => e.event.created.toList)
          creates.size shouldEqual 1
        }
      }

    }
  }

  private def cid(commandId: String) = s"$commandId $instanceId"

  def submitRequestWithId(ctx: LedgerContext, commandId: String): SubmitRequest =
    M.submitRequest.update(
      _.commands.modify(_.copy(commandId = commandId, ledgerId = ctx.ledgerId)))

  private def createCommandWithId(ctx: LedgerContext, commandId: String) = {
    val reqWithId = submitRequestWithId(ctx, commandId)
    val arguments = List("operator" -> "party".asParty)

    reqWithId.update(
      _.commands.update(_.commands := dummyTemplates.map(i => Command(create(i, arguments)))))
  }

  private def create(templateId: Identifier, arguments: immutable.Seq[(String, Value)]): Create = {
    Create(CreateCommand(Some(templateId), Some(arguments.asRecordOf(templateId))))
  }

  private def listenForCompletionAsApplication(
      ctx: LedgerContext,
      applicationId: String,
      requestingParty: String,
      offset: LedgerOffset,
      commandIdToListenFor: String) = {
    ctx.commandClient(applicationId = applicationId).flatMap { commandClient =>
      commandClient
          .completionSource(List(requestingParty), offset)
          .collect {
            case CompletionStreamElement.CompletionElement(completion)
              if completion.commandId == commandIdToListenFor =>
              completion
          }
          .take(1)
          .takeWithin(3.seconds)
          .runWith(Sink.seq)
          .map(_.headOption)
    }
  }

  private lazy val getAllContracts = M.transactionFilter

  private def getAllContractsForParties(parties: Seq[String]) = TransactionFilter(parties.map(_ -> Filters()).toMap)


  private def createContracts(ctx: LedgerContext, commandId: String) = {
    val command = createCommandWithId(ctx, commandId)
    submitAndListenForSingleResultOfCommand(ctx, command, getAllContracts)
  }

  private def createdEventsInTreeNodes(nodes: Seq[TreeEvent]): Seq[CreatedEvent] =
    nodes.map(_.kind).collect {
      case TreeEvent.Kind.Created(createdEvent) => createdEvent
    }

  def findCreatedEventIn(
      contractCreationTx: Transaction,
      templateToLookFor: Identifier): CreatedEvent = {
    // for helpful scalatest error message
    createdEventsIn(contractCreationTx).flatMap(_.templateId.toList) should contain(
      templateToLookFor)
    createdEventsIn(contractCreationTx).find(_.templateId.contains(templateToLookFor)).value
  }

  private def findCreatedEventInResultOf(
      ctx: LedgerContext,
      cid: String,
      templateToLookFor: Identifier): Future[CreatedEvent] = {
    for {
      tx <- createContracts(ctx, cid)
    } yield {
      findCreatedEventIn(tx, templateToLookFor)
    }
  }

  private def listenForResultOfCommand(
      ctx: LedgerContext,
      transactionFilter: TransactionFilter,
      commandId: Option[String],
      txEndOffset: LedgerOffset): Future[immutable.Seq[Transaction]] = {

    ctx.transactionClient
        .getTransactions(
          txEndOffset,
          None,
          transactionFilter
        )
        .filter(x => commandId.fold(true)(cid => x.commandId == cid))
        .take(1)
        .takeWithin(3.seconds)
        .runWith(Sink.seq)
  }

  private def listenForTreeResultOfCommand(
      ctx: LedgerContext,
      transactionFilter: TransactionFilter,
      commandId: Option[String],
      txEndOffset: LedgerOffset): Future[immutable.Seq[TransactionTree]] = {
    ctx.transactionClient
        .getTransactionTrees(
          txEndOffset,
          None,
          transactionFilter
        )
        .filter(x => commandId.fold(true)(cid => x.commandId == cid))
        .take(1)
        .takeWithin(3.seconds)
        .runWith(Sink.seq)
  }

  private def topLevelExercisedIn(transaction: TransactionTree): Seq[ExercisedEvent] =
    exercisedEventsInNodes(transaction.rootEventIds.map(evId => transaction.eventsById(evId)))

  private def exercisedEventsInNodes(nodes: Iterable[TreeEvent]): Seq[ExercisedEvent] =
    nodes
        .map(_.kind)
        .collect {
          case TreeEvent.Kind.Exercised(exercisedEvent) => exercisedEvent
        }(breakOut)

  /**
    * @return A LedgerOffset before the result transaction.
    */
  private def submitSuccessfullyAndReturnOffset(
      ctx: LedgerContext,
      submitRequest: SubmitRequest): Future[LedgerOffset] =
    for {
      txEndOffset <- ctx.transactionClient.getLedgerEnd.map(_.getOffset)
      _ <- submitSuccessfully(ctx, submitRequest)
    } yield txEndOffset

  private def submitAndListenForTreeResultsOfCommand(
      ctx: LedgerContext,
      submitRequest: SubmitRequest,
      transactionFilter: TransactionFilter,
      filterCid: Boolean = true): Future[immutable.Seq[TransactionTree]] = {
    val commandId = submitRequest.getCommands.commandId
    for {
      txEndOffset <- submitSuccessfullyAndReturnOffset(ctx, submitRequest)
      transactions <- listenForTreeResultOfCommand(
        ctx,
        transactionFilter,
        if (filterCid) Some(commandId) else None,
        txEndOffset)
    } yield {
      transactions
    }
  }

  private def submitAndListenForSingleResultOfCommand(
      ctx: LedgerContext,
      submitRequest: SubmitRequest,
      transactionFilter: TransactionFilter): Future[Transaction] = {
    for {
      _ <- Future.successful(println(s"About to submit transaction"))
      (txEndOffset, transactionId) <- submitAndReturnOffsetAndTransactionId(ctx, submitRequest)
      _ = println(s"Got end offset $txEndOffset, transaction id $transactionId")
      tx <- ctx.transactionClient
          .getTransactions(
            txEndOffset,
            None,
            transactionFilter
          )
          .filter(_.transactionId == transactionId)
          .take(1)
          .runWith(Sink.head)
      _ = println(s"Got tx $transactionId")
    } yield {
      tx
    }
  }

  /** Submit the command, then listen as the submitter for the transactionID, such that test code can run assertions
    * on the presence and contents of that transaction as other parties.
    */
  private def submitAndReturnOffsetAndTransactionId(
      ctx: LedgerContext,
      submitRequest: SubmitRequest): Future[(LedgerOffset, String)] =
    for {
      offsetBeforeSubmission <- submitSuccessfullyAndReturnOffset(ctx, submitRequest)
      transactionId <- ctx.transactionClient
          .getTransactions(
            offsetBeforeSubmission,
            None,
            TransactionFilter(Map(submitRequest.getCommands.party -> Filters.defaultInstance)))
          .filter(_.commandId == submitRequest.getCommands.commandId)
          .map(_.transactionId)
          .runWith(Sink.head)
    } yield {
      offsetBeforeSubmission -> transactionId
    }

  private def submitAndListenForSingleTreeResultOfCommand(
      ctx: LedgerContext,
      command: SubmitRequest,
      transactionFilter: TransactionFilter,
      filterCid: Boolean = true): Future[TransactionTree] = {
    submitAndListenForTreeResultsOfCommand(ctx, command, transactionFilter, filterCid).map {
      transactions =>
        transactions should have length 1
        transactions.headOption.value
    }
  }

  private def createdEventsIn(transaction: Transaction): Seq[CreatedEvent] =
    transaction.events.map(_.event).collect {
      case Created(createdEvent) => createdEvent
    }

  private def requestToCallExerciseWithId(
      ctx: LedgerContext,
      factoryContractId: String,
      commandId: String) = {
    submitRequestWithId(ctx, commandId).update(
      _.commands.commands := List(
        ExerciseCommand(
          Some(templateIds.dummyFactory),
          factoryContractId,
          "DummyFactoryCall",
          Some(Value(Sum.Record(Record())))).wrap))
  }

  private def recordWithArgument(original: Record, fieldToInclude: RecordField): Record = {
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

  private def createParamShowcaseWith(
      ctx: LedgerContext,
      commandId: String,
      createArguments: Record) = {
    val commandList = List(
      CreateCommand(Some(templateIds.parameterShowcase), Some(createArguments)).wrap)
    submitRequestWithId(ctx, commandId).update(
      _.commands.modify(_.update(_.commands := commandList)))
  }

  private def getHead[T](elements: Iterable[T]): T = {
    elements should have size 1
    elements.headOption.value
  }

  private def paramShowcaseArgumentsToChoice1Argument(args: Record): Value =
    Value(
      Value.Sum.Record(
        args
            .update(_.fields.modify { originalFields =>
              originalFields.collect {
                // prune "operator" -- we do not have it in the choice params
                case original if original.label != "operator" =>
                  val newLabel = "new" + original.label.capitalize
                  RecordField(newLabel, original.value)
              }
            })
            .update(_.recordId.set(templateIds.parameterShowcaseChoice1))))

  private def verifyParamShowcaseChoice(
      ctx: LedgerContext,
      choice: String,
      lbl: String,
      exerciseArg: Value,
      expectedCreateArg: Record): Future[Assertion] = {
    val command: SubmitRequest =
      createParamShowcaseWith(
        ctx,
        cid(s"Creating contract with a multitude of param types for exercising ($choice, $lbl)"),
        paramShowcaseArgs)
    for {
      tx <- submitAndListenForSingleResultOfCommand(ctx, command, getAllContracts)
      contractId = getHead(createdEventsIn(tx)).contractId
      // first, verify that if we submit with the same inputs they're equal
      exercise = ExerciseCommand(
        Some(templateIds.parameterShowcase),
        contractId,
        choice,
        exerciseArg).wrap
      tx <- submitAndListenForSingleTreeResultOfCommand(
        ctx,
        submitRequestWithId(ctx, cid(s"Exercising with a multitiude of params ($choice, $lbl)"))
            .update(_.commands.update(_.commands := List(exercise))),
        getAllContracts,
        true
      )
    } yield {
      // check that we have the exercise
      val exercise = getHead(topLevelExercisedIn(tx))

      //Note: Daml-lf engine returns no labels
      // if verbose flag is off as prescribed
      // and these tests work with verbose=false requests
      val expectedExerciseFields =
      removeLabels(exerciseArg.getRecord.fields)
      val expectedCreateFields =
        removeLabels(expectedCreateArg.fields)

      exercise.templateId shouldEqual Some(templateIds.parameterShowcase)
      exercise.choice shouldEqual choice
      exercise.actingParties should contain("party")
      exercise.getChoiceArgument.getRecord.fields shouldEqual expectedExerciseFields
      // check that we have the create
      val create = getHead(createdEventsInTreeNodes(exercise.childEventIds.map(tx.eventsById)))
      create.getCreateArguments.fields shouldEqual expectedCreateFields
      //    expectedCreateFields
      succeed
    }
  }

  private def removeLabels(fields: Seq[RecordField]): Seq[RecordField] = {
    fields.map { f =>
      f.value match {
        case Some(Value(Value.Sum.Record(r))) =>
          RecordField("", Some(Value(Value.Sum.Record(removeLabelsFromRecord(r)))))
        case other =>
          RecordField("", other)
      }
    }
  }

  private def removeLabelsFromRecord(r: Record): Record = {
    r.update(_.fields.modify(removeLabels))
  }

  // Create a template instance and return the resulting create event.
  private def simpleCreateWithListener(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      listener: String,
      template: Identifier,
      arg: Record
  ): Future[CreatedEvent] = {
    for {
      tx <- submitAndListenForSingleResultOfCommand(
        ctx,
        submitRequestWithId(ctx, cid(commandId))
            .update(
              _.commands.commands :=
                  List(CreateCommand(Some(template), Some(arg)).wrap),
              _.commands.party := submitter
            ),
        TransactionFilter(Map(listener -> Filters.defaultInstance))
      )
    } yield {
      getHead(createdEventsIn(tx))
    }
  }

  // Create a template instance and return the resulting create event.
  private def simpleCreate(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      template: Identifier,
      arg: Record
  ): Future[CreatedEvent] =
    simpleCreateWithListener(ctx, commandId, submitter, submitter, template, arg)

  private def failingCreate(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      template: Identifier,
      arg: Record,
      code: Code,
      pattern: String
  ): Future[Assertion] =
    assertCommandFailsWithCode(
      ctx,
      submitRequestWithId(ctx, cid(commandId))
          .update(
            _.commands.commands :=
                List(CreateCommand(Some(template), Some(arg)).wrap),
            _.commands.party := submitter
          ),
      code,
      pattern
    )

  // Exercise a choice and return all resulting create events.
  private def simpleExerciseWithListener(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      listener: String,
      template: Identifier,
      contractId: String,
      choice: String,
      arg: Value
  ): Future[TransactionTree] = {
    submitAndListenForSingleTreeResultOfCommand(
      ctx,
      submitRequestWithId(ctx, cid(commandId))
          .update(
            _.commands.commands :=
                List(ExerciseCommand(Some(template), contractId, choice, Some(arg)).wrap),
            _.commands.party := submitter
          ),
      TransactionFilter(Map(listener -> Filters.defaultInstance)),
      false
    )
  }

  private def simpleCreateWithListenerForTransactions(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      listener: String,
      template: Identifier,
      arg: Record
  ): Future[CreatedEvent] = {
    for {
      tx <- submitAndListenForSingleResultOfCommand(
        ctx,
        submitRequestWithId(ctx, cid(commandId))
            .update(
              _.commands.commands :=
                  List(CreateCommand(Some(template), Some(arg)).wrap),
              _.commands.party := submitter
            ),
        TransactionFilter(Map(listener -> Filters.defaultInstance))
      )
    } yield {
      getHead(createdEventsIn(tx))
    }
  }

  private def transactionsFromsimpleCreate(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      template: Identifier,
      arg: Record
  ): Future[CreatedEvent] =
    simpleCreateWithListenerForTransactions(ctx, commandId, submitter, submitter, template, arg)

  private def submitAndListenForTransactionResultOfCommand(
      ctx: LedgerContext,
      command: SubmitRequest,
      transactionFilter: TransactionFilter,
      filterCid: Boolean = true): Future[Seq[Transaction]] = {
    submitAndListenForTransactionResultsOfCommand(ctx, command, transactionFilter, filterCid)
  }

  private def submitAndListenForTransactionResultsOfCommand(
      ctx: LedgerContext,
      submitRequest: SubmitRequest,
      transactionFilter: TransactionFilter,
      filterCid: Boolean = true): Future[immutable.Seq[Transaction]] = {
    val commandId = submitRequest.getCommands.commandId
    for {
      txEndOffset <- submitSuccessfullyAndReturnOffset(ctx, submitRequest)
      transactions <- listenForTransactionResultOfCommand(
        ctx,
        transactionFilter,
        if (filterCid) Some(commandId) else None,
        txEndOffset)
    } yield {
      transactions
    }
  }

  private def listenForTransactionResultOfCommand(
      ctx: LedgerContext,
      transactionFilter: TransactionFilter,
      commandId: Option[String],
      txEndOffset: LedgerOffset): Future[immutable.Seq[Transaction]] = {
    ctx.transactionClient
        .getTransactions(
          txEndOffset,
          None,
          transactionFilter
        )
        .filter(x => commandId.fold(true)(cid => x.commandId == cid))
        .take(1)
        .takeWithin(3.seconds)
        .runWith(Sink.seq)
  }


  private def simpleExerciseWithListenerForTransactions(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      listener: String,
      template: Identifier,
      contractId: String,
      choice: String,
      arg: Value
  ): Future[Seq[Transaction]] = {
    submitAndListenForTransactionResultOfCommand(
      ctx,
      submitRequestWithId(ctx, cid(commandId))
          .update(
            _.commands.commands :=
                List(ExerciseCommand(Some(template), contractId, choice, Some(arg)).wrap),
            _.commands.party := submitter
          ),
      TransactionFilter(Map(listener -> Filters.defaultInstance)),
      false
    )
  }

  private def transactionsFromSimpleExercise(
      ctx: LedgerContext,
      commandId: String,
      submitter: String,
      template: Identifier,
      contractId: String,
      choice: String,
      arg: Value): Future[Seq[Transaction]] =
    simpleExerciseWithListenerForTransactions(
      ctx,
      commandId,
      submitter,
      submitter,
      template,
      contractId,
      choice,
      arg)

  private def assertCommandFailsWithCode(
      ctx: LedgerContext,
      submitRequest: SubmitRequest,
      expectedErrorCode: Code,
      expectedMessageSubString: String): Future[Assertion] = {
    for {
      ledgerEnd <- ctx.transactionClient.getLedgerEnd
      completion <- submitCommand(ctx, submitRequest)
      txs <- listenForResultOfCommand(
        ctx,
        getAllContractsForParties(List(submitRequest.getCommands.party)),
        Some(submitRequest.getCommands.commandId),
        ledgerEnd.getOffset)
    } yield {
      completion.getStatus should have('code (expectedErrorCode.value))
      completion.getStatus.message should include(expectedMessageSubString)
      txs shouldBe empty
    }
  }

}
