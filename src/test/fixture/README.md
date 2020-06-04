
[![CircleCI](https://circleci.com/gh/digital-asset/daml-on-fabric.svg?style=svg&circle-token=a00ee8cfc2d112608361c5698f62fdbf208aea30)](https://circleci.com/gh/digital-asset/daml-on-fabric) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/digital-asset/daml-on-fabric/blob/master/LICENSE)


# DAML on Fabric technical README

We have seen how to launch a pre-defined Hyperledger Fabric Network with DAML. 
This README will be for technical staff that wants to customize their installation.

# ./config-local.yaml

Here in config-local.yaml we can customize and parameterize the network, channel, chaincode and ledgerID for DAML

## Properties

  * **organizations**: encapsulates the data about the components that compose each organization of the network.
    * **name**: name of the organization
    * **mspId**: Id of organization in the Membership Service Provider
    * **adminMsp**: base folder where the certificates of the client user are stored
    * **hlfTlsCertFile**: TLS certificate of the organization. It is used to communicate with peers and orderers through the client SDK
    * **hlfClientUser**: Fabric client user
    * **hlfClientKeyFile**: Fabric client key file
    * **hlfClientCertFile**: Fabric client certificate file
    * **hlfClientAuth**: Fabric client requires authentication (boolean, set to true)
    * **peers**: list of peers of the organization
        * **name**: name of the peer
        * **url**: core peer address
    * **orderers**: list of orderers of the organization
        * **name**: name of orderer
        * **url**: orderer address
  
  * **channel**: configuration of the channel where the DAML-on-Fabric chaincode is going to run. A channel is a communication link between multiple organizations. It has a common ledger available to all participants through their peers.
    * **name**: name of the channel
    * **channelTxFile**: channel configuration file. It will help if you use it when you want to configure your local instance. When connecting to an existing network, this attribute should be empty.
    * **endorsementPolicy**: endorsement policy of the channel. It will help if you use it when you want to configure your local instance. When connecting to an existing network, this attribute should be empty.
    * **chaincode**: information about chaincode
        * **name**: name of chaincode
        * **type**: (golang|java|nodejs) the programming language used in the chaincode. Right now, this project only support the default chaincode built-in golang
        * **version**: version of the chaincode
        * **gopath**: location of the chaincode folder shared in this project.  When connecting to an existing network, this attribute should be empty
        * **metapath**: location of the META-INF folder of the chaincode.  When connecting to an existing network, this attribute should be empty
        * **entryPath**: location of entry path of the chaincode
        * **queryWaitTime**: time that the Fabric SDK will wait for a query in the chaincode
        * **invokeWaitTime**: time that the Fabric SDK will wait for invoking a chaincode function
  
  * **explorer**: port where the custom fabric explorer will run (it provides information about transactions and blocks)
    * **port**: port where the explorer will run
  
  * **ledgerId**: id used by DAML ledger API to synchronize with the network ledger


# ./src/test/fixture/peer-base/

On this folder there is the base peer definition that is launched when the ./restart_fabric.sh script is ran 
This can be customized accordingly to the project needs.
This base peer is used in docker-compose.yaml file.

# ./chaincode/

This is where the DAML on Fabric chaincode has it's code and all it's dependencies

# ./src/test/fixture/data/

The data folder has some jwt token examples and certificates that can be used when running Ledger API with authentication enabled
There are also some network and channel configuration files for Hyperledger Fabric

# ./src/test/fixture/tmp/

This folder is generated when launching the application. It will be copied to the docker containers that run the network.
It contains all of the crypto-config certificates and keys, the genesis block and the .tx file for the fabric channel


# How to add CouchDB to the network

1. Add couchDB container per peer
```   
   peer0.org1.example.com-CouchDB:
     container_name: peer0.org1.example.com-CouchDB
     logging:
       options:
         max-file: "5"
         max-size: "50m"
     image: hyperledger/fabric-couchdb:0.4.20
     environment:
       - COUCHDB_USER=peer0.org1.example.com
       - COUCHDB_PASSWORD=peer0.org1.example.com:peer0.org1.example.compw
     ports:
       - 5984:5984
```

2. Add the following env variables to peer0.org1.example.com container

```
- CORE_LEDGER_STATE_STATEDATABASE=CouchDB
- CORE_LEDGER_STATE_COUCHDBCONFIG_COUCHDBADDRESS=peer0.org1.example.com-CouchDB:5984
- CORE_LEDGER_STATE_COUCHDBCONFIG_USERNAME=peer0.org1.example.com
- CORE_LEDGER_STATE_COUCHDBCONFIG_PASSWORD=peer0.org1.example.com:peer0.org1.example.compw 
```

If there is a need to create CouchDB indexes, continue to the next step.

3. Change chaincode metapath on config-local.yaml file

 ``` 
  chaincode:
    metapath: chaincode/src/github.com/digital-asset/daml-on-fabric/META-INF
```
  
4. Add index by creating the following folders and add the following file:
    * Create the following folder structure inside the chaincode folder: 
      ```
      META-INF/statedb/couchdb/indexes/
      ```
    * Create a file with the following content and named indexState:
      ```
        {"index":{"fields":["DState:"]},"ddoc":"indexOwnerDoc", "name":"indexState","type":"json"}
      ```
