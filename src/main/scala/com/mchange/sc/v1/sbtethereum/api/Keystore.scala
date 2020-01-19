package com.mchange.sc.v1.sbtethereum.api

import com.mchange.sc.v1.sbtethereum.{syncOut,SbtEthereumPlugin}
import com.mchange.sc.v1.sbtethereum.SbtEthereumPlugin.autoImport._

import com.mchange.sc.v1.sbtethereum.util.Formatting.hexString

import com.mchange.sc.v1.consuela.ethereum.{wallet,EthPrivateKey}

object Keystore {

  def addWalletV3WithInteractiveAlias( state : sbt.State, log : sbt.Logger, is : sbt.InteractionService, privateKey : EthPrivateKey, chainId : Int ) : Unit = {
    syncOut {
      println( s"Adding private key for address '${hexString(privateKey.address)}' to the sbt-ethereum shoebox keystore." )
      val passphrase = Interaction.readConfirmCredential( is, "Please enter a passphrase: " )
      addWalletV3( state, log, privateKey, passphrase, None )
      SbtEthereumPlugin.interactiveSetAliasForAddress( chainId )( state, log, is, "new wallet address '${hexString(privateKey.address)}'", privateKey.address )
    }
  }

  def addWalletV3( state : sbt.State, log : sbt.Logger, is : sbt.InteractionService, privateKey : EthPrivateKey, mbAlias : Option[Tuple2[Int,String]] ) : Unit = {
    syncOut {
      println( s"Adding private key for address '${hexString(privateKey.address)}' to the sbt-ethereum shoebox keystore." )
      val passphrase = Interaction.readConfirmCredential( is, "Please enter a passphrase: " )
      addWalletV3( state, log, privateKey, passphrase, mbAlias )
    }
  }

  def addWalletV3( state : sbt.State, log : sbt.Logger, privateKey : EthPrivateKey, passphrase : String, mbAlias : Option[Tuple2[Int,String]] ) : Unit = {
    val extracted : sbt.Extracted = sbt.Project.extract(state)
    import extracted._

    // this is still mysterious to me
    // see https://www.scala-sbt.org/1.x/docs/Build-State.html

    val n     = (xethcfgWalletV3ScryptN in currentRef get structure.data).get
    val r     = (xethcfgWalletV3ScryptR in currentRef get structure.data).get
    val p     = (xethcfgWalletV3ScryptP in currentRef get structure.data).get
    val dklen = (xethcfgWalletV3ScryptDkLen in currentRef get structure.data).get

    val entropySource = (ethcfgEntropySource in currentRef get structure.data).get

    log.info( s"Generating V3 wallet, algorithm=scrypt, n=${n}, r=${r}, p=${p}, dklen=${dklen}" )
    
    val w = wallet.V3.generateScrypt( passphrase = passphrase, n = n, r = r, p = p, dklen = dklen, privateKey = Some( privateKey ), random = entropySource )

    addWalletV3( log, w, mbAlias )
  }
  def addWalletV3( log : sbt.Logger,  w : wallet.V3, mbAlias : Option[Tuple2[Int,String]] ) : Unit = {
    SbtEthereumPlugin.activeShoebox.keystore.V3.storeWallet( w ).assert
    val address = w.address
    mbAlias.foreach { case ( chainId, alias ) =>
      val mbOld = SbtEthereumPlugin.activeShoebox.addressAliasManager.findAddressByAddressAlias( chainId, alias ).assert
      mbOld match {
        case Some( `address` )    => /* ignore, nothing to be done */
        case Some( otherAddress ) => {
          log.warn(s"Proposed alias '${alias}' on chain with ID ${chainId} already points to '${hexString(otherAddress)}'.")
          log.warn(s"You'll have to manually reset it to point to '${hexString(address)}' if you wish.")
        }
        case None => {
          SbtEthereumPlugin.activeShoebox.addressAliasManager.insertAddressAlias( chainId, alias, address ).assert
        }
      }
    }
  }
}
