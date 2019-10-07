package com.mchange.sc.v1.sbtethereum.util

import InteractiveQuery.readCredential
import Formatting._

import com.mchange.sc.v1.sbtethereum.BadCredentialException

import com.mchange.sc.v1.consuela.ethereum.{wallet, EthAddress, EthPrivateKey}

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v3.failable._

import scala.collection._

import scala.util.control.NonFatal

private [sbtethereum] object PrivateKey {

  private implicit lazy val logger = mlogger( this )

  private [sbtethereum]
  def findRawPrivateKey( log : sbt.Logger, is : sbt.InteractionService, address : EthAddress, gethWallets : immutable.Set[wallet.V3] ) : EthPrivateKey = {
    val credential = readCredential( is, address )
    findPrivateKey( log, address, gethWallets, credential )
  }

  private [sbtethereum]
  def findPrivateKey( log : sbt.Logger, address : EthAddress, gethWallets : immutable.Set[wallet.V3], credential : String ) : EthPrivateKey = {
    def forceKey = {
      try {
        val out = EthPrivateKey( credential )
        require( out.address == address, "The hex private key provided does not match desired address ${hexString(address)}." )
        log.info( s"Successfully interpreted the credential supplied as hex private key for '${hexString(address)}'." )
        out
      }
      catch {
        case NonFatal(e) => {
          WARNING.log( s"Converting an Exception that occurred while trying to interpret a credential as hex into a BadCredentialException.", e )
          throw new BadCredentialException(address)
        }
      }
    }

    if ( gethWallets.isEmpty ) {
      log.info( "No wallet available. Trying passphrase as hex private key." )
      forceKey
    }
    else {
      def tryWallet( gethWallet : wallet.V3 ) : Failable[EthPrivateKey] = Failable {
        val walletAddress = gethWallet.address
        try {
          assert( walletAddress == address, s"We should only have pulled wallets for our desired address '${hexString(address)}', but found a wallet for '${hexString(walletAddress)}'." )
          wallet.V3.decodePrivateKey( gethWallet, credential )
        } catch {
          case v3e : wallet.V3.Exception => {
            val maybe = {
              try {
                forceKey
              }
              catch {
                case bce : BadCredentialException => {
                  bce.initCause( v3e )
                  throw bce
                }
              }
            }
            if (maybe.toPublicKey.toAddress != walletAddress) {
              throw new BadCredentialException( walletAddress )
            } else {
              log.info("Successfully interpreted the credential supplied as a hex private key for address '${address}'.")
              maybe
            }
          }
        }
      }
      val lazyAllAttempts = gethWallets.toStream.map( tryWallet )
      val mbGood = lazyAllAttempts.find( _.isSucceeded )
      mbGood match {
        case Some( succeeded ) => succeeded.assert
        case None              => {
          log.warn( "Tried and failed to decode a private key from multiple wallets." )
          lazyAllAttempts.foreach { underachiever =>
            log.warn( s"Failed to decode private key from wallet: ${underachiever.assertFailed.message}" )
          }
          throw new BadCredentialException()
        }
      }
    }
  }
}
