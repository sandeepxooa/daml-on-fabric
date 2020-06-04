// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.fabric

import com.daml.ledger.participant.state.v1.SeedService.Seeding

case class ExtraConfig(
    maxInboundMessageSize: Int,
    roleLedger: Boolean,
    roleTime: Boolean,
    roleProvision: Boolean,
    roleExplorer: Boolean,
    seeding: Option[Seeding]
)

object ExtraConfig {
  val defaultMaxInboundMessageSize: Int = 64 * 1024 * 1024
  val default = ExtraConfig(
    maxInboundMessageSize = defaultMaxInboundMessageSize,
    roleLedger = true,
    roleTime = true,
    roleProvision = true,
    roleExplorer = true,
    seeding = None
  )
}
