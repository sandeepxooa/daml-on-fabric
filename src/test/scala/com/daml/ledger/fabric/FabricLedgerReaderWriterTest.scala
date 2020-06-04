// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.fabric

import java.util.UUID

import com.daml.ledger.api.health.HealthStatus
import com.daml.ledger.participant.state.v1.LedgerId
import com.daml.lf.data.Ref
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class FabricLedgerReaderWriterTest extends AsyncWordSpec with Matchers {

  "Fabric Ledger Reader" should {

//    "return healthy when hitting health endpoint" in {
//      val reader = new FabricLedgerReaderWriter(newLedgerId)
//      reader.currentHealth() should be(HealthStatus.healthy)
//    }
//
//    "return ledger id" in {
//      val ledgerId = newLedgerId
//      val reader = new FabricLedgerReaderWriter(ledgerId)
//      reader.ledgerId() should be(ledgerId)
//    }
//
//    //TODO BH: implement me
//    "return expected events" ignore {
//      val reader = new FabricLedgerReaderWriter(newLedgerId)
//      reader.events(None) should be(None)
//    }
  }

  private def newLedgerId: LedgerId =
    Ref.LedgerString.assertFromString(s"fabric-ledger-${UUID.randomUUID()}")
}
