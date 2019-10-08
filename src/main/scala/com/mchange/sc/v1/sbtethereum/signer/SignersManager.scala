package com.mchange.sc.v1.sbtethereum.signer

import sbt._

import java.io.File

import scala.collection._
import scala.util.control.NonFatal
import scala.concurrent.duration._

import com.mchange.sc.v1.consuela.ethereum._

import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.sbtethereum.{PriceFeed,util,syncOut}
import util.Formatting._
import util.InteractiveQuery._
import util.PrivateKey._
import util.WalletsV3._

import com.mchange.sc.v2.concurrent.Scheduler

private [sbtethereum] object SignersManager {
  private [SignersManager] final case class UnlockedAddress( address : EthAddress, privateKey : EthPrivateKey, autoRelockTimeMillis : Long )

  private implicit lazy val logger = mlogger( this )
}
private [sbtethereum] class SignersManager(
  scheduler : Scheduler, // careful with the scheduler, which will embed references to the internal state
  keystoresV3 : immutable.Seq[File],
  publicTestAddresses : immutable.Map[EthAddress,EthPrivateKey],
  abiOverridesForChain : Int => immutable.Map[EthAddress,jsonrpc.Abi],
  maxUnlocked : Int
) {
  import SignersManager._

  TRACE.log( s"Initializing SignersManager [${this}]" )

  private val RelockMarginSeconds = 5

  object State {

    // MT: protected by State's lock
    private val currentAddresses = mutable.Map.empty[EthAddress,UnlockedAddress]
    private val addressesByRelockTime = mutable.SortedMap.empty[Long,UnlockedAddress] // we ensure unlock times are unique!

    private [SignersManager]
    def checkWithoutValidating( address : EthAddress ) : Option[UnlockedAddress] = State.synchronized {
      val mbua = currentAddresses.get( address )
      mbua.foreach( ua => assertConsistent( address, ua ) )
      mbua
    }

    private [SignersManager]
    def reunlock( address : EthAddress, privateKey : EthPrivateKey, untilMillis : Long ) : UnlockedAddress = {
      val ( expiryMillis, out ) = State.synchronized {
        var safeUntil = untilMillis
        while (addressesByRelockTime.contains( safeUntil )) safeUntil -= 1 // if we have to be inexact (extremely rare) err on the side of going shorter
        val newUnlockedAddressMapsClear = currentAddresses.get( address ) match {
          case Some( ua ) => {
            assertConsistent( address, ua )
            currentAddresses -= address
            addressesByRelockTime -= ua.autoRelockTimeMillis
            DEBUG.log( s"Validity of unlocked address '${hexString(address)}' will be extended to ${formatInstant(safeUntil)}" )
            ua.copy( autoRelockTimeMillis = safeUntil )
          }
          case None => {
            DEBUG.log( s"Address '${hexString(address)}' will be unlocked until ${formatInstant(safeUntil)}" )
            UnlockedAddress( address, privateKey, safeUntil )
          }
        }
        currentAddresses += Tuple2( address, newUnlockedAddressMapsClear )
        addressesByRelockTime += Tuple2( safeUntil, newUnlockedAddressMapsClear )
        while ( currentAddresses.size > maxUnlocked ) removeNext()
        Tuple2( safeUntil, newUnlockedAddressMapsClear )
      }
      scheduler.schedule( () => checkExpiry( address ), ((expiryMillis - System.currentTimeMillis) + (RelockMarginSeconds * 1000)).milliseconds )
      out
    }

    private [SignersManager]
    def remove( address : EthAddress ) : Unit = State.synchronized {
      TRACE.log( s"remove( ${hexString(address)} )" )
      currentAddresses.get( address ).foreach { ua =>
        assertConsistent( address, ua )
        currentAddresses -= address
        addressesByRelockTime -= ua.autoRelockTimeMillis
      }
    }

    private [SignersManager]
    def clear() : Unit = State.synchronized {
      DEBUG.log( s"Resetting SignersManager [${SignersManager.this}]" )
      currentAddresses.clear()
      addressesByRelockTime.clear()
    }

    // should only be called while holding State's lock
    private def assertConsistent( address : EthAddress, ua : UnlockedAddress ) = {
      assert( currentAddresses.size == addressesByRelockTime.size )
      assert( address == ua.address )
      assert( addressesByRelockTime.get( ua.autoRelockTimeMillis ) == Some( ua ) )
    }

    // should only be called while holding State's lock
    private def assertConsistent( untilMillis : Long, ua : UnlockedAddress ) = {
      assert( currentAddresses.size == addressesByRelockTime.size )
      assert( untilMillis == ua.autoRelockTimeMillis )
      assert( addressesByRelockTime.get( ua.autoRelockTimeMillis ) == Some( ua ) )
    }

    private def removeNext() : Unit = State.synchronized {
      TRACE.log( "removeNext()" )
      val ( untilMillis, ua ) = addressesByRelockTime.head
      assertConsistent( untilMillis, ua )
      currentAddresses -= ua.address
      addressesByRelockTime -= untilMillis
    }

    private def checkExpiry( address : EthAddress ) : Unit = State.synchronized {
      TRACE.log( s"checkExpiry( ${hexString(address)} )" )
      currentAddresses.get( address ) match {
        case Some( ua ) => {
          if ( System.currentTimeMillis() > ua.autoRelockTimeMillis ) {
            remove( address )
            DEBUG.log( s"Unlocked address '0x${hexString(address)}' expired at ${formatInstant(ua.autoRelockTimeMillis)}. Relocked." )
          }
          else {
            DEBUG.log( s"Unlocked address '0x${hexString(address)}' checked and is still valid, expires at ${formatInstant(ua.autoRelockTimeMillis)}." )
          }
        }
        case None => {
          DEBUG.log( s"Address '0x${hexString(address)}' not unlocked. No need to verify expiry." )
        }
      }
    }

  }

  private [sbtethereum]
  def reset() : Unit = State.clear()

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

  private // for now, we're not exporting this. there's little reason to use it, since we now cache multiple addresses
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

    val credential = syncOut( newLineAfter = true )( readCredential( is, address ) )

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
    checkForCachedPrivateKey( is, chainId, address, userValidateIfCached = true, resetOnVeto = false ) getOrElse findNoCachePrivateKey( state, log, is, chainId, address )
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

  private def checkForCachedPrivateKey( is : sbt.InteractionService, chainId : Int, address : EthAddress, userValidateIfCached : Boolean = true, resetOnVeto : Boolean = true) : Option[EthPrivateKey] = State.synchronized {
    // caps for value matches rather than variable names
    val Address = address
    val now = System.currentTimeMillis
    State.checkWithoutValidating( address ) match {
      // if chainId and/or ethcfgAddressSender has changed, this will no longer match
      // note that we never deliver an expired, cached key even if we have one
      case Some( UnlockedAddress( Address, privateKey, autoRelockTimeMillis ) ) if (now < autoRelockTimeMillis ) => {
        val ok = {
          if ( userValidateIfCached ) {
            syncOut( newLineAfter = true )( queryYN( is, s"Using sender address ${verboseAddress( chainId, address )}, which is already unlocked. OK? [y/n] " ) )
          } else {
            true
          }
        }
        if ( ok ) {
          Some( privateKey )
        }
        else {
          if ( resetOnVeto ) {
            DEBUG.log( s"Reuse of unlocked address vetoed, and unlockOnVeto set to true. Relocking '${hexString(address)}'." )
            State.remove( address )
          }
          aborted( s"Use of sender address ${verboseAddress( chainId, address)} vetoed by user." )
        }
      }
      case Some( UnlockedAddress( Address, _, autoRelockTimeMillis ) ) if (now >= autoRelockTimeMillis ) => {
        // we always reset expired keys when we see them
        State.remove( address )
        None
      }
      case Some( ua ) => {
        throw new AssertionError( s"Unexpected address, looking for ${hexString(address)} found ${hexString(ua.address)} (UnlockedAddress: ${ua})." )
      }
      case None => {
        None
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
  ) : EthPrivateKey = State.synchronized {

    def recache( privateKey : EthPrivateKey ) = {
      if ( maxUnlocked > 0 && autoRelockSeconds > 0 ) {
        assert( address == privateKey.address )
        State.reunlock( address, privateKey, System.currentTimeMillis + (autoRelockSeconds * 1000) )
      }
      else {
        State.remove( address )
      }
    }

    def updateCached : EthPrivateKey = {
      val privateKey = findNoCachePrivateKey( state, log, is, chainId, address )
      assert( address == privateKey.address )
      recache( privateKey )
      privateKey
    }
    def goodCached : Option[EthPrivateKey] = checkForCachedPrivateKey( is, chainId, address, userValidateIfCached = userValidateIfCached, resetOnVeto = true )

    def realFindUpdateCache : EthPrivateKey = {
      goodCached match {
        case Some( privateKey ) => {
          recache( privateKey )
          privateKey
        }
        case None => updateCached
      }
    }

    // handle special cases for testing, then actually lookup and recache...
    publicTestAddresses.get( address ) getOrElse realFindUpdateCache
  }
}

