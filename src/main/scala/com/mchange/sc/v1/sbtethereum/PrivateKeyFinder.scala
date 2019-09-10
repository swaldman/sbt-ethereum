package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthHash,EthPrivateKey,EthSignature,EthSigner}

/*
 * Instances should not store the private key, but
 * construct it interactively from safe antecedents
 */ 
private class PrivateKeyFinder( val address : EthAddress, findOp : () => EthPrivateKey ) {
  def find() = findOp().ensuring( privateKey => address == privateKey.address )

  def asSigner() : EthSigner = new EthSigner {
    def sign( document : Array[Byte] ) : EthSignature.Basic = findOp().sign( document )
    def sign( document : Seq[Byte] )   : EthSignature.Basic = findOp().sign( document )

    def signPrehashed( documentHash : EthHash ) : EthSignature.Basic = findOp().signPrehashed( documentHash )

    def address = PrivateKeyFinder.this.address
  }
}

