package com.mchange.sc.v1.sbtethereum.signers

import sbt._

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import scala.collection._
import scala.util.control.NonFatal
import scala.concurrent.duration._

import com.mchange.sc.v1.consuela.ethereum._

import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.sbtethereum.{BadCredentialException,PriceFeed,util}
import util.Formatting._
import util.InteractiveQuery._
import util.WalletsV3._

import com.mchange.sc.v2.concurrent.Scheduler

private [sbtethereum] object SignersManager {
  private trait AddressInfo
  private final case object NoAddress                                                                                                 extends AddressInfo
  private final case class  UnlockedAddress( chainId : Int, address : EthAddress, privateKey : EthPrivateKey, autoRelockTime : Long ) extends AddressInfo

  private implicit lazy val logger = mlogger( this )
}
private [sbtethereum] class SignersManager( scheduler : Scheduler, keystoresV3 : Seq[File], publicTestAddresses : immutable.Map[EthAddress,EthPrivateKey], abiOverridesForChain : Int => immutable.Map[EthAddress,jsonrpc.Abi] ) {
  import SignersManager._

  // MT: protected by CurrentAddress' lock
  private val CurrentAddress = new AtomicReference[AddressInfo]( NoAddress )

  private [sbtethereum]
  def reset() : Unit = {
    CurrentAddress.synchronized {
      CurrentAddress.set( NoAddress )
    }
  }

  private [sbtethereum]
  def findUpdateCacheLazySigner(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int,
    address              : EthAddress,
    autoRelockSeconds    : Int,
    userValidateIfCached : Boolean
  ) : LazySigner = {
    new LazySigner( address, () => findUpdateCachePrivateKey(state, log, is, chainId, address, autoRelockSeconds, userValidateIfCached ) )
  }

  private [sbtethereum]
  def findUpdateCacheCautiousSigner(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int, // for alias display only
    address              : EthAddress,
    priceFeed            : PriceFeed,
    currencyCode         : String,
    description          : Option[String],
    autoRelockSeconds    : Int
  ) : CautiousSigner = {
    new CautiousSigner( log, is, priceFeed, currencyCode, description )( findUpdateCacheLazySigner(state,log,is,chainId,address,autoRelockSeconds,userValidateIfCached = true /* Cautious */), abiOverridesForChain )
  }

  private [sbtethereum]
  def findCheckCacheCautiousSigner(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int, // for alias display only
    address              : EthAddress,
    priceFeed            : PriceFeed,
    currencyCode         : String,
    description          : Option[String]
  ) : CautiousSigner = {
    new CautiousSigner( log, is, priceFeed, currencyCode, description )( findCheckCacheLazySigner(state,log,is,chainId,address), abiOverridesForChain )
  }

  private [sbtethereum]
  def findRawPrivateKey( log : sbt.Logger, is : sbt.InteractionService, address : EthAddress, gethWallets : immutable.Set[wallet.V3] ) : EthPrivateKey = {
    val credential = readCredential( is, address )
    findPrivateKey( log, address, gethWallets, credential )
  }

  private def findPrivateKey( log : sbt.Logger, address : EthAddress, gethWallets : immutable.Set[wallet.V3], credential : String ) : EthPrivateKey = {
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

  private def findNoCachePrivateKey(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int, // for alias display only
    address              : EthAddress
  ) : EthPrivateKey = {
    // this is ugly and awkward, but it gives time for any log messages to get emitted before prompting for a credential
    // it also slows down automated attempts to guess passwords, i guess...
    Thread.sleep(1000)

    val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "" )( _.fold("")( commasep => s", aliases $commasep" ) )

    log.info( s"Unlocking address '0x${address.hex}' (on chain with ID ${chainId}$aliasesPart)" )

    val credential = readCredential( is, address )

    val wallets = walletsForAddress( address, keystoresV3 )

    findPrivateKey( log, address, wallets, credential )
  }

  private def findCheckCachePrivateKey(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int, // for alias display only
    address              : EthAddress
  ) : EthPrivateKey = {
    checkForCachedPrivateKey( is, chainId, address, userValidateIfCached = true, resetOnFailure = false ) getOrElse findNoCachePrivateKey( state, log, is, chainId, address )
  }

  private def findCheckCacheLazySigner(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int,
    address              : EthAddress
  ) : LazySigner = {
    new LazySigner( address, () => findCheckCachePrivateKey(state, log, is, chainId, address ) )
  }

  private val RelockMarginSeconds = 5

  private val CheckRelockPrivateKeyTask : () => Unit = {
    () => {
      CurrentAddress.synchronized {
        val now = System.currentTimeMillis
        CurrentAddress.get match {
          case UnlockedAddress( _, address, _, autoRelockTime ) if (now >= autoRelockTime ) => {
            CurrentAddress.set( NoAddress )
            DEBUG.log( s"Relocked private key for address '${address}' (expired '${formatInstant(autoRelockTime)}', checked '${formatInstant(now)}')" )
          }
          case _ => /* ignore */
        }
      }
    }
  }

  private def checkForCachedPrivateKey( is : sbt.InteractionService, chainId : Int, address : EthAddress, userValidateIfCached : Boolean = true, resetOnFailure : Boolean = true) : Option[EthPrivateKey] = {
    CurrentAddress.synchronized {
      // caps for value matches rather than variable names
      val ChainId = chainId
      val Address = address
      val now = System.currentTimeMillis
      CurrentAddress.get match {
        // if chainId and/or ethcfgAddressSender has changed, this will no longer match
        // note that we never deliver an expired, cached key even if we have one
        case UnlockedAddress( ChainId, Address, privateKey, autoRelockTime ) if (now < autoRelockTime ) => {
          val aliasesPart = commaSepAliasesForAddress( ChainId, Address ).fold( _ => "")( _.fold("")( commasep => s", aliases $commasep" ) )
          val ok = {
            if ( userValidateIfCached ) {
              is.readLine( s"Using sender address ${verboseAddress( chainId, address)}, which is already unlocked. OK? [y/n] ", false ).getOrElse( throwCantReadInteraction ).trim().equalsIgnoreCase("y")
            } else {
              true
            }
          }
          if ( ok ) {
            Some( privateKey )
          } else {
            if ( resetOnFailure ) CurrentAddress.set( NoAddress )
            aborted( s"Use of sender address ${verboseAddress( chainId, address)} vetoed by user." )
          }
        }
        case UnlockedAddress( _, _, _, autoRelockTime ) if (now >= autoRelockTime ) => {
          // we always reset expired keys when we see them
          CurrentAddress.set( NoAddress )
          None
        }
        case _ => {
          // otherwise, we honor the resetOnFailure argument
          if ( resetOnFailure ) CurrentAddress.set( NoAddress )
          None
        }
      }
    }
  }

  /*
   *  generally we only try to update the cache if the private key
   *  we are looking up is the current session's chainId and sender
   */ 
  private def findUpdateCachePrivateKey(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int,
    address              : EthAddress,
    autoRelockSeconds    : Int,
    userValidateIfCached : Boolean
  ) : EthPrivateKey = {

    def recache( privateKey : EthPrivateKey ) = {
      if ( autoRelockSeconds > 0 ) {
        CurrentAddress.set( UnlockedAddress( chainId, address, privateKey, System.currentTimeMillis + (autoRelockSeconds * 1000) ) )
        scheduler.schedule( CheckRelockPrivateKeyTask, (autoRelockSeconds + RelockMarginSeconds).seconds )
      }
      else {
        CurrentAddress.set( NoAddress )
      }
    }

    def updateCached : EthPrivateKey = {
      val privateKey = findNoCachePrivateKey( state, log, is, chainId, address )
      recache( privateKey )
      privateKey
    }
    def goodCached : Option[EthPrivateKey] = checkForCachedPrivateKey( is, chainId, address, userValidateIfCached = userValidateIfCached, resetOnFailure = true )

    def realFindUpdateCache : EthPrivateKey = {
      CurrentAddress.synchronized {
        goodCached match {
          case Some( privateKey ) => {
            recache( privateKey )
            privateKey
          }
          case None => updateCached
        }
      }
    }

    // handle special cases for testing, then actually lookup and recache...
    publicTestAddresses.get( address ) getOrElse realFindUpdateCache
  }
}

