#!/bin/bash
# Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

################################################################################

set -e

mkdir -p tmp
cd tmp
mkdir -p data
rm -rf ./data/crypto-config/

#this variable is needed for configtxgen to read our configtx.yaml configuration file
export FABRIC_CFG_PATH=$(pwd)/../data

echo "Generating crypto certs"
cryptogen generate --config=../data/crypto-config.yaml --output ./data/crypto-config/

echo "Generating genesis.block"
configtxgen -profile FiveOrgsOrdererGenesis -outputBlock ./data/genesis.block -channelID testchainid

echo "Generating channel.tx"
configtxgen -profile FiveOrgsChannel -outputCreateChannelTx ./data/mainchannel.tx -channelID mainchannel

cp ../data/endorsement-policy.yaml ./data