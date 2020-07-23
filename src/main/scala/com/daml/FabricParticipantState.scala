// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.time.{Clock, Duration}
import java.util.UUID
import java.util.concurrent.{CompletableFuture, CompletionStage}

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.pattern.gracefulStop
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.daml_lf_dev.DamlLf.Archive
import com.daml.ledger.api.health.{HealthStatus, Healthy}
import com.daml.ledger.participant.state.kvutils.DamlKvutils._
import com.daml.ledger.participant.state.kvutils.{
  Envelope,
  OffsetBuilder,
  KeyValueCommitting,
  KeyValueConsumption,
  KeyValueSubmission,
  Pretty,
  DamlKvutils => Proto
}
import com.daml.ledger.participant.state.v1._
import com.daml.lf.data.Ref
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics
import com.daml.platform.akkastreams.dispatcher.Dispatcher
import com.daml.platform.akkastreams.dispatcher.SubSource.OneAfterAnother
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.protobuf.ByteString
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object FabricParticipantState {

  sealed trait Commit extends Serializable with Product

  /** A commit sent to the [[FabricParticipantState.CommitActor]]
    */
  final case class CommitSubmission(
      entryId: Proto.DamlLogEntryId,
      envelope: ByteString
  ) extends Commit
}

/** Implementation of the participant-state [[ReadService]] and [[WriteService]] using
  * the key-value utilities and a Fabric store.
  */
