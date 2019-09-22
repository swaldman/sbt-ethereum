package com.mchange.sc.v1.sbtethereum.signers

import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthHash,EthPrivateKey,EthSignature,EthSigner}

/*
 * Instances should not store the inner signer, but
 * construct it interactively from safer antecedents
 */ 
private [sbtethereum] class LazySigner( val address : EthAddress, findOp : () => EthSigner ) extends EthSigner {
  def signWithoutHashing( bytesToSign : Array[Byte] ) : EthSignature.Basic = {
    findOp().ensuring( _.address == this.address ).signWithoutHashing( bytesToSign )
  }
}
