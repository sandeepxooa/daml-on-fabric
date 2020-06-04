#!/usr/bin/env bash
# Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0


JWKS_TOKEN=$(curl -d '{"claims": {"ledgerId": "fabric-ledger", "participantId": "fabric-standalone-participant", "applicationId": null,"admin": true, "actAs": null,  "readAs": null },  "exp": 1900819380}' -X POST -H "Content-Type: application/json" jwt-provider:8088)
COMPLETE_TOKEN="Bearer $JWKS_TOKEN"
echo ${COMPLETE_TOKEN}

echo ${COMPLETE_TOKEN} > ./data/ledger_tokens/jwks_jwt_token