class FabricParticipantState(
    roleTime: Boolean,
    roleLedger: Boolean,
    participantId: ParticipantId,
    metrics: Metrics,
    engine: Engine
)(
    implicit system: ActorSystem,
    mat: Materializer
) extends ReadService
    with WriteService
    with AutoCloseable {

  val keyValueSubmission = new KeyValueSubmission(metrics)
  val keyValueCommitting = new KeyValueCommitting(engine, metrics)

  // TODO BH: this should be pulled out to config
  val maximumWeightConfig = 100000000
  val stateCache: Cache[DamlStateKey, DamlStateValue] = Scaffeine()
    .maximumWeight(maximumWeightConfig)
    .weigher[DamlStateKey, DamlStateValue] {
      case (key: DamlStateKey, value: DamlStateValue) =>
        key.getSerializedSize + value.getSerializedSize
    }
    .build[DamlStateKey, DamlStateValue]

  import FabricParticipantState._

  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit private val ec: ExecutionContext = mat.executionContext

  // The ledger configuration

  private val ledgerConfig = Configuration(
    generation = 0L,
    timeModel = TimeModel(
      Duration.ofSeconds(0L),
      Duration.ofSeconds(120L),
      Duration.ofSeconds(120L)
    ).get,
    maxDeduplicationTime = Duration.ofDays(1)
  )

  // Random number generator for generating unique entry identifiers.
  private val rng = new java.util.Random

  // Namespace prefix for log entries.
  private val NS_LOG_ENTRIES = ByteString.copyFromUtf8("L")

  // Namespace prefix for DAML state.
  private val NS_DAML_STATE = ByteString.copyFromUtf8("DS")

  // Fabric connection
  private val fabricConn = com.daml.DAMLKVConnector.get

  val ledgerId: LedgerId = fabricConn.getLedgerId

  private def serializeCommit(commit: Commit): Array[Byte] = {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(commit)
    oos.close()
    baos.toByteArray
  }

  private def unserializeCommit(bytes: Array[Byte]): Commit = {
    val bais = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStream(bais)
    val commit = ois.readObject.asInstanceOf[Commit]
    ois.close()
    commit
  }

  /** Akka actor that receives submissions sequentially and
    * commits them one after another to the state, e.g. appending
    * a new ledger commit entry, and applying it to the key-value store.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  class CommitActor extends Actor {

    override def receive: Receive = {
      case commit @ CommitSubmission(entryId, envelope) =>
        val submission: Proto.DamlSubmission = Envelope.open(envelope) match {
          case Left(err)                                     => sys.error(s"Cannot open submission envelope: $err")
          case Right(Envelope.SubmissionMessage(submission)) => submission
          case Right(_)                                      => sys.error("Unexpected message in envelope")
        }
        //TODO BH: need to revist how best to handle time
//        val newRecordTime = Timestamp.assertFromString(fabricConn.getRecordTime)
        val newRecordTime = getNewRecordTime
        val t1 = System.nanoTime

        logger.debug(
          s"Starting with key ${Pretty.prettyEntryId(entryId)} ${System.nanoTime}"
        )

        // check if entry already exists
        val existingEntry = fabricConn.getValue(entryId.getEntryId.toByteArray)
        if (existingEntry != null && existingEntry.nonEmpty) {
          // The entry identifier already in use, drop the message and let the
          // client retry submission.
          logger.warn(s"CommitActor: duplicate entry identifier in commit message, ignoring.")
        } else {
          logger.trace(
            s"CommitActor: processing submission ${Pretty.prettyEntryId(entryId)}..."
          )

          // Process the submission to produce the log entry and the state updates.
          val (logEntry, damlStateUpdates) = keyValueCommitting.processSubmission(
            entryId,
            newRecordTime,
            ledgerConfig,
            submission,
            participantId,
            submission.getInputDamlStateList.asScala
              .map(key => {
                val maybeValue: Option[DamlStateValue] = getDamlState(key)
                maybeValue.foreach(
                  value => if (value.hasArchive || value.hasParty) stateCache.put(key, value)
                )
                key -> maybeValue
              })(breakOut)
          )

          // Combine the abstract log entry and the state updates into concrete updates to the store.
          val allUpdates =
            damlStateUpdates.map {
              case (k, v) =>
                NS_DAML_STATE.concat(keyValueCommitting.packDamlStateKey(k)) ->
                  Envelope.enclose(v)
            } + (entryId.getEntryId -> Envelope.enclose(logEntry))

          logger.trace(
            s"CommitActor: committing ${Pretty.prettyEntryId(entryId)} and ${allUpdates.size} updates to store."
          )

          val batchUpdates = Array.ofDim[Byte](allUpdates.size * 2 + 1, 1)
          var i = 0

          for ((k, v) <- allUpdates) {
            batchUpdates.update(i, k.toByteArray)
            batchUpdates.update(i + 1, fabricConn.gzipBytes(v.toByteArray))
            i += 2
          }
          batchUpdates.update(
            batchUpdates.length - 1,
            fabricConn.gzipBytes(serializeCommit(commit))
          )

          // Write some state to Fabric
          fabricConn.putBatchAndCommit(batchUpdates)

          val duration = System.nanoTime - t1
          logger.debug(
            s"With key ${Pretty.prettyEntryId(entryId)}  time after batch - $duration "
          )

          // Check and write archive
          if (submission.hasPackageUploadEntry) {
            val archives = submission.getPackageUploadEntry.getArchivesList
            archives.forEach { ar =>
              val currentArchives = fabricConn.getPackageList
              if (!currentArchives.contains(ar.getHash)) {
                fabricConn.putPackage(ar.getHash, ar.toByteArray)
              }
            }
          }
          dispatcher.signalNewHead(fabricConn.getCommitHeight)
        }
    }
  }

  /** Instance of the [[CommitActor]] to which we send messages. */
  private val commitActorRef = system.actorOf(Props(new CommitActor), s"commit-actor-$ledgerId")

  //TODO BH: consider renaming class so it is clear this is CommitHeightReader from Fabric
  /** Thread that reads new commits back from Fabric */
  class CommitReader extends Thread {
    override def run(): Unit = {
      var running = true
      var lastHeight = fabricConn.getCommitHeight
      while (running) {

        // we may not use block events directly to generate updates...
        // however, we can check if there are new blocks to not query the chain too often
        if (fabricConn.checkNewBlocks()) {
          val height = fabricConn.getCommitHeight
          if (height > lastHeight) {
            lastHeight = height
            dispatcher.signalNewHead(height)
          }
        }

        try {
          Thread.sleep(50)
        } catch {
          case e: InterruptedException => running = false
        }
      }
    }
  }

  private val commitReaderRef = {
    val threadRef = new CommitReader

    threadRef.start()

    threadRef
  }

  /** The start index */
  type Index = Int
  val StartIndex: Index = 0

  /** The index of the beginning of the commit log */
  private val beginning: Int = fabricConn.getCommitHeight

  if (beginning == 0 && roleTime) {
    // write first ever time into the ledger.. just to make sure that there _is_ time until it starts working
    fabricConn.putRecordTime(getNewRecordTime.toString)
  }

  if (beginning == 0) {
    // now as bad as this is we really need to wait until first record time appears.
    // otherwise, ledger cannot function
    while (fabricConn.getRecordTime == "") {
      Thread.sleep(500)
    }
  }

  /** Dispatcher to subscribe to 'Update' events derived from the state.
    * The index we use here is the "height" of the State.commitLog.
    * This index is transformed into [[Offset]] in [[getUpdate]].
    * *
    * [[Dispatcher]] is an utility written by Digital Asset implementing a fanout
    * for a stream of events. It is initialized with an initial offset and a method for
    * retrieving an event given an offset. It provides the method
    * [[Dispatcher.startingAt]] to subscribe to the stream of events from a
    * given offset, and the method [[Dispatcher.signalNewHead]] to signal that
    * new elements has been added.
    */
  private val dispatcher: Dispatcher[Int] =
    Dispatcher("fabric-participant-state", zeroIndex = StartIndex, headAtInitialization = beginning)

  /** Helper for [[dispatcher]] to fetch [[DamlLogEntry]] from the
    * state and convert it into [[Update]].
    */
  private def getUpdate(idx: Int): List[Update] = {

    // read commit from log (stored on Fabric)
    var commitBytes: Array[Byte] = null
    try {
      commitBytes = fabricConn.getCommit(idx)
    } catch {
      case t: Throwable =>
        t.printStackTrace(System.err)
        commitBytes = null
    }

    if (commitBytes == null) {
      sys.error(s"getUpdate: commit index $idx was not found on the ledger")
    }

    val commit = unserializeCommit(commitBytes)

    commit match {
      case CommitSubmission(entryId, _) =>
        logger.debug(
          s"Ending with key ${Pretty.prettyEntryId(entryId)} ${System.nanoTime}"
        )
        // read update from commit: submission
        val updateBytes = fabricConn.getValue(entryId.getEntryId.toByteArray)
        val logEntry: DamlLogEntry = Envelope.open(ByteString.copyFrom(updateBytes)) match {
          case Left(err)                                 => sys.error(s"getUpdate: cannot open envelope: $err")
          case Right(Envelope.LogEntryMessage(logEntry)) => logEntry
          case Right(_)                                  => sys.error(s"getUpdate: Envelope did not contain log entry")
        }
        logger.trace(s" PRINTING LOG_ENTRY ${logEntry.toString}")
        KeyValueConsumption.logEntryToUpdate(entryId, logEntry)
    }
  }

  override def currentHealth(): HealthStatus = Healthy

  /** Subscribe to updates to the participant state.
    * Implemented using the [[Dispatcher]] helper which handles the signalling
    * and fetching of entries from the state.
    *
    * See [[ReadService.stateUpdates]] for full documentation for the properties
    * of this method.
    */
  override def stateUpdates(beginAfter: Option[Offset]): Source[(Offset, Update), NotUsed] = {

    dispatcher
      .startingAt(
        beginAfter
          .map(OffsetBuilder.highestIndex(_).toInt)
          .getOrElse(StartIndex), // this get index from commitHeight of fabric network
        //            .getOrElse(StartIndex),
        //          check if we need this for recovery cases, also deal with it being larger than 0
        OneAfterAnother[Int, List[Update]](
          (idx: Int) => idx + 1,
          (idx: Int) => Future.successful(getUpdate(idx - 1))
        )
      )
      .collect {
        case (off, updates) =>
          val updateOffset: (Offset, Int) => Offset =
            if (updates.size > 1) OffsetBuilder.setMiddleIndex else (offset, _) => offset
          updates.zipWithIndex.map {
            //TODO BH: index is always 0 : expected?
            case (update, index) =>
              updateOffset(OffsetBuilder.fromLong(off.toLong), index.toInt) -> update
          }
      }
      .mapConcat(identity)
      //TODO BH: check if we really need this
      .filter {
        case (offset, _) => beginAfter.forall(offset > _)
      }
  }

  /** Submit a transaction to the ledger.
    *
    * @param submitterInfo   : the information provided by the submitter for
    *                        correlating this submission with its acceptance or rejection on the
    *                        associated [[ReadService]].
    * @param transactionMeta : the meta-data accessible to all consumers of the
    *   transaction. See [[TransactionMeta]] for more information.
    * @param transaction     : the submitted transaction. This transaction can
    *                        contain contract-ids that are relative to this transaction itself.
    *                        These are used to refer to contracts created in the transaction
    *   itself. The participant state implementation is expected to convert
    *                        these into absolute contract-ids that are guaranteed to be unique.
    *                        This typically happens after a transaction has been assigned a
    *                        globally unique id, as then the contract-ids can be derived from that
    *                        transaction id.
    *
    *                        See [[WriteService.submitTransaction]] for full documentation for the properties
    *                        of this method.
    */
  override def submitTransaction(
      submitterInfo: SubmitterInfo,
      transactionMeta: TransactionMeta,
      transaction: SubmittedTransaction
  ): CompletionStage[SubmissionResult] =
    CompletableFuture.completedFuture({
      // Construct a [[DamlSubmission]] message using the key-value utilities.
      // [[DamlSubmission]] contains the serialized transaction and metadata such as
      // the input contracts and other state required to validate the transaction.
      val submission: DamlSubmission =
        keyValueSubmission.transactionToSubmission(
          submitterInfo,
          transactionMeta,
          transaction
        )

      // Send the [[DamlSubmission]] to the commit actor. The messages are
      // queued and the actor's receive method is invoked sequentially with
      // each message, hence this is safe under concurrency.
      commitActorRef ! CommitSubmission(
        allocateEntryId,
        Envelope.enclose(
          submission
        )
      )
      SubmissionResult.Acknowledged
    })

  /** Allocate a party on the ledger */
  override def allocateParty(
      hint: Option[Party],
      displayName: Option[String],
      submissionId: SubmissionId
  ): CompletionStage[SubmissionResult] = {
    val party = hint.getOrElse(generateRandomParty())
    val submission =
      keyValueSubmission.partyToSubmission(submissionId, Some(party), displayName, participantId)

    CompletableFuture.completedFuture({
      commitActorRef ! CommitSubmission(
        allocateEntryId,
        Envelope.enclose(
          submission
        )
      )
      SubmissionResult.Acknowledged
    })
  }

  private def generateRandomParty(): Ref.Party =
    Ref.Party.assertFromString(s"party-${UUID.randomUUID().toString.take(8)}")

  /** Upload a collection of DAML-LF packages to the ledger. */
  override def uploadPackages(
      submissionId: SubmissionId,
      archives: List[Archive],
      sourceDescription: Option[String]
  ): CompletionStage[SubmissionResult] =
    CompletableFuture.completedFuture({
      commitActorRef ! CommitSubmission(
        allocateEntryId,
        Envelope.enclose(
          keyValueSubmission
            .archivesToSubmission(
              submissionId,
              archives,
              sourceDescription.getOrElse(""),
              participantId
            )
        )
      )
      SubmissionResult.Acknowledged
    })

  /** Retrieve the static initial conditions of the ledger, containing
    * the ledger identifier and the initial ledger record time.
    *
    * Returns a future since the implementation may need to first establish
    * connectivity to the underlying ledger. The implementer may assume that
    * this method is called only once, or very rarely.
    */
  override def getLedgerInitialConditions(): Source[LedgerInitialConditions, NotUsed] =
    Source.single(initialConditions)

  /** Shutdown by killing the [[CommitActor]]. */
  override def close(): Unit = {
    val _ = Await.ready(gracefulStop(commitActorRef, 5.seconds, PoisonPill), 6.seconds)
  }

  private def getDamlState(key: Proto.DamlStateKey): Option[Proto.DamlStateValue] = {

    lazy val valueFromFabric = {
      lazy val entryBytes: Array[Byte] = fabricConn.getValue(
        NS_DAML_STATE.concat(keyValueCommitting.packDamlStateKey(key)).toByteArray
      )
      if (entryBytes == null || entryBytes.isEmpty)
        None
      else {
        Envelope.open(ByteString.copyFrom(entryBytes)) match {
          case Right(Envelope.StateValueMessage(v)) =>
            logger.trace(s" PRINTING getDamlState ${v.toString}")
            Option(v)
          case _ => sys.error(s"getDamlState: Envelope did not contain a state value")
        }
      }
    }

    stateCache.getIfPresent(key).orElse(valueFromFabric)
  }

  //TODO by default the SUbmissionValidator will be doing this
  private def allocateEntryId: Proto.DamlLogEntryId = {
    val nonce: Array[Byte] = Array.ofDim(8)
    rng.nextBytes(nonce)
    Proto.DamlLogEntryId.newBuilder
      .setEntryId(NS_LOG_ENTRIES.concat(ByteString.copyFrom(nonce)))
      .build
  }

  /** The initial conditions of the ledger. The initial record time is the instant
    * at which this class has been instantiated.
    */
  private val initialConditions = LedgerInitialConditions(ledgerId, ledgerConfig, getNewRecordTime)

  /** Get a new record time for the ledger from the system clock.
    * Public for use from integration tests.
    */
  def getNewRecordTime: Timestamp =
    Timestamp.assertFromInstant(Clock.systemUTC().instant())

  /** Submit a new configuration to the ledger. */
  override def submitConfiguration(
      maxRecordTime: Timestamp,
      submissionId: SubmissionId,
      config: Configuration
  ): CompletionStage[SubmissionResult] =
    CompletableFuture.completedFuture({
      val submission =
        keyValueSubmission
          .configurationToSubmission(maxRecordTime, submissionId, participantId, config)
      commitActorRef ! CommitSubmission(
        allocateEntryId,
        Envelope.enclose(submission)
      )
      SubmissionResult.Acknowledged
    })

}
