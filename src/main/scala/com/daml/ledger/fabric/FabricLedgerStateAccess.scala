// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.fabric

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import com.daml.DAMLKVConnector
import com.daml.ledger.participant.state.kvutils.DamlKvutils
import com.daml.ledger.validator.LedgerStateOperations.{Key, Value}
import com.daml.ledger.validator.{
  LedgerStateAccess,
  LedgerStateOperations,
  NonBatchingLedgerStateOperations
}
import com.google.protobuf.ByteString

import scala.concurrent.{ExecutionContext, Future}

final private class FabricLedgerStateAccess() extends LedgerStateAccess[Index] {
  val fabricConn: DAMLKVConnector = DAMLKVConnector.get
  override def inTransaction[T](body: LedgerStateOperations[Index] => Future[T]): Future[T] = ???
  //TODO BH: implement me
//    state.write { (log, state) =>
//      body(new TimedLedgerStateOperations(new FabricLedgerStateOperations(log, state), metrics))
}

final private class FabricLedgerStateOperations(fabricConn: DAMLKVConnector)(
    implicit executionContext: ExecutionContext
) extends NonBatchingLedgerStateOperations[Index] {
  override def readState(key: Key): Future[Option[Value]] = {
    Future {
      val entryBytes = fabricConn.getValue(key.toByteArray)
      if (entryBytes == null || entryBytes.isEmpty)
        None
      else {
        Some(ByteString.copyFrom(entryBytes))
      }
    }
  }

  override def writeState(key: Key, value: Value): Future[Unit] =
    Future(fabricConn.putValue(key.toByteArray, value.toByteArray))

  override def appendToLog(key: Key, value: Value): Future[Index] = ???
}

object FabricParticipantState {

  sealed trait Commit extends Serializable with Product

  final case class CommitSubmission(entryId: DamlKvutils.DamlLogEntryId, bytes: ByteString)
      extends Commit
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
}
