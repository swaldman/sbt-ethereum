package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthHash,EthPrivateKey,EthSignature,EthSigner}

/*
 * Instances should not store the private key, but
 * construct it interactively from safe antecedents
 */ 
private class PrivateKeyFinder( val address : EthAddress, findOp : () => EthPrivateKey ) {

  /** @deprecated use asSigner() */
  def find() = findOp().ensuring( privateKey => address == privateKey.address )

  def asSigner() : EthSigner = new EthSigner {
    def signWithoutHashing( bytesToSign : Array[Byte] ) : EthSignature.Basic = findOp().signWithoutHashing( bytesToSign )
    def address = PrivateKeyFinder.this.address
  }
}

