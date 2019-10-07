package com.mchange.sc.v1.sbtethereum.mutables

import com.mchange.sc.v1.sbtethereum.{compile,signer,util,PriceFeed}
import compile.Compiler
import signer._
import util.warner._
import util.ChainIdMutable

import com.mchange.sc.v1.consuela.ethereum.{jsonrpc, EthAddress, EthPrivateKey}

import com.mchange.sc.v2.concurrent.Scheduler

import scala.collection._

import scala.sys.process.Process

import java.io.File

import java.util.concurrent.atomic.AtomicReference

private [sbtethereum] final class Mutables (
  scheduler            : Scheduler,
  keystoresV3          : immutable.Seq[File],
  publicTestAddresses  : immutable.Map[EthAddress,EthPrivateKey],
  maxUnlockedAddresses : Int
) {
  // MT: internally thread-safe
  private val MainSignersManager = new SignersManager( scheduler, keystoresV3, publicTestAddresses, this.abiOverridesForChain, maxUnlockedAddresses )

  // MT: protected by SessionSolidityCompilers' lock
  private val SessionSolidityCompilers = new AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]]( None )

  // MT: protected by CurrentSolidityCompiler's lock
  private val CurrentSolidityCompiler = new AtomicReference[Option[( String, Compiler.Solidity )]]( None )

  // MT: protected by ChainIdOverride' lock
  private val ChainIdOverride = new AtomicReference[Option[Int]]( None ) // Only supported for Compile config

  // MT: internally thread-safe
  val SenderOverrides = new ChainIdMutable[EthAddress]

  // MT: internally thread-safe
  val NodeUrlOverrides = new ChainIdMutable[String]

  // MT: internally thread-safe
  private val AbiOverrides = new ChainIdMutable[immutable.Map[EthAddress,jsonrpc.Abi]]

  // MT: internally thread-safe
  val GasLimitTweakOverrides = new ChainIdMutable[jsonrpc.Invoker.MarkupOrOverride]

  // MT: internally thread-safe
  val GasPriceTweakOverrides = new ChainIdMutable[jsonrpc.Invoker.MarkupOrOverride]

  // MT: internally thread-safe
  val NonceOverrides = new ChainIdMutable[BigInt]

  // MT: internally thread-safe
  val OneTimeWarner = new OneTimeWarner[OneTimeWarnerKey]

  // MT: protected by LocalGanache's lock
  private val LocalGanache = new AtomicReference[Option[Process]]( None )

  // API to make synchronized access to simple holders available
  private def _with[T <: AnyRef,U]( t : T )( op : T => U ) : U = t.synchronized( op(t) )

  private [sbtethereum] def withSessionSolidityCompilers[U]( op : AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]] => U) : U = _with( SessionSolidityCompilers )( op )  
  private [sbtethereum] def withCurrentSolidityCompiler[U]( op : AtomicReference[Option[( String, Compiler.Solidity )]] => U) : U            = _with( CurrentSolidityCompiler )( op )  
  private [sbtethereum] def withCompileConfigChainIdOverride[U]( op : AtomicReference[Option[Int]] => U) : U                                 = _with( ChainIdOverride )( op )
  private [sbtethereum] def withLocalGanache[U]( op : AtomicReference[Option[Process]] => U) : U                                             = _with( LocalGanache )( op )

  // API to warn all overrides for a chainId (usually the current Session's chain in configuration Compile )
  private [sbtethereum]
  def logWarnOverrides( log : sbt.Logger, chainId : Int ) = {
    import util.Formatting._

    SenderOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: The sender has been overridden to ${verboseAddress( chainId, ovr )}.") }
    NodeUrlOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: The node URL has been overridden to '${ovr}'.") }
    AbiOverrides.get( chainId ).foreach { ovr => log.warn( s"""NOTE: ABI overrides are set for the following addresses on this chain: ${ovr.keys.map(hexString).mkString(", ")}""" ) }
    GasLimitTweakOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: A gas limit override remains set for this chain, ${formatGasLimitTweak( ovr )}." ) }
    GasPriceTweakOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: A gas price override remains set for this chain, ${formatGasPriceTweak( ovr )}." ) }
    NonceOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: A nonce override remains set for this chain. Its value is ${ovr}." ) }
  }

  // API for managing ABI overrides
  private [sbtethereum]
  def abiOverridesForChain( chainId : Int ) : immutable.Map[EthAddress,jsonrpc.Abi] = {
    AbiOverrides.get( chainId ).getOrElse( immutable.Map.empty[EthAddress,jsonrpc.Abi] )
  }

  private [sbtethereum]
  def addAbiOverrideForChain( chainId : Int, address : EthAddress, abi : jsonrpc.Abi ) : Unit = {
    AbiOverrides.modify( chainId ) { pre =>
      Some( pre.getOrElse( immutable.Map.empty[EthAddress,jsonrpc.Abi] ) + Tuple2( address, abi ) )
    }
  }

  private [sbtethereum]
  def removeAbiOverrideForChain( chainId : Int, address : EthAddress ) : Boolean = {
    val modified = {
      AbiOverrides.modify( chainId ) { pre =>
        pre match {
          case Some( mapping ) => Some(mapping - address).filter( _.nonEmpty )
          case None            => None
        }
      }
    }
    modified.pre != modified.post
  }

  private [sbtethereum]
  def clearAbiOverrideForChain( chainId : Int ) : Boolean = {
    AbiOverrides.getDrop( chainId ) != None
  }

  // API for managing signers and private keys
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
    MainSignersManager.findUpdateCacheLazySigner(state, log, is, chainId, address, autoRelockSeconds, userValidateIfCached )
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
    MainSignersManager.findUpdateCacheCautiousSigner(state, log, is, chainId, address, priceFeed, currencyCode, description, autoRelockSeconds )
  }

  private [sbtethereum]
  def reset() : Unit = {
    MainSignersManager.reset()
    withSessionSolidityCompilers( _.set( None ) )
    withCurrentSolidityCompiler( _.set( None ) )
    withCompileConfigChainIdOverride( _.set( None ) )
    SenderOverrides.reset()
    NodeUrlOverrides.reset()
    AbiOverrides.reset()
    GasLimitTweakOverrides.reset()
    GasPriceTweakOverrides.reset()
    NonceOverrides.reset()
    OneTimeWarner.resetAll()
    withLocalGanache( _.set( None ) )
  }
}

