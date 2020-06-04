#!/bin/bash
# Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

################################################################################

./fabric.sh down
./fabric.sh clean
rm ./client/hfc-key-store/*
./fabric.sh up

