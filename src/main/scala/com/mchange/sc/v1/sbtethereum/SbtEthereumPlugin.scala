package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.Def.Initialize

import sjsonnew._
import BasicJsonProtocol._

import compile.{Compiler, ResolveCompileSolidity, SemanticVersion, SolcJInstaller, SourceFile}

import util.BaseCodeAndSuffix
import util.OneTimeWarner
import util.ChainIdMutable
import util.Erc20
import util.EthJsonRpc._
import util.Parsers._
import util.SJsonNewFormats._
import util.Abi._
import util.InteractiveQuery._
import util.Spawn._
import util.ClientTransactionReceipt._
import util.Formatting._
import util.WalletsV3._
import signers._
import generated._

import java.io.{BufferedInputStream, File, FileInputStream, FilenameFilter}
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import play.api.libs.json.{JsObject, Json}
import com.mchange.sc.v1.etherscan
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.io._
import com.mchange.sc.v2.util.Platform
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.log.MLogger
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.io._
import com.mchange.sc.v1.consuela.ethereum._
import jsonrpc.{Abi,Client,MapStringCompilationContractFormat}
import specification.Denominations
import com.mchange.sc.v1.consuela.ethereum.specification.Types.{Unsigned8,Unsigned256}
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations._
import com.mchange.sc.v1.consuela.ethereum.ethabi.{Decoded, Encoder, abiFunctionForFunctionNameAndArgs, callDataForAbiFunctionFromStringArgs, decodeReturnValuesForFunction}
import com.mchange.sc.v1.consuela.ethereum.stub
import com.mchange.sc.v1.consuela.ethereum.jsonrpc.Invoker
import com.mchange.sc.v1.consuela.ethereum.clients
import com.mchange.sc.v1.consuela.ethereum.encoding.RLP
import com.mchange.sc.v2.ens
import com.mchange.sc.v1.texttable
import scala.annotation.tailrec
import scala.collection._
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.sys.process.{Process, ProcessLogger}
import scala.io.Source
import scala.util.{Try,Success,Failure}
import scala.util.control.NonFatal
import scala.util.matching.Regex

import scala.concurrent.ExecutionContext

import com.mchange.sc.v2.jsonrpc.Exchanger
import com.mchange.sc.v2.jsonrpc.Exchanger.Factory.{Default => DefaultExchangerFactory}

// global implicits
import scala.concurrent.ExecutionContext.Implicits.global
import com.mchange.sc.v2.concurrent.{Poller, Scheduler}

object SbtEthereumPlugin extends AutoPlugin {

  initializeLoggingConfig()

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally we should try to log through sbt loggers
  private implicit val logger = mlogger( this )

  final case class TimestampedAbi( abi : Abi, timestamp : Option[Long] )

  object OneTimeWarnerKey {
    final object NodeChainIdInBuild extends OneTimeWarnerKey
    final object NodeUrlInBuild extends OneTimeWarnerKey
    final object AddressSenderInBuild  extends OneTimeWarnerKey
    final object EtherscanApiKeyInBuild  extends OneTimeWarnerKey

    final object EthDefaultNodeSupportedOnlyForMainet extends OneTimeWarnerKey
    final object UsingUnreliableBackstopNodeUrl extends OneTimeWarnerKey
  }
  sealed trait OneTimeWarnerKey

  private val MainScheduler = Scheduler.Default

  private val PublicTestAddresses = immutable.Map( testing.Default.Faucet.Address -> testing.Default.Faucet.PrivateKey )

  // TODO: Make a consistent choice about whether overrides should be scoped to ethNodeChainIds, or whether
  //       they should be unscoped but reset upon any sort of update of ethNodeChainId
  //
  //       They are reset, along with all mutables, if the session is reinitialized via
  //
  //         'set ethNodeChainId := <int>'
  //
  //       But perhaps its best to retain overrides across (to-be-implemented) gentler switches (and report the overrides
  //       upon any switch
  //
  private final object Mutables {

    val MainSignersManager = new SignersManager( MainScheduler, OnlyShoeboxKeystoreV3, PublicTestAddresses, abiOverridesForChain )

