#!/usr/bin/env bash
# Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0


ORG_PEERS=(peer0.org1.example.com peer0.org2.example.com)

for i in "${#ORG_PEERS[@]}"
do

  while ! nc -z ${ORG_PEERS[ite]} 7051; do sleep 3; echo "waiting for: ${ORG_PEERS[ite]}"; done

done

sleep ${LEDGER_DELAY}
java -jar daml-on-fabric.jar --port ${LEDGER_PORT} ${LEDGER_AUTH} --role ${LEDGER_ROLES} ${LEDGER_ARGS}