    val SessionSolidityCompilers = new AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]]( None )

    val CurrentSolidityCompiler = new AtomicReference[Option[( String, Compiler.Solidity )]]( None )

    // MT: protected by ChainIdOverride' lock
    val ChainIdOverride = new AtomicReference[Option[Int]]( None ) // Only supported for Compile config

    // MT: internally thread-safe
    val SenderOverrides = new ChainIdMutable[EthAddress]

    // MT: internally thread-safe
    val NodeUrlOverrides = new ChainIdMutable[String]

    // MT: internally thread-safe
    val AbiOverrides = new ChainIdMutable[immutable.Map[EthAddress,Abi]]

    // MT: internally thread-safe
    val GasLimitTweakOverrides = new ChainIdMutable[Invoker.MarkupOrOverride]

    // MT: internally thread-safe
    val GasPriceTweakOverrides = new ChainIdMutable[Invoker.MarkupOrOverride]

    // MT: internally thread-safe
    val NonceOverrides = new ChainIdMutable[BigInt]

    // MT: internally thread-safe
    val OneTimeWarner = new OneTimeWarner[OneTimeWarnerKey]

    // MT: protected by LocalGanache's lock
    val LocalGanache = new AtomicReference[Option[Process]]( None )

    def reset() : Unit = {
      MainSignersManager.reset()
      SessionSolidityCompilers.set( None )
      CurrentSolidityCompiler.set( None )
      ChainIdOverride.set( None )
      SenderOverrides.reset()
      NodeUrlOverrides.reset()
      AbiOverrides.reset()
      GasLimitTweakOverrides.reset()
      GasPriceTweakOverrides.reset()
      NonceOverrides.reset()
      OneTimeWarner.resetAll()
      LocalGanache synchronized {
        LocalGanache.set( None )
      }
    }
  }

  private def resetAllState() : Unit = {
    Mutables.reset()
    shoebox.reset()
    util.Parsers.reset()
  }

  private def unwrapNonceOverride( mbLog : Option[sbt.Logger], chainId : Int ) : Option[Unsigned256] = {
    val out = Mutables.NonceOverrides.get( chainId ).map( Unsigned256.apply )
    out.foreach { noverride =>
      mbLog.foreach { log =>
        log.info( s"Nonce override set: ${noverride.widen}" )
      }
    }
    out
  }

  private def unwrapNonceOverrideBigInt( mbLog : Option[sbt.Logger], chainId : Int ) : Option[BigInt] = {
    val out = Mutables.NonceOverrides.get( chainId )
    out.foreach { noverride =>
      mbLog.foreach { log =>
        log.info( s"Nonce override set: ${noverride}" )
      }
    }
    out
  }

  private def abiOverridesForChain( chainId : Int ) : immutable.Map[EthAddress,Abi] = {
    Mutables.AbiOverrides.get( chainId ).getOrElse( immutable.Map.empty[EthAddress,Abi] )
  }

  private def addAbiOverrideForChain( chainId : Int, address : EthAddress, abi : Abi ) : Unit = {
    Mutables.AbiOverrides.modify( chainId ) { pre =>
      Some( pre.getOrElse( immutable.Map.empty[EthAddress,Abi] ) + Tuple2( address, abi ) )
    }
  }

  private def removeAbiOverrideForChain( chainId : Int, address : EthAddress ) : Boolean = {
    val modified = {
      Mutables.AbiOverrides.modify( chainId ) { pre =>
        pre match {
          case Some( mapping ) => Some(mapping - address).filter( _.nonEmpty )
          case None            => None
        }
      }
    }
    modified.pre != modified.post
  }

  private def clearAbiOverrideForChain( chainId : Int ) : Boolean = {
    Mutables.AbiOverrides.getDrop( chainId ) != None
  }

  private val BufferSize = 4096

  private val PollSeconds = 15

  private val PollAttempts = 9

  private val Zero    = BigInt(0)
  private val Zero8   = Unsigned8( 0 )
  private val Zero256 = Unsigned256( 0 )

  private val EmptyBytes = List.empty[Byte]

  private val InfuraNames = Map[Int,String] (
    1  -> "mainnet",
    3  -> "ropsten",
    4  -> "rinkeby",
    42 -> "kovan"
  )

  private val HardcodedBackstopNodeUrls = Map[Int,String] (
    1  -> "https://ethjsonrpc.mchange.com/",
    3  -> "https://ropsten.infura.io/",
    4  -> "https://rinkeby.infura.io/",
    42 -> "https://kovan.infura.io/"
  )

  private val LastResortMaybeEthAddressSender = ExternalValue.EthSender.map( EthAddress.apply )

  private val LastResortMaybeTestEthAddressSender = Some( testing.Default.Faucet.Address ) 

  private val DefaultEthNetcompileUrl = "http://ethjsonrpc.mchange.com:8456"

  private val DefaultPriceRefreshDelay = 300.seconds

  private val priceFeed = new PriceFeed.Coinbase( DefaultPriceRefreshDelay )

  private val EnsRegisterRenewMarkup = 0.05d

  final object JsonFilter extends FilenameFilter {
    val DotSuffix = ".json"
    def accept( dir : File, name : String ) : Boolean = {
      val lcName = name.toLowerCase
      lcName != DotSuffix && lcName.endsWith( DotSuffix )
    }
  }

  // if we've started a child test process,
  // kill it on exit
  val GanacheDestroyer: Thread = new Thread {
    override def run() : Unit = {
      Mutables.LocalGanache synchronized {
        Mutables.LocalGanache.get.foreach ( _.destroy )
      }
    }
  }

  java.lang.Runtime.getRuntime.addShutdownHook( GanacheDestroyer )


  /*
   * Transitioning from supporting a user-configurable list of keystore dirs
   * to letting users define a set of wallet V3 keystore dirs from which
   * wallets will automatically be imported.
   * 
   * The goal is to make sure that all wallets SBT ethereum ever uses
   * are available in the shoebox (so that backing up the shoebox is
   * sufficient to back up the wallets.
   */ 
  lazy val OnlyShoeboxKeystoreV3 = shoebox.Keystore.V3.Directory.map( dir => immutable.Seq( dir ) ).assert

  object autoImport {

    // settings
    val enscfgNameServiceAddress        = settingKey[EthAddress]("The address of the ENS name service smart contract")
    val enscfgNameServicePublicResolver = settingKey[EthAddress]("The address of a publically accessible resolver (if any is available) that can be used to map names to addresses.")

    val ethcfgAutoDeployContracts           = settingKey[Seq[String]] ("Names (and optional space-separated constructor args) of contracts compiled within this project that should be deployed automatically.")
    val ethcfgBaseCurrencyCode              = settingKey[String]      ("Currency code for currency in which prices of ETH and other tokens should be displayed.")
    val ethcfgEntropySource                 = settingKey[SecureRandom]("The source of randomness that will be used for key generation")
    val ethcfgGasLimitCap                   = settingKey[BigInt]      ("Maximum gas limit to use in transactions")
    val ethcfgGasLimitFloor                 = settingKey[BigInt]      ("Minimum gas limit to use in transactions (usually left unset)")
    val ethcfgGasLimitMarkup                = settingKey[Double]      ("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")
    val ethcfgGasPriceCap                   = settingKey[BigInt]      ("Maximum gas limit to use in transactions")
    val ethcfgGasPriceFloor                 = settingKey[BigInt]      ("Minimum gas limit to use in transactions (usually left unset)")
    val ethcfgGasPriceMarkup                = settingKey[Double]      ("Fraction by which automatically estimated gas price will be marked up (if not overridden) in executing transactions")
    val ethcfgIncludeLocations              = settingKey[Seq[String]] ("Directories or URLs that should be searched to resolve import directives, besides the source directory itself")
    val ethcfgKeystoreAutoImportLocationsV3 = settingKey[Seq[File]]   ("Directories from which V3 wallets will be automatically imported into the sbt-ethereum shoebox")
    val ethcfgKeystoreAutoRelockSeconds     = settingKey[Int]         ("Number of seconds after which an unlocked private key should automatically relock")
    val ethcfgNetcompileUrl                 = settingKey[String]      ("Optional URL of an eth-netcompile service, for more reliabe network-based compilation than that available over json-rpc.")
    val ethcfgNodeChainId                   = settingKey[Int]         ("The EIP-155 chain ID for the network with which the application will interact ('mainnet' = 1, 'ropsten' = 3, 'rinkeby' = 4, etc. id<0 for ephemeral chains)")
    val ethcfgNodeUrl                       = settingKey[String]      ("URL of the Ethereum JSON-RPC service the build should work with")
    val ethcfgScalaStubsPackage             = settingKey[String]      ("Package into which Scala stubs of Solidity compilations should be generated")
    val ethcfgAddressSender                 = settingKey[String]      ("The address from which transactions will be sent")
    val ethcfgSolidityCompilerOptimize      = settingKey[Boolean]     ("Sets whether the Solidity compiler should run its optimizer on generated code, if supported.")
    val ethcfgSolidityCompilerOptimizerRuns = settingKey[Int]         ("Sets the number of optimization runs the Solidity compiler will execute, if supported and 'ethcfgSolidityCompilerOptimize' is set to 'true'.")
    val ethcfgSoliditySource                = settingKey[File]        ("Solidity source code directory")
    val ethcfgSolidityDestination           = settingKey[File]        ("Location for compiled solidity code and metadata")
    val ethcfgTargetDir                     = settingKey[File]        ("Location in target directory where ethereum artifacts will be placed")
    val ethcfgTransactionReceiptPollPeriod  = settingKey[Duration]    ("Length of period after which sbt-ethereum will poll and repoll for a Client.TransactionReceipt after a transaction")
    val ethcfgTransactionReceiptTimeout     = settingKey[Duration]    ("Length of period after which sbt-ethereum will give up on polling for a Client.TransactionReceipt after a transaction")
    val ethcfgUseReplayAttackProtection     = settingKey[Boolean]     ("Defines whether transactions should be signed with EIP-155 \"simple replay attack protection\", if (and only if) we are on a nonephemeral chain.")

    val xethcfgAsyncOperationTimeout      = settingKey[Duration]      ("Length of time to wait for asynchronous operations, like HTTP calls and external processes.")
    val xethcfgNamedAbiSource             = settingKey[File]          ("Location where files containing json files containing ABIs for which stubs should be generated. Each as '<stubname>.json'.")
    val xethcfgTestingResourcesObjectName = settingKey[String]        ("The name of the Scala object that will be automatically generated with resources for tests.")
    val xethcfgTransactionUnsignedTimeout = settingKey[Duration]("How long users might wait while trying to prepare a transaction for offline signing.")
    val xethcfgWalletV3ScryptDkLen        = settingKey[Int]           ("The derived key length parameter used when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptN            = settingKey[Int]           ("The value to use for parameter N when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptR            = settingKey[Int]           ("The value to use for parameter R when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptP            = settingKey[Int]           ("The value to use for parameter P when generating Scrypt V3 wallets")
    val xethcfgWalletV3Pbkdf2DkLen        = settingKey[Int]           ("The derived key length parameter used when generating pbkdf2 V3 wallets")
    val xethcfgWalletV3Pbkdf2C            = settingKey[Int]           ("The value to use for parameter C when generating pbkdf2 V3 wallets")

    // tasks

    val ensAddressLookup    = inputKey[Option[EthAddress]]("Prints the address given ens name should resolve to, if one has been set.")
    val ensAddressSet       = inputKey[Unit]              ("Sets the address a given ens name should resolve to.")
    val ensMigrateRegistrar = inputKey[Unit]              ("Migrates a name from a predecessor registar (e.g. the original auction registrar) to any successor registrar.")
    val ensNameExtend       = inputKey[Unit]              ("Extends the registration period of a given ENS name.")
    val ensNameHashes       = inputKey[Unit]              ("Prints the name hash and label hash associated with an ENS name.")
    val ensNamePrice        = inputKey[Unit]              ("Estimate the cost of renting (registering / renewing) a name for a period of time.")
    val ensNameRegister     = inputKey[Unit]              ("Registers a given ENS name.")
    val ensNameStatus       = inputKey[Unit]              ("Prints the current status of a given name.")
    val ensOwnerLookup      = inputKey[Option[EthAddress]]("Prints the address of the owner of a given name, if the name has an owner.")
    val ensOwnerSet         = inputKey[Unit]              ("Sets the owner of a given name to an address.")
    val ensResolverLookup   = inputKey[Option[EthAddress]]("Prints the address of the resolver associated with a given name.")
    val ensResolverSet      = inputKey[Unit]              ("Sets the resolver for a given name to an address.")
    val ensSubnodeCreate    = inputKey[Unit]              ("Creates a subnode (if it does not already exist) beneath an existing ENS name with the current sender as its owner.")
    val ensSubnodeOwnerSet  = inputKey[Unit]              ("Sets the owner of a name beneath an ENS name (creating the 'subnode' if it does not already exist).")

    val etherscanApiKeyDrop  = taskKey[Unit]  ("Removes the API key for etherscan services from the sbt-ethereum database.")
    val etherscanApiKeyPrint = taskKey[Unit]  ("Reveals the currently set API key for etherscan services, if any.")
    val etherscanApiKeySet   = inputKey[Unit] ("Sets an API key for etherscan services.")

    val eth = taskKey[Unit]("Prints information about the current session to the console.")

    val ethAddressAliasCheck          = inputKey[Unit]               ("Reveals the address associated with a given alias, or the aliases associated with a given address.")
    val ethAddressAliasDrop           = inputKey[Unit]               ("Drops an alias for an ethereum address from the sbt-ethereum shoebox database.")
    val ethAddressAliasList           = taskKey [Unit]               ("Lists aliases for ethereum addresses that can be used in place of the hex address in many tasks.")
    val ethAddressAliasSet            = inputKey[Unit]               ("Defines (or redefines) an alias for an ethereum address that can be used in place of the hex address in many tasks.")
    val ethAddressBalance             = inputKey[BigDecimal]         ("Computes the balance in ether of a given address, or of current sender if no address is supplied")
    val ethAddressOverride            = inputKey[Unit]               ("Basically an alias to 'ethAddressSenderOverrideSet'.")
    val ethAddressOverrideDrop        = taskKey [Unit]               ("Removes any sender override, reverting to any 'ethcfgAddressSender' or default sender that may be set.")
    val ethAddressOverrideSet         = inputKey[Unit]               ("Sets an ethereum address to be used as sender in prefernce to any 'ethcfgAddressSender' or default sender that may be set.")
    val ethAddressOverridePrint       = taskKey [Unit]               ("Displays any sender override, if set.")
    val ethAddressPrint               = taskKey [Unit]               ("Prints the sender address, which will be used to send ether or messages, and explains where and how it has ben set.")
    val ethAddressSender              = taskKey [Option[EthAddress]] ("Silently returns the current session sender address, if one is available, intended for use by plugins.")
    val ethAddressSenderDefaultDrop   = taskKey [Unit]               ("Removes any sender override, reverting to any 'ethcfgAddressSender' or default sender that may be set.")
    val ethAddressSenderDefaultSet    = inputKey[Unit]               ("Sets an ethereum address to be used as sender in prefernce to any 'ethcfgAddressSender' or default sender that may be set.")
    val ethAddressSenderDefaultPrint  = taskKey [Unit]               ("Displays any sender override, if set.")
    val ethAddressSenderOverride      = inputKey[Unit]               ("Basically an alias to 'ethAddressSenderOverrideSet'.")
    val ethAddressSenderOverrideDrop  = taskKey [Unit]               ("Removes any sender override, reverting to any 'ethcfgAddressSender' or default sender that may be set.")
    val ethAddressSenderOverrideSet   = inputKey[Unit]               ("Sets an ethereum address to be used as sender in prefernce to any 'ethcfgAddressSender' or default sender that may be set.")
    val ethAddressSenderOverridePrint = taskKey [Unit]               ("Displays any sender override, if set.")
    val ethAddressSenderPrint         = taskKey [Unit]               ("Prints the address that will be used to send ether or messages, and explains where and how it has ben set.")

    val ethContractAbiAliasDrop       = inputKey[Unit] ("Drops an alias for an ABI.")
    val ethContractAbiAliasList       = taskKey [Unit] ("Lists aliased ABIs and their hashes.")
    val ethContractAbiAliasSet        = inputKey[Unit] ("Defines a new alias for an ABI, taken from any ABI source.")
    val ethContractAbiCallDecode      = inputKey[Unit] ("Takes an ABI and a function call hex-encoded with that ABI, and decodes them.")
    val ethContractAbiCallEncode      = inputKey[Unit] ("Takes an ABI, a function name, and arguments and geneated the hex-encoded data that would invoke the function.")
    val ethContractAbiDefaultDrop     = inputKey[Unit] ("Removes an ABI definition that was added to the sbt-ethereum database via 'ethContractAbiDefaultSet' or 'ethContractAbiDefaultImport'")
    val ethContractAbiDefaultList     = inputKey[Unit] ("Lists the addresses for which default ABI definitions have been defined. (Includes explicitly set deaults and our own deployed compilations.)")
    val ethContractAbiDefaultImport   = inputKey[Unit] ("Import an ABI definition for a contract, from an external source or entered directly into a prompt.")
    val ethContractAbiDefaultSet      = inputKey[Unit] ("Uses as the ABI definition for a contract address the ABI of a different contract, specified by codehash or contract address")
    val ethContractAbiImport          = inputKey[Unit] ("Basically an alias to 'ethContractAbiDefaultImport'.")
    val ethContractAbiOverride        = inputKey[Unit] ("Basically an alias to 'ethContractAbiOverrideSet'.")
    val ethContractAbiOverrideSet     = inputKey[Unit] ("Sets a temporary (just this session) association between an ABI an address, that overrides any persistent association")
    val ethContractAbiOverrideDropAll = taskKey[Unit]  ("Clears all temporary associations (on the current chain) between an ABI an address")
    val ethContractAbiOverrideList    = taskKey[Unit]  ("Show all addresses (on the current chain) for which a temporary association between an ABI an address has been set")
    val ethContractAbiOverridePrint   = inputKey[Unit] ("Pretty prints any ABI a temporarily associated with an address as an ABI override")
    val ethContractAbiOverrideDrop    = inputKey[Unit] ("Drops a temporary (just this session) association between an ABI an address that may have ben set with 'ethContractAbiOverrideSet'")
    val ethContractAbiPrint           = inputKey[Unit] ("Prints the contract ABI associated with a provided address, if known.")
    val ethContractAbiPrintPretty     = inputKey[Unit] ("Pretty prints the contract ABI associated with a provided address, if known.")
    val ethContractAbiPrintCompact    = inputKey[Unit] ("Compactly prints the contract ABI associated with a provided address, if known.")

    val ethContractCompilationCull    = taskKey [Unit] ("Removes never-deployed compilations from the shoebox database.")
    val ethContractCompilationInspect = inputKey[Unit] ("Dumps to the console full information about a compilation, based on either a code hash or contract address")
    val ethContractCompilationList    = taskKey [Unit] ("Lists summary information about compilations known in the shoebox")

    val ethDebugGanacheStart = taskKey[Unit] (s"Starts a local ganache environment (if the command '${testing.Default.Ganache.Executable}' is in your PATH)")
    val ethDebugGanacheHalt  = taskKey[Unit] ("Stops any local ganache environment that may have been started previously")

    val ethKeystoreFromJsonImport               = taskKey [Unit] ("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")
    val ethKeystoreFromPrivateKeyImport         = taskKey [Unit] ("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")
    val ethKeystoreList                         = taskKey[immutable.SortedMap[EthAddress,immutable.SortedSet[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined")
    val ethKeystorePrivateKeyReveal             = inputKey[Unit] ("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")
    val ethKeystoreWalletV3Create               = taskKey [Unit] ("Generates a new V3 wallet, using ethcfgEntropySource as a source of randomness")
    val ethKeystoreWalletV3FromJsonImport       = taskKey [Unit] ("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")
    val ethKeystoreWalletV3FromPrivateKeyImport = taskKey [Unit] ("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")
    val ethKeystoreWalletV3Print                = inputKey[Unit] ("Prints V3 wallet as JSON to the console.")
    val ethKeystoreWalletV3Validate             = inputKey[Unit] ("Verifies that a V3 wallet can be decoded for an address, and decodes to the expected address.")

    val ethLanguageSolidityCompilerInstall = inputKey[Unit] ("Installs a best-attempt platform-specific solidity compiler into the sbt-ethereum shoebox (or choose a supported version)")
    val ethLanguageSolidityCompilerPrint   = taskKey [Unit] ("Displays currently active Solidity compiler")
    val ethLanguageSolidityCompilerSelect  = inputKey[Unit] ("Manually select among solidity compilers available to this project")

    val ethNodeBlockNumberPrint = taskKey[Unit]("Displays the current blocknumber of the current node.")

    val ethNodeChainIdDefaultDrop  = taskKey[Unit] ("Removes any default chain ID that has been set, leaving sbt-ethereum to a hard-coded default.")
    val ethNodeChainIdDefaultSet   = inputKey[Unit]("Sets the default chain ID sbt-ethereum should use.")
    val ethNodeChainIdDefaultPrint = taskKey[Unit] ("Displays any default chain ID that may have been set.")

    val ethNodeChainIdOverride      = inputKey[Unit]("Basically an alias to 'ethNodeChainIdOverrideSet'.")
    val ethNodeChainIdOverrideDrop  = taskKey[Unit] ("Removes session override of the default and/or hard-coded chain ID that may have been set.")
    val ethNodeChainIdOverrideSet   = inputKey[Unit]("Sets a session override of any default or hard-coded chain ID.")
    val ethNodeChainIdOverridePrint = taskKey[Unit] ("Displays any session override of the chain ID that may have been set.")

    val ethNodeChainIdPrint = taskKey[Unit]("Displays the node chain ID for the current configuration, and explains how it is configured.")

    val ethNodeChainId = taskKey[Int]("Yields the node chain ID for the current configuration.")

    val ethNodeUrlDefaultSet   = inputKey[Unit]("Sets the default node json-rpc URL, which will be used if not overridden by an 'ethcfgNodeUrl' hardcoded into the build.")
    val ethNodeUrlDefaultDrop  = taskKey[Unit] ("Drops the default node json-rpc URL.")
    val ethNodeUrlDefaultPrint = taskKey[Unit] ("Displays the current default node json-rpc URL.")

    val ethNodeUrlOverride      = inputKey[Unit]("Basically an alias to 'ethNodeUrlOverrideSet'.")
    val ethNodeUrlOverrideDrop  = taskKey[Unit] ("Drops any override, reverting to use of the default node json-rpc URL.")
    val ethNodeUrlOverrideSet   = inputKey[Unit]("Overrides the default node json-rpc URL.")
    val ethNodeUrlOverridePrint = taskKey[Unit] ("Displays any override of the default node json-rpc URL.")

    val ethNodeUrlPrint = taskKey[Unit] ("Displays the currently effective node json-rpc URL, and explains how it is configured.")

    val ethNodeUrl = taskKey[String]("Yields the current node URL.")

    val ethShoeboxBackup              = taskKey[Unit] ("Backs up the sbt-ethereum shoebox.")
    val ethShoeboxDatabaseDumpCreate  = taskKey[Unit] ("Dumps the sbt-ethereum shoebox database as an SQL text file, stored inside the sbt-ethereum shoebox directory.")
    val ethShoeboxDatabaseDumpRestore = taskKey[Unit] ("Restores the sbt-ethereum shoebox database from a previously generated dump.")
    val ethShoeboxRestore             = taskKey[Unit] ("Restores the sbt-ethereum shoebox from a backup generated by 'ethRespositoryBackup'.")

    val ethTransactionDeploy = inputKey[immutable.Seq[Tuple2[String,Either[EthHash,Client.TransactionReceipt]]]]("""Deploys the named contract, if specified, or else all contracts in 'ethcfgAutoDeployContracts'""")

    val ethTransactionEtherSend   = inputKey[Client.TransactionReceipt] ("Sends ether from current sender to a specified account, format 'ethTransactionEtherSend <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

    val ethTransactionForward = inputKey[Client.TransactionReceipt] ("Takes an already signed transaction (specified as a binary file or as hex bytes) and forwards it to the current session's network.")

    val ethTransactionGasLimitOverride      = inputKey[Unit] ("Basically an alias to 'ethTransactionGasLimitOverrideSet'.")
    val ethTransactionGasLimitOverrideSet   = inputKey[Unit] ("Defines a value which overrides the usual automatic marked-up estimation of gas required for a transaction.")
    val ethTransactionGasLimitOverrideDrop  = taskKey [Unit] ("Removes any previously set gas override, reverting to the usual automatic marked-up estimation of gas required for a transaction.")
    val ethTransactionGasLimitOverridePrint = taskKey [Unit] ("Displays the current gas override, if set.")

    val ethTransactionGasPriceOverride      = inputKey[Unit] ("Basically an alias to 'ethTransactionGasPriceOverrideSet'.")
    val ethTransactionGasPriceOverrideSet   = inputKey[Unit] ("Defines a value which overrides the usual automatic marked-up default gas price that will be paid for a transaction.")
    val ethTransactionGasPriceOverrideDrop  = taskKey [Unit] ("Removes any previously set gas price override, reverting to the usual automatic marked-up default.")
    val ethTransactionGasPriceOverridePrint = taskKey [Unit] ("Displays the current gas price override, if set.")

    val ethTransactionInvoke = inputKey[Client.TransactionReceipt]("Calls a function on a deployed smart contract")
    val ethTransactionLookup = inputKey[Client.TransactionReceipt]("Looks up (and potentially waits for) the transaction associated with a given transaction hash.")
    val ethTransactionMock   = inputKey[(Abi.Function,immutable.Seq[Decoded.Value])] ("Mocks a call to any function. Burns no Ether, makes no persistent changes, returns a simulated result.")

    val ethTransactionNonceOverride      = inputKey[Unit]("Basically an alias to 'ethTransactionNonceOverrideSet'.")
    val ethTransactionNonceOverrideDrop  = taskKey[Unit]("Removes any nonce override that may have been set.")
    val ethTransactionNonceOverridePrint = taskKey[Unit]("Prints any nonce override that may have been set.")
    val ethTransactionNonceOverrideSet   = inputKey[Unit]("Sets a fixed nonce to be used in the transactions, rather than automatically choosing the next nonce. (Remains fixed and set until explicitly dropped.)")
    val ethTransactionNonceOverrideValue = taskKey[Option[BigInt]]("Silently finds the currently set session override for the nonce, if one is set.")

    val ethTransactionSign = inputKey[EthTransaction.Signed]("Loads an unsigned transaction, signs it, then prints and optionally saves its hex for future execution.")

    val ethTransactionPing   = inputKey[Option[Client.TransactionReceipt]]           ("Sends 0 ether from current sender to an address, by default the sender address itself")
    val ethTransactionRaw    = inputKey[Client.TransactionReceipt]                   ("Sends a transaction with user-specified bytes, amount, and optional nonce")

    val ethTransactionView   = inputKey[(Abi.Function,immutable.Seq[Decoded.Value])] ("Makes a call to a constant function, consulting only the local copy of the blockchain. Burns no Ether. Returns the latest available result.")

    val ethTransactionUnsignedInvoke    = inputKey[EthTransaction.Unsigned]("Prepare a method-invokation transaction to be signed elsewhere.")
    val ethTransactionUnsignedRaw       = inputKey[EthTransaction.Unsigned]("Prepare a raw message transaction to be signed elsewhere.")
    val ethTransactionUnsignedEtherSend = inputKey[EthTransaction.Unsigned]("Prepare send transaction to be signed elsewhere.")

    // erc20 tasks
    val erc20AllowancePrint       = inputKey[Erc20.Balance]("Prints the allowance of an address to operate on ERC20 tokens owned by a different address.")
    val erc20AllowanceSet         = inputKey[Client.TransactionReceipt]("Approves ability to transfer tokens an account's tokens by a third-party account." )
    val erc20Balance              = inputKey[Erc20.Balance]("Prints the balance in ERC20 tokens of an address.")
    val erc20ConvertAtomsToTokens = inputKey[Unit]("For a given ERC20 token contract, print the number of tokens a given number of atoms corresponds to.")
    val erc20ConvertTokensToAtoms = inputKey[Unit]("For a given ERC20 token contract, print the number of atoms a given token amount corresponds to.")
    val erc20Summary              = inputKey[Unit]("Prints an ERC20 token's (self-reported) name, symbol, decimals, and total supply.")
    val erc20Transfer             = inputKey[Client.TransactionReceipt]("Transfers ERC20 tokens to a given address.")

    // xens tasks

    val xensClient = taskKey[ens.Client]("Loads an ENS client instance.")

    // xeth tasks

    val xethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")
    val xethFindCacheRichParserInfo = taskKey[RichParserInfo]("Finds and caches information (aliases, ens info) needed by some parsers")
    val xethFindCacheSessionSolidityCompilerKeys = taskKey[immutable.Set[String]]("Finds and caches keys for available compilers for use by the parser for ethLanguageSolidityCompilerSelect")
    val xethFindCacheSeeds = taskKey[immutable.Map[String,MaybeSpawnable.Seed]]("Finds and caches compiled, deployable contracts, omitting ambiguous duplicates. Triggered by compileSolidity")
    val xethFindCurrentSolidityCompiler = taskKey[Compiler.Solidity]("Finds and caches keys for available compilers for use parser for ethLanguageSolidityCompilerSelect")
    val xethGasPrice = taskKey[BigInt]("Finds the current gas price, including any overrides or gas price markups")
    val xethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethcfgEntropySource as a source of randomness")
    val xethGenScalaStubsAndTestingResources = taskKey[immutable.Seq[File]]("Generates stubs for compiled Solidity contracts, and resources helpful in testing them.")
    val xethKeystoreWalletV3CreateDefault = taskKey[wallet.V3]("Generates a new V3 wallet, using a default algorithm (currently Scrypt), using ethcfgEntropySource as a source of randomness, no querying for alias or set-as-default")
    val xethKeystoreWalletV3CreatePbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethcfgEntropySource as a source of randomness, no querying for alias or set-as-default")
    val xethKeystoreWalletV3CreateScrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethcfgEntropySource as a source of randomness, no querying for alias or set-as-default")
    val xethInvokeData = inputKey[immutable.Seq[Byte]]("Prints the data portion that would be sent in a message invoking a function and its arguments on a deployed smart contract")
    val xethInvokerContext = taskKey[Invoker.Context]("Puts together gas and jsonrpc configuration to generate a context for transaction invocation.")
    val xethLoadAbiFor = inputKey[Abi]("Finds the ABI for a contract address, if known")
    val xethLoadCurrentCompilationsKeepDups = taskKey[immutable.Iterable[(String,jsonrpc.Compilation.Contract)]]("Loads compiled solidity contracts, permitting multiple nonidentical contracts of the same name")
    val xethLoadCurrentCompilationsOmitDupsCumulative = taskKey[immutable.Map[String,jsonrpc.Compilation.Contract]]("Loads compiled solidity contracts, omitting contracts with multiple nonidentical contracts of the same name")
    val xethLoadSeeds = taskKey[immutable.Map[String,MaybeSpawnable.Seed]]("""Loads compilations available for deployment (or "spawning"), which may include both current and archived compilations""")
    val xethLoadWalletsV3 = taskKey[immutable.Set[wallet.V3]]("Loads a V3 wallet from ethWalletsV3 for current sender")
    val xethLoadWalletsV3For = inputKey[immutable.Set[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")
    val xethNamedAbis = taskKey[immutable.Map[String,TimestampedAbi]]("Loads any named ABIs from the 'xethcfgNamedAbiSource' directory")
    val xethOnLoadAutoImportWalletsV3 = taskKey[Unit]("Import any not-yet-imported wallets from directories specified in 'ethcfgKeystoreAutoImportLocationsV3'")
    val xethOnLoadBanner = taskKey[Unit]( "Prints the sbt-ethereum post-initialization banner." )
    val xethOnLoadRecoverInconsistentSchema = taskKey[Unit]( "Checks to see if the shoebox database schema is in an inconsistent state, and offers to recover a consistent version from dump." )
    val xethOnLoadSolicitCompilerInstall = taskKey[Unit]("Intended to be executed in 'onLoad', checks whether the default Solidity compiler is installed and if not, offers to install it.")
    val xethOnLoadSolicitWalletV3Generation = taskKey[Unit]("Intended to be executd in 'onLoad', checks whether sbt-ethereum has any wallets available, if not offers to install one.")
    val xethShoeboxRepairPermissions = taskKey[Unit]("Repairs filesystem permissions in sbt's shoebox to its required user-only values.")
    val xethSignerFinder = taskKey[(EthAddress, Option[String]) => EthSigner]("Finds a (cautious, interactive) signer that applications can use to sign documents for a known, unlockable EthAddress.")
    val xethSqlQueryShoeboxDatabase = inputKey[Unit]("Primarily for debugging. Query the internal shoebox database.")
    val xethSqlUpdateShoeboxDatabase = inputKey[Unit]("Primarily for development and debugging. Update the internal shoebox database with arbitrary SQL.")
    val xethStubEnvironment = taskKey[Tuple2[stub.Context, stub.Sender.Signing]]("Offers the elements you need to work with smart-contract stubs from inside an sbt-ethereum build.")
    val xethTransactionCount = taskKey[BigInt]("Finds the next nonce for the current sender")
    val xethTriggerDirtyAliasCache = taskKey[Unit]("Indirectly provokes an update of the cache of aliases used for tab completions.")
    val xethTriggerDirtySolidityCompilerList = taskKey[Unit]("Indirectly provokes an update of the cache of aavailable solidity compilers used for tab completions.")
    val xethUpdateContractDatabase = taskKey[Boolean]("Integrates newly compiled contracts into the contract database. Returns true if changes were made.")
    val xethUpdateSessionSolidityCompilers = taskKey[immutable.SortedMap[String,Compiler.Solidity]]("Finds and tests potential Solidity compilers to see which is available.")

    // unprefixed keys

    val compileSolidity = taskKey[Unit]("Compiles solidity files")

  } // end object autoImport


  import autoImport._

  // commands... don't forget to add them to the commands key!

  // could i replace some or all of these with addCommandAlias(...)? hasn't worked so far. how should it be done?

  private val ethDebugGanacheRestartCommand = Command.command( "ethDebugGanacheRestart" ) { state =>
    "ethDebugGanacheHalt" :: "ethDebugGanacheStart" :: state
  }

  private val ethDebugGanacheTestCommand = Command.command( "ethDebugGanacheTest" ) { state =>
    "Test/compile" :: "ethDebugGanacheHalt" :: "ethDebugGanacheStart" :: "Test/ethTransactionDeploy" :: "test" :: "ethDebugGanacheHalt" :: state
  }

  // definitions

  /*
   * The strategy we are using to support dynamic, post-task tab completions
   * is taken most closely from here
   *
   *    https://github.com/etsy/sbt-compile-quick-plugin/blob/7c99b0197634c72924791591e3a790bd7e0e3e82/src/main/scala/com/etsy/sbt/CompileQuick.scala
   *
   * See also Josh Suereth here
   *
   *    http://grokbase.com/t/gg/simple-build-tool/151vq0w03t/sbt-sbt-plugin-developing-using-value-from-anither-task-in-parser-exampels-for-tab-completion
   *
   * It is still rather mysterious to me.
   */
  lazy val ethDefaults : Seq[sbt.Def.Setting[_]] = Seq(

    // enscfg settings

    enscfgNameServiceAddress in Compile := ens.StandardNameServiceAddress,

    enscfgNameServicePublicResolver in Compile := ens.StandardNameServicePublicResolver,

    // ethcfg settings

    ethcfgBaseCurrencyCode := "USD",

    ethcfgNodeChainId in Test := {
      findBackstopChainId( Test ).get // we know there is a value for Test
    },

    ethcfgNodeUrl in Test := {
      val log = sLog.value
      val chainId = (ethcfgNodeChainId in Test).value

      // there is always a backstop URL with the default chain ID for test
      // builds that overwrite Test/ethcfgChainId probably will want to overwrite this as well
      findBackstopUrl(warn=false)( log, Test, chainId ).get 
    },

    ethcfgEntropySource := new java.security.SecureRandom,

    ethcfgGasLimitMarkup := 0.2,

    ethcfgGasPriceMarkup := 0.0, // by default, use conventional gas price

    ethcfgIncludeLocations := Nil,

    ethcfgKeystoreAutoImportLocationsV3 := {
      def debug( location : String ) : String = s"Failed to find V3 keystore in ${location}"
      def listify( fd : Failable[File] ) = fd.fold( _ => (Nil : List[File]))( f => List(f) )
      listify( clients.geth.KeyStore.Directory.xdebug( debug("geth home directory") ) ) ::: Nil
    },

    ethcfgKeystoreAutoRelockSeconds := 300,

    ethcfgSolidityCompilerOptimize := true,

    ethcfgSolidityCompilerOptimizerRuns := 200,

    ethcfgSoliditySource in Compile := (sourceDirectory in Compile).value / "solidity",

    ethcfgSoliditySource in Test := (sourceDirectory in Test).value / "solidity",

    ethcfgSolidityDestination in Compile := (ethcfgTargetDir in Compile).value / "solidity",

    ethcfgSolidityDestination in Test := (ethcfgTargetDir in Test).value / "solidity",

    ethcfgTargetDir in Compile := (target in Compile).value / "ethereum",

    ethcfgTargetDir in Test := (ethcfgTargetDir in Compile).value / "test",

    ethcfgTransactionReceiptPollPeriod := 3.seconds,

    ethcfgTransactionReceiptTimeout := 5.minutes,

    ethcfgUseReplayAttackProtection := true,

    // xeth settings

    xethcfgAsyncOperationTimeout := 30.seconds,

    xethcfgNamedAbiSource in Compile := (sourceDirectory in Compile).value / "ethabi",

    xethcfgNamedAbiSource in Test := (sourceDirectory in Test).value / "ethabi",

    xethcfgTestingResourcesObjectName in Test := "Testing",

    xethcfgTransactionUnsignedTimeout := Duration.Inf,

    xethcfgWalletV3Pbkdf2C := wallet.V3.Default.Pbkdf2.C,

    xethcfgWalletV3Pbkdf2DkLen := wallet.V3.Default.Pbkdf2.DkLen,

    xethcfgWalletV3ScryptDkLen := wallet.V3.Default.Scrypt.DkLen,

    xethcfgWalletV3ScryptN := wallet.V3.Default.Scrypt.N,

    xethcfgWalletV3ScryptR := wallet.V3.Default.Scrypt.R,

    xethcfgWalletV3ScryptP := wallet.V3.Default.Scrypt.P,

    // tasks

    ensAddressLookup in Compile := { ensAddressLookupTask( Compile ).evaluated },

    ensAddressLookup in Test := { ensAddressLookupTask( Test ).evaluated },

    ensAddressSet in Compile := { ensAddressSetTask( Compile ).evaluated },

    ensAddressSet in Test := { ensAddressSetTask( Test ).evaluated },

    ensMigrateRegistrar in Compile := { ensMigrateRegistrarTask( Compile ).evaluated },

    ensMigrateRegistrar in Test := { ensMigrateRegistrarTask( Test ).evaluated },

    ensNameExtend in Compile := { ensNameExtendTask( Compile ).evaluated },

    ensNameExtend in Test := { ensNameExtendTask( Test ).evaluated },

    ensNameHashes in Compile := { ensNameHashesTask( Compile ).evaluated },

    ensNameHashes in Test := { ensNameHashesTask( Test ).evaluated },

    ensNamePrice in Compile := { ensNamePriceTask( Compile ).evaluated },

    ensNamePrice in Test := { ensNamePriceTask( Test ).evaluated },

    ensNameRegister in Compile := { ensNameRegisterTask( Compile ).evaluated },

    ensNameRegister in Test := { ensNameRegisterTask( Test ).evaluated },

    ensNameStatus in Compile := { ensNameStatusTask( Compile ).evaluated },

    ensNameStatus in Test := { ensNameStatusTask( Test ).evaluated },

    ensOwnerLookup in Compile := { ensOwnerLookupTask( Compile ).evaluated },

    ensOwnerLookup in Test := { ensOwnerLookupTask( Test ).evaluated },

    ensOwnerSet in Compile := { ensOwnerSetTask( Compile ).evaluated },

    ensOwnerSet in Test := { ensOwnerSetTask( Test ).evaluated },

    ensResolverLookup in Compile := { ensResolverLookupTask( Compile ).evaluated },

    ensResolverLookup in Test := { ensResolverLookupTask( Test ).evaluated },

    ensResolverSet in Compile := { ensResolverSetTask( Compile ).evaluated },

    ensResolverSet in Test := { ensResolverSetTask( Test ).evaluated },

    ensSubnodeCreate in Compile := { ensSubnodeCreateTask( Compile ).evaluated },

    ensSubnodeCreate in Test := { ensSubnodeCreateTask( Test ).evaluated },

    ensSubnodeOwnerSet in Compile := { ensSubnodeOwnerSetTask( Compile ).evaluated },

    ensSubnodeOwnerSet in Test := { ensSubnodeOwnerSetTask( Test ).evaluated },

    etherscanApiKeyDrop := { etherscanApiKeyDropTask.value },

    etherscanApiKeySet := { etherscanApiKeySetTask.evaluated },

    etherscanApiKeyPrint := { etherscanApiKeyPrintTask.value },

    eth in Compile := { logSessionInfoTask( Compile ).value },

    eth in Test := { logSessionInfoTask( Test ).value },

    ethAddressAliasDrop in Compile := { ethAddressAliasDropTask( Compile ).evaluated },

    ethAddressAliasDrop in Test := { ethAddressAliasDropTask( Test ).evaluated },

    ethAddressAliasList in Compile := { ethAddressAliasListTask( Compile ).value },

    ethAddressAliasList in Test := { ethAddressAliasListTask( Test ).value },

    ethAddressAliasCheck in Compile := { ethAddressAliasCheckTask( Compile ).evaluated },

    ethAddressAliasCheck in Test := { ethAddressAliasCheckTask( Test ).evaluated },

    ethAddressAliasSet in Compile := { ethAddressAliasSetTask( Compile ).evaluated },

    ethAddressAliasSet in Test := { ethAddressAliasSetTask( Test ).evaluated },

    ethAddressBalance in Compile := { ethAddressBalanceTask( Compile ).evaluated },

    ethAddressBalance in Test := { ethAddressBalanceTask( Test ).evaluated },

    ethAddressOverrideDrop in Compile := { ethAddressSenderOverrideDropTask( Compile ).value },

    ethAddressOverrideDrop in Test := { ethAddressSenderOverrideDropTask( Test ).value },

    ethAddressOverridePrint in Compile := { ethAddressSenderOverridePrintTask( Compile ).value },

    ethAddressOverridePrint in Test := { ethAddressSenderOverridePrintTask( Test ).value },

    ethAddressOverrideSet in Compile := { ethAddressSenderOverrideSetTask( Compile ).evaluated },

    ethAddressOverrideSet in Test := { ethAddressSenderOverrideSetTask( Test ).evaluated },

    ethAddressOverride in Compile := { ethAddressSenderOverrideSetTask( Compile ).evaluated },

    ethAddressOverride in Test := { ethAddressSenderOverrideSetTask( Test ).evaluated },

    ethAddressPrint in Compile := { ethAddressSenderPrintTask( Compile ).value },

    ethAddressPrint in Test := { ethAddressSenderPrintTask( Test ).value },

    ethAddressSender in Compile := { ethAddressSenderTask( Compile ).value },

    ethAddressSender in Test := { ethAddressSenderTask( Test ).value },

    ethAddressSenderPrint in Compile := { ethAddressSenderPrintTask( Compile ).value },

    ethAddressSenderPrint in Test := { ethAddressSenderPrintTask( Test ).value },

    ethAddressSenderDefaultDrop in Compile := { ethAddressSenderDefaultDropTask( Compile ).value },

    ethAddressSenderDefaultDrop in Test := { ethAddressSenderDefaultDropTask( Test ).value },

    ethAddressSenderDefaultPrint in Compile := { ethAddressSenderDefaultPrintTask( Compile ).value },

    ethAddressSenderDefaultPrint in Test := { ethAddressSenderDefaultPrintTask( Test ).value },

    ethAddressSenderDefaultSet in Compile := { ethAddressSenderDefaultSetTask( Compile ).evaluated },

    ethAddressSenderDefaultSet in Test := { ethAddressSenderDefaultSetTask( Test ).evaluated },

    ethAddressSenderOverrideDrop in Compile := { ethAddressSenderOverrideDropTask( Compile ).value },

    ethAddressSenderOverrideDrop in Test := { ethAddressSenderOverrideDropTask( Test ).value },

    ethAddressSenderOverridePrint in Compile := { ethAddressSenderOverridePrintTask( Compile ).value },

    ethAddressSenderOverridePrint in Test := { ethAddressSenderOverridePrintTask( Test ).value },

    ethAddressSenderOverrideSet in Compile := { ethAddressSenderOverrideSetTask( Compile ).evaluated },

    ethAddressSenderOverrideSet in Test := { ethAddressSenderOverrideSetTask( Test ).evaluated },

    ethAddressSenderOverride in Compile := { ethAddressSenderOverrideSetTask( Compile ).evaluated },

    ethAddressSenderOverride in Test := { ethAddressSenderOverrideSetTask( Test ).evaluated },

    ethContractAbiAliasDrop in Compile := { ethContractAbiAliasDropTask( Compile ).evaluated },

    ethContractAbiAliasDrop in Test := { ethContractAbiAliasDropTask( Test ).evaluated },

    ethContractAbiAliasList in Compile := { ethContractAbiAliasListTask( Compile ).value },

    ethContractAbiAliasList in Test := { ethContractAbiAliasListTask( Test ).value },

    ethContractAbiAliasSet in Compile := { ethContractAbiAliasSetTask( Compile ).evaluated },

    ethContractAbiAliasSet in Test := { ethContractAbiAliasSetTask( Test ).evaluated },

    ethContractAbiCallDecode in Compile := { ethContractAbiCallDecodeTask( Compile ).evaluated },

    ethContractAbiCallDecode in Test := { ethContractAbiCallDecodeTask( Test ).evaluated },

    ethContractAbiCallEncode in Compile := { ethContractAbiCallEncodeTask( Compile ).evaluated },

    ethContractAbiCallEncode in Test := { ethContractAbiCallEncodeTask( Test ).evaluated },

    ethContractAbiDefaultDrop in Compile := { ethContractAbiDefaultDropTask( Compile ).evaluated },

    ethContractAbiDefaultDrop in Test := { ethContractAbiDefaultDropTask( Test ).evaluated },

    ethContractAbiDefaultList in Compile := { ethContractAbiDefaultListTask( Compile ).evaluated },

    ethContractAbiDefaultList in Test := { ethContractAbiDefaultListTask( Test ).evaluated },

    ethContractAbiDefaultSet in Compile := { ethContractAbiDefaultSetTask( Compile ).evaluated },

    ethContractAbiDefaultSet in Test := { ethContractAbiDefaultSetTask( Test ).evaluated },

    ethContractAbiDefaultImport in Compile := { ethContractAbiDefaultImportTask( Compile ).evaluated },

    ethContractAbiDefaultImport in Test := { ethContractAbiDefaultImportTask( Test ).evaluated },

    ethContractAbiImport in Compile := { ethContractAbiDefaultImportTask( Compile ).evaluated },

    ethContractAbiImport in Test := { ethContractAbiDefaultImportTask( Test ).evaluated },

    ethContractAbiOverride in Compile := { ethContractAbiOverrideSetTask( Compile ).evaluated },

    ethContractAbiOverride in Test := { ethContractAbiOverrideSetTask( Test ).evaluated },

    ethContractAbiOverrideSet in Compile := { ethContractAbiOverrideSetTask( Compile ).evaluated },

    ethContractAbiOverrideSet in Test := { ethContractAbiOverrideSetTask( Test ).evaluated },

    ethContractAbiOverrideDropAll in Compile := { ethContractAbiOverrideDropAllTask( Compile ).value },

    ethContractAbiOverrideDropAll in Test := { ethContractAbiOverrideDropAllTask( Test ).value },

    ethContractAbiOverrideList in Compile := { ethContractAbiOverrideListTask( Compile ).value },

    ethContractAbiOverrideList in Test := { ethContractAbiOverrideListTask( Test ).value },

    ethContractAbiOverridePrint in Compile := { ethContractAbiOverridePrintTask( Compile ).evaluated },

    ethContractAbiOverridePrint in Test := { ethContractAbiOverridePrintTask( Test ).evaluated },

    ethContractAbiOverrideDrop in Compile := { ethContractAbiOverrideDropTask( Compile ).evaluated },

    ethContractAbiOverrideDrop in Test := { ethContractAbiOverrideDropTask( Test ).evaluated },

    ethContractAbiPrint in Compile := { ethContractAbiPrintTask( Compile ).evaluated },

    ethContractAbiPrint in Test := { ethContractAbiPrintTask( Test ).evaluated },

    ethContractAbiPrintCompact in Compile := { ethContractAbiPrintCompactTask( Compile ).evaluated },

    ethContractAbiPrintCompact in Test := { ethContractAbiPrintCompactTask( Test ).evaluated },

    ethContractAbiPrintPretty in Compile := { ethContractAbiPrintPrettyTask( Compile ).evaluated },

    ethContractAbiPrintPretty in Test := { ethContractAbiPrintPrettyTask( Test ).evaluated },

    ethContractCompilationCull := { ethContractCompilationCullTask.value },

    ethContractCompilationInspect in Compile := { ethContractCompilationInspectTask( Compile ).evaluated },

    ethContractCompilationInspect in Test := { ethContractCompilationInspectTask( Test ).evaluated },

    ethContractCompilationList := { ethContractCompilationListTask.value },

    ethDebugGanacheStart in Test := { ethDebugGanacheStartTask.value },

    ethDebugGanacheHalt in Test := { ethDebugGanacheHaltTask.value },

    ethKeystoreFromJsonImport in Compile := { ethKeystoreWalletV3FromJsonImportTask( Compile ).value },

    ethKeystoreFromJsonImport in Test := { ethKeystoreWalletV3FromJsonImportTask( Test ).value },

    ethKeystoreFromPrivateKeyImport in Compile := { ethKeystoreWalletV3FromPrivateKeyImportTask( Compile ).value },

    ethKeystoreFromPrivateKeyImport in Test := { ethKeystoreWalletV3FromPrivateKeyImportTask( Test ).value },

    ethKeystoreList in Compile := { ethKeystoreListTask( Compile ).value },

    ethKeystoreList in Test := { ethKeystoreListTask( Test ).value },

    ethKeystorePrivateKeyReveal in Compile := { ethKeystorePrivateKeyRevealTask( Compile ).evaluated },

    ethKeystorePrivateKeyReveal in Test := { ethKeystorePrivateKeyRevealTask( Test ).evaluated },

    ethKeystoreWalletV3Create in Compile := { ethKeystoreWalletV3CreateTask( Compile ).value },

    ethKeystoreWalletV3Create in Test := { ethKeystoreWalletV3CreateTask( Test ).value },

    ethKeystoreWalletV3FromJsonImport in Compile := { ethKeystoreWalletV3FromJsonImportTask( Compile ).value },

    ethKeystoreWalletV3FromJsonImport in Test := { ethKeystoreWalletV3FromJsonImportTask( Test ).value },

    ethKeystoreWalletV3FromPrivateKeyImport in Compile := { ethKeystoreWalletV3FromPrivateKeyImportTask( Compile ).value },

    ethKeystoreWalletV3FromPrivateKeyImport in Test := { ethKeystoreWalletV3FromPrivateKeyImportTask( Test ).value },

    ethKeystoreWalletV3Print in Compile := { ethKeystoreWalletV3PrintTask( Compile ).evaluated },

    ethKeystoreWalletV3Print in Test := { ethKeystoreWalletV3PrintTask( Test ).evaluated },

    ethKeystoreWalletV3Validate in Compile := { ethKeystoreWalletV3ValidateTask( Compile ).evaluated },

    ethKeystoreWalletV3Validate in Test := { ethKeystoreWalletV3ValidateTask( Test ).evaluated },

    ethLanguageSolidityCompilerSelect in Compile := { ethLanguageSolidityCompilerSelectTask.evaluated },

    ethLanguageSolidityCompilerInstall in Compile := { ethLanguageSolidityCompilerInstallTask.evaluated },

    ethLanguageSolidityCompilerPrint in Compile := { ethLanguageSolidityCompilerPrintTask.value },

    ethNodeBlockNumberPrint in Compile := { ethNodeBlockNumberPrintTask( Compile ).value },

    ethNodeBlockNumberPrint in Test := { ethNodeBlockNumberPrintTask( Test ).value },

    ethNodeChainId in Compile := { findNodeChainIdTask(warn=true)( Compile ).value },

    ethNodeChainId in Test := { findNodeChainIdTask(warn=true)( Test ).value },

    ethNodeChainIdDefaultDrop in Compile := { ethNodeChainIdDefaultDropTask( Compile ).value }, // only Compile config is supported

    ethNodeChainIdDefaultPrint in Compile := { ethNodeChainIdDefaultPrintTask( Compile ).value }, // only Compile config is supported

    ethNodeChainIdDefaultSet in Compile := { ethNodeChainIdDefaultSetTask( Compile ).evaluated }, // only Compile config is supported

    ethNodeChainIdOverrideDrop in Compile := { ethNodeChainIdOverrideDropTask( Compile ).value }, // only Compile config is supported

    ethNodeChainIdOverridePrint in Compile := { ethNodeChainIdOverridePrintTask( Compile ).value }, // only Compile config is supported

    ethNodeChainIdOverrideSet in Compile := { ethNodeChainIdOverrideSetTask( Compile ).evaluated }, // only Compile config is supported

    ethNodeChainIdOverride in Compile := { ethNodeChainIdOverrideSetTask( Compile ).evaluated }, // only Compile config is supported

    ethNodeChainIdPrint in Compile := { ethNodeChainIdPrintTask( Compile ).value },

    ethNodeChainIdPrint in Test := { ethNodeChainIdPrintTask( Test ).value },

    ethNodeUrl in Compile := { findNodeUrlTask(warn=true)( Compile ).value },

    ethNodeUrl in Test := { findNodeUrlTask(warn=true)( Test ).value },

    ethNodeUrlDefaultDrop in Compile := { ethNodeUrlDefaultDropTask( Compile ).value },

    ethNodeUrlDefaultDrop in Test := { ethNodeUrlDefaultDropTask( Test ).value },

    ethNodeUrlDefaultPrint in Compile := { ethNodeUrlDefaultPrintTask( Compile ).value },

    ethNodeUrlDefaultPrint in Test := { ethNodeUrlDefaultPrintTask( Test ).value },

    ethNodeUrlDefaultSet in Compile := { ethNodeUrlDefaultSetTask( Compile ).evaluated },

    ethNodeUrlDefaultSet in Test := { ethNodeUrlDefaultSetTask( Test ).evaluated },

    ethNodeUrlOverrideDrop in Compile := { ethNodeUrlOverrideDropTask( Compile ).value },

    ethNodeUrlOverrideDrop in Test := { ethNodeUrlOverrideDropTask( Test ).value },

    ethNodeUrlOverridePrint in Compile := { ethNodeUrlOverridePrintTask( Compile ).value },

    ethNodeUrlOverridePrint in Test := { ethNodeUrlOverridePrintTask( Test ).value },

    ethNodeUrlOverrideSet in Compile := { ethNodeUrlOverrideSetTask( Compile ).evaluated },

    ethNodeUrlOverrideSet in Test := { ethNodeUrlOverrideSetTask( Test ).evaluated },

    ethNodeUrlOverride in Compile := { ethNodeUrlOverrideSetTask( Compile ).evaluated },

    ethNodeUrlOverride in Test := { ethNodeUrlOverrideSetTask( Test ).evaluated },

    ethNodeUrlPrint in Compile := { ethNodeUrlPrintTask( Compile ).value },

    ethNodeUrlPrint in Test := { ethNodeUrlPrintTask( Test ).value },

    ethShoeboxBackup := { ethShoeboxBackupTask.value },

    ethShoeboxRestore := { ethShoeboxRestoreTask.value },

    ethShoeboxDatabaseDumpCreate := { ethShoeboxDatabaseDumpCreateTask.value },

    ethShoeboxDatabaseDumpRestore := { ethShoeboxDatabaseDumpRestoreTask.value },

    ethTransactionDeploy in Compile := { ethTransactionDeployTask( Compile ).evaluated },

    ethTransactionDeploy in Test := { ethTransactionDeployTask( Test ).evaluated },

    ethTransactionEtherSend in Compile := { ethTransactionEtherSendTask( Compile ).evaluated },

    ethTransactionEtherSend in Test := { ethTransactionEtherSendTask( Test ).evaluated },

    ethTransactionForward in Compile := { ethTransactionForwardTask( Compile ).evaluated },

    ethTransactionForward in Test := { ethTransactionForwardTask( Test ).evaluated },

    ethTransactionGasLimitOverrideSet in Compile := { ethTransactionGasLimitOverrideSetTask( Compile ).evaluated },

    ethTransactionGasLimitOverrideSet in Test := { ethTransactionGasLimitOverrideSetTask( Test ).evaluated },

    ethTransactionGasLimitOverride in Compile := { ethTransactionGasLimitOverrideSetTask( Compile ).evaluated },

    ethTransactionGasLimitOverride in Test := { ethTransactionGasLimitOverrideSetTask( Test ).evaluated },

    ethTransactionGasLimitOverrideDrop in Compile := { ethTransactionGasLimitOverrideDropTask( Compile ).value },

    ethTransactionGasLimitOverrideDrop in Test := { ethTransactionGasLimitOverrideDropTask( Test ).value },

    ethTransactionGasLimitOverridePrint in Compile := { ethTransactionGasLimitOverridePrintTask( Compile ).value },

    ethTransactionGasLimitOverridePrint in Test := { ethTransactionGasLimitOverridePrintTask( Test ).value },

    ethTransactionGasPriceOverrideSet in Compile := { ethTransactionGasPriceOverrideSetTask( Compile ).evaluated },

    ethTransactionGasPriceOverrideSet in Test := { ethTransactionGasPriceOverrideSetTask( Test ).evaluated },

    ethTransactionGasPriceOverride in Compile := { ethTransactionGasPriceOverrideSetTask( Compile ).evaluated },

    ethTransactionGasPriceOverride in Test := { ethTransactionGasPriceOverrideSetTask( Test ).evaluated },

    ethTransactionGasPriceOverrideDrop in Compile := { ethTransactionGasPriceOverrideDropTask( Compile ).value },

    ethTransactionGasPriceOverrideDrop in Test := { ethTransactionGasPriceOverrideDropTask( Test ).value },

    ethTransactionGasPriceOverridePrint in Compile := { ethTransactionGasPriceOverridePrintTask( Compile ).value },

    ethTransactionGasPriceOverridePrint in Test := { ethTransactionGasPriceOverridePrintTask( Test ).value },

    ethTransactionInvoke in Compile := { ethTransactionInvokeTask( Compile ).evaluated },

    ethTransactionInvoke in Test := { ethTransactionInvokeTask( Test ).evaluated },

    ethTransactionLookup in Compile := { ethTransactionLookupTask( Compile ).evaluated },

    ethTransactionLookup in Test := { ethTransactionLookupTask( Test ).evaluated },

    ethTransactionMock in Compile := { ethTransactionMockTask( Compile ).evaluated },

    ethTransactionMock in Test := { ethTransactionMockTask( Test ).evaluated },

    ethTransactionNonceOverride in Compile := { ethTransactionNonceOverrideSetTask( Compile ).evaluated },

    ethTransactionNonceOverride in Test := { ethTransactionNonceOverrideSetTask( Test ).evaluated },

    ethTransactionNonceOverrideSet in Compile := { ethTransactionNonceOverrideSetTask( Compile ).evaluated },

    ethTransactionNonceOverrideSet in Test := { ethTransactionNonceOverrideSetTask( Test ).evaluated },

    ethTransactionNonceOverrideDrop in Compile := { ethTransactionNonceOverrideDropTask( Compile ).value },

    ethTransactionNonceOverrideDrop in Test := { ethTransactionNonceOverrideDropTask( Test ).value },

    ethTransactionNonceOverridePrint in Compile := { ethTransactionNonceOverridePrintTask( Compile ).value },

    ethTransactionNonceOverridePrint in Test := { ethTransactionNonceOverridePrintTask( Test ).value },

    ethTransactionNonceOverrideValue in Compile := { ethTransactionNonceOverrideValueTask( Compile ).value },

    ethTransactionNonceOverrideValue in Test := { ethTransactionNonceOverrideValueTask( Test ).value },

    ethTransactionPing in Compile := { ethTransactionPingTask( Compile ).evaluated },

    ethTransactionPing in Test := { ethTransactionPingTask( Test ).evaluated },

    ethTransactionSign in Compile := { ethTransactionSignTask( Compile ).evaluated },

    ethTransactionSign in Test := { ethTransactionSignTask( Test ).evaluated },

    ethTransactionUnsignedInvoke in Compile := { ethTransactionUnsignedInvokeTask( Compile ).evaluated },

    ethTransactionUnsignedInvoke in Test := { ethTransactionUnsignedInvokeTask( Test ).evaluated },

    ethTransactionUnsignedRaw in Compile := { ethTransactionUnsignedRawTask( Compile ).evaluated },

    ethTransactionUnsignedRaw in Test := { ethTransactionUnsignedRawTask( Test ).evaluated },

    ethTransactionUnsignedEtherSend in Compile := { ethTransactionUnsignedEtherSendTask( Compile ).evaluated },

    ethTransactionUnsignedEtherSend in Test := { ethTransactionUnsignedEtherSendTask( Test ).evaluated },

    ethTransactionRaw in Compile := { ethTransactionRawTask( Compile ).evaluated },

    ethTransactionRaw in Test := { ethTransactionRawTask( Test ).evaluated },

    ethTransactionView in Compile := { ethTransactionViewTask( Compile ).evaluated },

    ethTransactionView in Test := { ethTransactionViewTask( Test ).evaluated },

    // erc20 tasks

    erc20AllowancePrint in Compile := { erc20AllowancePrintTask( Compile ).evaluated },

    erc20AllowancePrint in Test := { erc20AllowancePrintTask( Test ).evaluated },

    erc20AllowanceSet in Compile := { erc20AllowanceSetTask( Compile ).evaluated },

    erc20AllowanceSet in Test := { erc20AllowanceSetTask( Test ).evaluated },

    erc20Balance in Compile := { erc20BalanceTask( Compile ).evaluated },

    erc20Balance in Test := { erc20BalanceTask( Test ).evaluated },

    erc20ConvertAtomsToTokens in Compile := { erc20ConvertAtomsToTokensTask( Compile ).evaluated },

    erc20ConvertAtomsToTokens in Test := { erc20ConvertAtomsToTokensTask( Test ).evaluated },

    erc20ConvertTokensToAtoms in Compile := { erc20ConvertTokensToAtomsTask( Compile ).evaluated },

    erc20ConvertTokensToAtoms in Test := { erc20ConvertTokensToAtomsTask( Test ).evaluated },

    erc20Summary in Compile := { erc20SummaryTask( Compile ).evaluated },

    erc20Summary in Test := { erc20SummaryTask( Test ).evaluated },

    erc20Transfer in Compile := { erc20TransferTask( Compile ).evaluated },

    erc20Transfer in Test := { erc20TransferTask( Test ).evaluated },

    // xens tasks

    xensClient in Compile := { xensClientTask( Compile ).value },

    xensClient in Test := { xensClientTask( Test ).value },

    // xeth tasks

    xethDefaultGasPrice in Compile := { xethDefaultGasPriceTask( Compile ).value },

    xethDefaultGasPrice in Test := { xethDefaultGasPriceTask( Test ).value },

    xethFindCacheRichParserInfo in Compile := { (xethFindCacheRichParserInfoTask( Compile ).storeAs( xethFindCacheRichParserInfo in Compile ).triggeredBy( xethTriggerDirtyAliasCache )).value },

    xethFindCacheRichParserInfo in Test := { (xethFindCacheRichParserInfoTask( Test ).storeAs( xethFindCacheRichParserInfo in Test ).triggeredBy( xethTriggerDirtyAliasCache )).value },

    xethFindCacheSeeds in Compile := { (xethFindCacheSeedsTask( Compile ).storeAs( xethFindCacheSeeds in Compile ).triggeredBy( compileSolidity in Compile )).value },

    xethFindCacheSeeds in Test := { (xethFindCacheSeedsTask( Test ).storeAs( xethFindCacheSeeds in Test ).triggeredBy( compileSolidity in Test )).value },

    xethFindCacheSessionSolidityCompilerKeys in Compile := { (xethFindCacheSessionSolidityCompilerKeysTask.storeAs( xethFindCacheSessionSolidityCompilerKeys in Compile ).triggeredBy( xethTriggerDirtySolidityCompilerList )).value },

    xethFindCurrentSolidityCompiler in Compile := { xethFindCurrentSolidityCompilerTask.value },

    xethGasPrice in Compile := { xethGasPriceTask( Compile ).value },

    xethGasPrice in Test := { xethGasPriceTask( Test ).value },

    xethGenKeyPair := { xethGenKeyPairTask.value }, // global config scope seems appropriate

    xethGenScalaStubsAndTestingResources in Compile := { xethGenScalaStubsAndTestingResourcesTask( Compile ).value },

    xethGenScalaStubsAndTestingResources in Test := { (xethGenScalaStubsAndTestingResourcesTask( Test ).dependsOn( Keys.compile in Compile )).value },

    xethInvokeData in Compile := { xethInvokeDataTask( Compile ).evaluated },

    xethInvokeData in Test := { xethInvokeDataTask( Test ).evaluated },

    xethInvokerContext in Compile := { xethInvokerContextTask( Compile ).value },

    xethInvokerContext in Test := { xethInvokerContextTask( Test ).value },

    xethKeystoreWalletV3CreateDefault := { xethKeystoreWalletV3CreateDefaultTask.value }, // global config scope seems appropriate

    xethKeystoreWalletV3CreatePbkdf2 := { xethKeystoreWalletV3CreatePbkdf2Task.value }, // global config scope seems appropriate

    xethKeystoreWalletV3CreateScrypt := { xethKeystoreWalletV3CreateScryptTask.value }, // global config scope seems appropriate

    xethLoadAbiFor in Compile := { xethLoadAbiForTask( Compile ).evaluated },

    xethLoadAbiFor in Test := { xethLoadAbiForTask( Test ).evaluated },

    xethLoadCurrentCompilationsKeepDups in Compile := { xethLoadCurrentCompilationsKeepDupsTask( Compile ).value },

    xethLoadCurrentCompilationsKeepDups in Test := { xethLoadCurrentCompilationsKeepDupsTask( Test ).value },

    xethLoadCurrentCompilationsOmitDupsCumulative in Compile := { xethLoadCurrentCompilationsOmitDupsTask( cumulative = true )( Compile ).value },

    xethLoadCurrentCompilationsOmitDupsCumulative in Test := { xethLoadCurrentCompilationsOmitDupsTask( cumulative = true )( Test ).value },

    xethLoadSeeds in Compile := { xethLoadSeedsTask( Compile ).value },

    xethLoadSeeds in Test := { xethLoadSeedsTask( Test ).value },

    xethLoadWalletsV3 in Compile := { xethLoadWalletsV3Task( Compile ).value },

    xethLoadWalletsV3 in Test := { xethLoadWalletsV3Task( Test ).value },

    xethLoadWalletsV3For in Compile := { xethLoadWalletsV3ForTask( Compile ).evaluated },

    xethLoadWalletsV3For in Test := { xethLoadWalletsV3ForTask( Test ).evaluated },

    xethOnLoadAutoImportWalletsV3 := { xethOnLoadAutoImportWalletsV3Task.value },

    xethOnLoadBanner := { xethOnLoadBannerTask.value },

    xethOnLoadRecoverInconsistentSchema := { xethOnLoadRecoverInconsistentSchemaTask.value },

    xethOnLoadSolicitCompilerInstall := { xethOnLoadSolicitCompilerInstallTask.value },

    xethOnLoadSolicitWalletV3Generation := { xethOnLoadSolicitWalletV3GenerationTask.value },

    xethNamedAbis in Compile := { xethNamedAbisTask( Compile ).value },

    xethNamedAbis in Test := { xethNamedAbisTask( Test ).value },

    xethTransactionCount in Compile := { xethTransactionCountTask( Compile ).value },

    xethTransactionCount in Test := { xethTransactionCountTask( Test ).value },

    xethShoeboxRepairPermissions := { xethShoeboxRepairPermissionsTask.value },

    xethSignerFinder in Compile := { xethSignerFinderTask( Compile ).value },

    xethSignerFinder in Test := { xethSignerFinderTask( Test ).value },

    xethSqlQueryShoeboxDatabase := { xethSqlQueryShoeboxDatabaseTask.evaluated }, // we leave this unscoped, just because scoping it to Compile seems weird

    xethSqlUpdateShoeboxDatabase := { xethSqlUpdateShoeboxDatabaseTask.evaluated }, // we leave this unscoped, just because scoping it to Compile seems weird

    xethStubEnvironment in Compile := { xethStubEnvironmentTask( Compile ).value },

    xethStubEnvironment in Test := { xethStubEnvironmentTask( Test ).value },

    // we leave triggers unscoped, not for any particular reason
    // (we haven't tried scoping them and seen a problem)
    xethTriggerDirtyAliasCache := { xethTriggerDirtyAliasCacheTask.value }, // this is a no-op, its execution just triggers a re-caching of aliases

    xethTriggerDirtySolidityCompilerList := { xethTriggerDirtySolidityCompilerListTask.value }, // this is a no-op, its execution just triggers a re-caching of aliases

    xethUpdateContractDatabase in Compile := { xethUpdateContractDatabaseTask( Compile ).value },

    xethUpdateContractDatabase in Test := { xethUpdateContractDatabaseTask( Test ).value },

    xethUpdateSessionSolidityCompilers in Compile := { xethUpdateSessionSolidityCompilersTask.value },

    compileSolidity in Compile := { compileSolidityTask( Compile ).value },

    compileSolidity in Test := { compileSolidityTask( Test ).value },

    commands ++= Seq( ethDebugGanacheRestartCommand, ethDebugGanacheTestCommand ),

    libraryDependencies ++= {
      ethcfgScalaStubsPackage.?.value.fold( Nil : Seq[ModuleID] )( _ => Consuela.ModuleID :: Nil )
    },

    resolvers ++= {
      if (Consuela.ModuleID.revision.endsWith("SNAPSHOT")) {
        ("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots") :: Nil
      }
      else {
        Nil
      }
    },

    autoStartServer := false,

    Keys.compile in Compile := { (Keys.compile in Compile).dependsOn(compileSolidity in Compile).value },

    Keys.compile in Test := { (Keys.compile in Test).dependsOn(compileSolidity in Test).value },

    onLoad in Global := {
      val origF : State => State = (onLoad in Global).value
      val newF  : State => State = ( state : State ) => {
        val lastState = origF( state )
        val state1 = attemptAdvanceStateWithTask( xethOnLoadRecoverInconsistentSchema,                 lastState )
        val state2 = attemptAdvanceStateWithTask( xethOnLoadAutoImportWalletsV3,                       state1    )
        val state3 = attemptAdvanceStateWithTask( xethOnLoadSolicitWalletV3Generation,                 state2    )
        val state4 = attemptAdvanceStateWithTask( xethOnLoadSolicitCompilerInstall,                    state3    )
        val state5 = attemptAdvanceStateWithTask( xethFindCacheSessionSolidityCompilerKeys in Compile, state4    )
        val state6 = attemptAdvanceStateWithTask( xethFindCacheRichParserInfo in Compile,              state5    )
        val state7 = attemptAdvanceStateWithTask( xethFindCacheRichParserInfo in Test,                 state6    )
        val state8 = attemptAdvanceStateWithTask( xethOnLoadBanner,                                    state7    )
        state8
      }
      newF
    },

    onUnload in Global := {
      val origF : State => State = (onUnload in Global).value
      val newF  : State => State = ( state : State ) => {
        val lastState = origF( state )
        resetAllState()
        lastState
      }
      newF
    },

    sourceGenerators in Compile += (xethGenScalaStubsAndTestingResources in Compile).taskValue,

    sourceGenerators in Test += (xethGenScalaStubsAndTestingResources in Test).taskValue,

    watchSources ++= {
      val dir = (ethcfgSoliditySource in Compile).value
      val filter = new FilenameFilter {
        def accept( dir : File, name : String ) = ResolveCompileSolidity.goodSolidityFileName( name )
      }
      if ( dir.exists ) {
        dir.list( filter ).map( name => new File( dir, name ) ).toSeq
      } else {
        Nil
      }
    }
  )

  // private, internal task definitions

  private val EmptyTask : Initialize[Task[Unit]] = Def.task( () )

  private def logSessionInfoTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=false)(config).value
    val mbSender = (config / ethAddressSender).value

    log.info( s"The session is now active on chain with ID ${chainId}." )
    mbSender match {
      case Some( sender ) => log.info( s"The current session sender is ${verboseAddress( chainId, sender )}." )
      case None           => {
        log.warn( "There is no sender available for the current session." )
        log.warn( "Consider using 'ethAddressSenderDefaultSet' or 'ethAddressSenderOverrideSet' to define one." )
      }
    }
    Mutables.SenderOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: The sender has been overridden to ${verboseAddress( chainId, ovr )}.") }
    Mutables.NodeUrlOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: The node URL has been overridden to '${ovr}'.") }
    Mutables.AbiOverrides.get( chainId ).foreach { ovr => log.warn( s"""NOTE: ABI overrides are set for the following addresses on this chain: ${ovr.keys.map(hexString).mkString(", ")}""" ) }
    Mutables.GasLimitTweakOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: A gas limit override remains set for this chain, ${formatGasLimitTweak( ovr )}." ) }
    Mutables.GasPriceTweakOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: A gas price override remains set for this chain, ${formatGasPriceTweak( ovr )}." ) }
    Mutables.NonceOverrides.get( chainId ).foreach { ovr => log.warn( s"NOTE: A nonce override remains set for this chain. Its value is ${ovr}." ) }
  }

  private def markPotentiallyResetChainId( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    val logit = logSessionInfoTask( config ).value
    xethTriggerDirtyAliasCache
  }

  private def findGasLimitTweak( warnOverridden : Boolean )( config : Configuration ) : Initialize[Task[Invoker.MarkupOrOverride]] = Def.task {
    val log = streams.value.log

    val rawChainId = findNodeChainIdTask(warn=true)(config).value

    val gasLimitMarkup  = ethcfgGasLimitMarkup.value
    val gasLimitCap     = ethcfgGasLimitCap.?.value
    val gasLimitFloor   = ethcfgGasLimitFloor.?.value

    Mutables.GasLimitTweakOverrides.get( rawChainId ) match {
      case Some( overrideTweak ) => {
        if ( warnOverridden ) log.warn( s"Gas limit override set, ${formatGasLimitTweak( overrideTweak )}")
        overrideTweak
      }
      case None => {
        Invoker.Markup( gasLimitMarkup, gasLimitCap, gasLimitFloor )
      }
    }
  }

  private def findGasPriceTweak( warnOverridden : Boolean )( config : Configuration ) : Initialize[Task[Invoker.MarkupOrOverride]] = Def.task {
    val log = streams.value.log

    val rawChainId = findNodeChainIdTask(warn=true)(config).value
    
    val gasPriceMarkup  = ethcfgGasPriceMarkup.value
    val gasPriceCap     = ethcfgGasPriceCap.?.value
    val gasPriceFloor   = ethcfgGasPriceFloor.?.value

    Mutables.GasPriceTweakOverrides.get( rawChainId ) match {
      case Some( overrideTweak ) => {
        if ( warnOverridden) log.warn( s"Gas price override set, ${formatGasPriceTweak( overrideTweak )}" )
        overrideTweak
      }
      case None => {
        Invoker.Markup( gasPriceMarkup, gasPriceCap, gasPriceFloor )
      }
    }
  }

  private def findAddressSenderTask( warn : Boolean )( config : Configuration ) : Initialize[Task[Failable[EthAddress]]] = Def.task {
    Failable {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val mbOverrideAddressSender = Mutables.SenderOverrides.get( chainId )
      mbOverrideAddressSender match {
        case Some( address ) => {
          address
        }
        case None => {
          val mbDbAddressSender = shoebox.Database.findDefaultSenderAddress( chainId ).assert
          (config/ethcfgAddressSender).?.value match {
            case Some( addressStr ) => {
              val address = EthAddress( addressStr )
              if ( warn ) {
                val warningLinesBuilder = {
                  () => {
                    val pfx = configPrefix( config )
                    mbDbAddressSender.map ( dbAddressSender =>
                      Seq (
                        s"'${pfx}ethcfgAddressSender' has been explicitly set to '${hexString(address)}' in the build or as a global setting in the .sbt directory.",
                        s" + This value will be used in preference to the default sender defined in the sbt-ethereum shoebox via '${pfx}ethAddressSenderDefaultSet' (currently '${hexString(dbAddressSender)}').",
                        s" + However, you can temporarily override this hard-coded value for the current session using '${pfx}ethAddressSenderOverrideSet'."
                      )
                    )
                  }
                }
                Mutables.OneTimeWarner.warn( OneTimeWarnerKey.AddressSenderInBuild, config, chainId, log, warningLinesBuilder )
              }
              address
            }
            case None if mbDbAddressSender.nonEmpty => {
              mbDbAddressSender.get
            }
            case None if config == Compile => {
              LastResortMaybeEthAddressSender.getOrElse {
                throw new SenderNotAvailableException(
                  "No address for sender! None of 'ethAddressSenderOverride', 'ethcfgAddressSender', 'ethAddressSenderDefault' " +
                    s"for chain with ID ${chainId}, System property 'eth.sender', nor environment variable 'ETH_SENDER' have been set."
                )
              }
            }
            case None if config == Test => {
              LastResortMaybeTestEthAddressSender.getOrElse {
                throw new SenderNotAvailableException( "No address for testing could be found. Which is weird, because there should be a hard-coded default value." )
              }
            }
            case None => {
              throw new UnexpectedConfigurationException( config )
            }
          }
        }
      }
    }
  }

  // make sure this task is kept in sync with ethNodeChainIdPrint
  private def maybeFindNodeChainIdTask( warn : Boolean )( config : Configuration ) : Initialize[Task[Option[Int]]] = Def.task {
    val log = streams.value.log
    val mbOverrideNodeChainId = {
      if (config == Compile ) { // chain ID overrides only supported for Compile config
        Mutables.ChainIdOverride.synchronized {
          Mutables.ChainIdOverride.get
        }
      }
      else {
        None
      }
    }
    mbOverrideNodeChainId match {
      case Some( id ) => {
        Some( id )
      }
      case None => {
        val mbDbNodeChainId = {
          config match {
            case Compile => shoebox.Database.getDefaultChainId().assert
            case Test    => None // default chain IDs not supported for Test. modifications must be embedded in build
            case _       => None
          }
        }
        (config/ethcfgNodeChainId).?.value match {
          case Some( explicitId ) => {
            if ( warn ) {
              val warningLinesBuilder = {
                () => {
                  val pfx = configPrefix( config )
                  mbDbNodeChainId.map ( dbNodeChainId =>
                    Seq (
                      s"'${pfx}ethcfgNodeChainId' has been explicitly set to ${explicitId} in the build or as a global setting in the .sbt directory.",
                      s" + This value will be used in preference to the default chain ID defined in the sbt-ethereum shoebox via '${pfx}ethNodeChainIdDefaultSet' (currently ${dbNodeChainId}).",
                      s" + However, you can temporarily override this hard-coded value for the current session using '${pfx}ethNodeChainIdOverrideSet'."
                    )
                  )
                }
              }
              Mutables.OneTimeWarner.warn( OneTimeWarnerKey.NodeChainIdInBuild, config, explicitId, log, warningLinesBuilder )
            }
            Some( explicitId )
          }
          case None => {
            mbDbNodeChainId orElse findBackstopChainId( config )
          }
        }
      }
    }
  }

  private def findNodeChainIdTask( warn : Boolean )( config : Configuration ) : Initialize[Task[Int]] = Def.task {
    val mbNodeChainId = maybeFindNodeChainIdTask( warn )( config ).value

    def oops : Nothing = {
      config match {
        case Compile | Test => throw new SbtEthereumException( s"Could not find a chain ID for supported configuration '${config}'." )
        case _              => throw new UnexpectedConfigurationException( config )
      }
    }

    mbNodeChainId.getOrElse( oops )
  }

  // make sure this task is kept in sync with ethNodeUrlPrint
  private def maybeFindNodeUrlTask( warn : Boolean )( config : Configuration ) : Initialize[Task[Option[String]]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val mbOverrideNodeUrl = Mutables.NodeUrlOverrides.get( chainId )
    mbOverrideNodeUrl match {
      case Some( url ) => {
        Some( url )
      }
      case None => {
        val mbDbNodeUrl = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
        (config/ethcfgNodeUrl).?.value match {
          case Some( url ) => {
            if ( warn ) {
              val warningLinesBuilder = {
                () => {
                  val pfx = configPrefix( config )
                  mbDbNodeUrl.map ( dbNodeUrl =>
                    Seq (
                      s"'${pfx}ethcfgNodeUrl' has been explicitly set to '${url}' in the build or as a global setting in the .sbt directory.",
                      s" + This value will be used in preference to the default node URL defined in the sbt-ethereum shoebox via '${pfx}ethNodeUrlDefaultSet' (currently '${dbNodeUrl}').",
                      s" + However, you can temporarily override this hard-coded value for the current session using '${pfx}ethNodeUrlOverrideSet'."
                    )
                  )
                }
              }
              Mutables.OneTimeWarner.warn( OneTimeWarnerKey.NodeUrlInBuild, config, chainId, log, warningLinesBuilder )
            }
            Some( url )
          }
          case None => {
            mbDbNodeUrl orElse findBackstopUrl(warn=warn)( log, config, chainId )
          }
        }
      }
    }
  }

  private def findNodeUrlTask( warn : Boolean )( config : Configuration ) : Initialize[Task[String]] = Def.task {
    val mbNodeUrl = maybeFindNodeUrlTask( warn )( config ).value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    mbNodeUrl.getOrElse( throw new NodeUrlNotAvailableException( s"No 'ethNodeUrl' for chain with ID '${chainId}' is curretly available." ) )
  }

  private def findCurrentSenderLazySignerTask( config : Configuration ) : Initialize[Task[LazySigner]] = Def.task {
    val s = state.value
    val log = streams.value.log
    val is = interactionService.value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val caller = findAddressSenderTask(warn=true)(config).value.assert
    val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
    Mutables.MainSignersManager.findUpdateCacheLazySigner(s, log, is, chainId, caller, autoRelockSeconds, true )
  }

  private def findTransactionLoggerTask( config : Configuration ) : Initialize[Task[Invoker.TransactionLogger]] = Def.task {
    val chainId = findNodeChainIdTask(warn=true)(config).value

    ( tle : Invoker.TransactionLogEntry, ec : ExecutionContext ) => Future (
      shoebox.TransactionLog.logTransaction( chainId, tle.jsonRpcUrl, tle.transaction, tle.transactionHash ).get
    )( ec )
  }

  private def findExchangerConfigTask( config : Configuration ) : Initialize[Task[Exchanger.Config]] = Def.task {
    val httpUrl = findNodeUrlTask(warn=true)(config).value
    val timeout = xethcfgAsyncOperationTimeout.value
    Exchanger.Config( new URL(httpUrl), timeout ) 
  }

  // task definitions

  // ens tasks

  private def ensAddressLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      val chainId   = findNodeChainIdTask(warn=true)(config).value
      val ensClient = ( config / xensClient).value
      val path      = parser.parsed.fullName
      val mbAddress = ensClient.address( path )

      mbAddress match {
        case Some( address ) => println( s"The name '${path}' resolves to address ${verboseAddress(chainId, address)}." )
        case None            => println( s"The name '${path}' does not currently resolve to any address." )
      }

      mbAddress
    }
  }

  private def ensAddressSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathAddressParser )

    Def.inputTask {
      val log                  = streams.value.log
      val lazySigner           = findCurrentSenderLazySignerTask( config ).value
      val chainId              = findNodeChainIdTask(warn=true)(config).value
      val ensClient            = ( config / xensClient).value
      val is                   = interactionService.value
      val mbDefaultResolver    = ( config / enscfgNameServicePublicResolver).?.value
      val ( epp, address )     = parser.parsed
      val ensName              = epp.fullName
      val nonceOverride        = unwrapNonceOverrideBigInt( Some( log ), chainId )

      try {
        ensClient.setAddress( lazySigner, ensName, address, forceNonce = nonceOverride )
      }
      catch {
        case e : ens.NoResolverSetException => {
          val defaultResolver = mbDefaultResolver.getOrElse( throw e )
          val setAndRetry = {
            log.warn( s"No resolver has been set for '${ensName}'. If you wish, you can attach it to the default public resolver and then set the address." )
            nonceOverride.foreach { nonce =>
              log.warn( s"Note: The currently set nonce override of ${nonce} will be ignored. To control the nonce, set the resolver separately, then retry." )
            }
            kludgeySleepForInteraction()
            is.readLine( s"Do you wish to use the default public resolver '${hexString(defaultResolver)}'? [y/n] ", false )
              .getOrElse( throwCantReadInteraction )
              .trim()
              .equalsIgnoreCase("y")
          }
          if ( setAndRetry ) {
            log.info( s"Preparing transaction to set the resolver." ) 
            ensClient.setResolver( lazySigner, ensName, defaultResolver )
            log.info( s"Resolver for '${ensName}' set to public resolver '${hexString( defaultResolver )}'." )

            // await propogation back to us that the resolver has actually been set
            log.info( "Verifiying resolver." )
            var resolverSet = false
            var tick = false
            while ( !resolverSet ) {
              try {
                Thread.sleep(1000)
                val mbFound = ensClient.resolver( ensName )
                mbFound.foreach { found =>
                  assert( found == defaultResolver, s"Huh? The resolver we just set to ${hexString(defaultResolver)} was found to be ${hexString(found)}. Bailing." )
                  resolverSet = true
                }
              }
              catch {
                case _ : ens.NoResolverSetException => {
                  tick = true
                  print( '.' )
                  /* continue */
                }
              }
            }
            if (tick) println()
            log.info( s"Preparing transaction to set address." ) 
            ensClient.setAddress( lazySigner, ensName, address )
          } else {
            throw e
          }
        }
      }
      log.info( s"The name '${ensName}' now resolves to ${verboseAddress(chainId, address)}." )
    }
  }

  private def ensMigrateRegistrarTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      import ens.ParsedPath._

      val log              = streams.value.log
      val chainId          = findNodeChainIdTask(warn=true)(config).value
      val lazySigner       = findCurrentSenderLazySignerTask( config ).value
      val ensClient        = ( config / xensClient ).value
      val epp              = parser.parsed
      val nonceOverride    = unwrapNonceOverrideBigInt( Some( log ), chainId )
      

      epp match {
        case bn : BaseNameTld => {
          val ( baseName, tld ) = bn.baseNameTld
          val forTldClient = ensClient.forTopLevelDomain( tld )
          forTldClient.nameExpires( bn.baseName ) match {
            case Some( instant ) => {
              log.warn( s"ENS name '${bn.fullPath}' is already known to the current registrar, and is registered until ${formatInstant(instant)}." ) 
            }
            case None => {
              forTldClient.migrateFromPredecessor( lazySigner, baseName, forceNonce = nonceOverride )
              log.info( s"The name '${bn.fullPath}' has successfully migrated." )
            }
          }
        }
        case _  : Tld => {
          bail( s"Top-level-domains like '${epp.fullPath}' cannot be migrated." )
        }
        case sn : Subnode => {
          val ( baseName, tld ) = sn.baseNameTld
          bail( s"Cannot currently migrate subnodes like '${sn.fullPath}', only names directly below the top-level, like '${baseName}.${tld}'." )
        }
        case _  : Reverse => {
          bail( s"Reverse paths ${epp.fullPath} cannot be migrated." )
        }
      }
    }
  }

  private def ensNamePriceTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      import ens.ParsedPath._

      val is        = interactionService.value
      val log       = streams.value.log
      val chainId   = findNodeChainIdTask(warn=true)(config).value
      val ensClient = ( config / xensClient ).value
      val epp       = parser.parsed

      val baseCurrencyCode = ethcfgBaseCurrencyCode.value

      def doFindRent( name : String, rmd : ensClient.RegistrarManagedDomain ) : Unit = {
        require( rmd.hasValidRegistrar, s"There is no registrar associated with ENS domain '${rmd.domain}'." )

        val DurationParsers.SecondsViaUnit(seconds, unit) = {
          queryDurationInSeconds( log, is, """For how long would you like to rent the name (ex: "3 years")? """ ).getOrElse( aborted( "User failed to supply a desired time interval." ) )
        }
        val minTime = rmd.minRegistrationDurationInSeconds
        val desiredPeriod = formatDurationInSeconds( seconds, unit )
        if ( seconds < minTime ) {
          log.warn( s"Registration period must be longer than ${formatDurationInSeconds(minTime.toLong, unit)}." )
          log.warn( s"You cannot register for just ${desiredPeriod}, although you can extend an existing registration by that amount." )
        }

        val rentInWei = rmd.rentPriceInWei( name, seconds )
        val etherValue = EthValue( rentInWei, Denominations.Ether ).denominated
        println( s"In order to register or extend '${epp.fullPath}' for ${desiredPeriod}, it would cost ${etherValue} ether (${rentInWei} wei)." )
        printFiatValueForEtherValue( println(_) )( chainId, baseCurrencyCode, etherValue )
      }

      epp match {
        case bntld : BaseNameTld => {
          doFindRent( bntld.baseName, ensClient.forTopLevelDomain( bntld.tld ) )
        }
        case sn : Subnode => {
          doFindRent( sn.label, ensClient.RegistrarManagedDomain( sn.parent.fullName ) )
        }
        case tld : Tld => {
          bail( s"Top-level domain names (like '${tld.fullPath}') are not paid rentals." )
        }
        case rev : Reverse => {
          bail( s"Reverse ENS names (like '${rev.fullPath}') are not paid rentals." )
        }
      }
    }
  }

  private def markupEnsRent( rawRent : BigInt ) : BigInt = rounded( BigDecimal( rawRent ) * BigDecimal(1 + EnsRegisterRenewMarkup) )

  private def ensNameRegisterTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathMbAddressMbSecretParser )

    Def.inputTask {
      val is        = interactionService.value
      val log       = streams.value.log
      val chainId   = findNodeChainIdTask(warn=true)(config).value
      val ensClient = ( config / xensClient ).value

      val lazySigner = findCurrentSenderLazySignerTask( config ).value
      val sender = lazySigner.address

      val ( epp, mbAddress, mbSecret ) = parser.parsed

      val registrant = mbAddress.getOrElse( sender )

      val nonceOverride = unwrapNonceOverrideBigInt( Some( log ), chainId )

      val baseCurrencyCode = ethcfgBaseCurrencyCode.value

      lazy val ( commitmentNonceOverride , registrationNonceOverride ) = {
        ( nonceOverride, mbSecret ) match {
          case ( None, _ ) => {
            ( None, None )
          }
          case ( Some( no ), Some( secret ) ) => {
            log.warn( s"A nonce override of ${no} has been set." )
            log.warn(  "Since a secret has been provided, only one transaction will be required to register and this override will be used." )
            val ok = queryYN( is, "Is this okay? [y/n] " )
            if (!ok) aborted( "Nonce override rejected. (Use 'ethTransactionNonceOverrideDrop' to use an automatically set nonce.)" )
            ( None, Some( no ) )
          }
          case ( Some( no ), None ) => {
            log.warn( s"A nonce override of ${no} has been set." )
            log.warn( s"Since no secret has been provided, two transactions will be required to register '${epp.fullName}'." )
            log.warn( s"Nonce override value ${no} will be used for the commitment transaction, and ${no+1} will be used for finalizing the registration." )
            val ok = queryYN( is, "Is this okay? [y/n] " )
            if (!ok) aborted( "Nonce override rejected. (Use 'ethTransactionNonceOverrideDrop' to use an automatically set nonce.)" )
            ( Some( no ), Some( no+1 ) )
          }
        }
      }

      def doNameCommitRegister( name : String, rmd : ensClient.RegistrarManagedDomain ) : Unit = {
        require( rmd.hasValidRegistrar, s"There is no registrar associated with ENS domain '${rmd.domain}'." )

        @tailrec
        def doQueryDurationInSecondsPaymentInWei : ( Long, BigInt ) = {
          val DurationParsers.SecondsViaUnit(seconds, unit) = {
            queryDurationInSeconds( log, is, """For how long would you like to rent the name (ex: "3 years")? """ ).getOrElse( aborted( "User failed to supply a desired time interval." ) )
          }
          val minTime = rmd.minRegistrationDurationInSeconds
          val desiredPeriod = formatDurationInSeconds( seconds, unit )
          if ( seconds < minTime ) {
            log.warn( s"Registration period must be longer than ${formatDurationInSeconds(minTime.toLong, unit)}." )
            log.warn( s"You cannot register for just ${desiredPeriod}, although you can extend an existing registration by that amount." )
            println( "Try again. Or just press [enter] to abort." )
            doQueryDurationInSecondsPaymentInWei
          }
          else {
            val paymentInWei = interactiveAssertAcceptablePayment( log, is, chainId, epp, name, seconds, unit, baseCurrencyCode, rmd )
            ( seconds, paymentInWei )
          }
        }

        def doNameCommit : ( Long, ens.Commitment ) = {
          val minSeconds = rmd.minCommitmentAgeInSeconds.toLong
          val maxSeconds = rmd.maxCommitmentAgeInSeconds.toLong
          val maxHours   = maxSeconds.toDouble / 3600
          val commitment = rmd.makeCommitment( name, registrant )
          println()
          println( s"In order to register '${epp.fullName}', two transactions will be performed..." )
          println( s"     (1) a commitment transaction" )
          println( s"     (2) a registration transaction" )
          println(  "You will need to approve both transactions." )
          println( s"A ${minSeconds} second pause will be required between the two transactions." ) 
          println()
          println(  "Establishing registration commitment." )
          println( s"""The random "secret" to which we are committing is '${hexString(commitment.secret)}'.""" )
          println(  "If we sadly time out while waiting for the transaction to mine, if it does eventually successfully mine...")
          println( s"  you can continue where you left off with" )
          println( s"    'ensNameRegister ${epp.fullName} ${hexString(registrant)} ${hexString(commitment.secret)}'" )
          commitmentNonceOverride.foreach { cno =>
            println( s"  But you will need to drop the nonce override with 'ethTransactionNonceOverrideDrop' or set it to ${cno+1}." )
          }
          println( s"The registration must be completed after a minimum of ${minSeconds} seconds, but within a maximum of ${maxSeconds} seconds (${maxHours} hours) or the commitment will be lost." )
          println(  "Preparing commitment transaction..." )
          rmd.commit( lazySigner, commitment, forceNonce = commitmentNonceOverride )
          log.info( s"Temporary commitment of name '${name}' for registrant ${verboseAddress(chainId,registrant)} has succeeded!" )
          ( minSeconds, commitment )
        }

        def doNameRegister( durationInSeconds : Long, paymentInWei : BigInt, secret : immutable.Seq[Byte] ) : Unit = {
          println()
          println( s"Now finalizing the registration of name '${name}' for registrant '${registrant}'." )
          println(  "If we sadly time out while waiting for the transaction to mine, it still may eventually succeed." )
          println( s"Use 'ensNameStatus ${epp.fullName}' to check." )
          rmd.register( lazySigner, name, registrant, durationInSeconds, secret, paymentInWei, forceNonce = registrationNonceOverride )
          log.info( s"Name '${epp.fullName}' has been successfully registered to ${verboseAddress(chainId, registrant)}!" )
          log.info( s"The registration is valid until '${formatInstantOrUnknown(rmd.nameExpires(name))}'" )
        }

        val ( durationInSeconds, paymentInWei ) = doQueryDurationInSecondsPaymentInWei
        val ( mbWait, secret ) = {
          mbSecret match {
            case Some( secret ) => ( None, secret )
            case None => {
              val ( wait, commitment ) = doNameCommit
              ( Some( wait ), commitment.secret.widen )
            }
          }
        }
        mbWait.foreach { waitSeconds =>
          println
          println( s"We must wait a minimum of ${waitSeconds} seconds before we can complete the registration." )
          println( "We'll add 10% to be sure. Please wait.")
          Thread.sleep( ((waitSeconds * 1000) * 11)/10 )
        }
        doNameRegister( durationInSeconds, paymentInWei, secret )
      }

      import ens.ParsedPath._

      epp match {
        case bntld : BaseNameTld => {
          doNameCommitRegister( bntld.baseName, ensClient.forTopLevelDomain( bntld.tld ) )
        }
        case sn : Subnode => {
          doNameCommitRegister( sn.label, ensClient.RegistrarManagedDomain( sn.parent.fullName ) )
        }
        case tld : Tld => {
          bail( s"Top-level domain names (like '${tld.fullPath}') cannot be registered." )
        }
        case rev : Reverse => {
          bail( s"Reverse ENS names (like '${rev.fullPath}') cannot be registered." )
        }
      }
    }
  }

  private def interactiveAssertAcceptablePayment(
    log : sbt.Logger,
    is : sbt.InteractionService,
    chainId : Int,
    epp : ens.ParsedPath,
    name : String,
    seconds : Long,
    unit : ChronoUnit,
    baseCurrencyCode : String,
    rmd : ens.Client#RegistrarManagedDomain
  ) : BigInt = {
    val desiredPeriod = formatDurationInSeconds( seconds, unit )
    val rentInWei = rmd.rentPriceInWei( name, seconds )
    val markedupRentInWei = markupEnsRent( rentInWei )
    val rentInEther = EthValue( rentInWei, Denominations.Ether ).denominated
    val markedupRentInEther = EthValue( markedupRentInWei, Denominations.Ether ).denominated
    println( s"In order to rent '${epp.fullPath}' for ${desiredPeriod}, it would cost approximately ${rentInEther} ether (${rentInWei} wei)." )
    println( s"""To be sure the renewal succeeds, we'll mark it up a bit to ${markedupRentInEther} ether (${markedupRentInWei} wei). Any "change" will be returned.""" )
    printFiatValueForEtherValue( println(_) )( chainId, baseCurrencyCode, markedupRentInEther )
    val ok = queryYN( is, "Is that okay? [y/n] " )
    if (!ok) aborted( "User rejected renewal fee." )
    markedupRentInWei
  }

  private def ensNameExtendTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      val is         = interactionService.value
      val log        = streams.value.log
      val chainId    = findNodeChainIdTask(warn=true)(config).value
      val ensClient  = ( config / xensClient ).value
      val lazySigner = findCurrentSenderLazySignerTask( config ).value
      val epp        = parser.parsed

      val nonceOverride = unwrapNonceOverrideBigInt( Some( log ), chainId )

      val baseCurrencyCode = ethcfgBaseCurrencyCode.value

      def doNameRenew( name : String, rmd : ensClient.RegistrarManagedDomain ) : Unit = {
        require( rmd.hasValidRegistrar, s"There is no registrar associated with ENS domain '${rmd.domain}'." )

        val svu = {
          queryDurationInSeconds( log, is, """For how long would you like to extend the name (ex: "3 years")? """ ).getOrElse( aborted( "User failed to supply a desired time interval." ) )
        }
        val seconds = svu.seconds
        val paymentInWei = interactiveAssertAcceptablePayment( log, is, chainId, epp, name, seconds, svu.unitProvided, baseCurrencyCode, rmd )
        rmd.renew( lazySigner, name, seconds, paymentInWei, forceNonce = nonceOverride )
        log.info( s"Registration of '${epp.fullName}' has been extended for ${seconds} seconds (${svu.formattedNumUnits})." )
        log.info( s"The registration is now valid until '${formatInstantOrUnknown(rmd.nameExpires(name))}'" )
      }

      import ens.ParsedPath._

      epp match {
        case bntld : BaseNameTld => {
          doNameRenew( bntld.baseName, ensClient.forTopLevelDomain( bntld.tld ) )
        }
        case sn : Subnode => {
          doNameRenew( sn.label, ensClient.RegistrarManagedDomain( sn.parent.fullName ) )
        }
        case tld : Tld => {
          bail( s"Top-level domain names (like '${tld.fullPath}') are not paid rentals." )
        }
        case rev : Reverse => {
          bail( s"Reverse ENS names (like '${rev.fullPath}') are not paid rentals." )
        }
      }
    }
  }

  private def ensNameHashesTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      val log       = streams.value.log
      val epp       = parser.parsed
      log.info( s"The ENS namehash of '${epp.fullName}' is '${hexString(epp.namehash)}'." )
      epp match {
        case hbn : ens.ParsedPath.HasBaseName => {
          log.info( s"The labelhash of label '${hbn.label}' is '${hexString(hbn.labelhash)}'." )
        }
        case _ => /* ignore */
      }
    }
  }

  private val GracePeriod90DaysMillis = 90L * 24 * 3600 * 1000

  private def ensNameStatusTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      val log       = streams.value.log
      val chainId   = findNodeChainIdTask(warn=true)(config).value
      val ensClient = ( config / xensClient ).value
      val epp       = parser.parsed

      epp match {
        case pp_tld : ens.ParsedPath.Tld => {
          val forTldClient = ensClient.forTopLevelDomain( pp_tld.tld )
          forTldClient.maybeDomainRegistrarAddress match {
            case Right( address ) => log.info( s"ENS name '${pp_tld.tld}' appears to be a valid top-level domain whose registrar has address '${hexString(address)}'." )
            case Left ( problem ) => {
              log.info( s"ENS name '${pp_tld.tld}' does not appear to be a valid top-level domain. (Could not find or contact a registrar. See sbt-ethereum debug logs for more details.)" )
              DEBUG.log( s"Could not find or contact registrar for putative ENS top-level domain '${pp_tld.tld}'.", problem )
            }
          }
        }
        case pp_bntld : ens.ParsedPath.BaseNameTld => {
          val ( baseName, tld ) = pp_bntld.baseNameTld
          val forTldClient = ensClient.forTopLevelDomain( tld )
          if ( forTldClient.hasValidRegistrar ) {
            val path = pp_bntld.fullPath
            if ( forTldClient.isValid( baseName ) ) {
              if ( forTldClient.isAvailable( baseName ) ) {
                log.info( s"'${path}' is currently available.")
              }
              else {
                ensClient.owner( path ) match {
                  case Some(owner) => log.info( s"ENS name '${path}' is currently owned by '${hexString(owner)}'." )
                  case None        => log.info( s"ENS name '${path}' is not avaiable, but does not have an owner. It is likely in a transitional state" )
                }
                forTldClient.nameExpires( baseName ) match {
                  case Some( expiry ) => {
                    val now = Instant.now()
                    if ( expiry.isAfter( now ) ) {
                      log.info( s"This registration will expire at '${formatInstant(expiry)}'." )
                    }
                    else {
                      log.info( s"This registration expired at '${formatInstant(expiry)}'." )
                      if ( ( now.toEpochMilli() - expiry.toEpochMilli() ) < GracePeriod90DaysMillis ) {
                        log.warn(  "This registration may still be valid within an unguaranteed 90-day grace period, but name owners should not rely upon that." )
                        log.warn( s"If it is not extended, the name should become available to new registrants by ${formatInstant(expiry.plusMillis(GracePeriod90DaysMillis))} at latest." )
                      }
                    }
                  }
                  case None => {
                    log.info( s"The expiration date of this domain could not be determined. (Perhaps it needs to be migrated to the current registrar.)" )
                  }
                }
              }
            }
            else {
              log.warn( s"'${path}' is not currently a valid name." )
            }
          }
          else {
            val path = pp_bntld.fullPath
            log.info( s"The top-level domain of '${path}' does not appear to have a valid registrar.")
            ensClient.owner( path ) match {
              case Some(owner) => log.info( s"Nevertheless, it appears '${path}' is currently owned by '${hexString(owner)}'." )
              case None        => log.info( s"ENS name '${path}' does not have an owner." )
            }
          }
        }
        case pp_subnode : ens.ParsedPath.Subnode => {
          val path = pp_subnode.fullPath
          ensClient.owner( path ) match {
            case Some(owner) => log.info( s"Subnode '${path}' is currently owned by '${hexString(owner)}'." )
            case None        => log.info( s"Subnode '${path}' does not have an owner." )
          }
          val parentPath = pp_subnode.parent.fullPath
          ensClient.owner( parentPath ) match {
            case Some(owner) => log.info( s"Its parent '${parentPath}' is currently owned by '${hexString(owner)}'." )
            case None        => log.info( s"Its parent '${parentPath}' does not have an owner." )
          }
        }
        case pp_reverse : ens.ParsedPath.Reverse => { // XXX
          log.warn( s"Status of reverse names not currently supported. No information for '${pp_reverse.fullPath}'." )
        }
      }
    }
  }

  private def ensOwnerLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      val chainId   = findNodeChainIdTask(warn=true)(config).value
      val ensClient = ( config / xensClient).value
      val name      = parser.parsed.fullName
      val mbOwner   = ensClient.owner( name )

      mbOwner match {
        case Some( address ) => println( s"The name '${name}' is owned by address ${verboseAddress(chainId, address)}." )
        case None            => println( s"No owner has been assigned to the name '${name}'." )
      }

      mbOwner
    }
  }

  private def ensOwnerSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathOwnerAddressParser )

    Def.inputTask {
      val log           = streams.value.log
      val lazySigner    = findCurrentSenderLazySignerTask( config ).value
      val chainId       = findNodeChainIdTask(warn=true)(config).value
      val ensClient     = ( config / xensClient).value
      val nonceOverride = unwrapNonceOverrideBigInt( Some( log ), chainId )

      val ( ensParsedPath, ownerAddress ) = parser.parsed
      val ensName = ensParsedPath.fullName

      ensClient.setOwner( lazySigner, ensName, ownerAddress, forceNonce = nonceOverride )
      log.info( s"The name '${ensName}' is now owned by ${verboseAddress(chainId, ownerAddress)}." )
    }
  }

  private def ensResolverLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathParser )

    Def.inputTask {
      val chainId    = findNodeChainIdTask(warn=true)(config).value
      val ensClient  = ( config / xensClient).value
      val name       = parser.parsed.fullName
      val mbResolver = ensClient.resolver( name )

      mbResolver match {
        case Some( address ) => println( s"The name '${name}' is associated with a resolver at address ${verboseAddress(chainId, address)}'." )
        case None            => println( s"No resolver has been associated with the name '${name}'." )
      }

      mbResolver
    }
  }

  private def ensResolverSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsPathResolverAddressParser )

    Def.inputTask {
      val log           = streams.value.log
      val lazySigner    = findCurrentSenderLazySignerTask( config ).value
      val chainId       = findNodeChainIdTask(warn=true)(config).value
      val ensClient     = ( config / xensClient).value
      val nonceOverride = unwrapNonceOverrideBigInt( Some( log ), chainId )
      val ( ensParsedPath, resolverAddress ) = parser.parsed
      val ensName = ensParsedPath.fullName
      ensClient.setResolver( lazySigner, ensName, resolverAddress, forceNonce = nonceOverride )
      log.info( s"The name '${ensName}' is now set to be resolved by a contract at ${verboseAddress(chainId, resolverAddress)}." )
    }
  }

  private def ensSubnodeCreateTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsSubnodeParser )

    Def.inputTaskDyn {
      val sender = findAddressSenderTask(warn=true)(config).value.assert
      val eppSubnode = parser.parsed
      ( config / ensSubnodeOwnerSet ).toTask( s" ${eppSubnode.fullPath} 0x${sender.hex}" )
    }
  }

  private def ensSubnodeOwnerSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsSubnodeOwnerSetParser )

    Def.inputTask {
      val log           = streams.value.log
      val lazySigner    = findCurrentSenderLazySignerTask( config ).value
      val chainId       = findNodeChainIdTask(warn=true)(config).value
      val ensClient     = ( config / xensClient ).value
      val nonceOverride = unwrapNonceOverrideBigInt( Some( log ), chainId )
      val ( eppSubnode, newOwnerAddress) = parser.parsed
      ensClient.setSubnodeOwner( lazySigner, eppSubnode.parent.fullName, eppSubnode.label, newOwnerAddress, forceNonce = nonceOverride )
      log.info( s"The name '${eppSubnode.fullName}' now exists, with owner ${verboseAddress(chainId, newOwnerAddress)}." )
    }
  }

  // etherscan tasks

  private def etherscanApiKeyDropTask : Initialize[Task[Unit]] = Def.task {
    val deleted = shoebox.Database.deleteEtherscanApiKey().assert
    if ( deleted ) {
      println("Etherscan API key successfully dropped.")
    }
    else {
      println("Nothing to do. No Etherscan API key was set.")
    }
  }

  private def etherscanApiKeySetTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val apiKey = etherscanApiKeyParser("<etherscan-api-key>").parsed
    shoebox.Database.setEtherscanApiKey( apiKey ).assert
    println("Etherscan API key successfully set.")
  }

  private def etherscanApiKeyPrintTask : Initialize[Task[Unit]] = Def.task {
    val mbApiKey = shoebox.Database.getEtherscanApiKey().assert
    mbApiKey match {
      case Some( apiKey ) => println( s"The currently set Etherscan API key is ${apiKey}" )
      case None           => println(  "No Etherscan API key has been set." )
    }
  }

  // eth tasks

  private def ethAddressAliasDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressAliasParser )

    Def.inputTaskDyn {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value

      // not sure why, but without this xethFindCacheRichParserInfo, which should be triggered by the parser,
      // sometimes fails initialize the parser
      val ensureAliases = (xethFindCacheRichParserInfo in config)

      val alias = parser.parsed
      val check = shoebox.AddressAliasManager.dropAddressAlias( chainId, alias ).get // assert no database problem
      if (check) log.info( s"Alias '${alias}' successfully dropped (for chain with ID ${chainId}).")
      else log.warn( s"Alias '${alias}' is not defined (on blockchain with ID ${chainId}), and so could not be dropped." )

      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethAddressAliasListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log      = streams.value.log
    val chainId  = findNodeChainIdTask(warn=true)(config).value
    val faliases = shoebox.AddressAliasManager.findAllAddressAliases( chainId )
    faliases.fold( _ => log.warn("Could not read aliases from shoebox database.") ) { aliases =>
      aliases.foreach { case (alias, address) => println( s"${alias} -> ${verboseAddress(chainId, address)}" ) }
    }
  }

  private def ethAddressAliasCheckTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genPermissiveAddressAliasOrAddressAsStringParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val aliasOrAddress = parser.parsed
      val mbAddressForAlias = shoebox.AddressAliasManager.findAddressByAddressAlias( chainId, aliasOrAddress ).assert
      val mbEntryAsAddress = Failable( EthAddress( aliasOrAddress ) ).toOption

      mbAddressForAlias match {
        case Some( addressForAlias ) => println( s"The alias '${aliasOrAddress}' points to address ${verboseAddress( chainId, addressForAlias )}." )
        case None => {
          val aliasesForAddress = mbEntryAsAddress.toSeq.flatMap( addr => shoebox.AddressAliasManager.findAddressAliasesByAddress( chainId, addr ).get )
          ( mbEntryAsAddress, aliasesForAddress ) match {
            case ( Some( entryAsAddress ),        Seq() ) => println( s"The address '${hexString(entryAsAddress)}' is not associated with any aliases." )
            case ( Some( entryAsAddress ), Seq( alias ) ) => println( s"The address '${hexString(entryAsAddress)}' is associated with alias '${alias}'." )
            case ( Some( entryAsAddress ),      aliases ) => println( s"""The address '${hexString(entryAsAddress)}' is associated with aliases ${aliases.mkString( "['","', '", "']" )}.""" )
            case (                   None,            _ ) => {
              if (mbAddressForAlias.isEmpty) { // so we'd not have printed any message yet
                println( s"The alias '${aliasOrAddress}' is not associated with any address." )
              }
            }
          }
        }
      }
    }
  }

  private def interactiveOptionalCreateAliasTask( config : Configuration, address : EthAddress ) : Initialize[Task[Unit]] = Def.taskDyn {
    val log = streams.value.log
    val is = interactionService.value
    val chainId = findNodeChainIdTask(warn=false)(config).value
    def doAskSetAlias = queryYN( is, s"Would you like to define an alias for address '${hexString(address)}' (on chain with ID ${chainId})? [y/n] " )
    val update = doAskSetAlias
    if ( update ) {
      @tailrec
      def doQueryAlias : Initialize[Task[Unit]] = {
        val putative = assertReadLine( is, s"Please enter an alias for address '${hexString(address)}' (on chain with ID ${chainId}): ", mask = false ).trim
        if ( putative.isEmpty ) {
          log.info( "No alias provided." )
          if ( doAskSetAlias ) {
            doQueryAlias
          }
          else {
            println("No alias set.")
            EmptyTask
          }
        }
        else if (!prelimGoodAlias(putative)) {
          println( s"'${putative}' is not a valid alias." )
          doQueryAlias
        }
        else {
          interactiveUpdateAliasTask( config, putative, address )
        }
      }
      doQueryAlias
    }
    else {
      EmptyTask
    }
  }

  private def prelimGoodAlias( putative : String ) : Boolean = {
    sbt.complete.Parser.parse( putative, RawAddressAliasParser ) match {
      case Left(_) => false
      case Right( ok ) => !goodHexAddress( ok )
    }
  }

  private def goodHexAddress( s : String ) = Failable( EthAddress(s) ).isSucceeded

  private def interactiveUpdateAliasTask( config : Configuration, alias : String, address : EthAddress ) : Initialize[Task[Unit]] = Def.taskDyn {
    val log = streams.value.log
    val is = interactionService.value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    if (goodHexAddress(alias)) {
      throw new SbtEthereumException( s"You cannot use what would be a legitimate Ethereum hex address as an alias. Bad attempted alias: '${alias}'" )
    }
    val oldValue = shoebox.AddressAliasManager.findAddressByAddressAlias( chainId, alias ).assert

    val shouldUpdate = {
      oldValue match {
        case Some( `address` ) => {
          log.info( s"The alias '${alias}' already points to address '${hexString(address)}' (for chain with ID ${chainId}). Nothing to do." )
          false
        }
        case Some( differentAddress ) => {
          kludgeySleepForInteraction()
          val replace = queryYN( is, s"The alias '${alias}' currently points to address '${hexString(differentAddress)}' (for chain with ID ${chainId}). Replace? [y/n] " )
          if (! replace ) {
            throw new OperationAbortedByUserException( s"User chose not to replace previously defined alias '${alias}' (for chain with ID ${chainId}). It continues to point to '${hexString(differentAddress)}'." )
          }
          else {
            val didDrop = shoebox.AddressAliasManager.dropAddressAlias( chainId, alias ).assert
            assert( didDrop, "Expected deletion of address alias '${alias}' did not in fact delete any rows!" )
            true
          }
        }
        case None => {
          true
        }
      }
    }

    if ( shouldUpdate ) {
      val check = shoebox.AddressAliasManager.insertAddressAlias( chainId, alias, address )
      check.fold( _.vomit ){ _ =>
        log.info( s"Alias '${alias}' now points to address '0x${address.hex}' (for chain with ID ${chainId})." )
      }
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
    else {
      EmptyTask
    }
  }

  private def ethAddressAliasSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genNewAddressAliasParser )

    Def.inputTaskDyn {
      val ( alias, address ) = parser.parsed
      interactiveUpdateAliasTask( config, alias, address )
    }
  }

  private def ethAddressBalanceTask( config : Configuration ) : Initialize[InputTask[BigDecimal]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genOptionalGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value
      
      val jsonRpcUrl       = findNodeUrlTask(warn=true)(config).value
      val timeout          = xethcfgAsyncOperationTimeout.value
      val baseCurrencyCode = ethcfgBaseCurrencyCode.value
      val mbAddress        = parser.parsed
      val address          = mbAddress.getOrElse( findAddressSenderTask(warn=true)(config).value.assert )

      val exchangerConfig = findExchangerConfigTask( config ).value

      val result           = doPrintingGetBalance( exchangerConfig, log, timeout, address, jsonrpc.Client.BlockNumber.Latest, Denominations.Ether )
      val etherValue       = result.denominated

      printFiatValueForEtherValue()( chainId, baseCurrencyCode, etherValue )

      etherValue
    }
  }

  private def printFiatValueForEtherValue( pfunc : String => Unit = println(_) )( chainId : Int, baseCurrencyCode : String, etherValue : BigDecimal ) : Unit = {
    priceFeed.ethPriceInCurrency( chainId, baseCurrencyCode ) match {
      case Some( datum ) => {
        val value = etherValue * datum.price
        val roundedValue = value.setScale(2, BigDecimal.RoundingMode.HALF_UP )
        pfunc( s"This corresponds to approximately ${roundedValue} ${baseCurrencyCode} (at a rate of ${datum.price} ${baseCurrencyCode} per ETH, retrieved at ${ formatTime( datum.timestamp ) } from ${priceFeed.source})" )
      }
      case None => {
        pfunc( s"(The ${baseCurrencyCode} value of this is unknown, no exchange value is currently available for chain with ID ${chainId} from ${priceFeed.source}.)" )
      }
    }
  }

  private def ethAddressSenderTask( config : Configuration ) : Initialize[Task[Option[EthAddress]]] = Def.task {
    findAddressSenderTask(warn=false)(config).value match {
      case Succeeded( addr )                                => Some( addr )
      case Failed( noSender : SenderNotAvailableException ) => None
      case oops : Failed[_]                                 => oops.vomit
    }
  }

  private def ethAddressSenderPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val f_effective = findAddressSenderTask(warn=false)(config).value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    try {
      val effective = f_effective.assert
      val mbOverride = Mutables.SenderOverrides.get( chainId )
      val mbBuildSetting = (config/ethcfgAddressSender).?.value
      val mbShoeboxDefault = shoebox.Database.findDefaultSenderAddress( chainId ).assert

      log.info( s"The current effective sender address is ${verboseAddress(chainId, effective)}." )

      ( mbOverride, mbBuildSetting, mbShoeboxDefault ) match {
        case ( Some( ov ), _, _) => {
          assert( effective == ov, "We expect that if a session override is set, it is the effective sender address." )
          log.info( " + This value has been explicitly set as a session override via 'ethAddressSenderOverrideSet'." )
          mbBuildSetting.foreach { hardCoded =>
            try {
              val hardCodedAddress = EthAddress( hardCoded )
              log.info( s" + It has overridden a value explicitly set in the project build or the '.sbt' folder as 'ethcfgAddressSender': ${verboseAddress(chainId,hardCodedAddress)}" )
            }
            catch {
              case e : Exception => log.info(
                " + It has overridden a value explicitly set in the project build or the '.sbt' folder as 'ethcfgAddressSender' which is not a properly formed address: '${hardCoded}'"
              )
            }
          }
          mbShoeboxDefault.foreach( shoeboxDefault => log.info( s" + It has overridden a default sender address for chain with ID ${chainId} set in the sbt-ethereum shoebox: ${verboseAddress(chainId,shoeboxDefault)}" ) )
        }
        case ( None, Some( buildSetting ), _ ) => {
          assert( effective == EthAddress( buildSetting ), "We expect that if no session override is set, but a sender address is set as a build setting, it is the effective sender address." )
          log.info( " + This value has been explicitly defined as setting 'ethcfgAddressSender' in the project build or the '.sbt' folder, and has not been overridden by a session override." )
          mbShoeboxDefault.foreach( shoeboxDefault => log.info( s" + It has overridden a default sender address for chain with ID ${chainId} set in the sbt-ethereum shoebox: ${verboseAddress(chainId,shoeboxDefault)}" ) )
        }
        case ( None, None, Some( shoeboxDefault ) ) => {
          assert(
            effective == shoeboxDefault,
            s"We expect that if no session override is set, and no build setting, but a default sender address for chain with ID ${chainId} is set in the shoebox, it is the effective sender address for that chain."
          )
          log.info( s" + This value is the default sender address defined in the sbt-ethereum shoebox for chain with ID ${chainId}. " )
          log.info( " + It has not been overridden with a session override or by an 'ethcfgAddressSender' setting in the project build or the '.sbt' folder." )
        }
        case ( None, None, None ) if config == Compile => {
          assert(
            effective == LastResortMaybeEthAddressSender.get, // since we found 'effective' without an Exception, get should succeed
            "With no session override, no 'ethcfgAddressSender' setting in the build, and no sender address set for chain with ID ${chainID}, the effective sender address should be the 'last-resort' address."
          )
          log.info(  " + This is the 'last-resort' sender address, taken from environment variable 'ETH_SENDER' or system property 'eth.sender'. " )
          log.info(  " + It has not been overridden by a session override or by an 'ethcfgAddressSender' setting in the project build or the '.sbt' folder. " )
          log.info( s" + There is no default sender address defined for chain with ID ${chainId} defined in the sbt-ethereum shoebox." )
        }
        case ( None, None, None ) if config == Test => {
          assert(
            effective == LastResortMaybeTestEthAddressSender.get, // since we found 'effective' without an Exception, get should succeed
            "With no session override, no 'ethcfgAddressSender' setting in the build, and no sender address set for chain with ID ${chainID}, the effective sender address should be the 'last-resort' address for configuration Test."
          )
          log.info(  " + This is the 'last-resort' sender address, hardcoded into sbt-ethereum for the 'test' configuration. " )
          log.info(  " + It has not been overridden by a session override or by an 'ethcfgAddressSender' setting in the project build or the '.sbt' folder. " )
          log.info( s" + There is no default sender address defined for chain with ID ${chainId} defined in the sbt-ethereum shoebox." )
        }
        case ( None, None, None ) => {
          throw new UnexpectedConfigurationException( config )
        }
      }
    }
    catch {
      case e : SenderNotAvailableException => {
        log.info(  "No sender is available. Tasks that require a sender would fail." )
        log.info(  " + No session override has been set via 'ethAddressSenderOverrideSet'." )
        log.info(  " + No 'ethcfgSenderAddress has been defined in the project build or the '.sbt' folder." )
        log.info( s" + No default address for chain with ID ${chainId} has been set via 'ethAddressSenderDefaultSet'." )
        config match {
          case Compile => log.info( " + No 'last-resort' sender address has been defined via environment variable 'ETH_SENDER' or system propety 'eth.sender'.")
          case Test    => log.info( " + No 'last-resort' sender address has been defined (WHICH IS A SURPRISE, BECAUSE IN THE 'test' CONFIGURATION THERE SHOULD BE A HARD-CODED DEFAULT)." )
          case _       => throw new UnexpectedConfigurationException( config )
        }
      }
    }
  }

  private def ethAddressSenderDefaultPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val mbAddress = shoebox.Database.findDefaultSenderAddress( chainId ).assert
    mbAddress match {
      case Some( address ) => {
        log.info( s"The default sender address for chain with ID ${chainId} is '${hexString(address)}'." )
      }
      case None => {
        log.info( s"No default sender address for chain with ID ${chainId} has been set." )
      }
    }
  }

  private def ethAddressSenderDefaultSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTaskDyn {
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val newAddress = parser.parsed
      val oldAddress = shoebox.Database.findDefaultSenderAddress( chainId ).assert
      oldAddress.foreach { address =>
        println( s"A default sender address has already been set for chain with ID ${chainId}: '${hexString(address)}'." )
        val overwrite = queryYN( is, s"Do you wish to replace it? [y/n] " )
        if ( overwrite ) {
          shoebox.Database.dropDefaultSenderAddress( chainId ).assert
        }
        else {
          throw new OperationAbortedByUserException( s"User chose not to replace previously set default sender address for chain with ID ${chainId}, which remains '${hexString(address)}'." )
        }
      }
      shoebox.Database.setDefaultSenderAddress( chainId, newAddress ).assert
      log.info( s"Successfully set default sender address for chain with ID ${chainId} to ${verboseAddress(chainId, newAddress)}." )
      log.info( s"You can use the synthetic alias '${DefaultSenderAlias}' to refer to this address." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethAddressSenderDefaultDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val oldAddress = shoebox.Database.findDefaultSenderAddress( chainId ).assert
    oldAddress match {
      case Some( senderAddress ) => {
        val check = shoebox.Database.dropDefaultSenderAddress( chainId ).assert
        assert( check, "Huh? We had a an old senderAddress value, but trying to delete it failed to delete any rows?" )
        log.info( s"Previously, the default sender address for chain with ID ${chainId} was ${verboseAddress(chainId, senderAddress)}.")
        log.info(  "It has now been successfully dropped." )
      }
      case None => {
        log.info( s"No default sender address for chain with ID ${chainId} has been set. Nothing to do here." )
      }
    }
    Def.task {
      val markDirty = xethTriggerDirtyAliasCache.value
      val mbNewAddress = findAddressSenderTask(warn=false)(config).value
      mbNewAddress match {
        case Succeeded( newAddress ) => {
          log.info( s"The current session sender is now ${verboseAddress( chainId, newAddress )}." )
          log.info(  "(Use 'ethAddressSenderPrint' for an explanation of where this address comes from.)" )
        }
        case Failed( src : SenderNotAvailableException ) => log.warn( s"There is now no sender address associated with this sbt-ethereum session!" )
        case oops : Failed[_] => oops.vomit
      }
    }
  }

  private def ethAddressSenderOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.SenderOverrides.drop( chainId )
    log.info("No sender override is now set.")
    log.info("Effective sender will be determined by 'ethcfgAddressSender' setting, a value set via 'ethAddressSenderDefaultSet', the System property 'eth.sender', or the environment variable 'ETH_SENDER'.")
  }

  private def ethAddressSenderOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    val chainId = findNodeChainIdTask(warn=true)(config).value

    val mbSenderOverride = Mutables.SenderOverrides.get( chainId )

    val message = mbSenderOverride.fold( s"No sender override is currently set (for chain with ID ${chainId})." ) { address =>
      val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "" )( _.fold("")( str => s", aliases $str)" ) )
      s"A sender override is set, address '${hexString(address)}' (on chain with ID ${chainId}${aliasesPart})."
    }

    log.info( message )
  }

  private def ethAddressSenderOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val address = parser.parsed
      val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "")( _.fold("")( str => s", aliases $str)" ) )
      Mutables.SenderOverrides.set( chainId, address )
      log.info( s"Sender override set to '0x${address.hex}' (on chain with ID ${chainId}${aliasesPart})." )
    }
  }

  private def ethContractAbiAliasDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genExistingAbiAliasParser )

    Def.inputTaskDyn {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val log = streams.value.log
      val dropMeAbiAlias = parser.parsed
      shoebox.AbiAliasHashManager.dropAbiAlias( chainId, dropMeAbiAlias )
      log.info( s"Abi alias 'abi:${dropMeAbiAlias}' successfully dropped." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethContractAbiAliasListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val abiAliases = shoebox.AbiAliasHashManager.findAllAbiAliases( chainId ).assert
    val columns = immutable.Vector( "ABI Alias", "ABI Hash" ).map( texttable.Column.apply( _ ) )

    def extract( tup : Tuple2[String,EthHash] ) : Seq[String] = {
      val ( alias, hash ) = tup
      "abi:" + alias :: hexString(hash) :: Nil
    }

    texttable.printTable( columns, extract )( abiAliases.map( aa => texttable.Row(aa) ) )
  }

  private def ethContractAbiAliasSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genNewAbiAliasAbiSourceParser )

    Def.inputTaskDyn {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val log = streams.value.log
      val ( newAbiAlias, abiSource ) = parser.parsed
      val ( abi : Abi, sourceDesc : String) = standardSortAbiAndSourceDesc( log, abiSource )
      shoebox.AbiAliasHashManager.createUpdateAbiAlias( chainId, newAbiAlias, abi )
      log.info( s"Abi alias 'abi:${newAbiAlias}' successfully bound to ABI found via ${sourceDesc}." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethContractAbiCallDecodeTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAnyAbiSourceHexBytesParser )

    Def.inputTask {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val log = streams.value.log
      val ( abiSource, bytes ) = parser.parsed
      loggedAbiFromAbiSource( log, abiSource ).fold( throw nst( new AbiUnknownException( s"Can't find ABI for ${abiSource.sourceDesc}" ) ) ) { abi =>
        val ( fcn, values ) = ethabi.decodeFunctionCall( abi, bytes ).assert
        println( s"Function called: ${ethabi.signatureForAbiFunction(fcn)}" )
          (values.zip( Stream.from(1) )).foreach { case (value, index) =>
            println( s"   Arg ${index} [name=${value.parameter.name}, type=${value.parameter.`type`}]: ${value.stringRep}" )
          }
      }
    }
  }

  private def ethContractAbiCallEncodeTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAbiMaybeWarningFunctionInputsParser( restrictedToConstants = false ) )

    Def.inputTask {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val log = streams.value.log
      val ( abi, mbWarning, function, inputs ) = parser.parsed
      mbWarning.foreach( warning => log.warn( warning ) )
      val bytes = ethabi.callDataForAbiFunctionFromStringArgs( inputs, function ).assert
      println( "Encoded data:" )
      println( hexString(bytes) )
    }
  }

  private def ethContractAbiDefaultDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val log = streams.value.log
      val address = parser.parsed
      val found = shoebox.Database.deleteImportedContractAbi( chainId, address ).get // throw an Exception if there's a database issue
      if ( found ) {
        log.info( s"Previously imported or set ABI for contract with address '0x${address.hex}' (on chain with ID ${chainId}) has been dropped." )
      } else {
        val mbDeployment = shoebox.Database.deployedContractInfoForAddress( chainId, address ).get  // throw an Exception if there's a database issue
        mbDeployment match {
          case Some( _ ) => throw new SbtEthereumException( s"Contract at address '0x${address.hex}' (on chain with ID ${chainId}) is not an imported ABI but our own deployment. Cannot drop." )
          case None      => throw new SbtEthereumException( s"We have not set or imported an ABI for the contract at address '0x${address.hex}' (on chain with ID ${chainId})." )
        }
      }
    }
  }

  private def standardSortAbiAndSourceDesc( log : sbt.Logger, abiSource : AbiSource ) : Tuple2[Abi,String] = {
    val ( a : Abi, mbLookup : Option[AbiLookup] ) = abiFromAbiSource( abiSource ).getOrElse( throw nst( new AbiUnknownException( s"Can't find ABI for ${abiSource.sourceDesc}" ) ) )

    // be careful to warn about potential shadowing if we got an AbiLookup object. resolveAbi( Some( log ) ) logs.
    mbLookup match {
      case Some( abiLookup ) => ( abiLookup.resolveAbi( Some( log ) ).get.withStandardSort, abiSource.sourceDesc )
      case None              => ( a.withStandardSort, abiSource.sourceDesc )
    }
  }

  private def ethContractAbiDefaultSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressAnyAbiSourceParser )

    Def.inputTaskDyn {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val ( toLinkAddress, abiSource ) = parser.parsed

      // Note the we sort `abi` before binding to the val
      val ( abi : Abi, sourceDesc : String) = standardSortAbiAndSourceDesc( log, abiSource )
      
      val mbKnownCompilation = shoebox.Database.deployedContractInfoForAddress( chainId, toLinkAddress ).assert
      mbKnownCompilation match {
        case Some( knownCompilation ) => {
          knownCompilation.mbAbiHash match {
            case Some( abiHash ) => {
              log.warn( s"The contract at address '${hexString(toLinkAddress)}' was already associated with a deployed compilation, which has an ABI. A memorized ABI would shadow that." )

              kludgeySleepForInteraction()
              val doIt = queryYN( is, s"Do you still wish to memorize a new ABI for ${sourceDesc}? [y/n] " )
              if (! doIt ) throw new OperationAbortedByUserException( "User chose not shadow ABI of already defined compilation." )
            }
            case None => {
              // ignore, we won't be overriding anything
            }
          }
        }
        case None => {
          // ignore, we won't be overriding anything
        }
      }
      val currentAbiOverrides = abiOverridesForChain( chainId )
      currentAbiOverrides.get( toLinkAddress ).foreach { currentOverride =>
        if ( currentOverride.withStandardSort != abi /* already sorted */ ) {
          kludgeySleepForInteraction()
          val ignoreOverride = queryYN( is, s"An ABI override is set in the present session that differs from the ABI you wish to associate with '${hexString(toLinkAddress)}'. Continue? [y/n] " )
          if (! ignoreOverride ) throw new OperationAbortedByUserException( "User aborted ethContractAbiDefaultSet due to discrepancy between linked ABI and current override." )
        }
      }

      def finishUpdate = {
        log.info( s"The ABI previously associated with ${sourceDesc} ABI has been associated with address ${hexString(toLinkAddress)}." )
        if (! shoebox.AddressAliasManager.hasNonSyntheticAddressAliases( chainId, toLinkAddress ).assert ) {
          kludgeySleepForInteraction()
          interactiveSetAliasForAddress( chainId )( s, log, is, s"the address '${hexString(toLinkAddress)}', now associated with the newly matched ABI", toLinkAddress )
        }
        Def.taskDyn {
          xethTriggerDirtyAliasCache
        }
      }
      def noop = EmptyTask

      val lookupExisting = abiLookupForAddress( chainId, toLinkAddress, currentAbiOverrides )
      lookupExisting match {
        case AbiLookup( toLinkAddress, _, Some( `abi` ), _, _ ) => {
          log.info( s"The ABI you have tried to link is already associated with '${hexString(toLinkAddress)}'. No changes were made." )
          noop
        }
        case AbiLookup( toLinkAddress, _, Some( memorizedAbi ), Some( `abi` ), _ ) => {
          val deleteImported = queryYN( is, s"The ABI you have tried to link is the origial compilation ABI associated with ${hexString(toLinkAddress)}. Remove shadowing ABI to restore? [y/n] " )
          if (! deleteImported ) {
            throw new OperationAbortedByUserException( "User chose not to delete a currently associated ABI, which shadows an original compilation-derived ABI." )
          }
          else {
            shoebox.Database.deleteImportedContractAbi( chainId, toLinkAddress ).assert // throw an Exception if there's a database issue
            finishUpdate
          }
        }
        case AbiLookup( toLinkAddress, _, None, Some( `abi` ), _ ) => {
          log.info( s"The ABI you have tried to link is already associated with '${toLinkAddress}'. It is the unshadowed, original compilation ABI. No changes were made." )
          noop
        }
        case AbiLookup( toLinkAddress, _, Some( memorizedAbi ), Some( compilationAbi ), _ ) => {
          val overwriteShadow = queryYN( is, s"This operation would overwrite an existing ABI associated with '${hexString(toLinkAddress)}' (which itself shadowed an original compilation-derived ABI). Continue? [y/n] " )
          if (! overwriteShadow ) {
            throw new OperationAbortedByUserException( "User aborted ethContractAbiDefaultSet in order not to replace an existing association (which itself shadowed an original compilation-derived ABI)." )
          }
          else {
            shoebox.Database.resetImportedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
            finishUpdate
          }
        }
        case AbiLookup( toLinkAddress, _, Some( memorizedAbi ), None, _ ) => {
          val overwrite = queryYN( is, s"This operation would overwrite an existing ABI associated with '${hexString(toLinkAddress)}'. Continue? [y/n] " )
          if (! overwrite ) {
            throw new OperationAbortedByUserException( "User aborted ethContractAbiDefaultSet in order not to replace an existing association." )
          }
          else {
            shoebox.Database.resetImportedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
            finishUpdate
          }
        }
        case AbiLookup( toLinkAddress, _, None, Some( compilationAbi_ ), _ ) => {
          val shadow = queryYN( is, "This operation would shadow an original compilation-derived ABI. Continue? [y/n] " )
          if (! shadow ) {
            throw new OperationAbortedByUserException( "User aborted ethContractAbiDefaultSet in order not to replace an existing association (which itself overrode an original compilation-derived ABI)." )
          }
          else {
            shoebox.Database.setImportedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
            finishUpdate
          }
        }
        case AbiLookup( toLinkAddress, _, None, None, _ ) => {
          shoebox.Database.setImportedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
          finishUpdate
        }
        case unexpected => throw new SbtEthereumException( s"Unexpected AbiLookup: ${unexpected}" )
      }
    }
  }

  private def ethContractAbiOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressAnyAbiSourceParser )

    Def.inputTaskDyn {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val s = state.value
      val log = streams.value.log
      val ( toLinkAddress, abiSource ) = parser.parsed
      val ( abi, mbLookup ) = abiFromAbiSource( abiSource ).getOrElse( throw nst( new AbiUnknownException( s"Can't find ABI for ${abiSource.sourceDesc}" ) ) )
      mbLookup.foreach( _.logGenericShadowWarning( log ) )
      addAbiOverrideForChain( chainId, toLinkAddress, abi )
      log.info( s"ABI override successfully set." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethContractAbiOverrideListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val s = state.value
    val log = streams.value.log
    val currentAbiOverrides = abiOverridesForChain( chainId )
    val addressesToAbiHashes = currentAbiOverrides.map { case (k,v) => (k, abiHash(v)) }

    val columns = immutable.Vector( "ABI Override Addresses", "ABI Hash" ).map( texttable.Column.apply( _ ) )
    def extract( tup : Tuple2[EthAddress,EthHash] ) : Seq[String] = hexString( tup._1 ) :: hexString( tup._2 ) :: Nil
    def aliasesPartForTup( tup : Tuple2[EthAddress,EthHash] ) : String = {
      val ( address, hash ) = tup
      jointAliasesPartAddressAbi( chainId, address, hash ) match {
        case Some( part ) => s" <-- ${part}"
        case None         =>  ""
      }
    }
    texttable.printTable( columns, extract )( addressesToAbiHashes.map( tup => texttable.Row(tup, aliasesPartForTup( tup ) ) ) )
  }

  private def ethContractAbiOverrideDropAllTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val s = state.value
    val log = streams.value.log
    val out = clearAbiOverrideForChain( chainId )
    if ( out ) {
      log.info( s"ABI overrides on chain with ID ${chainId} successfully dropped." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
    else {
      log.info( s"No ABI override found on chain with ID ${chainId}." )
      Def.task {}
    }
  }

  private def ethContractAbiOverridePrintTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val s = state.value
      val log = streams.value.log
      val address = parser.parsed
      val currentAbiOverrides = abiOverridesForChain( chainId )
      currentAbiOverrides.get( address ) match {
        case Some( abi ) => {
          println( s"Session override of contract ABI for address '0x${address.hex}':" )
          val json = Json.toJson( abi )
          println( Json.prettyPrint( json ) )
        }
        case None => {
          log.warn( s"No ABI override set for address '${hexString(address)}' (for chain with ID ${chainId})." )
        }
      }
    }
  }

  private def ethContractAbiOverrideDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTaskDyn {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val s = state.value
      val log = streams.value.log
      val address = parser.parsed
      val out = removeAbiOverrideForChain( chainId, address )
      if ( out ) {
        log.info( s"ABI override successfully dropped." )
        Def.taskDyn {
          xethTriggerDirtyAliasCache
        }
      }
      else {
        log.info( s"No ABI override found for address '${hexString(address)}'." )
        Def.task {}
      }
    }
  }

  private def ethContractAbiAnyPrintTask( pretty : Boolean )( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo)( genAnyAbiSourceParser )

    Def.inputTask {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val log = streams.value.log
      val abiSource = parser.parsed
      val mbTup = abiFromAbiSource( abiSource )
      val mbAbi = mbTup.map( _._1 )
      mbTup.foreach( _._2.foreach( _.logGenericShadowWarning( log ) ) )
      mbAbi match {
        case None        => println( s"No contract ABI known for ${abiSource.sourceDesc}." )
        case Some( abi ) => {
          println( s"Contract ABI for ${abiSource.sourceDesc}:" )
          val json = Json.toJson( abi )
          println( if ( pretty ) Json.prettyPrint( json ) else  Json.stringify( json ) )
        }
      }
    }
  }

  private def ethContractAbiPrintTask( config : Configuration ) : Initialize[InputTask[Unit]] = ethContractAbiAnyPrintTask( pretty = false )( config )
  private def ethContractAbiPrintPrettyTask( config : Configuration ) : Initialize[InputTask[Unit]] = ethContractAbiAnyPrintTask( pretty = true )( config )
  private def ethContractAbiPrintCompactTask( config : Configuration ) : Initialize[InputTask[Unit]] = ethContractAbiAnyPrintTask( pretty = false )( config )

  private final object AbiListRecord {
    sealed trait Source
    case object Imported extends Source
    case class Deployed( mbContractName : Option[String] ) extends Source

    implicit final object SourceOrdering extends Ordering[Source] {
      def compare( x : Source, y : Source ) : Int = {
        (x, y) match {
          case ( Imported, Imported )                   =>  0
          case ( Deployed(_), Imported )                =>  1
          case ( Imported, Deployed(_) )                => -1
          case ( Deployed(None), Deployed(None) )       =>  0
          case ( Deployed(Some(_)), Deployed(None) )    =>  1
          case ( Deployed(None), Deployed(Some(_)) )    => -1
          case ( Deployed(Some(a)), Deployed(Some(b)) ) => String.CASE_INSENSITIVE_ORDER.compare( a, b )
        }
      }
    }
  }
  private final case class AbiListRecord( address : EthAddress, abiHash : EthHash, source : AbiListRecord.Source )

  private def ethContractAbiDefaultListTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val log = streams.value.log

    val mbRegex = regexParser( defaultToCaseInsensitive = true ).parsed

    val importedAddresses = shoebox.Database.getImportedContractAbiAddresses( chainId ).get
    val deployedContracts = shoebox.Database.allDeployedContractInfosForChainId( chainId ).get

    val allRecords = {
      val importedRecords = {
        val tups = importedAddresses.map( address => Tuple2( address, shoebox.Database.getImportedContractAbiHash( chainId, address ).assert ) )
        def checkTup( tup : Tuple2[EthAddress,Option[EthHash]] ) : Boolean = {
          val out = tup._2.nonEmpty
          if (!out) log.warn( s"No ABI hash associated with imported ABI for address ${hexString(tup._1)}?!?" )
          out
        }
        val goodTups = tups.filter( checkTup )
        goodTups.map { case ( address, checkedHash ) =>
          Tuple2( address, AbiListRecord( address, checkedHash.get, AbiListRecord.Imported ) )
        }.toMap
      }
      val deployedRecords  = {
        deployedContracts
          .filter( _.mbAbiHash.nonEmpty )
          .map (dci=>Tuple2(dci.contractAddress,AbiListRecord(dci.contractAddress,dci.mbAbiHash.get,AbiListRecord.Deployed(dci.mbName))))
          .toMap
      }
      val fullAddressMap = deployedRecords ++ importedRecords // imported records shadow deployed, ordering of the ++ is important
      (immutable.SortedSet.empty[AbiListRecord]( Ordering.by( r => (r.source, r.address.hex, r.abiHash.hex) ) ) ++ fullAddressMap.values).toSeq 
    }

    val rowFilter : String => Boolean = {
      mbRegex match {
        case Some( regex ) => {
          ( s : String ) => regex.findFirstIn( s ) != None
        }
        case None => {
          ( s : String ) => true
        }
      }
    }

    val columns = immutable.Vector("Address", "ABI Hash", "Source").map( texttable.Column.apply )

    def extract( record : AbiListRecord ) : Seq[String] = {
      val addrStr    = hexString( record.address )
      val abiHashStr = hexString( record.abiHash )
      val sourceStr  = if ( record.source == AbiListRecord.Imported ) "Imported" else "Deployed"
      immutable.Vector( addrStr, abiHashStr, sourceStr )
    }

    def annotation( record : AbiListRecord ) : String = {
      val mbAliasesPart = jointAliasesPartAddressAbi( chainId, record.address, record.abiHash )
      ( record, mbAliasesPart ) match {
        case ( AbiListRecord( address, abiHash, AbiListRecord.Imported ), None )                                =>  ""
        case ( AbiListRecord( address, abiHash, AbiListRecord.Imported ), Some( aliasesPart ) )                 => s" <-- ${aliasesPart}"
        case ( AbiListRecord( address, abiHash, AbiListRecord.Deployed( None ) ), None )                        =>  ""
        case ( AbiListRecord( address, abiHash, AbiListRecord.Deployed( None ) ), Some( aliasesPart ) )         => s" <-- ${aliasesPart}"
        case ( AbiListRecord( address, abiHash, AbiListRecord.Deployed( Some( name ) ) ), None )                => s" <-- contract name: '${name}'"
        case ( AbiListRecord( address, abiHash, AbiListRecord.Deployed( Some( name ) ) ), Some( aliasesPart ) ) => s" <-- contract name: '${name}', ${aliasesPart}"
      }
    }

    texttable.printTable( columns, extract, rowFilter )( allRecords.map( r => texttable.Row(r, annotation(r)) ) )
  }

  private def ethContractAbiDefaultImportTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTaskDyn {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val abiOverrides = abiOverridesForChain( chainId )
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val timeout = xethcfgAsyncOperationTimeout.value
      val address = parser.parsed
      val mbKnownCompilation = shoebox.Database.deployedContractInfoForAddress( chainId, address ).get
      mbKnownCompilation match {
        case Some( knownCompilation ) => {
          knownCompilation.mbAbiHash match {
            case Some( abiHash ) => {
              log.warn( s"The contract at address '$address' was already associated with a deployed compilation and ABI with hash '${hexString(abiHash)}', which a new ABI would shadow!" )

              kludgeySleepForInteraction()
              val shadow = queryYN( is, s"Are you sure you want to shadow the compilation ABI? [y/n] " )
              if (! shadow) throw new OperationAbortedByUserException( "User chose not to shadow ABI defined in compilation." )
            }
            case None => {
              // ignore, there's nothing we would shadow
            }
          }
        }
        case None => {
          val abiLookup = abiLookupForAddress( chainId, address, abiOverrides )
          if ( abiLookup.memorizedAbi.nonEmpty ) { 
            val overwrite = queryYN( is, s"A default ABI for '${hexString(address)}' on chain with ID ${chainId} has already been set. Overwrite? [y/n] " )
            if (! overwrite) throw new OperationAbortedByUserException( "User chose not to overwrite already defined default contract ABI." )
          }
          else if ( abiLookup.compilationAbi.nonEmpty ) {
            val shadow = queryYN( is, s"A compilation deployed at '${hexString(address)}' on chain with ID ${chainId} has a known, built-in ABI. Do you wish to shadow it? [y/n] " )
            if (! shadow) throw new OperationAbortedByUserException( "User chose not to shadow built-in compilation ABI." )
          }
        }
      }
      val abi = {
        val mbEtherscanAbi : Option[Abi] = {
          val fmbApiKey = shoebox.Database.getEtherscanApiKey()
          fmbApiKey match {
            case Succeeded( mbApiKey ) => {
              mbApiKey match {
                case Some( apiKey ) => {
                  val tryIt = queryYN( is, "An Etherscan API key has been set. Would you like to try to import the ABI for this address from Etherscan? [y/n] " )
                  if ( tryIt ) {
                    println( s"Attempting to fetch ABI for address '${hexString(address)}' from Etherscan." )
                    val fAbi = etherscan.Api.Simple( apiKey ).getVerifiedAbi( address )
                    Await.ready( fAbi, timeout )
                    fAbi.value.get match {
                      case Success( abi ) => {
                        println( "ABI found:" )
                        println( Json.stringify( Json.toJson( abi ) ) )
                        val useIt = queryYN( is, "Use this ABI? [y/n] ")
                        if (useIt) Some( abi ) else None
                      }
                      case Failure( e ) => {
                        println( s"Failed to import ABI from Etherscan: ${e}" )
                        DEBUG.log( "Etherscan verified ABI import failure.", e )
                        None
                      }
                    }
                  }
                  else {
                    None
                  }
                }
                case None => {
                  log.warn("No Etherscan API key has been set, so you will have to directly paste the ABI.")
                  log.warn("Consider acquiring an API key from Etherscan, and setting it via 'etherscanApiKeySet'.")
                  None
                }
              }
            }
            case failed : Failed[_] => {
              log.warn( s"An error occurred while trying to check if the database contains an Etherscan API key: '${failed.message}'" )
              failed.xdebug("An error occurred while trying to check if the database contains an Etherscan API key")
              None
            }
          }
        }
        mbEtherscanAbi match {
          case Some( etherscanAbi ) => etherscanAbi
          case None                 => parseAbi( is.readLine( "Contract ABI: ", mask = false ).getOrElse( throwCantReadInteraction ) )
        }
      }
      shoebox.Database.resetImportedContractAbi( chainId, address, abi  ).get // throw an Exception if there's a database issue
      log.info( s"A default ABI is now known for the contract at address ${hexString(address)}" )
      if (! shoebox.AddressAliasManager.hasNonSyntheticAddressAliases( chainId, address ).assert ) {
        interactiveSetAliasForAddress( chainId )( s, log, is, s"the address '${hexString(address)}', now associated with the newly imported default ABI", address )
      }
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethContractCompilationCullTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val fcount = shoebox.Database.cullUndeployedCompilations()
    val count = fcount.get
    log.info( s"Removed $count undeployed compilations from the shoebox database." )
  }

  private def ethContractCompilationInspectTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genContractAddressOrCodeHashParser )

    Def.inputTask {
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val log = streams.value.log

      println()
      val cap =     "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-="
      val minicap = "------------------------------------------------------------------------"
      println( cap )
      println("                       CONTRACT INFO DUMP")
      println( cap )

      def section( title : String, body : Option[String], hex : Boolean = false ) : Unit = body.foreach { b =>
        println()
        println( minicap )
        println( s"${title}:")
        println();
        println( (if ( hex ) "0x" else "") + b )
      }
      def addressSection( title : String, body : Set[ (Int,EthAddress) ] ) : Unit = {
        val ordered = immutable.SortedSet.empty[String] ++ body.map { tup =>
          val ( chainId, address ) = tup
          s"0x${address.hex} (on chain with ID ${chainId})"
        }
        val bodyOpt = if ( ordered.size == 0 ) None else Some( ordered.mkString(", ") )
        section( title, bodyOpt, false )
      }
      def jsonSection[T : play.api.libs.json.Writes]( title : String, body : Option[T] ) : Unit = {
        section( title, body.map( t => Json.stringify( Json.toJson( t ) ) ), false )
      }
      def constructorInputsSection( mbConstructorInputs : Option[immutable.Seq[Byte]], mbAbi : Option[Abi] ) : Unit = {
        section( "Constructor Inputs Hex", mbConstructorInputs.map( _.hex ), true )
        for {
          ctorInputs <- mbConstructorInputs
          if ctorInputs.nonEmpty
          abi <- mbAbi
        } {
          if ( abi.constructors.length == 0 ) println("Cannot decode inputs! No constructor found in ABI, but apparently constructor inputs were provided?!?")
          else if (abi.constructors.length > 1 ) println("Cannot decode inputs! ABI has multiple construtors, which is not legal.")
          else {
            val decodedInputs = ethabi.decodeConstructorArgs( ctorInputs, abi.constructors.head ).xwarn("Error decoding constructor inputs!").foreach { seq =>
              println( s"""Decoded inputs: ${seq.map( _.stringRep ).mkString(", ")}""" )
            }
          }
        }
      }

      val source = parser.parsed
      source match {
        case Left( address ) => {
          val mbinfo = shoebox.Database.deployedContractInfoForAddress( chainId, address ).get // throw any db problem
          mbinfo.fold( println( s"Contract with address ${verboseAddress( chainId, address )} not found. Perhaps try a different Chain ID?" ) ) { info =>
            section( s"Contract Address (on blockchain with ID ${info.chainId})", Some( info.contractAddress.hex ), true )
            section( "Deployer Address", info.mbDeployerAddress.map( _.hex ), true )
            section( "Transaction Hash", info.mbTransactionHash.map( _.hex ), true )
            section( "Deployment Timestamp", info.mbDeployedWhen.map( l => (new Date(l)).toString ) )
            section( "Code Hash", Some( info.codeHash.hex ), true )
            section( "Code", Some( info.code ), true )
            constructorInputsSection( info.mbConstructorInputs, info.mbAbi )
            section( "Contract Name", info.mbName )
            section( "Contract Source", info.mbSource )
            section( "Contract Language", info.mbLanguage )
            section( "Language Version", info.mbLanguageVersion )
            section( "Compiler Version", info.mbCompilerVersion )
            section( "Compiler Options", info.mbCompilerOptions )
            section( "ABI Hash", info.mbAbiHash.map( hexString ) )
            jsonSection( "ABI Definition", info.mbAbi )
            jsonSection( "User Documentation", info.mbUserDoc )
            jsonSection( "Developer Documentation", info.mbDeveloperDoc )
            section( "Metadata", info.mbMetadata )
            section( "AST", info.mbAst )
            section( "Project Name", info.mbProjectName )
          }
        }
        case Right( hash ) => {
          val mbinfo = shoebox.Database.compilationInfoForCodeHash( hash ).get // throw any db problem
          mbinfo.fold( println( s"Contract with code hash '$hash' not found." ) ) { info =>
            section( "Code Hash", Some( hash.hex ), true )
            section( "Code", Some( info.code ), true )
            section( "Contract Name", info.mbName )
            section( "Contract Source", info.mbSource )
            section( "Contract Language", info.mbLanguage )
            section( "Language Version", info.mbLanguageVersion )
            section( "Compiler Version", info.mbCompilerVersion )
            section( "Compiler Options", info.mbCompilerOptions )
            section( "ABI Hash", info.mbAbiHash.map( hexString ) )
            jsonSection( "ABI Definition", info.mbAbi )
            jsonSection( "User Documentation", info.mbUserDoc )
            jsonSection( "Developer Documentation", info.mbDeveloperDoc )
            section( "Metadata", info.mbMetadata )
            section( "AST", info.mbAst )
            section( "Project Name", info.mbProjectName )
            addressSection( "Deployments", shoebox.Database.chainIdContractAddressesForCodeHash( hash ).get )
          }
        }
      }
      println( cap )
      println()
    }
  }

  private def ethContractCompilationListTask : Initialize[Task[Unit]] = Def.task {
    val contractsSummary = shoebox.Database.contractsSummary.assert // throw for any db problem
    val columns = immutable.Vector( "Chain ID", "Contract Address", "Name", "Code Hash", "Deployment Timestamp" ).map( texttable.Column.apply( _ ) )

    def extract( csr : shoebox.Database.ContractsSummaryRow ) : Seq[String] = {
      import csr._
      val id = mb_chain_id.fold("")( _.toString )
      val ca = emptyOrHex( contract_address )
      val nm = blankNull( name )
      val ch = emptyOrHex( code_hash )
      val ts = blankNull( timestamp )
      immutable.Vector( id, ca, nm, ch, ts )
    }

    texttable.printTable( columns, extract )( contractsSummary.map( csr => texttable.Row(csr) ) )
  }

  private def ethDebugGanacheStartTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    def newGanacheProcess = {
      try {
        val plogger = ProcessLogger(
          line => log.info( s"ganache: ${line}" ),
          line => log.warn( s"ganache: ${line}" )
        )
        log.info(s"Executing command '${testing.Default.Ganache.Command}'")
        Process( testing.Default.Ganache.CommandParsed ).run( plogger )
      } catch {
        case t : Throwable => {
          log.error(s"Failed to start a local ganache process with command '${testing.Default.Ganache.Command}'.")
          throw t
        }
      }
    }

    Mutables.LocalGanache synchronized {
      Mutables.LocalGanache.get match {
        case Some( process ) => log.warn("A local ganache environment is already running. To restart it, please try 'ethDebugGanacheRestart'.")
        case _               => {
          Mutables.LocalGanache.set( Some( newGanacheProcess ) )
          log.info("A local ganache process has been started.")
        }
      }
    }
    log.info("Awaiting availability of testing jsonrpc interface.")

    val efactory = implicitly[Exchanger.Factory]
    val poller = implicitly[Poller]
    borrow( Client.forExchanger( efactory( testing.Default.EthJsonRpc.Url ) ) ) { client =>
      val task = Poller.Task( "await-ganache-jsonrpc", 1.seconds, () => Await.result( client.eth.blockNumber().map( Option.apply _ ) recover { case _ => None }, 1.seconds ) )
      Await.result( poller.addTask( task ), 5.seconds )
    }
    log.info("Testing jsonrpc interface found.")
  }

  private def ethDebugGanacheHaltTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    Mutables.LocalGanache synchronized {
      Mutables.LocalGanache.get match {
        case Some( process ) => {
          Mutables.LocalGanache.set( None )
          process.destroy()
          log.info("A local ganache environment was running but has been stopped.")
        }
        case _                                  => {
          log.warn("No local ganache process is running.")
        }
      }
    }
  }

  private def ethKeystoreListTask( config : Configuration ) : Initialize[Task[immutable.SortedMap[EthAddress,immutable.SortedSet[String]]]] = Def.task {
    val keystoresV3 = OnlyShoeboxKeystoreV3
    val log         = streams.value.log
    val chainId     = findNodeChainIdTask(warn=true)(config).value

    val combined = combinedKeystoresMultiMap( keystoresV3 )

    val out = {
      def aliasesSet( address : EthAddress ) : immutable.SortedSet[String] = immutable.TreeSet( shoebox.AddressAliasManager.findAddressAliasesByAddress( chainId, address ).get : _* )
      immutable.TreeMap( combined.map { case ( address : EthAddress, _ ) => ( address, aliasesSet( address ) ) }.toSeq : _* )( Ordering.by( _.hex ) )
    }
    val cap = "+" + dashspan(44) + "+"
    val KeystoreAddresses = "Keystore Addresses"
    println( cap )
    println( f"| $KeystoreAddresses%-42s |" )
    println( cap )
    out.keySet.toSeq.foreach { address =>
      val ka = s"0x${address.hex}"
      val aliasesArrow = {
        val aliases = out(address)
        if ( aliases.isEmpty ) "" else s""" <-- ${aliases.mkString(", ")}"""
      }
      println( f"| $ka%-42s |" +  aliasesArrow )
    }
    println( cap )
    out
  }

  private def ethKeystorePrivateKeyRevealTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val is = interactionService.value
      val log = streams.value.log

      val address = parser.parsed
      val addressStr = address.hex

      val s = state.value
      val extract = Project.extract(s)
      val (_, wallets) = extract.runInputTask(xethLoadWalletsV3For in config, addressStr, s) // config doesn't really matter here, since we provide hex, not a config dependent alias

      val privateKey = Mutables.MainSignersManager.findRawPrivateKey( log, is, address, wallets )
      val confirmation = {
        is.readLine(s"Are you sure you want to reveal the unencrypted private key on this very insecure console? [Type YES exactly to continue, anything else aborts]: ", mask = false)
          .getOrElse(throw new Exception("Failed to read a confirmation")) // fail if we can't get a credential
      }
      if ( confirmation == "YES" ) {
        println( s"0x${privateKey.bytes.widen.hex}" )
      } else {
        throw notConfirmedByUser
      }
    }
  }

  private def ethKeystoreWalletV3CreateTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    val wallet = xethKeystoreWalletV3CreateDefault.value
    interactiveOptionalCreateAliasTask( config, wallet.address )
  }

  private def ethKeystoreWalletV3FromJsonImportTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    val log = streams.value.log
    val is = interactionService.value
    val w = readV3Wallet( is )
    val address = w.address // a very cursory check of the wallet, NOT full validation
    shoebox.Keystore.V3.storeWallet( w ).get // asserts success
    log.info( s"Imported JSON wallet for address '0x${address.hex}', but have not validated it.")
    log.info( s"Consider validating the JSON using 'ethKeystoreWalletV3Validate 0x${address.hex}'." )
    interactiveOptionalCreateAliasTask( config, address )
  }

  private def ethKeystoreWalletV3FromPrivateKeyImportTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    val log   = streams.value.log
    val c     = xethcfgWalletV3Pbkdf2C.value
    val dklen = xethcfgWalletV3Pbkdf2DkLen.value

    val is = interactionService.value
    val entropySource = ethcfgEntropySource.value

    val privateKeyStr = {
      val raw = is.readLine( "Please enter the private key you would like to import (as 32 hex bytes): ", mask = true ).getOrElse( throwCantReadInteraction ).trim()
      if ( raw.startsWith( "0x" ) ) raw.substring(2) else raw
    }
    val privateKey = EthPrivateKey( privateKeyStr )
    val address = privateKey.address

    val confirm = {
      is.readLine( s"The imported private key corresponds to address '${hexString( address )}'. Is this correct? [y/n] ", mask = false ).getOrElse( throwCantReadInteraction ).trim().equalsIgnoreCase("y")
    }

    if (! confirm ) {
      log.info( "Import aborted." )
      throw new SbtEthereumException( "Import aborted." )
    }
    else {
      println( s"Generating V3 wallet, alogorithm=pbkdf2, c=${c}, dklen=${dklen}" ) // use println rather than log info to be sure this prints before the credential query
      val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
      val w = wallet.V3.generatePbkdf2( passphrase = passphrase, c = c, dklen = dklen, privateKey = Some( privateKey ), random = entropySource )
      shoebox.Keystore.V3.storeWallet( w ).get // asserts success
      log.info( s"Wallet created and imported into sbt-ethereum shoebox: '${shoebox.Directory.assert}'. Please backup, via 'ethShoeboxBackup' or manually." )
      log.info( s"Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x${w.address.hex}'." )
      interactiveOptionalCreateAliasTask( config, address )
    }
  }

  private def ethKeystoreWalletV3PrintTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val keystoreDirs = OnlyShoeboxKeystoreV3
    val wallets = (xethLoadWalletsV3For in config).evaluated
    val sz = wallets.size
    if ( sz == 0 ) unknownWallet( keystoreDirs )
    else if ( sz > 1 ) log.warn( s"Multiple (${sz}) wallets found." )
    wallets.zip( Stream.from(1) ) foreach { case ( w, idx ) =>
      if ( sz > 1 ) println( s"==== V3 Wallet #${idx} ====" )
      println( Json.stringify( w.withLowerCaseKeys ) )
      if ( sz > 1 ) println()
    }
  }

  private def ethKeystoreWalletV3ValidateTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val is = interactionService.value
      val keystoreDirs = OnlyShoeboxKeystoreV3
      val s = state.value
      val extract = Project.extract(s)
      val inputAddress = parser.parsed
      val (_, wallets) = extract.runInputTask(xethLoadWalletsV3For in config, inputAddress.hex, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias
      if ( wallets.isEmpty ) {
        unknownWallet( keystoreDirs )
      }
      else {
        val credential = readCredential( is, inputAddress, acceptHexPrivateKey = false )

        def validateWallet( w : wallet.V3 ) : Failable[Unit] = Failable {
          val privateKey = wallet.V3.decodePrivateKey( w, credential )
          val derivedAddress = privateKey.toPublicKey.toAddress
          if ( derivedAddress != inputAddress ) {
            throw new Exception(
              s"The wallet loaded for '0x${inputAddress.hex}' decodes with the credential given, but to a private key associated with a different address, 0x${derivedAddress}! Keystore files may be mislabeled or corrupted."
            )
          }
        }

        def happy = log.info( s"A wallet for address '0x${inputAddress.hex}' is valid and decodable with the credential supplied." )

        if ( wallets.size == 1 ) {
          validateWallet( wallets.head ).assert
          happy
        }
        else {
          log.warn( s"Multiple wallets found for '${hexString(inputAddress)}'." )
          val failables = wallets.map( validateWallet )
          if ( failables.exists( _.isFailed ) ) {
            log.warn( "Some wallets failed to decode with the credential supplied." )
            failables.filter( _.isFailed ).foreach { failed =>
              log.warn( s"Failure: ${failed.assertFailed.message}" )
            }
          }
          val success = failables.exists( _.isSucceeded )
          if ( success ) log.info( s"At least one wallet for '${hexString(inputAddress)}' did decode with the credential supplied." )
          else throw nst( new SbtEthereumException( s"No wallet for '${hexString(inputAddress)}' could be unlocked with the credential provided." ) )
        }
      }
    }
  }

  private def ethLanguageSolidityCompilerInstallTask : Initialize[InputTask[Unit]] = Def.inputTaskDyn {
    val log = streams.value.log

    val testTimeout = xethcfgAsyncOperationTimeout.value

    val mbVersion = SolcJVersionParser.parsed

    val versionToInstall = mbVersion.getOrElse( SolcJInstaller.DefaultSolcJVersion )

    log.info( s"Installing local solidity compiler into the sbt-ethereum shoebox. This may take a few minutes." )
    val check = shoebox.SolcJ.Directory.flatMap { rootSolcJDir =>
      Failable {
        val versionDir = new File( rootSolcJDir, versionToInstall )
        if ( versionDir.exists() ) {
          log.warn( s"Directory '${versionDir.getAbsolutePath}' already exists. If you would like to reinstall this version, please delete this directory by hand." )
          throw new TaskFailure( s"Cannot overwrite existing installation in '${versionDir.getAbsolutePath}'. Please delete this directory by hand if you wish to reinstall." )
        }
        else {
          installLocalSolcJ( log, rootSolcJDir, versionToInstall, testTimeout )
        }
      }
    }
    check.get // throw if a failure occurred

    xethTriggerDirtySolidityCompilerList // causes parse cache and SessionSolidityCompilers to get updated
  }

  private def ethLanguageSolidityCompilerPrintTask : Initialize[Task[Unit]] = Def.task {
    val log       = streams.value.log
    val ensureSet = (xethFindCurrentSolidityCompiler in Compile).value
    val ( key, compiler ) = Mutables.CurrentSolidityCompiler.get.get
    log.info( s"Current solidity compiler '$key', which refers to $compiler." )
  }

  private def ethLanguageSolidityCompilerSelectTask : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheSessionSolidityCompilerKeys)( genLiteralSetParser )

    Def.inputTask {
      val log = streams.value.log
      val key = parser.parsed
      val mbNewCompiler = Mutables.SessionSolidityCompilers.get.get.get( key )
      val newCompilerTuple = mbNewCompiler.map( nc => ( key, nc ) )
      Mutables.CurrentSolidityCompiler.set( newCompilerTuple )
      log.info( s"Set compiler to '$key'" )
    }
  }

  private def ethNodeBlockNumberPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    implicit val invokerContext = (xethInvokerContext in config).value

    val (url, blockNumber) = jsonrpc.Invoker.withClient { (client, url) =>
      ( url, Await.result( client.eth.blockNumber(), invokerContext.pollTimeout ) )
    }

    log.info( s"The current blocknumber is ${blockNumber}, according to node at '${url}'." )
  }

  private def ethNodeChainIdDefaultDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    assert( config == Compile, "Only the Compile confg is supported for now." )

    val log = streams.value.log
    val oldValue = shoebox.Database.getDefaultChainId().assert
    oldValue match {
      case Some( id ) => {
        config match {
          case Compile => shoebox.Database.deleteDefaultChainId().assert
          case Test    => throw new UnexpectedConfigurationException( config )
          case _       => throw new UnexpectedConfigurationException( config )
        }
        log.info( s"Default chain ID, previously set to ${id}, has now been dropped. No default node chain ID is set." )
        log.info(  "The node chain ID will be determined by hardcoded defaults, unless overridden by an on override." )
        markPotentiallyResetChainId( config )
      }
      case None => {
        log.info( s"No default chain ID was set to be dropped." )
        EmptyTask
      }
    }
  }

  private def ethNodeChainIdDefaultSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    assert( config == Compile, "Only the Compile confg is supported for now." )

    val parser  = intParser("<new-default-chain-id>")

    Def.inputTaskDyn {
      val log     = streams.value.log
      val chainId = parser.parsed
      config match {
        case Compile => {
          shoebox.Database.setDefaultChainId(chainId).assert
        }
        case Test => throw new UnexpectedConfigurationException( config ) 
        case _    => throw new UnexpectedConfigurationException( config )
      }
      log.info( s"The default chain ID has been set to ${chainId}." )
      markPotentiallyResetChainId( config )
    }
  }

  private def ethNodeChainIdDefaultPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    assert( config == Compile, "Only the Compile confg is supported for now." )

    val log = streams.value.log
    val mbChainId = {
      config match {
        case Compile => shoebox.Database.getDefaultChainId().assert
        case Test    => throw new UnexpectedConfigurationException( config )
        case _       => throw new UnexpectedConfigurationException( config )
      }
    }
    mbChainId match {
      case Some( chainId ) => log.info( s"A default chain ID has been explicity set to value ${chainId}." )
      case None            => log.info( s"No default chain ID has been explicitly set. A hardcoded default will be used." )
    }
  }

  private def ethNodeChainIdOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    assert( config == Compile, "Only the Compile confg is supported for now." )

    val log = streams.value.log
    Mutables.ChainIdOverride.synchronized {
      val oldValue = Mutables.ChainIdOverride.get
      Mutables.ChainIdOverride.set( None )
      oldValue match {
        case Some( chainId ) => {
          log.info( s"A chain ID override had been set to ${chainId}, but has now been dropped." ) // when we have the find task implemented, make this more informative
          log.info( "The effective chain ID will be determined either by a default set with 'ethNodeChainIdDefaultSet', by an 'ethcfgNodeChainId' set in the build or '.sbt. folder, or an sbt-ethereum hardcoded default." )
          markPotentiallyResetChainId( config )
        }
        case None => {
          log.info( "No chain ID override was set to be dropped." )
          EmptyTask
        }
      }
    }
  }

  private def ethNodeChainIdOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    assert( config == Compile, "Only the Compile confg is supported for now." )

    val parser  = intParser(s"<chain-id>")

    Def.inputTaskDyn {
      val log = streams.value.log
      Mutables.ChainIdOverride.synchronized {
        val oldValue = Mutables.ChainIdOverride.get
        val newValue = parser.parsed
        oldValue match {
          case Some( oldOverride ) if oldOverride == newValue => {
            log.info( s"A chain ID override had already been set to ${oldOverride}. Nothing to do." )
            EmptyTask
          }
          case Some( oldOverride ) => {
            Mutables.ChainIdOverride.set( Some( newValue ) )
            log.info( s"A prior chain ID override (old value ${oldOverride}) has been replaced with a new override, chain ID ${newValue}." )
            markPotentiallyResetChainId( config )
          }
          case None => {
            Mutables.ChainIdOverride.set( Some( newValue ) )
            log.info( s"The chain ID has been overridden to ${newValue}." )
            markPotentiallyResetChainId( config )
          }
        }
      }
    }
  }

  private def ethNodeChainIdOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    assert( config == Compile, "Only the Compile confg is supported for now." )

    Mutables.ChainIdOverride.synchronized {
      val log = streams.value.log
      val value = Mutables.ChainIdOverride.get
      value match {
        case Some( chainId ) => log.info( s"The chain ID is overridden to ${chainId}." )
        case None            => log.info(  "The chain ID has not been overridden." )
      }
    }
  }

  // make sure this task is kept in sync with maybeFindNodeChainIdTask(...)
  private def ethNodeChainIdPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val mbEffective = maybeFindNodeChainIdTask(warn=false)(config).value

    val log = streams.value.log

    val mbOverride = {
      config match {
        case Compile => Mutables.ChainIdOverride.synchronized { Mutables.ChainIdOverride.get }
        case Test    => None // overrides only supported for config compile, for now
        case _       => None 
      }
    }
    val mbBuildSetting = (config / ethcfgNodeChainId).?.value
    val mbShoeboxDefault = {
      config match {
        case Compile => shoebox.Database.getDefaultChainId().assert
        case Test    => None // shoebox default chain ID only supported in Compile
        case _       => None
      }
    }

    val pfx = configPrefix( config )

    mbEffective match {
      case Some( effective ) => {
        log.info( s"The current effective node chain ID is '${effective}'." )

        ( mbOverride, mbBuildSetting, mbShoeboxDefault ) match {
          case ( Some( ov ), _, _) => {
            assert( effective == ov, "We expect that if a session override is set, it is the effective node chain ID." )
            log.info( " + This value has been explicitly set as a session override via 'ethNodeChainIdOverrideSet'." )
            mbBuildSetting.foreach( hardCoded => log.info( s" + It has overridden a value explicitly set in the project build or the '.sbt' folder as '${pfx}ethcfgNodeChainId': ${hardCoded}" ) )
            mbShoeboxDefault.foreach( shoeboxDefault => log.info( s" + It has overridden a default node chain ID value set in the sbt-ethereum shoebox: ${shoeboxDefault}" ) )
          }
          case ( None, Some( buildSetting ), _ ) => {
            assert( effective == buildSetting, "We expect that if no session override is set, but a node chain ID is set as a build setting, it is the effective chain ID." )
            log.info( s" + This value has been explicitly defined as setting '${pfx}ethcfgNodeChainId' in the project build or the '.sbt' folder, and has not been overridden by a session override." )
            mbShoeboxDefault.foreach( shoeboxDefault => log.info( s" + It has overridden a default node chain ID value set in the sbt-ethereum shoebox: ${shoeboxDefault}" ) )
          }
          case ( None, None, Some( shoeboxDefault ) ) => {
            assert(
              effective == shoeboxDefault,
              s"We expect that if no session override is set, and no build setting, but a default node chain ID is set in the shoebox, it is the effective URL for that chain."
            )
            log.info( s" + This value is the default node chain ID defined in the sbt-ethereum shoebox. " )
            log.info( s" + It has not been overridden with a session override or by an '${pfx}ethcfgNodeChainId' setting in the project build or the '.sbt' folder." )
          }
          case ( None, None, None ) => {
            assert(
              effective == findBackstopChainId( config ).get,
              s"With no session override, no '${pfx}ethcfgNodeChainId' setting in the build, and no default chain ID set, the effective chain ID should be the last-resort hard-coded chain ID."
            )
            log.info(  " + This is the default chain ID hard-coded into sbt-ethereum. " )
            log.info( s" + It has not been overridden with a session override or by an '${pfx}ethcfgNodeChainId' setting in the project build or the '.sbt' folder. " )
            log.info( s" + There is no default node chain ID defined in the sbt-ethereum shoebox." )
          }
        }
      }
      case None => {
        log.warn(  "No node chain ID is currently available!")
        log.warn( s" + No session override has been set with '${pfx}ethNodeChainIdOverrideSet'" )
        log.warn( s" + No default node chain ID has been defined in the sbt-ethereum shoebox." )
        log.warn( s" + No backstop node chain ID is available as a hardcoded default." )
      }
    }
  }

  private def ethNodeUrlDefaultPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val mbJsonRpcUrl = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
    mbJsonRpcUrl match {
      case Some( url ) => {
        log.info( s"The default node json-rpc URL for chain with ID ${chainId} is '${url}'." )
      }
      case None => {
        log.info( s"No default node json-rpc URL for chain with ID ${chainId} has been set." )
      }
    }
  }

  private def ethNodeUrlDefaultSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val is = interactionService.value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val newUrl = urlParser( "<json-rpc-url>" ).parsed
    val oldValue = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
    oldValue.foreach { url =>
      println( s"A default node json-rpc URL for chain with ID ${chainId} has already been set: '${url}'." )
      val overwrite = queryYN( is, s"Do you wish to replace it? [y/n] " )
      if ( overwrite ) {
        shoebox.Database.dropDefaultJsonRpcUrl( chainId ).assert
      }
      else {
        throw new OperationAbortedByUserException( s"User chose not to replace previously set default node json-rpc URL for chain with ID ${chainId}, which remains '${url}'." )
      }
    }
    shoebox.Database.setDefaultJsonRpcUrl( chainId, newUrl ).assert
    log.info( s"Successfully set default node json-rpc URL for chain with ID ${chainId} to ${newUrl}." )
  }

  private def ethNodeUrlDefaultDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val oldValue = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
    oldValue match {
      case Some( url ) => {
        val check = shoebox.Database.dropDefaultJsonRpcUrl( chainId ).assert
        assert( check, "Huh? We had a an old jsonRpcUrl value, but trying to delete it failed to delete any rows?" )
        log.info( s"The default node json-rpc URL for chain with ID ${chainId} was '${url}', but it has now been successfully dropped." )
      }
      case None => {
        log.info( s"No default node json-rpc URL for chain with ID ${chainId} has been set. Nothing to do here." )
      }
    }
  }

  private def ethNodeUrlOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val mbNodeUrlOverride = Mutables.NodeUrlOverrides.get( chainId )
    mbNodeUrlOverride match {
      case Some( url ) => {
        log.info( s"The default node json-rpc URL for chain with ID ${chainId} has been overridden." )
        log.info( s"The overridden value '${url}' will be used for all tasks." )
      }
      case None => {
        log.info( "The default node json-rpc URL for chain with ID ${chainId} has not been overridden, and will be used for all tasks. Try 'ethNodeUrlDefaultPrint' to see that URL." )
      }
    }
  }

  private def ethNodeUrlOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val overrideUrl = urlParser( "<override-json-rpc-url>" ).parsed
    Mutables.NodeUrlOverrides.set( chainId, overrideUrl )
    log.info( s"The default node json-rpc URL for chain with ID ${chainId} has been overridden. The new overridden value '${overrideUrl}' will be used for all tasks." )
  }

  private def ethNodeUrlOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.NodeUrlOverrides.drop( chainId )
    log.info( s"Any override has been dropped. The default node json-rpc URL for chain with ID ${chainId}, or else an sbt-ethereum hardcoded value, will be used for all tasks." )
  }

  // make sure this task is kept in sync with maybeFindNodeUrlTask(...)
  private def ethNodeUrlPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val mbEffective = maybeFindNodeUrlTask(warn=false)(config).value

    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value

    val mbOverride = Mutables.NodeUrlOverrides.get( chainId )
    val mbBuildSetting = (config/ethcfgNodeUrl).?.value
    val mbShoeboxDefault = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert

    val pfx = configPrefix( config )

    mbEffective match {
      case Some( effective ) => {
        log.info( s"The current effective node json-rpc URL for chain with ID ${chainId} is '${effective}'." )

        ( mbOverride, mbBuildSetting, mbShoeboxDefault ) match {
          case ( Some( ov ), _, _) => {
            assert( effective == ov, "We expect that if a session override is set, it is the effective node json-rpc URL." )
            log.info( " + This value has been explicitly set as a session override via 'ethNodeUrlOverrideSet'." )
            mbBuildSetting.foreach( hardCoded => log.info( s" + It has overridden a value explicitly set in the project build or the '.sbt' folder as '${pfx}ethcfgNodeUrl': ${hardCoded}" ) )
            mbShoeboxDefault.foreach( shoeboxDefault => log.info( s" + It has overridden a default node json-rpc URL value for chain with ID ${chainId} set in the sbt-ethereum shoebox: ${shoeboxDefault}" ) )
          }
          case ( None, Some( buildSetting ), _ ) => {
            assert( effective == buildSetting, "We expect that if no session override is set, but a node json-rpc URL is set as a build setting, it is the effective URL." )
            log.info( s" + This value has been explicitly defined as setting '${pfx}ethcfgNodeUrl' in the project build or the '.sbt' folder, and has not been overridden by a session override." )
            mbShoeboxDefault.foreach( shoeboxDefault => log.info( s" + It has overridden a default node json-rpc URL value for chain with ID ${chainId} set in the sbt-ethereum shoebox: ${shoeboxDefault}" ) )
          }
          case ( None, None, Some( shoeboxDefault ) ) => {
            assert(
              effective == shoeboxDefault,
              s"We expect that if no session override is set, and no build setting, but a default node json-rpc URL for chain with ID ${chainId} is set in the shoebox, it is the effective URL for that chain."
            )
            log.info( s" + This value is the default node json-rpc URL defined in the sbt-ethereum shoebox for chain with ID ${chainId}. " )
            log.info( s" + It has not been overridden with a session override or by an '${pfx}ethcfgNodeUrl' setting in the project build or the '.sbt' folder." )
          }
          case ( None, None, None ) => {
            assert(
              effective == findBackstopUrl(warn=false)( log, config, chainId ).get,
              s"${effective} != ${findBackstopUrl(warn=false)( log, config, chainId ).get}: " +
              s"With no session override, no '${pfx}ethcfgNodeUrl' setting in the build, and no default URL set for chain with ID ${chainId}, the effective URL should be the last-resort 'backstop' URL."
            )
            log.info(  " + This is the 'last-resort', backstop URL, either taken from an environment variable or system property, or else hard-coded into sbt-ethereum. " )
            log.info( s" + It has not been overridden with a session override or by an '${pfx}ethcfgNodeUrl' setting in the project build or the '.sbt' folder. " )
            log.info( s" + There is no default node json-rpc URL for chain with ID ${chainId} defined in the sbt-ethereum shoebox." )
          }
        }
      }
      case None => {
        log.warn(  "No node json-rpc URL is currently available!")
        log.warn( s" + No session override has been set with '${pfx}ethNodeUrlOverrideSet'" )
        log.warn( s" + No default node json-rpc URL for chain with ID ${chainId} has been defined in the sbt-ethereum shoebox." )
        log.warn( s" + No backstop node URL suitable to chain ID ${chainId} is available as a system property, environment variable, nor hardcoded default." )
      }
    }
  }

  def ethShoeboxDatabaseDumpCreateTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val dump = shoebox.Database.dump().assert
    log.info( s"sbt-ethereum shoebox database dump successfully created at '${dump.file.getCanonicalPath}'." )
  }

  def ethShoeboxDatabaseDumpRestoreTask : Initialize[Task[Unit]] = Def.task {
    val is  = interactionService.value
    val log = streams.value.log

    def interactiveListAndSelectDump( dumps : immutable.SortedSet[shoebox.Database.Dump] ) : Option[shoebox.Database.Dump] = {
      val numberedFiles = immutable.TreeMap.empty[Int,shoebox.Database.Dump] ++ Stream.from(1).zip( dumps )
      println( "The following sbt-ethereum shoebox database dump files have been found:" )
      numberedFiles.foreach { case ( n, d ) => println( s"\t${n}. ${d.file.getCanonicalPath}" ) }
      val mbNumber = queryPositiveIntOrNone( is, "Which database dump should we restore? (Enter a number, or hit enter to abort) ", 1, dumps.size )
      mbNumber.map( num => numberedFiles(num) )
    }

    val dumpsByMostRecent = shoebox.Database.dumpsOrderedByMostRecent.assert
    if ( dumpsByMostRecent.isEmpty ) {
      log.warn( "No sbt-ethereum shoebox database dumps are available." )
    }
    else {
      val mbDump = interactiveListAndSelectDump( dumpsByMostRecent )
      mbDump match {
        case Some( dump ) => {
          shoebox.Database.restoreFromDump( dump ).assert
          log.info( s"Restore from dump successful. (Prior, replaced database should have backed up into '${shoebox.Database.supersededByDumpDirectory.assert}'." )
        }
        case None => throw new OperationAbortedByUserException( "User aborted replacement of the sbt-ethereum shoebox database from a previously generated dump file." )
      }
    }
  }

  private def updateBackupDir( f : File ) : Unit = shoebox.Database.setShoeboxBackupDir( f.getAbsolutePath )

  /*
   * Consider renaming this something like xethShoeboxDoBackup (key and task), then
   * implementing a command that sequentially executes this then reload...
   * 
   * See https://stackoverflow.com/questions/33252704/is-there-a-way-to-programmatically-call-reload-in-sbt
   */ 
  private def ethShoeboxBackupTask : Initialize[Task[Unit]] = Def.task {
    val is = interactionService.value
    val log = streams.value.log

    def queryCreateBackupDir( bd : File, alreadyDefault : Boolean ) : Option[File] = {
      val prefix = if (alreadyDefault) "Default b" else "B"
      val create = queryYN( is, s"${prefix}ackup directory '${bd.getAbsolutePath}' does not exist. Create? [y/n] " )
      if ( create ) {
        Some( ensureUserOnlyDirectory( bd ).assert )
      }
      else {
        None
      }
    }
    def promptForNewBackupDir( promptToSave : Boolean = true ) : File = {
      val rawPath = is.readLine( "Enter the path of the directory into which you wish to create a backup: ", mask = false ).getOrElse( throwCantReadInteraction )
      if ( rawPath.isEmpty ) throw new OperationAbortedByUserException( s"No directory provided. Backup aborted." )
      val checkAbsolute = new File( rawPath )
      val putativeDir = {
        if ( checkAbsolute.isAbsolute) {
          checkAbsolute
        }
        else {
          log.warn( s"A relative directory path was provided, interpreting relative to user home directory '${HomeDir.getAbsolutePath}'." )
          new File( HomeDir, rawPath )
        }
      }
      val existingDir = {
        if ( !putativeDir.exists() ) {
          queryCreateBackupDir( putativeDir, alreadyDefault = false ) match {
            case Some( f ) => f
            case None => throw new OperationAbortedByUserException( s"User aborted creation of selected directory for backups '${putativeDir.getAbsolutePath}'." )
          }
        }
        else {
          putativeDir
        }
      }
      if ( promptToSave ) {
        val makeDefault = {
          val currentDefault = shoebox.Database.getShoeboxBackupDir.assert
          val replacingPart = {
            currentDefault match {
              case Some( path ) => s" (replacing current default '${path}')"
              case None         =>  ""
            }
          }
          queryYN( is, s"Use directory '${existingDir.getAbsolutePath}' as the default sbt-ethereum shoebox backup directory${replacingPart}? [y/n] ")
        }
        if ( makeDefault ) updateBackupDir( existingDir )
      }
      existingDir
    }

    var databaseFailureDetected = false
    val dir = {
      val fmbPrior = shoebox.Database.getShoeboxBackupDir().xwarn("An error occurred while trying to read the default shoebox backup directory from the database.")
      fmbPrior match {
        case ok : Succeeded[Option[String]] => {
          ok.result match {
            case Some( path ) => {
              val dd = new File( path ).getAbsoluteFile()
              ( dd.exists, dd.isDirectory, dd.canWrite ) match {
                case ( true, true, true ) => {
                  val useDefault = queryYN( is, s"Create backup in default shoebox backup directory '${dd.getAbsolutePath}'? [y/n] " )
                  if ( useDefault ) dd else promptForNewBackupDir()
                }
                case ( false, _, _ ) => {
                  queryCreateBackupDir( dd, alreadyDefault = true ) match {
                    case Some( f ) => f
                    case None      => promptForNewBackupDir ()
                  }
                }
                case ( _, false, _ ) => {
                  log.warn( s"Selected default shoebox backup directory '${dd.getAbsolutePath}' exists, but is not a directory. Please select a new shoebox backup directory." )
                  promptForNewBackupDir()
                }
                case ( _, _, false ) => {
                  log.warn( s"Selected default shoebox backup directory '${dd.getAbsolutePath}' is not writable. Please select a new shoebox backup directory." )
                  promptForNewBackupDir()
                }
              }
            }
            case None => {
              log.warn( s"No default shoebox backup directory has been selected. Please select a new shoebox backup directory." )
              promptForNewBackupDir()
            }
          }
        }
        case failed : Failed[_] => {
          val msg = "sbt-ethereum experienced a failure while trying to read the default shoebox backup directory from the sbt-ethereum database!"
          failed.xwarn( msg )
          databaseFailureDetected = true
          promptForNewBackupDir( promptToSave = false )
        }
      }
    }
    shoebox.Backup.perform( Some( log ), databaseFailureDetected, dir )
  }

  private def ethShoeboxRestoreTask : Initialize[Task[Unit]] = Def.taskDyn {
    val s = state.value
    val is = interactionService.value
    val log = streams.value.log

    def interactiveListAndSelectBackup( backupFiles : immutable.SortedSet[shoebox.Backup.BackupFile] ) : Option[File] = {
      if ( backupFiles.isEmpty ) {
        log.warn( "No sbt-ethereum shoebox backup files found." )
        None
      }
      else {
        val numberedFiles = immutable.TreeMap.empty[Int,shoebox.Backup.BackupFile] ++ Stream.from(1).zip( backupFiles )
        println( "The following sbt-ethereum shoebox backup files have been found:" )
        numberedFiles.foreach { case ( n, bf ) => println( s"\t${n}. ${bf.file.getCanonicalPath}" ) }
        val mbNumber = queryPositiveIntOrNone( is, "Which backup should we restore? (Enter a number, or hit enter to abort) ", 1, backupFiles.size )
        mbNumber.map( num => numberedFiles(num).file )
      }
    }

    def interactiveMostRecentOrList( mostRecent : shoebox.Backup.BackupFile, fullList : immutable.SortedSet[shoebox.Backup.BackupFile] ) : Option[File] = {
      val use = queryYN( is, s"'${mostRecent.file}' is the most recent sbt-ethereum shoebox backup file found. Use it? [y/n] " )
      if ( use ) {
        Some( mostRecent.file )
      }
      else {
        interactiveListAndSelectBackup( fullList )
      }
    }

    def interactiveSelectFromBackupsOrderedByMostRecent( backupFiles : immutable.SortedSet[shoebox.Backup.BackupFile] ) : Option[File] = {
      if ( backupFiles.isEmpty ) {
        log.warn( s"No sbt-ethereum shoebox backup files found." )
        None
      }
      else {
        val mostRecent = backupFiles.head
        if ( mostRecent.dbDumpSucceeded ) {
          interactiveMostRecentOrList( mostRecent, backupFiles )
        }
        else {
          val backupFilesAllGood = backupFiles.filter( _.dbDumpSucceeded )
          if ( backupFilesAllGood.nonEmpty ) {
            val mostRecentAllGood = backupFilesAllGood.head
            println( "Would you like to use..." )
            println(s"\t1. The most recent backup with a good database dump: ${mostRecentAllGood.file.getCanonicalPath}" )
            println(s"\t2. The most recent backup of all, but with a failed database dump: ${mostRecent.file.getCanonicalPath}" )
            println( "\t3. Some other backup" )
            val mbNum = queryPositiveIntOrNone( is, "Enter a number, or hit return to abort: ", 1, 3 )
            mbNum.flatMap { num =>
              num match {
                case 1 => Some( mostRecentAllGood.file )
                case 2 => Some( mostRecent.file )
                case 3 => interactiveListAndSelectBackup( backupFiles )
              }
            }
          }
          else {
            interactiveMostRecentOrList( mostRecent, backupFiles )
          }
        }
      }
    }

    def interactiveSearchFile( backupFileOrDirectory : File ) : Option[File] = {
      if ( ! backupFileOrDirectory.exists() ) {
        log.warn( s"Selected file '${backupFileOrDirectory}' does not exist." )
        None
      }
      else if ( backupFileOrDirectory.isFile ) {
        val mbBackupFile = shoebox.Backup.attemptAsBackupFile( backupFileOrDirectory )
        mbBackupFile foreach { bf =>
          if (! bf.dbDumpSucceeded ) {
            log.warn( s"Although the database files were backup up as-is, the database from this backup could not be dumped to SQL text, which may indicate corruption." )
          }
        }
        mbBackupFile.map( _.file )
      }
      else if ( backupFileOrDirectory.isDirectory ) {
        val dir = backupFileOrDirectory
        if ( dir.canRead() ) {
          val backupFiles = shoebox.Backup.backupFilesOrderedByMostRecent( dir.listFiles )
          if ( backupFiles.isEmpty ) {
            println( s"No backup files found in '${backupFileOrDirectory}'." )
            val recurse = queryYN( is, "Do you want to recursively search subdirectories for backups? [y/n] " )
            if ( recurse ) {
              val recursiveBackupFiles = shoebox.Backup.backupFilesOrderedByMostRecent( recursiveListBeneath( dir ) )
              interactiveSelectFromBackupsOrderedByMostRecent( recursiveBackupFiles )
            }
            else {
              log.warn( "No backup file selected." )
              None
            }
          }
          else {
            interactiveSelectFromBackupsOrderedByMostRecent( backupFiles )
          }
        }
        else {
          log.warn( s"Selected directory '${dir}' is not readable." )
          None
        }

      }
      else {
        log.warn( s"Unexpected file type: ${backupFileOrDirectory}" )
        None
      }
    }
    def promptViaBackupOrDir() : Option[File] = {
      val rawPath = {
        is.readLine( "Enter the path to a directory containing sbt-ethereum shoebox backup files or a backup file directly (or return to abort): ", mask = false )
          .getOrElse( throwCantReadInteraction )
          .trim
      }
      if ( rawPath.isEmpty ) {
        None
      }
      else {
        val checkAbsolute = new File( rawPath )
        val putative = {
          if ( checkAbsolute.isAbsolute) {
            checkAbsolute
          }
          else {
            log.warn("A relative directory path was provided, interpreting relative to user home directory '${HomeDir.getAbsolutePath}'.")
            new File( HomeDir, rawPath )
          }
        }
        if (! putative.exists) {
          println( s"Selected file '${putative}' does not exist. Try again." )
          promptViaBackupOrDir()
        }
        else {
          val out = interactiveSearchFile( putative )
          out
        }
      }
    }

    val mbBackupFile = {
      shoebox.Database.getShoeboxBackupDir().xwarn("An error occurred while trying to read the default shoebox backup directory from the database.") match {
        case Succeeded( Some( dirPath ) ) => {
          val dir = new File( dirPath )
          if ( dir.isDirectory() && dir.exists() && dir.canRead() ) {
            val search = queryYN( is, s"Search default backup directory '${dir}' for backups? [y/n] " )
            if (search) {
              interactiveSearchFile( dir )
            }
            else {
              promptViaBackupOrDir()
            }
          }
          else {
            promptViaBackupOrDir()
          }
        }
        case _ => promptViaBackupOrDir()
      }
    }

    mbBackupFile match {
      case Some( backupFile ) => {
        resetAllState()
        shoebox.Backup.restore( Some( log ), backupFile )
        val extract = Project.extract(s)
        val (_, result) = extract.runTask( xethOnLoadSolicitCompilerInstall, s)
      }
      case None => throw new OperationAbortedByUserException( s"No sbt-ethereum shoebox backup file selected. Restore aborted." )
    }

    xethTriggerDirtySolidityCompilerList // causes parse cache and SessionSolidityCompilers to get updated
  }

  private def ethTransactionDeployTask( config : Configuration ) : Initialize[InputTask[immutable.Seq[Tuple2[String,Either[EthHash,Client.TransactionReceipt]]]]] = {
    val parser = Defaults.loadForParser(xethFindCacheSeeds in config)( genContractSpawnParser )

    Def.inputTaskDyn {
      val s = state.value
      val is = interactionService.value
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val ephemeralDeployment = isEphemeralChain( chainId )

      val lazySigner = findCurrentSenderLazySignerTask( config ).value
      val sender = lazySigner.address

      // at the time of parsing, a compiled contract may not not available.
      // in that case, we force compilation now, but can't accept contructor arguments
      //
      // alternatively, even if at the time of parsing a compilation WAS available
      // it may be out of date if the source for the prior compilation has changed
      // to be safe, we have to reload the compilation, rather than use the one found
      // by the parser
      val currentCompilationsMap = (xethLoadCurrentCompilationsOmitDupsCumulative in config).value
      val updateChangedDb = (xethUpdateContractDatabase in config).value

      def anySourceFreshSeed( deploymentAlias : String ) : MaybeSpawnable.Seed = {
        val mbCurrentCompilationSeed = {
          for {
            cc <- currentCompilationsMap.get( deploymentAlias )
          } yield {
            MaybeSpawnable.Seed( deploymentAlias, cc.code, cc.info.mbAbi.get, true /* this is a current compilation */ )  // asserts that we've generated an ABI
          }
        }

        // TODO: lookup archived compilation in case no current compilation is found

        mbCurrentCompilationSeed match {
          case Some( seed ) => seed
          case None         => throw new NoSuchCompilationException( s"""Could not find compilation '${deploymentAlias}' among available compilations [${currentCompilationsMap.keys.mkString(", ")}]""" )
        }
      }

      val mbAutoNameInputs = (ethcfgAutoDeployContracts in config).?.value

      def createQuintetFull( full : SpawnInstruction.Full ) : (String, String, immutable.Seq[String], Unsigned256, Abi ) = {
        if ( full.seed.currentCompilation ) {
          assert(
            full.deploymentAlias == full.seed.contractName,
            s"For current compilations, we expect deployment aliases and contract names to be identical! [deployment alias: ${full.deploymentAlias}, contract name: ${full.seed.contractName}]"
          )
          val compilation = currentCompilationsMap( full.seed.contractName ) // use the most recent compilation, in case source changed after the seed was cached
          ( full.deploymentAlias, compilation.code, full.args, Unsigned256(full.valueInWei), compilation.info.mbAbi.get ) // asserts that we've generated an ABI, but note that args may not be consistent with this latest ABI
        }
        else {
          ( full.deploymentAlias, full.seed.codeHex, full.args, Unsigned256(full.valueInWei), full.seed.abi )
        }
      }

      def createQuintetUncompiled( uncompiledName : SpawnInstruction.UncompiledName ) : (String, String, immutable.Seq[String], Unsigned256, Abi ) = {
        val deploymentAlias = uncompiledName.name
        val seed = anySourceFreshSeed( deploymentAlias ) // use the most recent compilation, in case source changed after the seed was cached
        ( deploymentAlias, seed.codeHex, Nil, Zero256, seed.abi ) // we can only handle uncompiled names if there are no constructor inputs
      }

      def createAutoQuintets() : immutable.Seq[(String, String, immutable.Seq[String], Unsigned256, Abi )] = {
        mbAutoNameInputs match {
          case None => {
            log.warn("No contract name or compilation alias provided. No 'ethcfgAutoDeployContracts' set, so no automatic contracts to deploy.")
            Nil
          }
          case Some ( autoNameInputs ) => {
            autoNameInputs.toList.map { nameAndArgs =>
              val trimmedNameAndArgs = nameAndArgs.trim()
              val words = trimmedNameAndArgs.split("""\s+""")
              require( words.length >= 1, s"Each element of 'ethcfgAutoDeployContracts' must contain at least a contract name! [word length: ${words.length}")
              val deploymentAlias = words.head
              val seed = anySourceFreshSeed( deploymentAlias )
              val argsAndMaybeValue = trimmedNameAndArgs.drop( deploymentAlias.length ) // don't trim this, because our parser expects leading whitespace!
              if ( argsAndMaybeValue.trim.nonEmpty ) {
                val cmvwParser = ctorArgsMaybeValueInWeiParser( seed )
                sbt.complete.Parser.parse( argsAndMaybeValue, cmvwParser ) match {
                  case Left( errorMessage )          => throw new SbtEthereumException( s"Failed to parse constructor arguments and value from autodeployment item '${deploymentAlias}'. Error Message: '${errorMessage}'" )
                  case Right( fullSpawnInstruction ) => ( deploymentAlias, seed.codeHex, fullSpawnInstruction.args, Unsigned256( fullSpawnInstruction.valueInWei ), seed.abi ) 
                }
              }
              else {
                ( deploymentAlias, seed.codeHex, Nil, Zero256, seed.abi )
              }
            }
          }
        }
      }

      val instruction = parser.parsed
      val quintets = {
        instruction match {
          case SpawnInstruction.Auto                        => createAutoQuintets()
          case uncompiled : SpawnInstruction.UncompiledName => immutable.Seq( createQuintetUncompiled( uncompiled ) )
          case full : SpawnInstruction.Full                 => immutable.Seq( createQuintetFull( full ) )
        }
      }

      val interactive = instruction != SpawnInstruction.Auto

      implicit val invokerContext = (xethInvokerContext in config).value

      val nonceOverride = {
        unwrapNonceOverride( Some( log ), chainId ) match {
          case noverride @ Some( _ ) if (quintets.length <= 1) => {
            noverride
          }
          case Some( u256 ) => {
            throw new SbtEthereumException( s"""Cannot create multiple contracts with a fixed nonce override ${u256.widen} set. Contract creations requested: ${quintets.map( _._1 ).mkString(", ")}""" )
          }
          case None => {
            None
          }
        }
      }

      def doSpawn( deploymentAlias : String, codeHex : String, inputs : immutable.Seq[String], valueInWei : Unsigned256, abi : Abi ) : ( String, Either[EthHash,Client.TransactionReceipt] ) = {

        val inputsBytes = ethabi.constructorCallData( inputs, abi ).get // asserts that we've found a meaningful ABI, and can parse the constructor inputs
        val inputsHex = inputsBytes.hex
        val dataHex = codeHex ++ inputsHex

        if ( inputsHex.nonEmpty ) {
          log.debug( s"Contract constructor inputs encoded to the following hex: '${inputsHex}'" )
        }

        val f_txnHash = Invoker.transaction.createContract( lazySigner, valueInWei, dataHex.decodeHexAsSeq, nonceOverride )

        val f_out = {
          for {
            txnHash <- f_txnHash
            _       <- Future.successful( log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." ) )
            receipt <- Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(abi), txnHash, invokerContext.pollTimeout, _ ) )
          } yield {
            log.info( s"Contract '${deploymentAlias}' deployed in transaction with hash '0x${txnHash.hex}'." )
            receipt.contractAddress.foreach { ca =>
              log.info( s"Contract '${deploymentAlias}' has been assigned address '0x${ca.hex}'." )

              if (! ephemeralDeployment ) {
                val dbCheck = {
                  shoebox.Database.insertNewDeployment( chainId, ca, codeHex, sender, txnHash, inputsBytes )
                }
                if ( dbCheck.isFailed ) {
                  dbCheck.xwarn("Could not insert information about deployed contract into the shoebox database.")
                  log.warn("Could not insert information about deployed contract into the shoebox database. See 'sbt-ethereum.log' for more information.")
                }
              }
            }
            receipt
          }
        }
        Await.ready( f_out, Duration.Inf ) // we use Duration.Inf because the Future will throw an Exception internally on a timeout

        val out = {
          f_out.value.get match {
            case Success( receipt ) => {
              Right( receipt )
            }
            case Failure( t ) => {
              def logFailedAbi() = {
                log.warn(  "Contract ABI For Incomplete or Failed Deployment" )
                log.warn(  "================================================" )
                log.warn( Json.stringify( Json.toJson( abi ) ) )
              }
              t match {
                case timeout : Poller.TimeoutException => {
                  f_txnHash onComplete { tryTxnHash =>
                    tryTxnHash.toOption match {
                      case Some( txnHash ) => {
                        log.warn( s"Timeout after ${invokerContext.pollTimeout}!!! -- ${timeout}" )
                        log.warn( s"Failed to retrieve a transaction receipt for the creation of contract '${deploymentAlias}'!" )
                        log.warn(  "The contract may have been created, but without a receipt, the compilation and ABI could not be associated with an address.")
                        log.warn( s"To recheck for contract creation, try 'ethTransactionLookup ${hexString(txnHash)}" )
                        log.warn( s"You may wish to check sender adddress '0x${sender.hex}' in a blockchain explorer (e.g. etherscan)." )
                        log.warn(  "Once you have verified the deployed contract's address, you will need to manually associate the ABI (see below) with the address of the transaction succeeded with `ethContractAbiImport <address>`." )
                        logFailedAbi()
                      }
                      case None => {
                        val message = s"We experienced a timeout, which we expect only if we successfully submitted the transaction, yet we apparently did not receive the transaction hash from the server?! ${timeout}"
                        log.error( message )
                        SEVERE.log( message, timeout )
                      }
                    }
                  }
                }
                case whatev => {
                  log.warn( s"Deployment of '${deploymentAlias}' did not succeed!" )
                  logFailedAbi()
                  log.warn( s"Failure: ${whatev.toString}" )
                  whatev.printStackTrace()
                }
              }
              Left( Await.result( f_txnHash, Duration.Inf ) ) // given prior await, should return immediately or else throw the unexpected Exception
            }
          }
        }

        if ( !ephemeralDeployment && interactive ) {
          out match {
            case Right( ctr ) => {
              val address = ctr.contractAddress.getOrElse( throw new SbtEthereumException("Huh? We deployed a contract, but the transaction receipt contains no contract address!") )
              interactiveSetAliasForAddress( chainId )( s, log, is, s"the newly deployed '${deploymentAlias}' contract at '${hexString(address)}'", address )
            }
            case Left( _ ) => ()
          }
        }

        ( deploymentAlias, out )
      }

      val result = quintets.map( (doSpawn _).tupled )

      Def.taskDyn {
        Def.task {
          val force = xethTriggerDirtyAliasCache.value
          result
        }
      }
    }
  }

  private def ethTransactionGasLimitOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.GasLimitTweakOverrides.drop( chainId )
    log.info( s"No gas override is now set for chain with ID ${chainId}. Quantities of gas will be automatically computed." )
  }

  @tailrec
  private def doReadMarkup( log : sbt.Logger, is : sbt.InteractionService, overObject : String, limitOrPrice : String ) : Float = {
    val markupStr = assertReadLine( is, s"Enter a markup over ${overObject} (as a fraction, e.g. 0.2): ", mask = false ).trim
    if ( markupStr.isEmpty ) {
      val checkAbort = queryYN( is, "No markup provided. Abort? [y/n] " )
      if ( checkAbort ) aborted( "User aborted the gas ${limitOrPrice} override." ) else doReadMarkup( log, is, overObject, limitOrPrice )
    }
    else {
      val fmarkup = Failable( markupStr.toFloat )
      fmarkup match {
        case Succeeded( markup ) => {
          if (markup < 0 || markup > 1) {
            val confirmed = queryYN( is, s"A markup of ${markup} (${markup * 100}%) is unusual. Are you sure? [y/n] " )
            if ( confirmed ) markup else doReadMarkup( log, is, overObject, limitOrPrice )
          }
          else {
            markup
          }
        }
        case Failed( _ : NumberFormatException ) => {
          log.warn( s"'${markupStr}' could not be interpreted as a floating point number." )
          doReadMarkup( log, is, overObject, limitOrPrice )
        }
        case oops @ Failed( _ ) => {
          oops.vomit
        }
      }
    }
  }

  private def ethTransactionGasLimitOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val is  = interactionService.value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val mbAmount = bigIntParser("[optional-gas-limit-override]").?.parsed
    val ovr = {
      mbAmount match {
        case Some( amount ) => Invoker.Override( amount )
        case None           => {
          val mbFixed = assertReadOptionalBigInt( log, is, "Enter a fixed gas limit override, or hit [Enter] to specify a dynamic markup with optional cap and floor: ", mask = false )
          mbFixed match {
            case Some( fixed ) => Invoker.Override( fixed )
            case None          => {
              val markup = doReadMarkup(log, is, "estimated gas costs", "limit")
              val cap = assertReadOptionalBigInt( log, is, "Enter a cap for the acceptable gas limit (or [Enter] for no cap): ", mask = false )
              val floor = assertReadOptionalBigInt( log, is, "Enter a floor for the acceptable gas limit (or [Enter] for no floor): ", mask = false )
              Invoker.Markup( fraction = markup, cap = cap, floor = floor )
            }
          }
        }
      }
    }
    Mutables.GasLimitTweakOverrides.set( chainId, ovr )
    log.info( s"Gas limit override set on chain with ID ${chainId}, ${formatGasLimitTweak( ovr )}." )
  }

  private def ethTransactionGasLimitOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.GasLimitTweakOverrides.get( chainId ) match {
      case Some( value ) => log.info( s"A gas limit override is set for chain with ID ${chainId}, ${formatGasLimitTweak( value )}." )
      case None          => log.info( s"No gas limit override is currently set for chain with ID ${chainId}." )
    }
  }

  private def ethTransactionGasPriceOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.GasPriceTweakOverrides.drop( chainId )
    log.info( s"No gas price override is now set for chain with ID ${chainId}." )
    log.info(  "Gas price will be automatically marked-up from your ethereum node's current default value." )
  }

  private def ethTransactionGasPriceOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.GasPriceTweakOverrides.get( chainId ) match {
      case Some( value ) => log.info( s"A gas price override is set for chain with ID ${chainId}, ${formatGasPriceTweak( value )}." )
      case None          => log.info( s"No gas price override is currently set for chain with ID ${chainId}." )
    }
  }

  private def ethTransactionGasPriceOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val is  = interactionService.value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val mbAmount = valueInWeiParser("[optional-gas-price-override-including-unit]").?.parsed
    val ovr = {
      mbAmount match {
        case Some( amount ) => Invoker.Override( amount )
        case None           => {
          val mbFixed = assertReadOptionalAmountInWei( log, is, "Enter a fixed gas price override as amount and unit (e.g. '5 gwei'), or hit [Enter] to specify a dynamic markup with optional cap and floor: ", mask = false )
          mbFixed match {
            case Some( fixed ) => Invoker.Override( fixed )
            case None          => {
              val markup = doReadMarkup(log, is, "default gas price", "price")
              val cap = assertReadOptionalAmountInWei( log, is, "Enter a cap (e.g. '10 gwei') for the acceptable gas price (or [Enter] for no cap): ", mask = false )
              val floor = assertReadOptionalAmountInWei( log, is, "Enter a floor (e.g. '1 gwei') for the acceptable gas price (or [Enter] for no floor): ", mask = false )
              Invoker.Markup( fraction = markup, cap = cap, floor = floor )
            }
          }
        }
      }
    }
    Mutables.GasPriceTweakOverrides.set( chainId, ovr )
    log.info( s"Gas price override set on chain with ID ${chainId}, ${formatGasPriceTweak( ovr )}." )
  }

  private def ethTransactionInvokeTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )

      val lazySigner = findCurrentSenderLazySignerTask( config ).value

      val ( ( contractAddress, function, args, abi, abiLookup ), mbWei ) = parser.parsed
      abiLookup.logGenericShadowWarning( log )

      val amount = mbWei.getOrElse( Zero )
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      log.debug( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
      log.debug( s"Call data for function call: ${callData.hex}" )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendMessage( lazySigner, contractAddress, Unsigned256( amount ), callData, nonceOverride ) flatMap { txnHash =>
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei ${mbWithNonceClause(nonceOverride)}to address '0x${contractAddress.hex}' in transaction with hash '0x${txnHash.hex}'.""" )
        log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(abi), txnHash, invokerContext.pollTimeout, _ ) )
      }
      Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will throw a TimeoutException internally on time out
    }
  }

  private def ethTransactionLookupTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = ethHashParser( "<transaction-hash-hex>" )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val abiOverrides = abiOverridesForChain( chainId )
      val txnHash = parser.parsed

      implicit val invokerContext = (xethInvokerContext in config).value

      log.info( s"Looking up transaction '0x${txnHash.hex}' (will wait up to ${invokerContext.pollTimeout})." )
      val f_out = Invoker.futureTransactionReceipt( txnHash ).map { ctr =>
        val mbAbi = {
          ctr.to flatMap { to =>
            val abiLookup = abiLookupForAddress( chainId, to, abiOverrides )
            abiLookup.resolveAbi( None ) // no need to warn, the ABI we choose only affects pretty-printing
          }
        }
        prettyPrintEval( log, mbAbi, txnHash, invokerContext.pollTimeout, ctr )
      }
      Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout
    }
  }

  private def ethTransactionMockTask( config : Configuration ) : Initialize[InputTask[(Abi.Function,immutable.Seq[Decoded.Value])]] = {
    ethTransactionViewMockTask( restrictToConstants = false )( config )
  }

  private def ethTransactionNonceOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.NonceOverrides.drop( chainId )
    log.info( s"Any nonce override for chain with ID ${chainId} has been unset. The nonces for any new transactions will be automatically computed." )
  }

  private def ethTransactionNonceOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val amount = bigIntParser("<nonce-override>").parsed
    Mutables.NonceOverrides.set( chainId, amount )
    log.info( s"Nonce override set to ${amount} for chain with ID ${chainId}." )
    log.info(  "Future transactions will use this value as a fixed nonce, until this override is explcitly unset with 'ethTransactionNonceOverrideDrop'." )
  }

  private def ethTransactionNonceOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.NonceOverrides.get( chainId ) match {
      case Some( value ) => {
        log.info( s"A nonce override is set, with value ${value}, on chain with ID ${chainId}." )
        log.info(  "Future transactions will use this value as a fixed nonce, until this override is explcitly unset with 'ethTransactionNonceOverrideDrop'." )
      }
      case None => {
        log.info( s"No nonce override is currently set for chain with ID ${chainId}. The nonces for any new transactions will be automatically computed." )
      }
    }
  }

  private def ethTransactionNonceOverrideValueTask( config : Configuration ) : Initialize[Task[Option[BigInt]]] = Def.task {
    val chainId = findNodeChainIdTask(warn=true)(config).value
    Mutables.NonceOverrides.get( chainId )
  }

  private def ethTransactionSignTask( config : Configuration ) : Initialize[InputTask[EthTransaction.Signed]] = Def.inputTask {
    val s = state.value
    val log = streams.value.log
    val is = interactionService.value
    val currencyCode = ethcfgBaseCurrencyCode.value

    val sessionChainId = findNodeChainIdTask(warn=false)(config).value
    val mbDefaultSigner = findAddressSenderTask(warn=false)(config).value.toOption

    val rpi = (config / xethFindCacheRichParserInfo).value

    val mbUnsignedTxnFromCommandLine : Option[EthTransaction.Unsigned] = bytesParser("[optional-signed-transaction-as-hex]").?.parsed.map { bytes =>
      RLP.decodeComplete[EthTransaction]( bytes ).assert match {
        case txn : EthTransaction.Unsigned => txn
        case _   : EthTransaction.Signed   => throw new SbtEthereumException( "The transaction data you provided is already signed. Cannot re-sign." )
      }
    }

    def queryUnsignedTransactionFile : Option[EthTransaction.Unsigned] = {

      def doQuery = queryOptionalGoodFile (
        is = is, 
        query = "Enter the path to a file containing a binary unsigned transaction, or just [return] to enter transaction data manually: ",
        goodFile = file => file.exists() && file.canRead(),
        notGoodFileRetryPrompt = file => s"The file '${file} does not exist, or is not readable. Please try again!"
      )

      for {
        file <- doQuery
      }
      yield {
        val bytes = file.contentsAsByteSeq
        RLP.decodeComplete[EthTransaction]( bytes ).assert match {
          case signed : EthTransaction.Signed => throw new SbtEthereumException( "The transaction in '${file}' has already been signed. We will not attempt to re-sign it." )
          case unsigned : EthTransaction.Unsigned => unsigned
        }
      }
    }

    val utxn = (mbUnsignedTxnFromCommandLine orElse queryUnsignedTransactionFile).getOrElse( throw new SbtEthereumException( "Could not find unsigned transaction to sign!" ) )

    val signer : EthAddress = {
      val defaultToMaybeUse = {
        mbDefaultSigner match {
          case Some( address ) => {
            val check = queryYN( is, s"Do you wish to sign for the sender associated with the current session, ${verboseAddress( sessionChainId, address )}? [y/n] " )
            if ( check ) Some( address ) else None 
          }
          case None => None
        }
      }
      if ( defaultToMaybeUse.nonEmpty ) {
        defaultToMaybeUse.get
      }
      else {
        val raw = is.readLine( "Signer (as hex address, ens-name, or alias): ", mask = false).getOrElse( throwCantReadInteraction ).trim
        sbt.complete.Parser.parse( raw, createAddressParser( "<hex-address-ens-name-or-alias>", Some( rpi ) ) ) match {
          case Left( oops ) => throw new SbtEthereumException( s"Attempt to parse failed: ${oops}" )
          case Right( address ) => address
        }
      }
    }
    val chainId = {
      val useSessionChainId = {
        if ( isEphemeralChain( sessionChainId ) ) {
          false
        }
        else {
          queryYN( is, s"The Chain ID associated with your current session is ${sessionChainId}. Would you like to sign with this Chain ID? [y/n] " )
        }
      }
      if ( useSessionChainId ) {
        Some( sessionChainId )
      }
      else {
        val raw = is.readLine( "Enter the Chain ID to sign with (or return for none, no replay protection): ", mask = false).getOrElse( throwCantReadInteraction ).trim
        if (raw.nonEmpty) {
          val asNum = raw.toInt
          if (asNum < 0) None else Some( asNum )
        }
        else {
          None
        }
      }
    }
    if ( chainId == None ) log.warn("No (non-negative) Chain ID value provided. Will sign with no replay attack protection!")

    val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value

    lazy val lazySigner = Mutables.MainSignersManager.findUpdateCacheLazySigner( s, log, is, chainId.getOrElse( DefaultEphemeralChainId ), signer, autoRelockSeconds, true )

    displayTransactionSignatureRequest( log, chainId.getOrElse( DefaultEphemeralChainId ), currencyCode, utxn, signer )
    val check = queryYN( is, "Would you like to sign this transaction? [y/n] " )
    if ( !check ) aborted( "User chose not to sign the transaction." )

    val signed = {
      chainId match {
        case Some( num ) => utxn.sign( lazySigner, EthChainId(num) )
        case None        => utxn.sign( lazySigner )
      }
    }
    val signedAsBytes = RLP.encode[EthTransaction]( signed )

    println()
    println(  "Full signed transaction:" )
    println( s"0x${signedAsBytes.hex}" )
    println()

    def querySignedTransactionFile : Option[File] = queryOptionalGoodFile (
      is = is,
      query = "Enter the path to a (not-yet-existing) file in which to write the binary signed transaction, or [return] to skip: ",
      goodFile = file => !file.exists() && file.getParentFile().canWrite(),
      notGoodFileRetryPrompt = file => s"The file '${file} must not yet exist, but must be writable. Please try again!"
    )

    querySignedTransactionFile match { 
      case Some( file ) => {
        file.replaceContents( signedAsBytes )
        log.info( s"Signed transaction saved as '${file}'." )
      }
      case None => {
        log.warn( s"Signed transaction bytes not saved." )
      }
    }

    signed
  }

  private def querySaveAndPrintUnsignedTransaction( is : sbt.InteractionService, log : sbt.Logger, chainId : Int, nonceOverridden : Boolean, autoNonceAddress : EthAddress, unsigned : EthTransaction.Unsigned ) : Unit = {

    def queryUnsignedTransactionFileToCreate( is : sbt.InteractionService ) : Option[File] = queryOptionalGoodFile (
      is = is,
      query = "Enter the path to a (not-yet-existing) file into which to write the binary unsigned transaction, or [return] not to save: ",
      goodFile = file => !file.exists() && file.getParentFile().canWrite(),
      notGoodFileRetryPrompt = file => s"The file '${file} must not yet exist, but must be writable. Please try again!"
    )

    if ( !nonceOverridden ) {
      log.warn( s"The nonce for this transaction (${unsigned.nonce.widen}) was automatically computed for ${verboseAddress( chainId, autoNonceAddress )}." )
      log.warn(  "The transaction will likely be invalid if signed on behalf of any other address, or if some of transaction is submitted by this address prior to this transaction." )
    }

    val transactionBytes = RLP.encode[EthTransaction]( unsigned )

    println(  "Full unsigned transaction:" )
    println( s"0x${transactionBytes.hex}" )
    println()

    queryUnsignedTransactionFileToCreate( is ) match {
      case Some( file ) => {
        file.replaceContents( transactionBytes )
        log.info( s"Unsigned transaction saved as '${file}'." )
      }
      case None => {
        log.warn( s"Unsigned transaction bytes not saved." )
      }
    }
  }

  private def ethTransactionUnsignedInvokeTask( config : Configuration ) : Initialize[InputTask[EthTransaction.Unsigned]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val caller = findAddressSenderTask(warn=true)(config).value.assert
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )
      val prepareTimeout = xethcfgTransactionUnsignedTimeout.value

      val ( ( contractAddress, function, args, abi, abiLookup ), mbWei ) = parser.parsed
      abiLookup.logGenericShadowWarning( log )

      val amount = mbWei.getOrElse( Zero )
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      log.debug( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
      log.debug( s"Call data for function call: ${callData.hex}" )

      implicit val invokerContext = (xethInvokerContext in config).value

      val unsigned = Await.result( Invoker.transaction.prepareSendMessage( caller, contractAddress, Unsigned256(amount), callData, nonceOverride ), prepareTimeout )

      querySaveAndPrintUnsignedTransaction( is, log, chainId, nonceOverride.nonEmpty, caller, unsigned )

      unsigned
    }
  }

  private def ethTransactionUnsignedRawTask( config : Configuration ) : Initialize[InputTask[EthTransaction.Unsigned]] = {
    val parser = Defaults.loadForParser( xethFindCacheRichParserInfo in config )( genToAddressBytesAmountParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val from = findAddressSenderTask(warn=true)(config).value.assert
      val (to, data, amount) = parser.parsed
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )
      val prepareTimeout = xethcfgTransactionUnsignedTimeout.value

      implicit val invokerContext = (xethInvokerContext in config).value

      val unsigned = Await.result( Invoker.transaction.prepareSendMessage( from, to, Unsigned256(amount), data, nonceOverride ), prepareTimeout )

      querySaveAndPrintUnsignedTransaction( is, log, chainId, nonceOverride.nonEmpty, from, unsigned )

      unsigned
    }
  }


  private def ethTransactionUnsignedEtherSendTask( config : Configuration ) : Initialize[InputTask[EthTransaction.Unsigned]] = {
    val parser = Defaults.loadForParser( xethFindCacheRichParserInfo in config )( genEthSendEtherParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val from = findAddressSenderTask(warn=true)(config).value.assert
      val (to, amount) = parser.parsed
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )
      val prepareTimeout = xethcfgTransactionUnsignedTimeout.value

      implicit val invokerContext = (xethInvokerContext in config).value

      val unsigned = Await.result( Invoker.transaction.prepareSendWei( from, to, Unsigned256(amount), nonceOverride ), prepareTimeout )

      querySaveAndPrintUnsignedTransaction( is, log, chainId, nonceOverride.nonEmpty, from, unsigned )

      unsigned
    }
  }

  private def ethTransactionPingTask( config : Configuration ) : Initialize[InputTask[Option[Client.TransactionReceipt]]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genOptionalGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val from = findAddressSenderTask(warn=true)(config).value.assert
      val mbTo = parser.parsed
      val to = mbTo.getOrElse {
        log.info(s"No recipient address supplied, sender address '0x${ from.hex }' will ping itself.")
        from
      }
      val sendArgs = s" ${to.hex} 0 wei"

      val s = state.value
      val extract = Project.extract(s)

      val recipientStr =  mbTo.fold( "itself" )( addr => "'0x${addr.hex}'" )
      try {
        val (_, result) = extract.runInputTask(ethTransactionEtherSend in config, sendArgs, s)

        log.info( "Ping succeeded!" )
        log.info( s"Sent 0 ether from '${from.hex}' to ${ recipientStr } in transaction with hash '0x${result.transactionHash.hex}'" )
        Some( result )
      }
      catch {
        case t : Poller.TimeoutException => {
          throw nst( new PingFailedException( s"""Ping failed! Our attempt to send 0 ether from '0x${from.hex}' to ${ recipientStr } may or may not eventually succeed, but we've timed out before hearing back.""" ) )
        }
        case inc @ Incomplete( _, _, mbMsg, _, mbCause ) => {
          mbMsg.foreach( msg => log.warn( s"sbt.Incomplete - Message: ${msg}" ) )
          mbCause.foreach( _.printStackTrace() )
          throw nst( new PingFailedException( s"""Ping failed! Our attempt to send 0 ether from '0x${from.hex}' to ${ recipientStr } yielded an sbt.Incomplete: ${inc}""") )
        }
        case NonFatal(t) => {
          t.printStackTrace()
          throw new PingFailedException( s"""Ping failed! Our attempt to send 0 ether from '0x${from.hex}' to ${ recipientStr } yielded an Exception: ${t}""", t)
        }
      }
    }
  }

  private def ethTransactionRawTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = Defaults.loadForParser( xethFindCacheRichParserInfo in config )( genToAddressBytesAmountParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val (to, data, amount) = parser.parsed
      val mbAbi = {
        val abiLookup = abiLookupForAddress( chainId, to, abiOverridesForChain( chainId ) )
        abiLookup.resolveAbi( None ) // don't log anything, the ABI here is not necessary, just makes for richer messages
      }
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )

      lazy val lazySigner = findCurrentSenderLazySignerTask( config ).value

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendMessage( lazySigner, to, Unsigned256( amount ), data, nonceOverride ) flatMap { txnHash =>
        log.info( s"""Sending data '0x${data.hex}' with ${amount} wei to address '0x${to.hex}' ${mbWithNonceClause(nonceOverride)}in transaction with hash '0x${txnHash.hex}'.""" )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, mbAbi, txnHash, invokerContext.pollTimeout, _ ) )
      }
      val out = Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout
      log.info("Transaction mined.")
      out
    }
  }

  private def ethTransactionEtherSendTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = Defaults.loadForParser( xethFindCacheRichParserInfo in config )( genEthSendEtherParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val (to, amount) = parser.parsed
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )

      val lazySigner = findCurrentSenderLazySignerTask( config ).value

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendWei( lazySigner, to, Unsigned256( amount ), nonceOverride ) flatMap { txnHash =>
        log.info( s"Sending ${amount} wei to address '0x${to.hex}' ${mbWithNonceClause(nonceOverride)}in transaction with hash '0x${txnHash.hex}'." )
        log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, None, txnHash, invokerContext.pollTimeout, _ ) )
      }
      val out = Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout
      log.info("Ether sent.")
      out
    }
  }

  private def ethTransactionForwardTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = Def.inputTask {

    val s = state.value
    val log = streams.value.log
    val is = interactionService.value
    val sessionChainId = findNodeChainIdTask(warn=true)(config).value
    val nodeUrl = findNodeUrlTask(warn=true)( config ).value
    val replayAttackProtection = ethcfgUseReplayAttackProtection.value

    implicit val invokerContext = (xethInvokerContext in config).value

    def txnBytesFromFile : Option[immutable.Seq[Byte]] = {
      queryOptionalGoodFile (
        is = is,
        query = "Enter the path to a file containing a binary signed transaction, or just [return] to paste or type hex data manually: ",
        goodFile = file => file.exists() && file.canRead(),
        notGoodFileRetryPrompt = file => s"The file '${file} does not exist, or is not readable. Please try again!"
      ).map ( _.contentsAsByteSeq )
    }

    def txnBytesFromInteraction : Option[immutable.Seq[Byte]] = {
      is.readLine( "Paste or type signed transaction as hex bytes, or type [return] to abort: ", mask = false ).map( _.decodeHexAsSeq )
    }
    
    val txnBytesFromCommandLine = bytesParser("[optional-signed-transaction-as-hex]").?.parsed
    val txnBytes = ( txnBytesFromCommandLine orElse txnBytesFromFile orElse txnBytesFromInteraction ).getOrElse {
      throw new SbtEthereumException("Failed to find signed transaction bytes as a task argument, from a file, or via user interaction. Can't forward what we don't have.")        
    }
    val stxn : EthTransaction.Signed = {
      RLP.decodeComplete[EthTransaction]( txnBytes ).assert match {
        case _ : EthTransaction.Unsigned => throw new SbtEthereumException( "The transaction provided is an unsigned, not signed, transaction. It cannot be forwarded to a network." )
        case signed : EthTransaction.Signed   => signed
      }
    }

    stxn.signature match {
      case withId : EthSignature.WithChainId => {
        val sigChainId = withId.chainId.value.unwrap.toInt
        if ( sigChainId != sessionChainId ) {
          log.warn( s"The Chain ID of the transaction you are forwarding (${sigChainId}) does not match the Chain ID associated with your current session (${sessionChainId})." )
          log.warn( s"If the session Node URL '${nodeUrl}' properly matches the session Chain ID ${sessionChainId}, the signature of the transaction will be invalid and it will fail." )
          log.warn( s"Consider aborting this transaction and switching to the proper chain via 'ethNodeChainIdOverrideSet'." )

          kludgeySleepForInteraction()
          val check = queryYN( is, "Do you want to forward the transaction despite the mismatched Chain IDs? [y/n] " )
          if (! check ) aborted( "Operation aborted by user after mismatched Chain IDs detected." )
        }
      }
      case noId : EthSignature.Basic => {
        if ( replayAttackProtection && !isEphemeralChain( sessionChainId ) ) {
          log.warn( s"The transaction you are submitting is signed without specifying a Chain ID." )
          log.warn(  """It is a valid transaction, but it could be inadvertantly or purposely forwarded to other chains, in a so-called "replay attack".""" )

          kludgeySleepForInteraction()
          val check = queryYN( is, "Are you sure you want to submit this transaction, despite its validity not being restricted with a Chain ID? [y/n] " )
          if (! check ) aborted( "Operation aborted by user after absence of Chain ID detected." )
        }
      }
    }

    try {
      val stxnNonce = stxn.nonce.widen
      val nextNonce = Await.result( Invoker.nextNonce( stxn.sender ), Duration.Inf )
      if ( stxnNonce != nextNonce ) {
        val intChainId = {
          invokerContext.chainId match {
            case Some( ecid ) => ecid.value.widen.toInt
            case None         => DefaultEphemeralChainId
          }
        }
        log.warn( s"The signed transaction has a nonce of ${stxnNonce}. The nonce expected for the next transaction by ${verboseAddress( intChainId, stxn.sender )} is ${nextNonce}." )
        log.warn(  "This is likely due to the sender of the unsigned transaction differing from the signer of the transaction." )
        log.warn(  "If submitted, the transaction is very likely to fail." )

        kludgeySleepForInteraction()
        val check = queryYN( is, "Do you still wish to submit the transaction? [y/n] " )
        if (!check) aborted( "Operation aborted by user after mismatched nonce detected." )
      }
    }
    catch {
      case oae : OperationAbortedByUserException => throw oae
      case e   : Exception                       => log.warn( s"Failed to check transaction nonce against the nonce expected by the chain: ${e}" ) 
    }

    val f_out = Invoker.transaction.sendSignedTransaction( stxn ) flatMap { txnHash =>
      log.info( s"""Sending signed transaction with transaction hash '0x${txnHash.hex}'.""" )
      Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, None, txnHash, invokerContext.pollTimeout, _ ) )
    }
    val out = Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout
    log.info("Transaction mined.")
    out
  }

  private def ethTransactionViewTask( config : Configuration ) : Initialize[InputTask[(Abi.Function,immutable.Seq[Decoded.Value])]] = {
    ethTransactionViewMockTask( restrictToConstants = true )( config )
  }

  private def ethTransactionViewMockTask( restrictToConstants : Boolean )( config : Configuration ) : Initialize[InputTask[(Abi.Function,immutable.Seq[Decoded.Value])]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = restrictToConstants ) )

    Def.inputTask {
      val log = streams.value.log
      val timeout = xethcfgAsyncOperationTimeout.value

      val from = findAddressSenderTask(warn=true)(config).value.recover { failed =>
        log.info( s"Failed to find a current sender, using the zero address as a default.\nCause: ${failed}" )
        EthAddress.Zero
      }.get

      val ( ( contractAddress, function, args, abi, abiLookup), mbWei ) = parser.parsed
      abiLookup.logGenericShadowWarning( log )
      if (! function.constant ) {
        log.warn( s"Simulating the result of calling nonconstant function '${function.name}'." )
        log.warn(  "An actual transaction would occur sometime in the future, with potentially different results!" )
        log.warn(  "No changes that would have been made to the blockchain by a call of this function will be preserved." )
      }
      val amount = mbWei.getOrElse( Zero )
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      log.debug( s"Call data for function call: ${callData.hex}" )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.constant.sendMessage( from, contractAddress, Unsigned256(amount), callData ) map { rawResult =>
        log.debug( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.debug( s"Raw result of call to function '${function.name}': 0x${rawResult.hex}" )
        val results = decodeReturnValuesForFunction( rawResult, abiFunction ).get // throw an Exception if we can't get results
        results.length match {
          case 0 => {
            assert( abiFunction.outputs.length == 0 )
            log.info( s"The function ${abiFunction.name} yields no result." )
          }
          case n => {
            assert( abiFunction.outputs.length == n )

            if ( n == 1 ) {
              log.info( s"The function '${abiFunction.name}' yields 1 result." )
            } else {
              log.info( s"The function '${abiFunction.name}' yields ${n} results." )
            }

            def formatResult( idx : Int, result : Decoded.Value ) : String = {
              val param = result.parameter
              val sb = new StringBuilder(256)
              sb.append( s" + Result ${idx} of type '${param.`type`}'")
              if ( param.name.length > 0 ) {
                sb.append( s", named '${param.name}'," )
              }
              sb.append( s" is ${result.stringRep}" )
              sb.toString
            }

            Stream.from(1).zip(results).foreach { case ( idx, result ) => log.info( formatResult( idx, result ) ) }
          }
        }
        ( abiFunction, results )
      }
      Await.result( f_out, timeout )
    }
  }

  // erc20 task definitions

  private def noDecimalsChecker(
    log : sbt.Logger,
    is : InteractionService,
    chainId : Int,
    tokenContractAddress : EthAddress,
    rawNumStr : String,
    directContractFunction : String
  ) : PartialFunction[Throwable, Unsigned8] = { case e : Exception =>
    log.warn( s"Could not lookup 'decimals' on ${verboseAddress( chainId, tokenContractAddress )}!" )
    log.warn( s"We default to a value of zero, that is rounding (if necessary) ${rawNumStr} and sending that number of atoms." )
    log.warn( s"This may not be what you want!" )
    log.warn( s"Use 'ethTransactionInvoke' and call the function '${directContractFunction}' to take complete control of the process." )
    log.warn( s"(As long as you are sure this is an ERC20 token contract, you can associate the ABI 'abi:standard:erc20' with the token contract address.)" )

    kludgeySleepForInteraction()
    val check = queryYN( is, s"Continue as if decimals is 0, treating the specified token amount (${rawNumStr}) as the number of atoms to transfer? [y/n] " )
    if (!check) aborted( "Could not lookup ERC20 decimals, so user chose to abort." )
    else Zero8
  }

  private def erc20AllowanceSetTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genCompleteErc20TokenApproveParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )

      val lazySigner = findCurrentSenderLazySignerTask( config ).value
      val caller = lazySigner.address

      implicit val invokerContext = (xethInvokerContext in config).value

      val ( tokenContractAddress, approveAddress, rawNumStr ) = parser.parsed
      val f_out = Erc20.lookupDecimals( tokenContractAddress ).recover( noDecimalsChecker( log, is, chainId, tokenContractAddress, rawNumStr, "approve" ) ).flatMap { decimalsUnsigned8 =>
        val decimals = decimalsUnsigned8.widen
        val numAtoms = Erc20.toValueInAtoms( BigDecimal( rawNumStr ), decimals )

        log.warn( s"For the ERC20 token with contract address ${verboseAddress( chainId, tokenContractAddress )}..." )
        log.warn( s"  you would approve use of..." )
        log.warn( s"    Amount:     ${rawNumStr} tokens, which (with ${decimals} decimals) translates to ${numAtoms} atoms." )
        log.warn( s"    Owned By:   ${verboseAddress( chainId, caller )}" )
        log.warn( s"    For Use By: ${verboseAddress( chainId, approveAddress )}" )
        log.warn( s"You are calling the 'approve' function on the contract at ${verboseAddress( chainId, tokenContractAddress )}." )
        log.warn( s"THIS FUNCTION COULD DO ANYTHING. " )
        log.warn( s"Make sure that you trust that the token contract does only what you intend, and carefully verify the transaction cost before approving the ultimate transaction." )

        kludgeySleepForInteraction()
        val check = queryYN( is, "Continue? [y/n] " )
        if (! check) aborted( "User aborted the approval of access to tokens by a third party." )

        Erc20.doApprove( tokenContractAddress, lazySigner, approveAddress, numAtoms, nonceOverride ) flatMap { txnHash =>
          log.info( s"ERC20 Allowance Approval, Token Contract ${verboseAddress( chainId, tokenContractAddress )}:")
          log.info( s"  --> Approved ${rawNumStr} tokens (${numAtoms} atoms)" )
          log.info( s"  -->   owned by ${verboseAddress( chainId, caller )}" )
          log.info( s"  -->   for use by ${verboseAddress( chainId, approveAddress )}" )
          log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." )
          Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(Erc20.Abi), txnHash, invokerContext.pollTimeout, _ ) )
        }
      }
      Await.result( f_out, Duration.Inf )
    }
  }

  private def erc20ConvertAtomsToTokensTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genCompleteErc20TokenConvertAtomsToTokensParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value

      implicit val invokerContext = (xethInvokerContext in config).value

      val ( tokenContractAddress, numAtoms ) = parser.parsed
      val f_out = Erc20.lookupDecimals( tokenContractAddress ).recover { case _ : Exception =>
        throw new SbtEthereumException( s"Failed to read ERC20 'decimals' from ${verboseAddress( chainId, tokenContractAddress )}. Cannot perform conversion." )
      } map { decimalsUnsigned8 =>
        val decimals  = decimalsUnsigned8.widen
        val numTokens = Erc20.toValueInTokens( numAtoms, decimals )
        log.info( s"For ERC20 Token Contract ${verboseAddress( chainId, tokenContractAddress )}, with ${decimals} decimals, ${numAtoms} atoms translates to...")
        log.info( s"${numTokens} tokens." )
      }
      Await.result( f_out, Duration.Inf )
    }
  }

  private def erc20ConvertTokensToAtomsTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genCompleteErc20TokenConvertTokensToAtomsParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value

      implicit val invokerContext = (xethInvokerContext in config).value

      val ( tokenContractAddress, numTokens ) = parser.parsed
      val f_out = Erc20.lookupDecimals( tokenContractAddress ).recover { case _ : Exception =>
        throw new SbtEthereumException( s"Failed to read ERC20 'decimals' from ${verboseAddress( chainId, tokenContractAddress )}. Cannot perform conversion." )
      } map { decimalsUnsigned8 =>
        val decimals  = decimalsUnsigned8.widen
        val numAtoms = Erc20.toValueInAtoms( numTokens, decimals )
        log.info( s"For ERC20 Token Contract ${verboseAddress( chainId, tokenContractAddress )}, with ${decimals} decimals, ${numTokens} tokens translates to...")
        log.info( s"${numAtoms} atoms." )
      }
      Await.result( f_out, Duration.Inf )
    }
  }

  private def erc20TransferTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genCompleteErc20TokenTransferParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val nonceOverride = unwrapNonceOverride( Some( log ), chainId )

      val lazySigner = findCurrentSenderLazySignerTask( config ).value
      val caller = lazySigner.address

      implicit val invokerContext = (xethInvokerContext in config).value

      val ( tokenContractAddress, toAddress, rawNumStr ) = parser.parsed
      val f_out = Erc20.lookupDecimals( tokenContractAddress ).recover( noDecimalsChecker( log, is, chainId, tokenContractAddress, rawNumStr, "transfer" ) ).flatMap { decimalsUnsigned8 =>
        val decimals = decimalsUnsigned8.widen
        val numAtoms = Erc20.toValueInAtoms( BigDecimal( rawNumStr ), decimals )

        log.warn( s"For the ERC20 token with contract address ${verboseAddress( chainId, tokenContractAddress )}..." )
        log.warn( s"  you would transfer ${rawNumStr} tokens, which (with ${decimals} decimals) translates to ${numAtoms} atoms." )
        log.warn( s"The transfer would be " )
        log.warn( s"  From: ${verboseAddress( chainId, caller )}" )
        log.warn( s"  To:   ${verboseAddress( chainId, toAddress )}" )
        log.warn( s"You are calling the 'transfer' function on the contract at ${verboseAddress( chainId, tokenContractAddress )}." )
        log.warn( s"THIS FUNCTION COULD DO ANYTHING. " )
        log.warn( s"Make sure that you trust that the token contract does only what you intend, and carefully verify the transaction cost before approving the ultimate transaction." )

        kludgeySleepForInteraction()
        val check = queryYN( is, "Continue? [y/n] " )
        if (! check) aborted( "User aborted the token transfer." )

        Erc20.doTransfer( tokenContractAddress, lazySigner, toAddress, numAtoms, nonceOverride ) flatMap { txnHash =>
          log.info( s"ERC20 Transfer, Token Contract ${verboseAddress( chainId, tokenContractAddress )}:")
          log.info( s"  --> Sent ${rawNumStr} tokens (${numAtoms} atoms)" )
          log.info( s"  -->   from ${verboseAddress( chainId, caller )}" )
          log.info( s"  -->   to ${verboseAddress( chainId, toAddress )}" )
          log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." )
          Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(Erc20.Abi), txnHash, invokerContext.pollTimeout, _ ) )
        }
      }
      Await.result( f_out, Duration.Inf )
    }
  }

  private def erc20BalanceTask( config : Configuration ) : Initialize[InputTask[Erc20.Balance]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genCompleteErc20TokenBalanceParser )

    Def.inputTask {
      val log = streams.value.log
      val caller = findAddressSenderTask(warn=false)(config).value.assert
      val chainId = findNodeChainIdTask(warn=true)(config).value

      implicit val invokerContext = (xethInvokerContext in config).value

      val ( tokenContractAddress, mbTokenHolderAddress ) = parser.parsed
      val tokenHolderAddress = mbTokenHolderAddress.getOrElse( caller )
      val f_out = Erc20.lookupDecimals( tokenContractAddress ).map( d => (Some( d.widen : Int ) : Option[Int]) ) recover { case e : Exception =>
        None
      } flatMap { mbDecimals =>
        Erc20.lookupAtomBalance( tokenContractAddress, tokenHolderAddress ) map { wrappedAtoms =>
          val balance = Erc20.Balance( wrappedAtoms.widen, mbDecimals )
          mbDecimals match {
            case Some( decimals ) => {
              log.info( s"For ERC20 Token Contract ${verboseAddress( chainId, tokenContractAddress )}, with ${decimals} decimals...")
              log.info( s"  For Address ${verboseAddress( chainId, tokenHolderAddress )})..." )
              log.info( s"    Balance: ${balance.tokens.get} tokens (which corresponds to ${balance.atoms} atoms)" )
            }
            case None => {
              log.warn( s"Could not read a value for 'decimals' from token contract ${verboseAddress( chainId, tokenContractAddress )}." )
              log.warn(  "We cannot distinguish convert atoms into standard-denomination token values." )
              log.info( s"For ERC20 Token Contract ${verboseAddress( chainId, tokenContractAddress )}...")
              log.info( s"  For Address ${verboseAddress( chainId, tokenHolderAddress )})..." )
              log.info( s"    Balance: ${balance.atoms} ATOMS (it is unclear how these should convert to token values)" )
            }
          }
          balance
        }
      }
      Await.result( f_out, Duration.Inf )
    }
  }

  private def erc20SummaryTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genCompleteErc20TokenContractAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value

      val tokenContractAddress = parser.parsed

      implicit val invokerContext = (xethInvokerContext in config).value

      def opt[T]( ft : Future[T] ) : Future[Option[T]] = ft.map( t => (Some(t) : Option[T]) ).recover { case e : Exception => (None : Option[T]) }

      val f_mbName             = opt( Erc20.lookupName( tokenContractAddress ) )
      val f_mbSymbol           = opt( Erc20.lookupSymbol( tokenContractAddress ) )
      val f_mbDecimals         = opt( Erc20.lookupDecimals( tokenContractAddress ) )
      val f_mbTotalSupplyAtoms = opt( Erc20.lookupTotalSupplyAtoms( tokenContractAddress ) )

      val f_out = {
        for {
          mbName             <- f_mbName
          mbSymbol           <- f_mbSymbol
          mbDecimals         <- f_mbDecimals
          mbTotalSupplyAtoms <- f_mbTotalSupplyAtoms
        } yield {

          val totalSupplyStr = {
            mbTotalSupplyAtoms match {
              case Some( atoms ) => {
                val a = atoms.widen
                mbDecimals match {
                  case Some( decimals ) => {
                    val d = decimals.widen
                    val t = Erc20.toValueInTokens( a, d )
                    s"${t} tokens (${a} atoms)"
                  }
                  case None => {
                    s"""${a} atoms (since no 'decimals' is defined, we can't definitively quantify that as "tokens")"""
                  }
                }
              }
              case None => "<total-supply-unknown>"
            }
          }

          log.info( s"""ERC20 Summary, token contract at ${verboseAddress( chainId, tokenContractAddress )}:""" )
          log.info( s"""  Self-Reported Name:   ${mbName.getOrElse("<name-unknown>")}""" )
          log.info( s"""  Self-Reported Symbol: ${mbSymbol.getOrElse("<symbol-unknown>")}""" )
          log.info( s"""  Decimals:             ${mbDecimals.map( _.widen.toString ).getOrElse( "<no-reported-decimals>" )}""" )
          log.info( s"""  Total Supply:         ${totalSupplyStr}""" )
        }
      }
      Await.result( f_out, Duration.Inf )
    }
  }

  private def erc20AllowancePrintTask( config : Configuration ) : Initialize[InputTask[Erc20.Balance]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genCompleteErc20TokenAllowanceParser )

    Def.inputTask {
      val log = streams.value.log
      val caller = findAddressSenderTask(warn=false)(config).value.assert
      val chainId = findNodeChainIdTask(warn=true)(config).value

      implicit val invokerContext = (xethInvokerContext in config).value

      val ( tokenContractAddress, ownerAddress, allowedAddress ) = parser.parsed
      val f_out = Erc20.lookupDecimals( tokenContractAddress ).map( d => (Some( d.widen : Int ) : Option[Int]) ) recover { case e : Exception =>
        None
      } flatMap { mbDecimals =>
        Erc20.lookupAllowanceAtoms( tokenContractAddress, ownerAddress, allowedAddress ) map { wrappedAtoms =>
          val approved = Erc20.Balance( wrappedAtoms.widen, mbDecimals )
          mbDecimals match {
            case Some( decimals ) => {
              log.info( s"For ERC20 Token Contract ${verboseAddress( chainId, tokenContractAddress )}, with ${decimals} decimals...")
              log.info( s"  Of tokens owned by ${verboseAddress( chainId, ownerAddress )})..." )
              log.info( s"    For use by ${verboseAddress( chainId, allowedAddress )}..." )
              log.info( s"      An allowance of ${approved.tokens.get} tokens (which corresponds to ${approved.atoms} atoms) has been approved." )
            }
            case None => {
              log.warn( s"Could not read a value for 'decimals' from token contract ${verboseAddress( chainId, tokenContractAddress )}." )
              log.warn(  "We cannot distinguish convert atoms into standard-denomination token values." )
              log.info( s"For ERC20 Token Contract ${verboseAddress( chainId, tokenContractAddress )}...")
              log.info( s"  Of tokens owned by ${verboseAddress( chainId, ownerAddress )})..." )
              log.info( s"    For use by ${verboseAddress( chainId, allowedAddress )}..." )
              log.info( s"      An allowance of ${approved.atoms} ATOMS (it is unclear how these should convert to token values) has been approved." )
            }
          }
          approved
        }
      }
      Await.result( f_out, Duration.Inf )
    }
  }

  // xens task definitions

  private def xensClientTask( config : Configuration ) : Initialize[Task[ens.Client]] = Def.task {
    val nameServiceAddress = (config / enscfgNameServiceAddress).value

    val icontext = (xethInvokerContext in config).value

    // for now, we'll hard-code the stub context defaults 
    // we can make this stuff configurable someday if it seems useful
    implicit val scontext = stub.Context( icontext, stub.Context.Default.EventConfirmations, MainScheduler )

    new ens.Client( nameServiceAddress )
  }

  // xeth task definitions

  private def xethDefaultGasPriceTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
    val log        = streams.value.log
    val timeout    = xethcfgAsyncOperationTimeout.value

    val exchangerConfig = findExchangerConfigTask( config ).value

    doGetDefaultGasPrice( exchangerConfig, log, timeout )
  }

  private def xethFindCacheRichParserInfoTask( config : Configuration ) : Initialize[Task[RichParserInfo]] = Def.task {
    val chainId                      = findNodeChainIdTask(warn=false)(config).value
    val mbJsonRpcUrl                 = maybeFindNodeUrlTask(warn=false)(config).value
    val addressAliases               = shoebox.AddressAliasManager.findAllAddressAliases( chainId ).assert
    val abiAliases                   = shoebox.AbiAliasHashManager.findAllAbiAliases( chainId ).assert
    val abiOverrides                 = abiOverridesForChain( chainId )
    val nameServiceAddress           = (config / enscfgNameServiceAddress).value
    val exampleNameServiceTld        = if ( chainId == 1 ) "eth" else "test"
    val exampleNameServiceReverseTld = "addr.reverse"
    
    RichParserInfo( chainId, mbJsonRpcUrl, addressAliases, abiAliases, abiOverrides, nameServiceAddress, exampleNameServiceTld, exampleNameServiceReverseTld )
  }

  private def xethFindCacheSeedsTask( config : Configuration ) : Initialize[Task[immutable.Map[String,MaybeSpawnable.Seed]]] = Def.task {
    (xethLoadSeeds in config).value
  }

  private def xethFindCacheSessionSolidityCompilerKeysTask : Initialize[Task[immutable.Set[String]]] = Def.task {
    val log = streams.value.log
    log.info("Updating available solidity compiler set.")
    val currentSessionCompilers = (xethUpdateSessionSolidityCompilers in Compile).value
    currentSessionCompilers.keySet
  }

  private def xethFindCurrentSolidityCompilerTask : Initialize[Task[Compiler.Solidity]] = Def.task {
    import Compiler.Solidity._

    // val compilerKeys = xethFindCacheSessionSolidityCompilerKeys.value
    val sessionCompilers = Mutables.SessionSolidityCompilers.get.getOrElse( throw new Exception("Internal error -- caching compiler keys during onLoad should have forced sessionCompilers to be set, but it's not." ) )
    val compilerKeys = sessionCompilers.keySet

    val mbJsonRpcUrl = Some( findNodeUrlTask(warn=false)(Compile).value )

    Mutables.CurrentSolidityCompiler.get.map( _._2).getOrElse {
      def latestLocalInstallVersion : Option[SemanticVersion] = {
        val versions = (immutable.TreeSet.empty[SemanticVersion] ++ compilerKeys.map( LocalSolc.versionFromKey ).filter( _ != None ).map( _.get ))
        if ( versions.size > 0 ) Some(versions.last) else None
      }
      def latestLocalInstallKey : Option[String] = latestLocalInstallVersion.map( version => s"${LocalSolc.KeyPrefix}${version.versionString}" )

      val key = {
        mbJsonRpcUrl.flatMap( jru => compilerKeys.find( key => key.startsWith(EthNetcompile.KeyPrefix) && key.endsWith( jru ) ) ) orElse  // use an explicitly set netcompile
        compilerKeys.find( _ == LocalPathSolcKey ) orElse                                                                                 // use a local compiler on the path
        latestLocalInstallKey orElse                                                                                                      // use the latest local compiler in the shoebox
        compilerKeys.find( _.startsWith(EthNetcompile.KeyPrefix) ) orElse                                                                 // use the default eth-netcompile
        compilerKeys.find( _.startsWith( EthJsonRpc.KeyPrefix ) )                                                                         // use the (deprecated, mostly disappeared) json-rpc eth_CompileSolidity
      }.getOrElse {
        throw new Exception( s"Cannot find a usable solidity compiler. compilerKeys: ${compilerKeys}, sessionCompilers: ${sessionCompilers}" )
      }
      val compiler = sessionCompilers.get( key ).getOrElse( throw new Exception( s"Could not find a solidity compiler for key '$key'. sessionCompilers: ${sessionCompilers}" ) )

      Mutables.CurrentSolidityCompiler.set( Some( Tuple2( key, compiler ) ) )

      compiler
    }
  }

  private def xethGasPriceTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
    val gasPriceTweak = findGasPriceTweak( warnOverridden = true )( config ).value
    val defaultGasPrice = (xethDefaultGasPrice in config).value
    gasPriceTweak.compute( defaultGasPrice )
  }

  private def xethGenKeyPairTask : Initialize[Task[EthKeyPair]] = Def.task {
    val log = streams.value.log
    val out = EthKeyPair( ethcfgEntropySource.value )

    // a ridiculous overabundance of caution
    assert {
      val checkpub = out.pvt.toPublicKey
      checkpub == out.pub && checkpub.toAddress == out.address
    }

    log.info( s"Generated keypair for address '0x${out.address.hex}'" )

    out
  }

  /*
   * Generates stubs in Compile, testing resources utility in Test.
   *
   * Note that most keys read by this task are insensitive to the config.
   * We need stuff from Compile for stubs, stuff from Test for testing resources
   * We get it all (so we can reuse a lot of shared task logic, although maybe we
   * should factor that logic out someday so that we can have separate tasks).
   *
   */
  private def xethGenScalaStubsAndTestingResourcesTask( config : Configuration ) : Initialize[Task[immutable.Seq[File]]] = Def.task {
    val log = streams.value.log

    // Used for both Compile and Test
    val mbStubPackage = ethcfgScalaStubsPackage.?.value
    val currentCompilations = xethLoadCurrentCompilationsOmitDupsTask( cumulative = false )( config ).value
    val namedAbis = (xethNamedAbis in config).value
    val dependencies = libraryDependencies.value

    // Used only for Test
    val testingResourcesObjectName = (xethcfgTestingResourcesObjectName in Test).value
    val testingEthNodeUrl = findNodeUrlTask(warn=true)(Test).value

    // Sensitive to config
    val scalaStubsTarget = (sourceManaged in config).value

    // merge ABI sources
    val overlappingNames = currentCompilations.keySet.intersect( namedAbis.keySet )
    if ( !overlappingNames.isEmpty ) {
      throw new SbtEthereumException( s"""Names conflict (overlap) between compilations and named ABIs. Conflicting names: ${overlappingNames.mkString(", ")}""" )
    }
    val allMbTsAbis : immutable.Map[String,Option[TimestampedAbi]] = {
      val sureNamedAbis = namedAbis map { case ( name, abi ) => ( name, Some( abi ) ) }
      val mbCompilationAbis = currentCompilations map { case ( name, contract ) => ( name,  contract.info.mbAbi.map( abi => TimestampedAbi( abi, contract.info.sourceTimestamp ) ) ) }
      sureNamedAbis ++ mbCompilationAbis
    }

    def skipNoPackage : immutable.Seq[File] = {
      log.info("No Scala stubs will be generated as the setting 'ethcfgScalaStubsPackage' has not ben set.")
      log.info("""If you'd like Scala stubs to be generated, please define 'ethcfgScalaStubsPackage'.""")
      immutable.Seq.empty[File]
    }

    def findBadChar( packageStr : String ) = packageStr.find( c => !(Character.isJavaIdentifierPart(c) || c == '.') )

    def findEmptyPackage( packages : Iterable[String] ) = packages.find( _ == "" )

    def findConsuela = dependencies.find( mid => mid.organization == "com.mchange" && mid.name == "consuela" )

    mbStubPackage.fold( skipNoPackage ){ stubPackage =>
      if ( findConsuela == None ) {
        val shortMessage = """Scala stub generation has been requested ('ethcfgScalaStubsPackage' is set), but 'libraryDependencies' do not include a recent version of "com.mchange" %% "consuela""""
        val fullMessage = {
          shortMessage + ", a dependency required by stubs."
        }
        log.error( shortMessage + '.' )
        log.error( """This ought to have been added automatically, but you might try adding a recent version of "com.mchange" %% "consuela" to 'libraryDependencies' (and reporting a bug!).""" )

        throw new SbtEthereumException( fullMessage )
      }
      findBadChar( stubPackage ) match {
        case Some( c ) => throw new SbtEthereumException( s"'ethcfgScalaStubsPackage' contains illegal character '${c}'. ('ethcfgScalaStubsPackage' is set to ${stubPackage}.)" )
        case None => {
          val packages = stubPackage.split("""\.""")
          findEmptyPackage( packages ) match {
            case Some( oops ) => throw new SbtEthereumException( s"'ethcfgScalaStubsPackage' contains an empty String as a package name. ('ethcfgScalaStubsPackage' is set to ${stubPackage}.)" )
            case None => {
              val stubsDirFilePath = packages.mkString( File.separator )
              val stubsDir = new File( scalaStubsTarget, stubsDirFilePath )
              stubsDir.mkdirs()
              val mbFileSets : immutable.Iterable[Option[immutable.Set[File]]] = allMbTsAbis map { case ( className, mbTsAbi ) =>
                mbTsAbi flatMap { tsabi =>
                  val regenerated = stub.Generator.regenerateStubClasses( stubsDir, className, stubPackage, tsabi.abi, tsabi.timestamp )
                  val fileset = regenerated map { regen =>
                    regen match {
                      case stub.Generator.Regenerated.Updated( srcFile, sourceCode ) => {
                        srcFile.replaceContents( sourceCode, CodecUTF8 )
                        srcFile
                      }
                      case stub.Generator.Regenerated.Unchanged( srcFile ) => srcFile
                    }
                  }
                  if ( fileset.isEmpty ) None else Some( fileset )
                } orElse {
                  log.warn( s"No ABI definition found for contract '${className}'. Skipping Scala stub generation." )
                  None : Option[immutable.Set[File]]
                }
              }
              val stubFiles = mbFileSets.filter( _.nonEmpty ).map( _.get ).foldLeft( Vector.empty[File])( _ ++ _ ).toVector
              val testingResourceFiles : immutable.Seq[File] = {
                if ( config == Test ) {
                  if ( allMbTsAbis.contains( testingResourcesObjectName ) ) { // TODO: A case insensitive check
                    log.warn( s"The name of the requested testing resources object '${testingResourcesObjectName}' conflicts with the name of a contract." )
                    log.warn(  "The testing resources object '${testingResourcesObjectName}' will not be generated." )
                    immutable.Seq.empty[File]
                  } else {
                    val gensrc = testing.TestingResourcesGenerator.generateTestingResources( testingResourcesObjectName, testingEthNodeUrl, stubPackage )
                    val testingResourcesFile = new File( stubsDir, s"${testingResourcesObjectName}.scala" )
                    Files.write( testingResourcesFile.toPath, gensrc.getBytes( CharsetUTF8 ) )
                    immutable.Seq( testingResourcesFile )
                  }
                } else {
                  immutable.Seq.empty[File]
                }
              }
              stubFiles ++ testingResourceFiles
            }
          }
        }
      }
    }
  }

  private def xethInvokeDataTask( config : Configuration ) : Initialize[InputTask[immutable.Seq[Byte]]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressFunctionInputsAbiParser( restrictedToConstants = false ) )

    Def.inputTask {
      val log = streams.value.log
      val ( contractAddress, function, args, abi, abiLookup ) = parser.parsed
      abiLookup.logGenericShadowWarning( log )
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      log.info( s"Call data: ${callData.hex}" )
      callData
    }
  }

  private def xethInvokerContextTask( config : Configuration ) : Initialize[Task[Invoker.Context]] = Def.task {

    val log = streams.value.log

    val jsonRpcUrl    = findNodeUrlTask(warn=true)(config).value

    val pollPeriod    = ethcfgTransactionReceiptPollPeriod.value
    val timeout       = ethcfgTransactionReceiptTimeout.value

    val httpTimeout   = xethcfgAsyncOperationTimeout.value

    val gasLimitTweak = findGasLimitTweak( warnOverridden = true )( config ).value
    val gasPriceTweak = findGasPriceTweak( warnOverridden = true )( config ).value

    val is            = interactionService.value
    val currencyCode  = ethcfgBaseCurrencyCode.value

    val useReplayAttackProtection = ethcfgUseReplayAttackProtection.value

    val rawChainId = findNodeChainIdTask(warn=true)(config).value

    val eip155ChainId = {
      if ( isEphemeralChain( rawChainId ) || !useReplayAttackProtection ) None else Some( EthChainId( rawChainId ) )
    }

    val transactionLogger = findTransactionLoggerTask( config ).value

    val approver = transactionApprover( log, rawChainId, is, currencyCode )

    Invoker.Context.fromUrl(
      jsonRpcUrl = jsonRpcUrl,
      chainId = eip155ChainId,
      gasPriceTweak = gasPriceTweak,
      gasLimitTweak = gasLimitTweak,
      pollPeriod = pollPeriod,
      pollTimeout = timeout,
      httpTimeout = httpTimeout,
      transactionApprover = approver,
      transactionLogger = transactionLogger
    )
  }

  private def xethKeystoreWalletV3CreateDefaultTask : Initialize[Task[wallet.V3]] = xethKeystoreWalletV3CreateScryptTask

  private val xethKeystoreWalletV3CreatePbkdf2Task : Initialize[Task[wallet.V3]] = Def.task {
    val log   = streams.value.log
    val c     = xethcfgWalletV3Pbkdf2C.value
    val dklen = xethcfgWalletV3Pbkdf2DkLen.value

    val is = interactionService.value
    val keyPair = xethGenKeyPair.value
    val entropySource = ethcfgEntropySource.value

    log.info( s"Generating V3 wallet, alogorithm=pbkdf2, c=${c}, dklen=${dklen}" )

    kludgeySleepForInteraction()
    val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
    val w = wallet.V3.generatePbkdf2( passphrase = passphrase, c = c, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
    val out = shoebox.Keystore.V3.storeWallet( w ).get // asserts success
    log.info( s"Wallet generated into sbt-ethereum shoebox: '${shoebox.Directory.assert}'. Please backup, via 'ethShoeboxBackup' or manually." )
    log.info( s"Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x${w.address.hex}'." )
    out
  }

  private val xethKeystoreWalletV3CreateScryptTask : Initialize[Task[wallet.V3]] = Def.task {
    val log   = streams.value.log
    val n     = xethcfgWalletV3ScryptN.value
    val r     = xethcfgWalletV3ScryptR.value
    val p     = xethcfgWalletV3ScryptP.value
    val dklen = xethcfgWalletV3ScryptDkLen.value

    val is = interactionService.value
    val keyPair = xethGenKeyPair.value
    val entropySource = ethcfgEntropySource.value

    log.info( s"Generating V3 wallet, alogorithm=scrypt, n=${n}, r=${r}, p=${p}, dklen=${dklen}" )

    kludgeySleepForInteraction()
    val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
    val w = wallet.V3.generateScrypt( passphrase = passphrase, n = n, r = r, p = p, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
    val out = shoebox.Keystore.V3.storeWallet( w ).get // asserts success
    log.info( s"Wallet generated into sbt-ethereum shoebox: '${shoebox.Directory.assert}'. Please backup, via 'ethShoeboxBackup' or manually." )
    log.info( s"Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x${w.address.hex}'." )
    out
  }

  private def xethLoadAbiForTask( config : Configuration ) : Initialize[InputTask[Abi]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = findNodeChainIdTask(warn=true)(config).value
      val abiOverrides = abiOverridesForChain( chainId )
      ensureAbiLookupForAddress( chainId, parser.parsed, abiOverrides ).resolveAbi( Some( log ) ).get
    }
  }

  private def xethLoadCurrentCompilationsKeepDupsTask( config : Configuration ) : Initialize[Task[immutable.Iterable[(String,jsonrpc.Compilation.Contract)]]] = Def.task {
    val log = streams.value.log

    val dummy = (compileSolidity in config).value // ensure compilation has completed

    val compileDir = (ethcfgSolidityDestination in Compile).value // we always want compilations from the compile dir

    val configDir = (ethcfgSolidityDestination in config).value // if distinct, config will usually Test, but we are writing a bit more generally

    def compilationsForDir( dir : File ) : immutable.Iterable[(String,jsonrpc.Compilation.Contract)] = {

      def addContracts( vec : immutable.Vector[(String,jsonrpc.Compilation.Contract)], name : String ) = {
        val next = {
          val file = new File( dir, name )
          try {
            borrow( new BufferedInputStream( new FileInputStream( file ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc.Compilation.Contract]] )
          } catch {
            case e : Exception => {
              log.warn( s"Bad or unparseable solidity compilation: ${file.getPath}. Skipping." )
              log.warn( s"  --> cause: ${e.toString}" )
              Map.empty[String,jsonrpc.Compilation.Contract]
            }
          }
        }
        vec ++ next
      }

      dir.list.filter( _.endsWith(".json") ).foldLeft( immutable.Vector.empty[(String,jsonrpc.Compilation.Contract)] )( addContracts )
    }

    val compileCompilations = compilationsForDir( compileDir )
    config match {
      case Compile => compileCompilations
      case _       => compileCompilations ++ compilationsForDir( configDir )
    }
  }

  // often small, abstract contracts like "owned" get imported into multiple compilation units
  // and compiled multiple times.

  // the duplicates we omit represent literally the same EVM code (although the metadata hash suffixes may
  // differ. we keep the shortest-source version that generates identical (pre-swarm-hash) EVM code

  // we also omit any compilations whose EVM code differs but that have identical names, as there is
  // no way to unambigous select one of these compilations to deploy

  private def xethLoadCurrentCompilationsOmitDupsTask( cumulative : Boolean )( config : Configuration ) : Initialize[Task[immutable.Map[String,jsonrpc.Compilation.Contract]]] = Def.task {
    require( config == Compile || config == Test, "For now we expect to load compilations for deployment selection or stub generation only in Compile and Test configurations." )

    val log = streams.value.log

    val dummy = (compileSolidity in config).value // ensure compilation has completed

    val compileDir = (ethcfgSolidityDestination in Compile).value //
    val configDir  = (ethcfgSolidityDestination in config).value  // if different, usually Test, but we are writing this more generally

    def addBindingKeepShorterSource( addTo : immutable.Map[String,jsonrpc.Compilation.Contract], binding : (String,jsonrpc.Compilation.Contract) ) = {
      val ( name, compilation ) = binding
      addTo.get( name ) match {
        case Some( existingCompilation ) => { // this is a duplicate name, we have to think about whether to add and override or keep the old version
          (existingCompilation.info.mbSource, compilation.info.mbSource) match {
            case ( Some( existingSource ), Some( newSource ) ) => {
              if ( existingSource.length > newSource.length ) addTo + binding else addTo // use the shorter-sourced duplicate
            }
            case ( None, Some( newSource ) )                   => addTo + binding // but prioritize compilations for which source is known
            case ( Some( existingSource ), None )              => addTo
            case ( None, None )                                => addTo
          }
        }
        case None => addTo + binding // not a duplicate name, so just add the binding
      }
    }
    def addAllKeepShorterSource( addTo : immutable.Map[String,jsonrpc.Compilation.Contract], nextBindings : Iterable[(String,jsonrpc.Compilation.Contract)] ) = {
      nextBindings.foldLeft( addTo )( ( accum, next ) => addBindingKeepShorterSource( accum, next ) )
    }
    def addContracts( tup : ( immutable.Map[String,jsonrpc.Compilation.Contract], immutable.Set[String] ), file : File ) = {
      val ( addTo, overlaps ) = tup
      val next = {
        try {
          borrow( new BufferedInputStream( new FileInputStream( file ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc.Compilation.Contract]] )
        } catch {
          case e : Exception => {
            log.warn( s"Bad or unparseable solidity compilation: ${file.getPath}. Skipping." )
            log.warn( s"  --> cause: ${e.toString}" )
            Map.empty[String,jsonrpc.Compilation.Contract]
          }
        }
      }
      val rawNewOverlaps = next.keySet.intersect( addTo.keySet )
      val realNewOverlaps = rawNewOverlaps.foldLeft( immutable.Set.empty[String] ){ ( cur, key ) =>
        val origCodeBcas = BaseCodeAndSuffix( addTo( key ).code )
        val nextCodeBcas = BaseCodeAndSuffix( next( key ).code )

        if ( origCodeBcas.baseCodeHex != nextCodeBcas.baseCodeHex ) cur + key else cur
      }
      ( addAllKeepShorterSource( addTo, next ), overlaps ++ realNewOverlaps )
    }

    val files = {
      def jsonFiles( dir : File ) = dir.list.filter( _.endsWith( ".json" ) ).map( name => new File( dir, name ) )
      config match {
        case Compile         => jsonFiles( compileDir )
        case _ if cumulative => jsonFiles( compileDir ) ++ jsonFiles( configDir )
        case _               => jsonFiles( configDir  )
      }
    }

    val ( rawCompilations, duplicateNames ) = files.foldLeft( ( immutable.Map.empty[String,jsonrpc.Compilation.Contract], immutable.Set.empty[String] ) )( addContracts )
    if ( !duplicateNames.isEmpty ) {
      val dupsStr = duplicateNames.mkString(", ")
      log.warn( s"The project contains mutiple contracts and/or libraries that have identical names but compile to distinct code: $dupsStr" )
      if ( duplicateNames.size > 1 ) {
        log.warn( s"Units '$dupsStr' have been dropped from the deployable compilations list as references would be ambiguous." )
      } else {
        log.warn( s"Unit '$dupsStr' has been dropped from the deployable compilations list as references would be ambiguous." )
      }
      rawCompilations -- duplicateNames
    } else {
      rawCompilations
    }
  }

  // when compilation aliases are done, add them here

  private def xethLoadSeedsTask( config : Configuration ) : Initialize[Task[immutable.Map[String,MaybeSpawnable.Seed]]] = Def.task {
    val log = streams.value.log

    val currentCompilations          = (xethLoadCurrentCompilationsOmitDupsCumulative in config ).value
    val currentCompilationsConverter = implicitly[MaybeSpawnable[Tuple2[String,jsonrpc.Compilation.Contract]]]

    val mbNamedSeeds = currentCompilations.map { cc =>
      val seed = currentCompilationsConverter.mbSeed( cc )
      if (seed.isEmpty) log.warn( s"Compilation missing name and/or ABI cannot be deployed: ${cc._1}" )
      ( cc._1, seed )
    }

    mbNamedSeeds.filter( _._2.nonEmpty ).map ( tup => ( tup._1, tup._2.get ) )
  }


  private def xethLoadWalletsV3Task( config : Configuration ) : Initialize[Task[immutable.Set[wallet.V3]]] = Def.task {
    val s = state.value
    val addressStr = findAddressSenderTask(warn=true)(config).value.assert.hex
    val extract = Project.extract(s)
    val (_, result) = extract.runInputTask((xethLoadWalletsV3For in config), addressStr, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias
    result
  }

  private def xethLoadWalletsV3ForTask( config : Configuration ) : Initialize[InputTask[immutable.Set[wallet.V3]]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val keystoresV3 = OnlyShoeboxKeystoreV3
      val log         = streams.value.log

      val chainId = findNodeChainIdTask(warn=true)(config).value

      val address = parser.parsed

      val out = walletsForAddress( address, keystoresV3 )

      val message = {
        val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "" )( _.fold("")( str => s" (aliases $str)" ) )
        if ( out.isEmpty ) s"""No V3 wallet found for '0x${address.hex}'${aliasesPart}. Directories checked: ${keystoresV3.mkString(", ")}"""
        else s"V3 wallet(s) found for '0x${address.hex}'${aliasesPart}"
      }
      log.info( message )
      out
    }
  }

  private def xethNamedAbisTask( config : Configuration ) : Initialize[Task[immutable.Map[String,TimestampedAbi]]] = Def.task {
    val log    = streams.value.log
    val srcDir = (xethcfgNamedAbiSource in config).value

    def empty = immutable.Map.empty[String,TimestampedAbi]

    if ( srcDir.exists) {
      if ( srcDir.isDirectory ) {
        val files = srcDir.listFiles( JsonFilter )

        def toTuple( f : File ) : ( String, TimestampedAbi ) = {
          val filename = f.getName()
          val name = filename.take( filename.length - JsonFilter.DotSuffix.length ) // the filter ensures they do have the suffix
          val json = borrow ( Source.fromFile( f ) )( _.mkString ) // is there a better way
          ( name, TimestampedAbi( Json.parse( json ).as[Abi], Some(f.lastModified) ) )
        }

        files.map( toTuple ).toMap // the directory ensures there should be no dups!
      } else {
        log.warn( s"Expected named ABI directory '${srcDir.getPath}' is not a directory!" )
        empty
      }
    } else {
      empty
    }
  }

  private def xethTransactionCountTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
    val log        = streams.value.log
    val jsonRpcUrl = findNodeUrlTask(warn=true)(config).value
    val timeout    = xethcfgAsyncOperationTimeout.value
    val sender     = findAddressSenderTask(warn=true)(config).value.assert

    val exchangerConfig = findExchangerConfigTask( config ).value

    doGetTransactionCount( exchangerConfig, log, timeout, sender, jsonrpc.Client.BlockNumber.Pending )
  }

  private def xethOnLoadBannerTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    // val mainEthNodeUrl = findNodeUrlTask(warn=false)(Compile).value
    // val testEthNodeUrl = findNodeUrlTask(warn=false)(Test).value
    // val chainId           = findNodeChainIdTask(warn=true)(Compile).value
    // val mbCurrentSender   = (xethFindCurrentSender in Compile).value
    
    log.info( s"sbt-ethereum-${generated.SbtEthereum.Version} successfully initialized (built ${SbtEthereum.BuildTimestamp})" )
    // log.info( s"sbt-ethereum shoebox: '${shoebox.Directory.assert}' <-- Please backup, via 'ethShoeboxBackup' or manually" )
    // log.info( s"sbt-ethereum main json-rpc endpoint configured to '${mainEthNodeUrl}'" )
    // log.info( s"sbt-ethereum test json-rpc endpoint configured to '${testEthNodeUrl}'" )
    // mbCurrentSender foreach { currentSender => 
    //   val aliasesPart = commaSepAliasesForAddress( chainId, currentSender ).fold( _ => "" )( _.fold("")( commasep => s", with aliases $commasep" ) )
    //   log.info( s"sbt-ethereum main current sender address: ${hexString(currentSender)}${aliasesPart}" )
    // }
  }

  private def xethOnLoadAutoImportWalletsV3Task : Initialize[Task[Unit]] = Def.task {
    val log         = streams.value.log
    val importFroms = ethcfgKeystoreAutoImportLocationsV3.value
    shoebox.Keystore.V3.Directory.foreach { keyStoreDir =>
      importFroms.foreach { importFrom =>
        if ( importFrom.exists() && importFrom.isDirectory() && importFrom.canRead() ) {
          val imported = clients.geth.KeyStore.importAll( keyStoreDir = keyStoreDir, srcDir = importFrom ).assert
          if ( imported.nonEmpty ) {
            log.info( s"""Imported from '${importFrom}' wallets for addresses [${imported.map( _.address ).map( hexString ).mkString(", ")}]""" )
          }
        }
      }
    }
  }

  private def xethOnLoadRecoverInconsistentSchemaTask : Initialize[Task[Unit]] = Def.task {
    val schemaOkay = shoebox.Database.DataSource
    val log        = streams.value.log
    val is         = interactionService.value

    if ( schemaOkay.isFailed ) {
      val msg = "Failed to initialize the sbt-ethereum shoebox database."
      SEVERE.log( msg, schemaOkay.assertThrowable )
      log.error( msg )

      if ( shoebox.Database.schemaVersionInconsistentUnchecked.assert ) {
        val mbLastSuccessful = shoebox.Database.getLastSuccessfulSbtEthereumVersionUnchecked().assert

        log.error( "The sbt-ethereum shoebox database schema is in an inconsistent state (probably because an upgrade failed)." )
        val mbLastDump = shoebox.Database.latestDumpIfAny.assert
        mbLastDump match {
          case Some( dump ) => {
            val dumpTime = formatInstant( dump.timestamp )
            kludgeySleepForInteraction()
            val recover = queryYN( is, s"The most recent sbt-ethereum shoebox database dump available was taken at ${dumpTime}. Attempt recovery? [y/n] " )
            if ( recover ) {
              val attemptedRecovery = shoebox.Database.restoreFromDump( dump )
              if ( attemptedRecovery.isFailed ) {
                val msg = s"Could not restore the database from dump file '${dump.file.getCanonicalPath}'."
                val t = attemptedRecovery.assertThrowable
                log.error( msg )
                SEVERE.log( msg, t )
                throw t
              }
              else {
                shoebox.Database.reset()
                val fmbPostRecoverySchemaVersionUnchecked = shoebox.Database.getSchemaVersionUnchecked()
                val targetSchemaVersion = shoebox.Database.TargetSchemaVersion

                val schemaOkayNow = shoebox.Database.DataSource.isSucceeded
                val inconsistent   = shoebox.Database.schemaVersionInconsistentUnchecked.assert

                ( schemaOkayNow, inconsistent ) match {
                  case ( true, false ) => log.info( "Recovery of sbt-ethereum shoebox database succeeded." )
                  case ( true, true )  => {
                    val msg = "Internal inconsistency. The database simultaneously is reporting itself to be okay while the schema version is inconsistent. Probably an sbt-ethereum bug."
                    log.error( msg )
                    SEVERE.log( msg )
                    throw new SbtEthereumException( msg )
                  }
                  case ( false, true ) => {
                    val baseMsg = {
                      if ( fmbPostRecoverySchemaVersionUnchecked.isSucceeded && fmbPostRecoverySchemaVersionUnchecked.assert != None ) { // we have the schema version we recovered to
                        val dbSchemaVersion = fmbPostRecoverySchemaVersionUnchecked.assert.get
                        s"""|The database restore seems to have succeeded, but the schema version is still inconsistent.
                            |A failure seems to be occurring while upgrading the restored database from schema version ${dbSchemaVersion} to ${targetSchemaVersion}.""".stripMargin
                      }
                      else {
                        s"""|The database restore seems to have succeeded, but the schema version is still inconsistent.
                            |A failure seems to be occurring while upgrading the restored database to ${targetSchemaVersion}.""".stripMargin
                      }
                    }
                    val versionSuggestion = {
                      mbLastSuccessful match {
                        case Some( lastSuccessful ) =>{
                          s"""|
                              |The last version of sbt-ethereum to successfully use the recovered database was ${lastSuccessful}.
                              |Perhaps try restoring from that version.""".stripMargin
                        }
                        case None => ""
                      }
                    }

                    val msg =  baseMsg + versionSuggestion
                    log.error( msg )
                    SEVERE.log( msg )
                    throw new SbtEthereumException( msg )
                  }
                  case ( false, false ) => {
                    val msg = "The schema of the recovered database does not seem inconsistent, but access to the database is still failing, for unknown reasons."
                    log.error( msg )
                    SEVERE.log( msg )
                    throw new SbtEthereumException( msg )
                  }
                }
              }
            }
            else {
              throw new OperationAbortedByUserException( "Offer to attempted recovery refused by user." )
            }
          }
          case None => throw new SbtEthereumException( "No database dumps are available in the shoebox from which to attempt a recover." )
        }
      }
      else {
        val msg = "The schema of the database does not seem inconsistent, but access to the database is still failing, for unknown reasons."
        log.error( msg )
        SEVERE.log( msg )
        throw new SbtEthereumException( msg )
      }
    }
  }

  private def xethOnLoadSolicitCompilerInstallTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val is  = interactionService.value

    val testTimeout = xethcfgAsyncOperationTimeout.value

    val currentDefaultCompilerVersion = SolcJInstaller.DefaultSolcJVersion
    val currentSolcJDirectory         = shoebox.SolcJ.Directory

    if ( currentSolcJDirectory.isFailed ) {
      log.warn( s"Cannot find or create '${shoebox.SolcJ.DirName}' directory in the sbt-ethereum shoebox directory. This is not a good sign." )
    }
    else {
      val DirSolcJ = currentSolcJDirectory.get
      val versionDir = new File( DirSolcJ, currentDefaultCompilerVersion )
      if (! versionDir.exists() ) {
        val compilers = DirSolcJ.list()
        if ( compilers.nonEmpty ) {
          println( s"""Solidity compiler directory '${DirSolcJ.getAbsolutePath}'.""" )
          println( s"""The following compiler versions currently appear to be installed: ${compilers.mkString(", ")}""" )
        }
        def prompt : Option[String] = is.readLine( s"The current default solidity compiler ['${currentDefaultCompilerVersion}'] is not installed. Install? [y/n] ", mask = false )

        @tailrec
        def checkInstall : Boolean = {
          prompt match {
            case None                                  => false
            case Some( str ) if str.toLowerCase == "y" => true
            case Some( str ) if str.toLowerCase == "n" => false
            case _                                     => {
              println( "Please type 'y' or 'n'." )
              checkInstall
            }
          }
        }

        if ( checkInstall ) installLocalSolcJ( log, DirSolcJ, currentDefaultCompilerVersion, testTimeout )
      }
    }
  }

  private def xethOnLoadSolicitWalletV3GenerationTask : Initialize[Task[Unit]] = Def.task {
    val s = state.value
    val is = interactionService.value
    val log = streams.value.log
    val keystoresV3  = OnlyShoeboxKeystoreV3
    val nontestConfig = Compile                                         // XXX: if you change this, change the hardcoded Compile value in the line below!
    val nontestChainId = findNodeChainIdTask(warn=true)(Compile).value  // XXX: note the hardcoding of Compile! illegal dynamic reference if i use nontestConfig
    val combined = combinedKeystoresMultiMap( keystoresV3 )
    if ( combined.isEmpty ) {
      val checkInstall = queryYN( is, "There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] " )
      if ( checkInstall ) {
        val extract = Project.extract(s)

        // NOTE: we use the xeth version of the wallet V3 create task to skip querying for an alias,
        //       since we instead query whetherthe new wallet should become the default sender
        val (_, result) = extract.runTask(xethKeystoreWalletV3CreateDefault, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias

        val address = result.address

        if ( mbDefaultSender( nontestChainId ).isEmpty ) {
          val checkSetDefault = queryYN( is, s"Would you like the new address '${hexString(address)}' to be the default sender on chain with ID ${nontestChainId}? [y/n] " )
          if ( checkSetDefault ) {
            extract.runInputTask( nontestConfig / ethAddressSenderDefaultSet, address.hex, s )
          }
          else {
            println(s"No default sender has been defined. To create one later, use the command 'ethAddressSenderDefaultSet <address>'.")
          }
        }
      }
      else {
        println("No wallet created. To create one later, use the command 'ethKeystoreWalletV3Create'.")
      }
    }
  }

  private def xethShoeboxRepairPermissionsTask : Initialize[Task[Unit]] = Def.task {
    val log   = streams.value.log
    log.info( "Repairing shoebox permissions..." )
    shoebox.repairPermissions().assert
    log.info( "Shoebox permissions repaired." )
  }

  // builds a handle on a very cautious, user accessible signer
  private def xethSignerFinderTask( config : Configuration ) : Initialize[Task[(EthAddress,Option[String]) => EthSigner]] = Def.task {
    val s = state.value
    val log = streams.value.log
    val is = interactionService.value
    val chainId = findNodeChainIdTask(warn=true)(config).value
    val currentSender = findAddressSenderTask(warn=true)(config).value.assert
    val currencyCode = ethcfgBaseCurrencyCode.value
    val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value

    ( address : EthAddress, description : Option[String] ) => {
      address match {
        case `currentSender` => Mutables.MainSignersManager.findUpdateCacheCautiousSigner( s, log, is, chainId, address, priceFeed, currencyCode, description, autoRelockSeconds )
        case _               => Mutables.MainSignersManager.findCheckCacheCautiousSigner( s, log, is, chainId, address, priceFeed, currencyCode, description )
      }
    }
  }

  private def xethSqlQueryShoeboxDatabaseTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log   = streams.value.log
    val query = DbQueryParser.parsed

    // removed guard of query (restriction to select),
    // since updating SQL issued via executeQuery(...)
    // usefully fails in h2

    try {
      val check = {
        shoebox.Database.UncheckedDataSource.map { ds =>
          borrow( ds.getConnection() ) { conn =>
            borrow( conn.createStatement() ) { stmt =>
              borrow( stmt.executeQuery( query ) ) { rs =>
                val rsmd = rs.getMetaData
                val numCols = rsmd.getColumnCount()
                val colRange = (1 to numCols)
                val displaySizes = colRange.map( rsmd.getColumnDisplaySize )
                val labels = colRange.map( rsmd.getColumnLabel )

                // XXX: make this pretty. someday.
                log.info( labels.mkString(", ") )
                while ( rs.next ) {
                  log.info( colRange.map( rs.getString ).mkString(", ") )
                }
              }
            }
          }
        }
      }
      check.get
    }
    catch {
      case t : Throwable => {
        t.printStackTrace()
        throw t
      }
    }
  }

  private def xethSqlUpdateShoeboxDatabaseTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log   = streams.value.log
    val update = DbQueryParser.parsed

    try {
      val check = {
        shoebox.Database.UncheckedDataSource.map { ds =>
          borrow( ds.getConnection() ) { conn =>
            borrow( conn.createStatement() ) { stmt =>
              val rows = stmt.executeUpdate( update )
              log.info( s"Update succeeded: $update" )
              log.info( s"$rows rows affected." )
            }
          }
        }
      }
      check.get // assert success
    }
    catch {
      case t : Throwable => {
        t.printStackTrace()
        throw t
      }
    }
  }

  private def xethStubEnvironmentTask( config : Configuration ) : Initialize[Task[Tuple2[stub.Context,stub.Sender.Signing]]] = Def.task {
    val log = streams.value.log
    val is = interactionService.value
    val currencyCode = ethcfgBaseCurrencyCode.value
    val icontext = (config / xethInvokerContext).value
    val lazySigner = findCurrentSenderLazySignerTask( config ).value

    // for use in closures, access synchronized on its own lock
    val preapprovals = mutable.HashSet.empty[Invoker.TransactionApprover.Inputs]
    val preapprove : Invoker.TransactionApprover.Inputs => Unit = { inputs =>
      preapprovals.synchronized {
        preapprovals += inputs
      }
    }
    val isPreapproved : Invoker.TransactionApprover.Inputs => Boolean = { inputs =>
      preapprovals.synchronized {
        preapprovals(inputs)
      }
    }

    val preapprovingApprover = transactionApprover( log, icontext.chainId, is, currencyCode, preapprove )( icontext.econtext )
    val preapprovingInvokerContext = icontext.copy( transactionApprover = preapprovingApprover )
    val scontext = stub.Context( preapprovingInvokerContext, stub.Context.Default.EventConfirmations, MainScheduler )
    val signer = new CautiousSigner( log, is, priceFeed, currencyCode, description = None )( lazySigner, abiOverridesForChain, isPreapproved )
    val sender = stub.Sender.Basic( signer )
    (scontext, sender)
  }

  // this is a no-op, its execution just triggers a re-caching of aliases
  private def xethTriggerDirtyAliasCacheTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    log.info( "Refreshing caches." )
  }

  // this is a no-op, its execution just triggers a re-caching of available compilers
  private def xethTriggerDirtySolidityCompilerListTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    log.info( "Refreshing compiler list." )
  }

  private def xethUpdateContractDatabaseTask( config : Configuration ) : Initialize[Task[Boolean]] = Def.task {
    val log          = streams.value.log
    val projectName  = name.value
    val compilations = (xethLoadCurrentCompilationsKeepDups in Compile).value // we want to "know" every contract we've seen, which might include contracts with multiple names

    shoebox.Database.updateContractDatabase( compilations, Some( projectName ) ).get
  }

  private def xethUpdateSessionSolidityCompilersTask : Initialize[Task[immutable.SortedMap[String,Compiler.Solidity]]] = Def.task {
    import Compiler.Solidity._

    val netcompileUrl = ethcfgNetcompileUrl.?.value
    val mbJsonRpcUrl  = maybeFindNodeUrlTask(warn=false)(Compile).value // we use the main (compile) configuration, don't bother with a test json-rpc for compilation

    val testTimeout = xethcfgAsyncOperationTimeout.value

    def check( key : String, compiler : Compiler.Solidity ) : Option[ ( String, Compiler.Solidity ) ] = {
      val test = Compiler.Solidity.test( compiler, testTimeout )
      if ( test ) {
        Some( Tuple2( key, compiler ) )
      } else {
        None
      }
    }
    def checkNetcompileUrl( ncu : String ) = check( s"${EthNetcompile.KeyPrefix}${ncu}", Compiler.Solidity.EthNetcompile( ncu ) )

    def netcompile = netcompileUrl.flatMap( checkNetcompileUrl )

    def defaultNetcompile = checkNetcompileUrl( DefaultEthNetcompileUrl )

    def ethJsonRpc = {
      mbJsonRpcUrl.flatMap { jsonRpcUrl =>
        check( s"${EthJsonRpc.KeyPrefix}${jsonRpcUrl}", Compiler.Solidity.EthJsonRpc( jsonRpcUrl ) )
      }
    }
    def localPath = {
      check( Compiler.Solidity.LocalPathSolcKey, Compiler.Solidity.LocalPathSolc )
    }
    def checkLocalShoeboxSolc( version : String ) = {
      shoebox.SolcJ.Directory.toOption.flatMap { rootSolcJDir =>
        check( s"${LocalSolc.KeyPrefix}${version}", Compiler.Solidity.LocalSolc( Some( new File( rootSolcJDir, version ) ) ) )
      }
    }
    def checkLocalShoeboxSolcs = {
      SolcJInstaller.SupportedVersions.map( checkLocalShoeboxSolc ).toSeq
    }

    val raw = checkLocalShoeboxSolcs :+ localPath :+ ethJsonRpc :+ netcompile :+ defaultNetcompile

    val out = immutable.SortedMap( raw.filter( _ != None ).map( _.get ) : _* )
    Mutables.SessionSolidityCompilers.set( Some( out ) )
    out
  }

  // unprefixed tasks

  private def compileSolidityTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    val compiler = (xethFindCurrentSolidityCompiler in Compile).value

    val includeStrings = ethcfgIncludeLocations.value

    val useOptimizer = ethcfgSolidityCompilerOptimize.value
    val numRuns      = ethcfgSolidityCompilerOptimizerRuns.value

    val optimizerRuns = if (useOptimizer) Some( numRuns ) else None

    val solSource      = (ethcfgSoliditySource in config).value
    val solDestination = (ethcfgSolidityDestination in config).value

    val baseDir = baseDirectory.value

    val includeLocations = includeStrings.map( SourceFile.Location.apply( baseDir, _ ) )

    ResolveCompileSolidity.doResolveCompile( log, compiler, optimizerRuns, includeLocations, solSource, solDestination )
  }

  // helper functions

  private def configPrefix( config : Configuration ) : String = {
    config match {
      case Compile => ""
      case Test    => "Test / "
      case other   => throw new SbtEthereumException( s"Unexpected task configuration: ${other}" )
    }
  }

  private def isEphemeralChain( chainId : Int ) : Boolean = {
    chainId < 0
  }

  private def findBackstopChainId( config : Configuration ) : Option[Int] = {
    config match {
      case Compile => Some( MainnetChainId )
      case Test    => Some( DefaultEphemeralChainId )// ephemeral chain with no EIP-155 chain ID
      case _       => None
    }
  }

  private def findBackstopUrl( warn : Boolean )( log : sbt.Logger, config : Configuration, chainId : Int ) : Option[String] = {
    def mbInfuraProjectId = { // thanks to Mike Slinn for suggesting these external defaults
      ExternalValue.EthInfuraToken.flatMap { projectId =>
        InfuraNames.get( chainId ).map( infuraName => s"https://${infuraName}.infura.io/v3/${projectId}")
      }
    }
    def mbInfuraToken = { // thanks to Mike Slinn for suggesting these external defaults
      ExternalValue.EthInfuraToken.flatMap {token =>
        InfuraNames.get( chainId ).map( infuraName => s"https://${infuraName}.infura.io/${token}")
      }
    }
    def mbSpecifiedDefaultNode = { // thanks to Mike Slinn for suggesting these external defaults
      ExternalValue.EthDefaultNode.flatMap { nodeUrl =>
        if (chainId != 1) {
          if (!isEphemeralChain( chainId )) {
            if ( warn ) {
              val warningLinesBuilder = {
                () => Some(
                  immutable.Seq(
                    s"Cannot use backstop URL from environment variable 'ETH_DEFAULT_NODE' or System property 'eth.defaullt.node'.",
                    s"These values are intended to refer to a mainnet node with chain ID 1, but we are set up for a chain with ID ${chainId}."
                  )
                )
              }
              Mutables.OneTimeWarner.warn( OneTimeWarnerKey.EthDefaultNodeSupportedOnlyForMainet, config, chainId, log, warningLinesBuilder )
            }
          }
          None
        }
        else {
          Some( nodeUrl )
        }
      }
    }
    def mbHardCodedBackstop = {
      HardcodedBackstopNodeUrls.get( chainId ).map { hardcodedNodeUrl =>
        if ( warn ) {
          val pfx = configPrefix( config )
          val warningLinesBuilder = {
            () => Some(
              immutable.Seq(
                s"Using hard-coded, backstop node URL '${hardcodedNodeUrl}', which may not be reliable.",
                s"Please use '${pfx}ethNodeUrlDefaultSet` to define a node URL (for chain with ID ${chainId}) to which you have reliable access."
              )
            )
          }
          Mutables.OneTimeWarner.warn( OneTimeWarnerKey.UsingUnreliableBackstopNodeUrl, config, chainId, log, warningLinesBuilder )
        }
        hardcodedNodeUrl
      }
    }
    def mbDefaultEphemeralNodeUrl = {
      if ( isEphemeralChain( chainId ) ) {
        Some( testing.Default.EthJsonRpc.Url )
      }
      else {
        None
      }
    }
  
    mbInfuraProjectId orElse mbInfuraToken orElse mbSpecifiedDefaultNode orElse mbHardCodedBackstop orElse mbDefaultEphemeralNodeUrl
  }


  private def mbDefaultSender( chainId : Int ) : Option[EthAddress] = shoebox.Database.findDefaultSenderAddress( chainId ).get

  private def installLocalSolcJ( log : sbt.Logger, rootSolcJDir : File, versionToInstall : String, testTimeout : Duration ) : Unit = {
    val versionDir = new File( rootSolcJDir, versionToInstall )
    SolcJInstaller.installLocalSolcJ( rootSolcJDir.toPath, versionToInstall )
    log.info( s"Installed local solcJ compiler, version ${versionToInstall} in '${rootSolcJDir}'." )
    val test = Compiler.Solidity.test( new Compiler.Solidity.LocalSolc( Some( versionDir ) ), testTimeout )
    if ( test ) {
      log.info( "Testing newly installed compiler... ok." )
    } else {
      log.warn( "Testing newly installed compiler... failed!" )
      Platform.Current match {
        case Some( Platform.Windows ) => {
          log.warn("You may need to install MS Video Studio 2015 Runtime, see https://www.microsoft.com/en-us/download/details.aspx?id=48145") // known to be necessay for 0.4.18
        }
        case _ => /* ignore */
      }
    }
  }

  private def allUnitsValue( valueInWei : BigInt ) = s"${valueInWei} wei (${Denominations.Ether.fromWei(valueInWei)} ether, ${Denominations.Finney.fromWei(valueInWei)} finney, ${Denominations.Szabo.fromWei(valueInWei)} szabo)"

  private def transactionApprover(
    log : sbt.Logger,
    chainId : Option[EthChainId],
    is : sbt.InteractionService,
    currencyCode : String,
    preapprove : Invoker.TransactionApprover.Inputs => Unit
  )( implicit ec : ExecutionContext ) : Invoker.TransactionApprover.Inputs => Future[Unit] = {
    val rawChainId : Int = {
      chainId match {
        case Some( ecid ) => ecid.value.widen.toInt
        case None         => DefaultEphemeralChainId
      }
    }
    transactionApprover( log, rawChainId, is, currencyCode, preapprove )( ec )
  }

  private def transactionApprover(
    log : sbt.Logger,
    chainId : Int,
    is : sbt.InteractionService,
    currencyCode : String,
    preapprove : Invoker.TransactionApprover.Inputs => Unit = _ => ()
  )( implicit ec : ExecutionContext ) : Invoker.TransactionApprover.Inputs => Future[Unit] = {
    if ( isEphemeralChain( chainId ) ) {
      ephemeralTransactionApprover( log, chainId, is, currencyCode, preapprove )( ec )
    }
    else {
      normalTransactionApprover( log, chainId, is, currencyCode, preapprove )( ec )
    }
  }

  private def ephemeralTransactionApprover(
    log : sbt.Logger,
    chainId : Int,
    is : sbt.InteractionService,
    currencyCode : String,
    preapprove : Invoker.TransactionApprover.Inputs => Unit
  )( implicit ec : ExecutionContext ) : Invoker.TransactionApprover.Inputs => Future[Unit] = {
    inputs => Future.successful( () )
  }

  private def normalTransactionApprover(
    log : sbt.Logger,
    chainId : Int,
    is : sbt.InteractionService,
    currencyCode : String,
    preapprove : Invoker.TransactionApprover.Inputs => Unit
  )( implicit ec : ExecutionContext ) : Invoker.TransactionApprover.Inputs => Future[Unit] = {

    inputs => Future {

      displayTransactionSignatureRequest( log, chainId, currencyCode, inputs.utxn, inputs.signerAddress )

      val check = queryYN( is, "Would you like to sign this transaction? [y/n] " )
      if ( !check ) Invoker.throwDisapproved( inputs, keepStackTrace = false )
      else preapprove( inputs )
    }( ec )
  }

  private def displayTransactionSignatureRequest( log : sbt.Logger, chainId : Int, currencyCode : String, txn : EthTransaction, proposedSender : EthAddress) : Unit = {
    util.Formatting.displayTransactionSignatureRequest( log, chainId, abiOverridesForChain( chainId ), priceFeed, currencyCode, txn, proposedSender )
  }

  private def displayTransactionSubmissionRequest( log : sbt.Logger, chainId : Int, currencyCode : String, txn : EthTransaction, proposedSender : EthAddress) : Unit = {
    util.Formatting.displayTransactionSubmissionRequest( log, chainId, abiOverridesForChain( chainId ), priceFeed, currencyCode, txn, proposedSender )
  }

  private def parseAbi( abiString : String ) = Json.parse( abiString ).as[Abi]

  private def interactiveSetAliasForAddress( chainId : Int )( state : State, log : sbt.Logger, is : sbt.InteractionService, describedAddress : String, address : EthAddress ) : Unit = {
    def rawFetch : String = is.readLine( s"Enter an optional alias for ${describedAddress} (or [return] for none): ", mask = false ).getOrElse( throwCantReadInteraction ).trim()
    def validate( alias : String ) : Boolean = parsesAsAddressAlias( alias )
    def inUse( alias : String ) : Boolean = shoebox.AddressAliasManager.findAddressByAddressAlias( chainId, alias ).assert.nonEmpty

    @tailrec
    def query : Option[String] = {
      rawFetch match {
        case ""                         => None
        case alias if inUse( alias )    => {
          println( s"'${alias}' is already defined. Please try again." )
          query
        }
        case alias if validate( alias ) => Some( alias )
        case alias                      => {
          println( s"'${alias}' is not a valid alias. Please try again." )
          query
        }
      }
    }

    query match {
      case Some( alias ) => {
        val check = shoebox.AddressAliasManager.insertAddressAlias( chainId, alias, address )
        check.fold( _.vomit ){ _ => 
          log.info( s"Alias '${alias}' now points to address '0x${address.hex}' (for chain with ID ${chainId})." )
        }
      }
      case None => log.info( s"No alias set for ${describedAddress}." )
    }
  }

  private def readV3Wallet( is : sbt.InteractionService ) : wallet.V3 = {
    val jsonStr = is.readLine( "V3 Wallet JSON: ", mask = false ).getOrElse( throwCantReadInteraction )
    val jsv = Json.parse( jsonStr )
    wallet.V3( jsv.as[JsObject] )
  }

  private def unknownWallet( loadDirs : Seq[File] ) : Nothing = {
    val dirs = loadDirs.map( _.getAbsolutePath() ).map( "'" + _ + "'" ).mkString(", ")
    throw new Exception( s"Could not find V3 wallet for the specified address in availble keystore directories: ${dirs}" )
  }

  private def assertSomeSender( log : Logger, fsender : Failable[EthAddress] ) : Option[EthAddress] = {
    val onFailed : Failed[EthAddress] => Nothing = failed => {
      val errMsg = {
        val base = "No sender found. Please define a default via 'ethAddressSenderDefaultSet', or define the build setting 'ethcfgAddressSender', or use 'ethAddressSenderOverrideSet' for a temporary sender."
        val extra = failed.source match {
          case _ : SenderNotAvailableException => ""
          case _                               => s" [Cause: ${failed.message}]"
        }
        base + extra
      }
      log.error( errMsg )
      throw new SenderNotAvailableException( errMsg )
    }
    fsender.fold( onFailed )( Some(_) )
  }

  /*
   * This is ridiculously kludgey
   * but gives time for log messages to reach the console prior to interaction prompts,
   * preventing confusing orderings
   */ 
  private def kludgeySleepForInteraction() : Unit = Thread.sleep(100) 


  private def attemptAdvanceStateWithTask[T]( taskKey : Def.ScopedKey[Task[T]], startState : State ) : State = {
    Project.runTask( taskKey, startState ) match {
      case None => {
        WARNING.log(s"Huh? Key '${taskKey}' was undefined in the original state. Ignoring attempt to run that task in onLoad/onUnload.")
        startState
      }
      case Some((newState, Inc(inc))) => {
        SEVERE.log(s"Failed to run '${taskKey}' on initialization: " + Incomplete.show(inc.tpe))
        //startState
        throw new FailureOnInitializationException( "A failure occurred while trying to initialize the sbt-ethereum plugin. Please see prior errors." )
      }
      case Some((newState, Value(_))) => {
        newState
      }
    }
  }

  // plug-in setup

  // very important to ensure the ordering of definitions,
  // so that JvmPlugin's compile actually gets overridden
  override def requires = JvmPlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
