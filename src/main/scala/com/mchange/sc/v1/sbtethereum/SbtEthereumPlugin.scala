package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.Def.Initialize

import sjsonnew._
import BasicJsonProtocol._

import util.BaseCodeAndSuffix
import compile.{Compiler, ResolveCompileSolidity, SemanticVersion, SolcJInstaller, SourceFile}
import util.EthJsonRpc._
import util.Parsers._
import util.SJsonNewFormats._
import util.Abi._
import generated._

import java.io.{BufferedInputStream, File, FileInputStream, FilenameFilter}
import java.nio.file.Files
import java.security.SecureRandom
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
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.io._
import com.mchange.sc.v1.consuela.ethereum._
import jsonrpc.{Abi,Client,MapStringCompilationContractFormat}
import specification.Denominations
import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations._
import com.mchange.sc.v1.consuela.ethereum.ethabi.{Decoded, Encoder, abiFunctionForFunctionNameAndArgs, callDataForAbiFunctionFromStringArgs, decodeReturnValuesForFunction}
import com.mchange.sc.v1.consuela.ethereum.stub
import com.mchange.sc.v1.consuela.ethereum.jsonrpc.Invoker
import com.mchange.sc.v1.consuela.ethereum.clients
import com.mchange.sc.v2.ens
import com.mchange.sc.v1.log.MLogger
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
import com.mchange.sc.v2.concurrent.Poller

object SbtEthereumPlugin extends AutoPlugin {

  initializeLoggingConfig()

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally we should try to log through sbt loggers
  private implicit val logger = mlogger( this )

  private trait AddressInfo
  private final case object NoAddress                                                                                                 extends AddressInfo
  private final case class  UnlockedAddress( chainId : Int, address : EthAddress, privateKey : EthPrivateKey, autoRelockTime : Long ) extends AddressInfo

  final case class TimestampedAbi( abi : Abi, timestamp : Option[Long] )

  object OneTimeWarnedKey {
    final object NodeJsonRpcInBuild extends OneTimeWarnedKey
  }
  trait OneTimeWarnedKey

  private final object Mutables {
    // MT: protected by CurrentAddress' lock
    val CurrentAddress = new AtomicReference[AddressInfo]( NoAddress )

    val SessionSolidityCompilers = new AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]]( None )

    val CurrentSolidityCompiler = new AtomicReference[Option[( String, Compiler.Solidity )]]( None )

    val GasLimitOverride = new AtomicReference[Option[BigInt]]( None )

    val GasPriceOverride = new AtomicReference[Option[BigInt]]( None )

    val NonceOverride = new AtomicReference[Option[BigInt]]( None )

    // MT: protected by OneTimeWarned's lock
    val OneTimeWarned = new AtomicReference[Set[Tuple2[OneTimeWarnedKey,Configuration]]]( Set.empty )

    // MT: protected by AbiOverride's lock
    val AbiOverrides = new AtomicReference[immutable.Map[Int,immutable.Map[EthAddress,Abi]]]( immutable.Map.empty[Int,immutable.Map[EthAddress,Abi]] )

    // MT: protected by SenderOverride's lock
    val SenderOverride = new AtomicReference[Option[ ( Int, EthAddress ) ]]( None )

    // MT: protected by TestSenderOverride's lock
    val TestSenderOverride = new AtomicReference[Option[ ( Int, EthAddress ) ]]( None )

    // MT: protected by NodeJsonRpcOverride's lock
    val NodeJsonRpcUrlOverride = new AtomicReference[Map[Int,String]]( Map.empty ) // Int is the chain ID

    // MT: protected by LocalGanache's lock
    val LocalGanache = new AtomicReference[Option[Process]]( None )

    def reset() : Unit = {
      CurrentAddress synchronized {
        CurrentAddress.set( NoAddress )
      }
      SessionSolidityCompilers.set( None )
      CurrentSolidityCompiler.set( None )
      GasLimitOverride.set( None )
      GasPriceOverride.set( None )
      NonceOverride.set( None )
      OneTimeWarned.set( Set.empty )
      AbiOverrides synchronized {
        AbiOverrides.set( immutable.Map.empty[Int,immutable.Map[EthAddress,Abi]] )
      }
      SenderOverride synchronized {
        SenderOverride.set( None )
      }
      TestSenderOverride synchronized {
        TestSenderOverride.set( None )
      }
      LocalGanache synchronized {
        LocalGanache.set( None )
      }
    }
  }

  private def oneTimeWarn( key : OneTimeWarnedKey, config : Configuration, log : sbt.Logger, messageBuilder : () => Option[String] ) : Unit = Mutables.OneTimeWarned.synchronized {
    val current = Mutables.OneTimeWarned.get
    val check = Tuple2( key, config )
    if( !current( check ) ) {
      messageBuilder().foreach { message =>
        log.warn( message )
        Mutables.OneTimeWarned.set( current + check )
      }
    }
  }

  private def oneTimeWarnedReset( key : OneTimeWarnedKey, config : Configuration ) : Unit = Mutables.OneTimeWarned.synchronized {
    Mutables.OneTimeWarned.set( Mutables.OneTimeWarned.get() - Tuple2( key, config ) )
  }

  private def resetAllState() : Unit = {
    Mutables.reset()
    shoebox.reset()
    util.Parsers.reset()
  }

  private def unwrapNonceOverride( mbLog : Option[sbt.Logger] ) : Option[Unsigned256] = {
    val out = Mutables.NonceOverride.get.map( Unsigned256.apply )
    out.foreach { noverride =>
      mbLog.foreach { log =>
        log.info( s"Nonce override set: ${noverride.widen}" )
      }
    }
    out
  }

  private def senderOverrideForConfig( config : Configuration ) : AtomicReference[Option[ ( Int, EthAddress ) ]] = if ( config == Test ) Mutables.TestSenderOverride else Mutables.SenderOverride

  private def getSenderOverrideAddress( config : Configuration )( log : sbt.Logger, chainId : Int ) : Option[EthAddress] = {
    val configSenderOverride = senderOverrideForConfig( config )

    configSenderOverride.synchronized {
      val ChainId = chainId

      configSenderOverride.get match {
        case Some( ( ChainId, address ) ) => Some( address )
        case Some( (badChainId, _) ) => {
          log.info( s"A sender override was set for the chain with ID ${badChainId}, but that is no longer the current 'ethcfgChainId'. The sender override is stale and will be dropped." )
          configSenderOverride.set( None )
          None
        }
        case None => None
      }
    }
  }

  private def abiOverridesForChain( chainId : Int ) : immutable.Map[EthAddress,Abi] = {
    Mutables.AbiOverrides.synchronized {
      val outerMap = Mutables.AbiOverrides.get
      outerMap.getOrElse( chainId, immutable.Map.empty[EthAddress,Abi] )
    }
  }

  private def addAbiOverrideForChain( chainId : Int, address : EthAddress, abi : Abi ) : Unit = {
    Mutables.AbiOverrides.synchronized {
      val oldOuterMap = Mutables.AbiOverrides.get
      val priorForChain = oldOuterMap.getOrElse( chainId, immutable.Map.empty[EthAddress,Abi] )
      val nextForChain = priorForChain + Tuple2( address, abi )
      val newOuterMap = oldOuterMap + Tuple2( chainId, nextForChain )
      Mutables.AbiOverrides.set( newOuterMap )
    }
  }

  private def removeAbiOverrideForChain( chainId : Int, address : EthAddress ) : Boolean = {
    Mutables.AbiOverrides.synchronized {
      val oldOuterMap = Mutables.AbiOverrides.get
      val priorForChain = oldOuterMap.getOrElse( chainId, immutable.Map.empty[EthAddress,Abi] )
      val out = priorForChain.keySet( address )
      val nextForChain = priorForChain - address
      val newOuterMap = oldOuterMap + Tuple2( chainId, nextForChain )
      Mutables.AbiOverrides.set( newOuterMap )
      out
    }
  }

  private def clearAbiOverrideForChain( chainId : Int ) : Boolean = {
    Mutables.AbiOverrides.synchronized {
      val oldOuterMap = Mutables.AbiOverrides.get
      val out = oldOuterMap.get( chainId ).nonEmpty
      if ( out ) {
        val newOuterMap = oldOuterMap - chainId
        Mutables.AbiOverrides.set( newOuterMap )
      }
      out
    }
  }

  private val BufferSize = 4096

  private val PollSeconds = 15

  private val PollAttempts = 9

  private val Zero = BigInt(0)

  private val Zero256 = Unsigned256( 0 )

  private val EmptyBytes = List.empty[Byte]

  private val DefaultEthJsonRpcUrl = "https://ethjsonrpc.mchange.com/"

  private val DefaultTestEthJsonRpcUrl = testing.Default.EthJsonRpc.Url

  private val DefaultEthNetcompileUrl = "http://ethjsonrpc.mchange.com:8456"

  private val DefaultPriceRefreshDelay = 300.seconds

  private val priceFeed = new PriceFeed.Coinbase( DefaultPriceRefreshDelay )

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
    val enscfgNameServiceAddress           = settingKey[EthAddress]("The address of the ENS name service smart contract")
    val enscfgNameServiceTld               = settingKey[String]    ("The top-level domain associated with the ENS name service smart contract at 'enscfgNameServiceAddress'.")
    val enscfgNameServiceReverseTld        = settingKey[String]    ("The top-level domain under which reverse lookups are supported in the ENS name service smart contract at 'enscfgNameServiceAddress'.")
    val enscfgNameServicePublicResolver    = settingKey[EthAddress]("The address of a publically accessible resolver (if any is available) that can be used to map names to addresses.")

    val ethcfgAutoDeployContracts           = settingKey[Seq[String]] ("Names (and optional space-separated constructor args) of contracts compiled within this project that should be deployed automatically.")
    val ethcfgBaseCurrencyCode              = settingKey[String]      ("Currency code for currency in which prices of ETH and other tokens should be displayed.")
    val ethcfgChainId                       = settingKey[Int]         ("The EIP-155 chain ID for the network with which the application will interact ('mainnet' = 1, 'ropsten' = 3, 'rinkeby' = 4, etc. id<=0 for ephemeral chains)")
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
    val ethcfgNodeJsonRpcUrl                = settingKey[String]      ("URL of the Ethereum JSON-RPC service the build should work with")
    val ethcfgScalaStubsPackage             = settingKey[String]      ("Package into which Scala stubs of Solidity compilations should be generated")
    val ethcfgSender                        = settingKey[String]      ("The address from which transactions will be sent")
    val ethcfgSolidityCompilerOptimize      = settingKey[Boolean]     ("Sets whether the Solidity compiler should run its optimizer on generated code, if supported.")
    val ethcfgSolidityCompilerOptimizerRuns = settingKey[Int]         ("Sets the number of optimization runs the Solidity compiler will execute, if supported and 'ethcfgSolidityCompilerOptimize' is set to 'true'.")
    val ethcfgSoliditySource                = settingKey[File]        ("Solidity source code directory")
    val ethcfgSolidityDestination           = settingKey[File]        ("Location for compiled solidity code and metadata")
    val ethcfgTargetDir                     = settingKey[File]        ("Location in target directory where ethereum artifacts will be placed")
    val ethcfgTransactionReceiptPollPeriod  = settingKey[Duration]    ("Length of period after which sbt-ethereum will poll and repoll for a Client.TransactionReceipt after a transaction")
    val ethcfgTransactionReceiptTimeout     = settingKey[Duration]    ("Length of period after which sbt-ethereum will give up on polling for a Client.TransactionReceipt after a transaction")
    val ethcfgUseReplayAttackProtection     = settingKey[Boolean]     ("Defines whether transactions should be signed with EIP-155 \"simple replay attack protection\"")

    val xethcfgAsyncOperationTimeout      = settingKey[Duration]("Length of time to wait for asynchronous operations, like HTTP calls and external processes.")
    val xethcfgNamedAbiSource             = settingKey[File]    ("Location where files containing json files containing ABIs for which stubs should be generated. Each as '<stubname>.json'.")
    val xethcfgTestingResourcesObjectName = settingKey[String]  ("The name of the Scala object that will be automatically generated with resources for tests.")
    val xethcfgWalletV3ScryptDkLen        = settingKey[Int]     ("The derived key length parameter used when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptN            = settingKey[Int]     ("The value to use for parameter N when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptR            = settingKey[Int]     ("The value to use for parameter R when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptP            = settingKey[Int]     ("The value to use for parameter P when generating Scrypt V3 wallets")
    val xethcfgWalletV3Pbkdf2DkLen        = settingKey[Int]     ("The derived key length parameter used when generating pbkdf2 V3 wallets")
    val xethcfgWalletV3Pbkdf2C            = settingKey[Int]     ("The value to use for parameter C when generating pbkdf2 V3 wallets")

    // tasks

    val ensAddressLookup    = inputKey[Option[EthAddress]]("Prints the address given ens name should resolve to, if one has been set.")
    val ensAddressSet       = inputKey[Unit]              ("Sets the address a given ens name should resolve to.")
    val ensAuctionFinalize  = inputKey[Unit]              ("Finalizes an auction for the given name, in the (optionally-specified) top-level domain of the ENS service.")
    val ensAuctionStart     = inputKey[Unit]              ("Starts an auction for the given name, in the (optionally-specified) top-level domain of the ENS service.")
    val ensAuctionBidList   = taskKey[Unit]               ("Places a bid in an currently running auction.")
    val ensAuctionBidPlace  = inputKey[Unit]              ("Places a bid in an currently running auction.")
    val ensAuctionBidReveal = inputKey[Unit]              ("Reveals a bid in an currently running auction.")
    val ensNameStatus       = inputKey[ens.NameStatus]    ("Prints the current status of a given name.")
    val ensOwnerLookup      = inputKey[Option[EthAddress]]("Prints the address of the owner of a given name, if the name has an owner.")
    val ensOwnerSet         = inputKey[Unit]              ("Sets the owner of a given name to an address.")
    val ensResolverLookup   = inputKey[Option[EthAddress]]("Prints the address of the resolver associated with a given name.")
    val ensResolverSet      = inputKey[Unit]              ("Sets the resolver for a given name to an address.")
    val ensSubnodeCreate    = inputKey[Unit]              ("Creates a subnode (if it does not already exist) beneath an existing ENS name with the current sender as its owner.")
    val ensSubnodeOwnerSet  = inputKey[Unit]              ("Sets the owner of a name beneath an ENS name (creating the 'subnode' if it does not already exist).")

    val etherscanApiKeyDrop       = taskKey[Unit]  ("Removes the API key for etherscan services from the sbt-ethereum database.")
    val etherscanApiKeyImport     = taskKey[Unit]  ("Imports an API key for etherscan services.")
    val etherscanApiKeyReveal     = taskKey[Unit]  ("Reveals the currently set API key for etherscan services, if any.")

    val ethAddressAliasDrop           = inputKey[Unit]                             ("Drops an alias for an ethereum address from the sbt-ethereum shoebox database.")
    val ethAddressAliasList           = taskKey [Unit]                             ("Lists aliases for ethereum addresses that can be used in place of the hex address in many tasks.")
    val ethAddressAliasPrint          = inputKey[Unit]                             ("Prints the address associated with a given alias.")
    val ethAddressAliasSet            = inputKey[Unit]                             ("Defines (or redefines) an alias for an ethereum address that can be used in place of the hex address in many tasks.")
    val ethAddressBalance             = inputKey[BigDecimal]                       ("Computes the balance in ether of a given address, or of current sender if no address is supplied")
    val ethAddressSenderEffective     = taskKey[Failable[EthAddress]]              ("Prints the address that will be used to send ether or messages, and explains where and how it has ben set.")
    val ethAddressSenderOverrideDrop  = taskKey [Unit]                             ("Removes any sender override, reverting to any 'ethcfgSender' or defaultSender that may be set.")
    val ethAddressSenderOverrideSet   = inputKey[Unit]                             ("Sets an ethereum address to be used as sender in prefernce to any 'ethcfgSender' or defaultSender that may be set.")
    val ethAddressSenderOverridePrint = taskKey [Unit]                             ("Displays any sender override, if set.")

    val ethContractAbiAliasDrop       = inputKey[Unit] ("Drops for an ABI.")
    val ethContractAbiAliasList       = taskKey [Unit] ("Lists aliased ABIs and their hashes.")
    val ethContractAbiAliasSet        = inputKey[Unit] ("Defines a new alias for an ABI, taken from any ABI source.")
    val ethContractAbiDecode          = inputKey[Unit] ("Takes an ABI and arguments hex-encoded with that ABI, and decodes them.")
    val ethContractAbiEncode          = inputKey[Unit] ("Takes an ABI, a function name, and arguments and geneated the hex-encoded data that would invoke the function.")
    val ethContractAbiForget          = inputKey[Unit] ("Removes an ABI definition that was added to the sbt-ethereum database via ethContractAbiImport")
    val ethContractAbiList            = inputKey[Unit] ("Lists the addresses for which ABI definitions have been memorized. (Does not include our own deployed compilations, see 'ethContractCompilationList'")
    val ethContractAbiImport          = inputKey[Unit] ("Import an ABI definition for a contract, from an external source or entered directly into a prompt.")
    val ethContractAbiMatch           = inputKey[Unit] ("Uses as the ABI definition for a contract address the ABI of a different contract, specified by codehash or contract address")
    val ethContractAbiOverrideAdd     = inputKey[Unit] ("Sets a temporary (just this session) association between an ABI an address, that overrides any persistent association")
    val ethContractAbiOverrideClear   = taskKey[Unit]  ("Clears all temporary associations (on the current chain) between an ABI an address")
    val ethContractAbiOverrideList    = taskKey[Unit]  ("Show all addresses (on the current chain) for which a temporary association between an ABI an address has been set")
    val ethContractAbiOverridePrint   = inputKey[Unit] ("Pretty prints any ABI a temporarily associated with an address as an ABI override")
    val ethContractAbiOverrideRemove  = inputKey[Unit] ("Removes a temporary (just this session) association between an ABI an address that may have ben set with 'ethContractAbiOverrideAdd'")
    val ethContractAbiPrint           = inputKey[Unit] ("Prints the contract ABI associated with a provided address, if known.")
    val ethContractAbiPrintPretty     = inputKey[Unit] ("Pretty prints the contract ABI associated with a provided address, if known.")
    val ethContractAbiPrintCompact    = inputKey[Unit] ("Compactly prints the contract ABI associated with a provided address, if known.")

    val ethContractCompilationCull    = taskKey [Unit] ("Removes never-deployed compilations from the shoebox database.")
    val ethContractCompilationInspect = inputKey[Unit] ("Dumps to the console full information about a compilation, based on either a code hash or contract address")
    val ethContractCompilationList    = taskKey [Unit] ("Lists summary information about compilations known in the shoebox")

    val ethDebugGanacheStart = taskKey[Unit] (s"Starts a local ganache environment (if the command '${testing.Default.Ganache.Executable}' is in your PATH)")
    val ethDebugGanacheStop  = taskKey[Unit] ("Stops any local ganache environment that may have been started previously")

    val ethKeystoreList                         = taskKey[immutable.SortedMap[EthAddress,immutable.SortedSet[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined")
    val ethKeystorePrivateKeyReveal             = inputKey[Unit]      ("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")
    val ethKeystoreWalletV3Create               = taskKey [wallet.V3] ("Generates a new V3 wallet, using ethcfgEntropySource as a source of randomness")
    val ethKeystoreWalletV3FromJsonImport       = taskKey [Unit]      ("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")
    val ethKeystoreWalletV3FromPrivateKeyImport = taskKey [Unit]      ("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")
    val ethKeystoreWalletV3Print                = inputKey[Unit]      ("Prints V3 wallet as JSON to the console.")
    val ethKeystoreWalletV3Validate             = inputKey[Unit]      ("Verifies that a V3 wallet can be decoded for an address, and decodes to the expected address.")

    val ethLanguageSolidityCompilerInstall = inputKey[Unit] ("Installs a best-attempt platform-specific solidity compiler into the sbt-ethereum shoebox (or choose a supported version)")
    val ethLanguageSolidityCompilerPrint   = taskKey [Unit] ("Displays currently active Solidity compiler")
    val ethLanguageSolidityCompilerSelect  = inputKey[Unit] ("Manually select among solidity compilers available to this project")

    val ethNodeJsonRpcUrlDefaultSet   = inputKey[Unit]("Sets the default Ethereum json-rpc service URL, which will be used if not overridden by an 'ethcfgNodeJsonRpcUrl' hardcoded into the build.")
    val ethNodeJsonRpcUrlDefaultDrop  = taskKey[Unit] ("Drops the default Ethereum json-rpc service URL.")
    val ethNodeJsonRpcUrlDefaultPrint = taskKey[Unit] ("Displays the current default Ethereum json-rpc service URL.")

    val ethNodeJsonRpcUrlOverrideSet   = inputKey[Unit]("Overrides the default Ethereum json-rpc service URL.")
    val ethNodeJsonRpcUrlOverrideDrop  = taskKey[Unit] ("Drops any override, reverting to use of the default Ethereum json-rpc service URL.")
    val ethNodeJsonRpcUrlOverridePrint = taskKey[Unit] ("Displays any override of the default Ethereum json-rpc service URL.")

    val ethShoeboxBackup              = taskKey[Unit] ("Backs up the sbt-ethereum shoebox.")
    val ethShoeboxDatabaseDumpCreate  = taskKey[Unit] ("Dumps the sbt-ethereum shoebox database as an SQL text file, stored inside the sbt-ethereum shoebox directory.")
    val ethShoeboxDatabaseDumpRestore = taskKey[Unit] ("Restores the sbt-ethereum shoebox database from a previously generated dump.")
    val ethShoeboxRestore             = taskKey[Unit] ("Restores the sbt-ethereum shoebox from a backup generated by 'ethRespositoryBackup'.")

    val ethTransactionDeploy = inputKey[immutable.Seq[Tuple2[String,Either[EthHash,Client.TransactionReceipt]]]]("""Deploys the named contract, if specified, or else all contracts in 'ethcfgAutoDeployContracts'""")

    val ethTransactionGasLimitOverrideSet   = inputKey[Unit] ("Defines a value which overrides the usual automatic marked-up estimation of gas required for a transaction.")
    val ethTransactionGasLimitOverrideDrop  = taskKey [Unit] ("Removes any previously set gas override, reverting to the usual automatic marked-up estimation of gas required for a transaction.")
    val ethTransactionGasLimitOverridePrint = taskKey [Unit] ("Displays the current gas override, if set.")
    val ethTransactionGasPriceOverrideSet   = inputKey[Unit] ("Defines a value which overrides the usual automatic marked-up default gas price that will be paid for a transaction.")
    val ethTransactionGasPriceOverrideDrop  = taskKey [Unit] ("Removes any previously set gas price override, reverting to the usual automatic marked-up default.")
    val ethTransactionGasPriceOverridePrint = taskKey [Unit] ("Displays the current gas price override, if set.")

    val ethTransactionInvoke = inputKey[Client.TransactionReceipt]("Calls a function on a deployed smart contract")
    val ethTransactionLookup = inputKey[Client.TransactionReceipt]("Looks up (and potentially waits for) the transaction associated with a given transaction hash.")

    val ethTransactionNonceOverrideDrop  = taskKey[Unit]("Removes any nonce override that may have been set.")
    val ethTransactionNonceOverridePrint = taskKey[Unit]("Prints any nonce override that may have been set.")
    val ethTransactionNonceOverrideSet   = inputKey[Unit]("Sets a fixed nonce to be used in the transactions, rather than automatically choosing the next nonce. (Remains fixed and set until explicitly dropped.)")

    val ethTransactionPing   = inputKey[Option[Client.TransactionReceipt]]           ("Sends 0 ether from current sender to an address, by default the sender address itself")
    val ethTransactionRaw    = inputKey[Client.TransactionReceipt]                   ("Sends a transaction with user-specified bytes, amount, and optional nonce")
    val ethTransactionSend   = inputKey[Client.TransactionReceipt]                   ("Sends ether from current sender to a specified account, format 'ethTransactionSend <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")
    val ethTransactionView   = inputKey[(Abi.Function,immutable.Seq[Decoded.Value])] ("Makes a call to a constant function, consulting only the local copy of the blockchain. Burns no Ether. Returns the latest available result.")

    // xens tasks

    val xensClient = taskKey[ens.Client]("Loads an ENS client instance.")

    // xeth tasks

    val xethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")
    val xethFindCacheRichParserInfo = taskKey[RichParserInfo]("Finds and caches information (aliases, ens info) needed by some parsers")
    val xethFindCacheSessionSolidityCompilerKeys = taskKey[immutable.Set[String]]("Finds and caches keys for available compilers for use by the parser for ethLanguageSolidityCompilerSelect")
    val xethFindCacheSeeds = taskKey[immutable.Map[String,MaybeSpawnable.Seed]]("Finds and caches compiled, deployable contracts, omitting ambiguous duplicates. Triggered by compileSolidity")
    val xethFindCurrentSender = taskKey[Failable[EthAddress]]("Finds the address that should be used to send ether or messages")
    val xethFindCurrentSolidityCompiler = taskKey[Compiler.Solidity]("Finds and caches keys for available compilers for use parser for ethLanguageSolidityCompilerSelect")
    val xethGasPrice = taskKey[BigInt]("Finds the current gas price, including any overrides or gas price markups")
    val xethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethcfgEntropySource as a source of randomness")
    val xethGenScalaStubsAndTestingResources = taskKey[immutable.Seq[File]]("Generates stubs for compiled Solidity contracts, and resources helpful in testing them.")
    val xethKeystoreWalletV3CreatePbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethcfgEntropySource as a source of randomness")
    val xethKeystoreWalletV3CreateScrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethcfgEntropySource as a source of randomness")
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
    val xethOnLoadSolicitCompilerInstall = taskKey[Unit]("Intended to be executd in 'onLoad', checks whether the default Solidity compiler is installed and if not, offers to install it.")
    val xethOnLoadSolicitWalletV3Generation = taskKey[Unit]("Intended to be executd in 'onLoad', checks whether sbt-ethereum has any wallets available, if not offers to install one.")
    val xethTransactionCount = taskKey[BigInt]("Finds the next nonce for the current sender")
    val xethShoeboxRepairPermissions = taskKey[Unit]("Repairs filesystem permissions in sbt's shoebox to its required user-only values.")
    val xethSqlQueryShoeboxDatabase = inputKey[Unit]("Primarily for debugging. Query the internal shoebox database.")
    val xethSqlUpdateShoeboxDatabase = inputKey[Unit]("Primarily for development and debugging. Update the internal shoebox database with arbitrary SQL.")
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
    "ethDebugGanacheStop" :: "ethDebugGanacheStart" :: state
  }

  private val ethDebugGanacheTestCommand = Command.command( "ethDebugGanacheTest" ) { state =>
    "Test/compile" :: "ethDebugGanacheStop" :: "ethDebugGanacheStart" :: "Test/ethTransactionDeploy" :: "test" :: "ethDebugGanacheStop" :: state
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

    enscfgNameServiceTld in Compile := ens.StandardNameServiceTld,

    enscfgNameServiceReverseTld in Compile := ens.StandardNameServiceReverseTld,

    enscfgNameServicePublicResolver in Compile := ens.StandardNameServicePublicResolver,

    // ethcfg settings

    ethcfgBaseCurrencyCode := "USD",

    ethcfgChainId := MainnetChainId,

    ethcfgChainId in Test := DefaultEphemeralChainId,

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

    ensAuctionBidPlace in Compile := { ensAuctionBidPlaceTask( Compile ).evaluated },

    ensAuctionBidPlace in Test := { ensAuctionBidPlaceTask( Test ).evaluated },

    ensAuctionBidReveal in Compile := { ensAuctionBidRevealTask( Compile ).evaluated },

    ensAuctionBidReveal in Test := { ensAuctionBidRevealTask( Test ).evaluated },

    ensAuctionBidList in Compile := { ensAuctionBidListTask( Compile ).value },

    ensAuctionBidList in Test := { ensAuctionBidListTask( Test ).value },

    ensAuctionFinalize in Compile := { ensAuctionFinalizeTask( Compile ).evaluated },

    ensAuctionFinalize in Test := { ensAuctionFinalizeTask( Test ).evaluated },

    ensAuctionStart in Compile := { ensAuctionStartTask( Compile ).evaluated },

    ensAuctionStart in Test := { ensAuctionStartTask( Test ).evaluated },

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

    etherscanApiKeyImport := { etherscanApiKeyImportTask.value },

    etherscanApiKeyReveal := { etherscanApiKeyRevealTask.value },

    ethAddressAliasDrop in Compile := { ethAddressAliasDropTask( Compile ).evaluated },

    ethAddressAliasDrop in Test := { ethAddressAliasDropTask( Test ).evaluated },

    ethAddressAliasList in Compile := { ethAddressAliasListTask( Compile ).value },

    ethAddressAliasList in Test := { ethAddressAliasListTask( Test ).value },

    ethAddressAliasPrint in Compile := { ethAddressAliasPrintTask( Compile ).evaluated },

    ethAddressAliasPrint in Test := { ethAddressAliasPrintTask( Test ).evaluated },

    ethAddressAliasSet in Compile := { ethAddressAliasSetTask( Compile ).evaluated },

    ethAddressAliasSet in Test := { ethAddressAliasSetTask( Test ).evaluated },

    ethAddressBalance in Compile := { ethAddressBalanceTask( Compile ).evaluated },

    ethAddressBalance in Test := { ethAddressBalanceTask( Test ).evaluated },

    ethTransactionPing in Compile := { ethTransactionPingTask( Compile ).evaluated },

    ethTransactionPing in Test := { ethTransactionPingTask( Test ).evaluated },

    ethAddressSenderEffective in Compile := { ethAddressSenderEffectiveTask( Compile ).value },

    ethAddressSenderEffective in Test := { ethAddressSenderEffectiveTask( Test ).value },

    ethAddressSenderOverrideDrop in Compile := { ethAddressSenderOverrideDropTask( Compile ).value },

    ethAddressSenderOverrideDrop in Test := { ethAddressSenderOverrideDropTask( Test ).value },

    ethAddressSenderOverridePrint in Compile := { ethAddressSenderOverridePrintTask( Compile ).value },

    ethAddressSenderOverridePrint in Test := { ethAddressSenderOverridePrintTask( Test ).value },

    ethAddressSenderOverrideSet in Compile := { ethAddressSenderOverrideSetTask( Compile ).evaluated },

    ethAddressSenderOverrideSet in Test := { ethAddressSenderOverrideSetTask( Test ).evaluated },

    ethContractAbiAliasDrop in Compile := { ethContractAbiAliasDropTask( Compile ).evaluated },

    ethContractAbiAliasDrop in Test := { ethContractAbiAliasDropTask( Test ).evaluated },

    ethContractAbiAliasList in Compile := { ethContractAbiAliasListTask( Compile ).value },

    ethContractAbiAliasList in Test := { ethContractAbiAliasListTask( Test ).value },

    ethContractAbiAliasSet in Compile := { ethContractAbiAliasSetTask( Compile ).evaluated },

    ethContractAbiAliasSet in Test := { ethContractAbiAliasSetTask( Test ).evaluated },

    ethContractAbiDecode in Compile := { ethContractAbiDecodeTask( Compile ).evaluated },

    ethContractAbiDecode in Test := { ethContractAbiDecodeTask( Test ).evaluated },

    ethContractAbiEncode in Compile := { ethContractAbiEncodeTask( Compile ).evaluated },

    ethContractAbiEncode in Test := { ethContractAbiEncodeTask( Test ).evaluated },

    ethContractAbiForget in Compile := { ethContractAbiForgetTask( Compile ).evaluated },

    ethContractAbiForget in Test := { ethContractAbiForgetTask( Test ).evaluated },

    ethContractAbiList in Compile := { ethContractAbiListTask( Compile ).evaluated },

    ethContractAbiList in Test := { ethContractAbiListTask( Test ).evaluated },

    ethContractAbiMatch in Compile := { ethContractAbiMatchTask( Compile ).evaluated },

    ethContractAbiMatch in Test := { ethContractAbiMatchTask( Test ).evaluated },

    ethContractAbiImport in Compile := { ethContractAbiImportTask( Compile ).evaluated },

    ethContractAbiImport in Test := { ethContractAbiImportTask( Test ).evaluated },

    ethContractAbiOverrideAdd in Compile := { ethContractAbiOverrideAddTask( Compile ).evaluated },

    ethContractAbiOverrideAdd in Test := { ethContractAbiOverrideAddTask( Test ).evaluated },

    ethContractAbiOverrideClear in Compile := { ethContractAbiOverrideClearTask( Compile ).value },

    ethContractAbiOverrideClear in Test := { ethContractAbiOverrideClearTask( Test ).value },

    ethContractAbiOverrideList in Compile := { ethContractAbiOverrideListTask( Compile ).value },

    ethContractAbiOverrideList in Test := { ethContractAbiOverrideListTask( Test ).value },

    ethContractAbiOverridePrint in Compile := { ethContractAbiOverridePrintTask( Compile ).evaluated },

    ethContractAbiOverridePrint in Test := { ethContractAbiOverridePrintTask( Test ).evaluated },

    ethContractAbiOverrideRemove in Compile := { ethContractAbiOverrideRemoveTask( Compile ).evaluated },

    ethContractAbiOverrideRemove in Test := { ethContractAbiOverrideRemoveTask( Test ).evaluated },

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

    ethDebugGanacheStop in Test := { ethDebugGanacheStopTask.value },

    ethKeystoreList in Compile := { ethKeystoreListTask( Compile ).value },

    ethKeystoreList in Test := { ethKeystoreListTask( Test ).value },

    ethKeystorePrivateKeyReveal in Compile := { ethKeystorePrivateKeyRevealTask( Compile ).evaluated },

    ethKeystorePrivateKeyReveal in Test := { ethKeystorePrivateKeyRevealTask( Test ).evaluated },

    ethKeystoreWalletV3Create := { xethKeystoreWalletV3CreateScrypt.value },

    ethKeystoreWalletV3FromJsonImport := { ethKeystoreWalletV3FromJsonImportTask.value },

    ethKeystoreWalletV3FromPrivateKeyImport := { ethKeystoreWalletV3FromPrivateKeyImportTask.value },

    ethKeystoreWalletV3Print in Compile := { ethKeystoreWalletV3PrintTask( Compile ).evaluated },

    ethKeystoreWalletV3Print in Test := { ethKeystoreWalletV3PrintTask( Test ).evaluated },

    ethKeystoreWalletV3Validate in Compile := { ethKeystoreWalletV3ValidateTask( Compile ).evaluated },

    ethKeystoreWalletV3Validate in Test := { ethKeystoreWalletV3ValidateTask( Test ).evaluated },

    ethLanguageSolidityCompilerSelect in Compile := { ethLanguageSolidityCompilerSelectTask.evaluated },

    ethLanguageSolidityCompilerInstall in Compile := { ethLanguageSolidityCompilerInstallTask.evaluated },

    ethLanguageSolidityCompilerPrint in Compile := { ethLanguageSolidityCompilerPrintTask.value },

    ethNodeJsonRpcUrlDefaultDrop in Compile := { ethNodeJsonRpcUrlDefaultDropTask( Compile ).value },

    ethNodeJsonRpcUrlDefaultDrop in Test := { ethNodeJsonRpcUrlDefaultDropTask( Test ).value },

    ethNodeJsonRpcUrlDefaultPrint in Compile := { ethNodeJsonRpcUrlDefaultPrintTask( Compile ).value },

    ethNodeJsonRpcUrlDefaultPrint in Test := { ethNodeJsonRpcUrlDefaultPrintTask( Test ).value },

    ethNodeJsonRpcUrlDefaultSet in Compile := { ethNodeJsonRpcUrlDefaultSetTask( Compile ).evaluated },

    ethNodeJsonRpcUrlDefaultSet in Test := { ethNodeJsonRpcUrlDefaultSetTask( Test ).evaluated },

    ethNodeJsonRpcUrlOverrideDrop in Compile := { ethNodeJsonRpcUrlOverrideDropTask( Compile ).value },

    ethNodeJsonRpcUrlOverrideDrop in Test := { ethNodeJsonRpcUrlOverrideDropTask( Test ).value },

    ethNodeJsonRpcUrlOverridePrint in Compile := { ethNodeJsonRpcUrlOverridePrintTask( Compile ).value },

    ethNodeJsonRpcUrlOverridePrint in Test := { ethNodeJsonRpcUrlOverridePrintTask( Test ).value },

    ethNodeJsonRpcUrlOverrideSet in Compile := { ethNodeJsonRpcUrlOverrideSetTask( Compile ).evaluated },

    ethNodeJsonRpcUrlOverrideSet in Test := { ethNodeJsonRpcUrlOverrideSetTask( Test ).evaluated },

    ethShoeboxBackup := { ethShoeboxBackupTask.value },

    ethShoeboxRestore := { ethShoeboxRestoreTask.value },

    ethShoeboxDatabaseDumpCreate := { ethShoeboxDatabaseDumpCreateTask.value },

    ethShoeboxDatabaseDumpRestore := { ethShoeboxDatabaseDumpRestoreTask.value },

    ethTransactionDeploy in Compile := { ethTransactionDeployTask( Compile ).evaluated },

    ethTransactionDeploy in Test := { ethTransactionDeployTask( Test ).evaluated },

    // we don't scope the gas override tasks for now
    // since any gas override gets used in tests as well as other contexts
    // we may bifurcate and scope this in the future
    ethTransactionGasLimitOverrideSet := { ethTransactionGasLimitOverrideSetTask.evaluated },

    ethTransactionGasLimitOverrideDrop := { ethTransactionGasLimitOverrideDropTask.value },

    ethTransactionGasLimitOverridePrint := { ethTransactionGasLimitOverridePrintTask.value },

    ethTransactionGasPriceOverrideSet := { ethTransactionGasPriceOverrideSetTask.evaluated },

    ethTransactionGasPriceOverrideDrop := { ethTransactionGasPriceOverrideDropTask.value },

    ethTransactionGasPriceOverridePrint := { ethTransactionGasPriceOverridePrintTask.value },

    ethTransactionInvoke in Compile := { ethTransactionInvokeTask( Compile ).evaluated },

    ethTransactionInvoke in Test := { ethTransactionInvokeTask( Test ).evaluated },

    ethTransactionLookup in Compile := { ethTransactionLookupTask( Compile ).evaluated },

    ethTransactionLookup in Test := { ethTransactionLookupTask( Test ).evaluated },

    // we don't scope the nonce override tasks for now
    // since any nonce override gets used in tests as well as other contexts
    // we may bifurcate and scope this in the future
    ethTransactionNonceOverrideSet := { ethTransactionNonceOverrideSetTask.evaluated },

    ethTransactionNonceOverrideDrop := { ethTransactionNonceOverrideDropTask.value },

    ethTransactionNonceOverridePrint := { ethTransactionNonceOverridePrintTask.value },

    ethTransactionRaw in Compile := { ethTransactionRawTask( Compile ).evaluated },

    ethTransactionRaw in Test := { ethTransactionRawTask( Test ).evaluated },

    ethTransactionSend in Compile := { ethTransactionSendTask( Compile ).evaluated },

    ethTransactionSend in Test := { ethTransactionSendTask( Test ).evaluated },

    ethTransactionView in Compile := { ethTransactionViewTask( Compile ).evaluated },

    ethTransactionView in Test := { ethTransactionViewTask( Test ).evaluated },

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

    xethFindCacheSessionSolidityCompilerKeys in Compile := {
      (xethFindCacheSessionSolidityCompilerKeysTask.storeAs( xethFindCacheSessionSolidityCompilerKeys in Compile ).triggeredBy( xethTriggerDirtySolidityCompilerList ).triggeredBy(ethShoeboxRestore)).value
    },

    xethFindCurrentSender in Compile := { xethFindCurrentSenderTask( Compile ).value },

    xethFindCurrentSender in Test := { xethFindCurrentSenderTask( Test ).value },

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

    xethSqlQueryShoeboxDatabase := { xethSqlQueryShoeboxDatabaseTask.evaluated }, // we leave this unscoped, just because scoping it to Compile seems weird

    xethSqlUpdateShoeboxDatabase := { xethSqlUpdateShoeboxDatabaseTask.evaluated }, // we leave this unscoped, just because scoping it to Compile seems weird

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

  private def findNodeJsonRpcUrlTask( warn : Boolean )( config : Configuration ) : Initialize[Task[String]] = Def.task {
    val log = streams.value.log
    val chainId = (config/ethcfgChainId).value
    val mbOverrideNodeJsonRpcUrl = {
      Mutables.NodeJsonRpcUrlOverride.synchronized {
        val map = Mutables.NodeJsonRpcUrlOverride.get
        map.get( chainId )
      }
    }
    mbOverrideNodeJsonRpcUrl match {
      case Some( url ) => {
        url
      }
      case None => {
        val mbDbNodeJsonRpcUrl = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
        (config/ethcfgNodeJsonRpcUrl).?.value match {
          case Some( url ) => {
            if ( warn ) {
              val warning = {
                () => {
                  val pfx = {
                    config match {
                      case Compile => ""
                      case Test    => "Test / "
                      case other   => throw new SbtEthereumException( s"Unexpected task configuration: ${other}" )
                    }
                  }
                  mbDbNodeJsonRpcUrl.map ( dbNodeJsonRpcUrl =>
                    s"'${pfx}ethcfgNodeJsonRpcUrl' has been explicitly set to '${url}' in the build or as a global setting in the .sbt directory. " +
                      s"This value will be used in preference to the value set in the sbt-ethereum shoebox via '${pfx}ethNodeJsonRpcUrlDefaultSet' (currently '${dbNodeJsonRpcUrl}'). " +
                      s"However, you can temporarily override the hard-coded value for a single session using '${pfx}ethNodeJsonRpcUrlOverrideSet'."
                  )
                }
              }
              oneTimeWarn( OneTimeWarnedKey.NodeJsonRpcInBuild, config, log, warning )
            }
            url
          }
          case None if config == Compile => {
            // thanks to Mike Slinn for suggesting these external defaults
            def mbInfura               = ExternalValue.EthInfuraToken.map(token => s"https://mainnet.infura.io/$token")
            def mbSpecifiedDefaultNode = ExternalValue.EthDefaultNode

            (mbInfura orElse mbSpecifiedDefaultNode).getOrElse( DefaultEthJsonRpcUrl )
          }
          case None if config == Test => {
            DefaultTestEthJsonRpcUrl
          }
          case None => {
            throw new SbtEthereumException( s"Unexpected config: ${config}" )
          }
        }
      }
    }
  }

  private def findPrivateKeyTask( config : Configuration ) : Initialize[Task[EthPrivateKey]] = Def.task {
    val s = state.value
    val log = streams.value.log
    val is = interactionService.value
    val chainId = (ethcfgChainId in config).value
    val caller = (xethFindCurrentSender in config).value.get
    val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
    findCachePrivateKey(s, log, is, chainId, caller, autoRelockSeconds, true )
  }

  private def findTransactionLoggerTask( config : Configuration ) : Initialize[Task[Invoker.TransactionLogger]] = Def.task {
    val chainId = (ethcfgChainId in config).value

    ( tle : Invoker.TransactionLogEntry, ec : ExecutionContext ) => Future (
      shoebox.TransactionLog.logTransaction( chainId, tle.jsonRpcUrl, tle.transaction, tle.transactionHash ).get
    )( ec )
  }

  private def findExchangerConfigTask( config : Configuration ) : Initialize[Task[Exchanger.Config]] = Def.task {
    val httpUrl = findNodeJsonRpcUrlTask(warn=true)(config).value
    val timeout = xethcfgAsyncOperationTimeout.value
    Exchanger.Config( new URL(httpUrl), timeout ) 
  }

  // task definitions

  // ens tasks

  private def ensAddressLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = Def.inputTask {
    val chainId   = (ethcfgChainId in config).value
    val ensClient = ( config / xensClient).value
    val name      = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val mbAddress = ensClient.address( name )

    mbAddress match {
      case Some( address ) => println( s"The name '${name}' resolves to address ${verboseAddress(chainId, address)}." )
      case None            => println( s"The name '${name}' does not currently resolve to any address." )
    }

    mbAddress
  }

  private def ensAddressSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsNameAddressParser )

    Def.inputTask {
      val log               = streams.value.log
      val privateKey        = findPrivateKeyTask( config ).value
      val chainId           = ( config / ethcfgChainId ).value
      val ensClient         = ( config / xensClient).value
      val is                = interactionService.value
      val mbDefaultResolver = ( config / enscfgNameServicePublicResolver).?.value
      val ( ensName, address ) = parser.parsed
      try {
        ensClient.setAddress( privateKey, ensName, address )
      }
      catch {
        case e : ens.NoResolverSetException => {
          val defaultResolver = mbDefaultResolver.getOrElse( throw e )
          val setAndRetry = {
            is.readLine( s"No resolver has been set for '${ensName}'. Do you wish to use the default public resolver '${hexString(defaultResolver)}'? [y/n] ", false )
              .getOrElse( throw new Exception( CantReadInteraction ) )
              .trim()
              .equalsIgnoreCase("y")
          }
          if ( setAndRetry ) {
            log.info( s"Preparing transaction to set the resolver." ) 
            ensClient.setResolver( privateKey, ensName, defaultResolver )
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
            ensClient.setAddress( privateKey, ensName, address )
          } else {
            throw e
          }
        }
      }
      log.info( s"The name '${ensName}' now resolves to ${verboseAddress(chainId, address)}." )
    }
  }

  private def ensAuctionBidListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    import shoebox.Schema_h2.Table.EnsBidStore.RawBid

    val chainId = (ethcfgChainId in config).value
    val rawBids = shoebox.Database.ensAllRawBidsForChainId( chainId ).get
    val columns = immutable.Vector( "Bid Hash", "Simple Name", "Bidder Address", "ETH", "Salt", "Timestamp", "Accepted", "Revealed", "Removed" ).map( texttable.Column.apply( _ ) )

    def extract( rawBid : RawBid ) : Seq[String] = immutable.Vector(
      hexString( rawBid.bidHash ),
      rawBid.simpleName,
      hexString( rawBid.bidderAddress ),
      Denominations.Ether.fromWei( rawBid.valueInWei ).toString,
      hexString( rawBid.salt ),
      formatInstant( rawBid.whenBid ),
      rawBid.accepted.toString,
      rawBid.revealed.toString,
      rawBid.removed.toString
    )
      
    texttable.printTable( columns, extract )( rawBids.map( rb => texttable.Row(rb) ) )
  }

  private def ensAuctionBidPlaceTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val chainId = (config / ethcfgChainId).value
    val ensClient = ( config / xensClient ).value
    val privateKey = findPrivateKeyTask( config ).value

    implicit val bidStore = shoebox.Database.ensBidStore( chainId, ensClient.tld, ensClient.nameServiceAddress )

    val ( name, valueInWei, mbOverpaymentInWei ) = ensPlaceNewBidParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993

    val status = ensClient.nameStatus( name )
    if ( status != ens.NameStatus.Auction ) {
      throw new NotCurrentlyUnderAuctionException( name, status )
    }
    else {
      // during auction, these should be available
      val revealStart = ensClient.revealStart( name ).get
      val auctionFinalized = ensClient.auctionEnd( name ).get

      val ( bid, transactionInfo ) = {
        mbOverpaymentInWei match {
          case Some( overpaymentInWei ) => ensClient.placeNewBid( privateKey, name, valueInWei, overpaymentInWei )
          case None                     => ensClient.placeNewBid( privateKey, name, valueInWei )
        }
      }

      shoebox.BidLog.logBid( bid, chainId, ensClient.tld, ensClient.nameServiceAddress )

      log.warn( s"A bid has been placed on name '${name}' for ${valueInWei} wei." )
      mbOverpaymentInWei.foreach( opw => log.warn( s"An additional ${opw} wei was transmitted to obscure the value of your bid." ) )
      log.warn( s"YOU MUST REVEAL THIS BID BETWEEN ${ formatInstant(revealStart) } AND ${ formatInstant(auctionFinalized) }. IF YOU DO NOT, YOUR FUNDS WILL BE LOST!" )
      log.warn(  "Bid details, which are required to reveal, have been automatically stored in the sbt-ethereum shoebox," )
      log.warn( s"and will be provided automatically if revealed by this client, configured with chain ID '${chainId}'." )
      log.warn(  "However, it never hurts to be neurotic. You may wish to note:" )
      log.warn( s"    Simple Name:      ${bid.simpleName}" )
      log.warn( s"    Simple Name Hash: 0x${ ens.componentHash( bid.simpleName ).hex }" )
      log.warn( s"    Bidder Address:   0x${bid.bidderAddress.hex}" )
      log.warn( s"    Value In Wei:     ${ bid.valueInWei }" )
      log.warn( s"    Salt:             0x${bid.salt.hex}" )
      log.warn( s"    Full Bid Hash:    0x${bid.bidHash.hex}" )
    }
  }

  private def ensAuctionBidRevealTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val chainId = (config / ethcfgChainId).value
    val ensClient = ( config / xensClient).value
    val privateKey = findPrivateKeyTask( config ).value
    val tld = ensClient.tld
    val ensAddress = ensClient.nameServiceAddress
    val is = interactionService.value

    implicit val bidStore = shoebox.Database.ensBidStore( chainId, tld, ensAddress )

    def revealBidForHash( hash : EthHash ) : Unit = {
      try { ensClient.revealBid( privateKey, hash, force=false ) }
      catch {
        case e : ens.EnsException => {
          log.info( s"Initial attempt to reveal bid failed: ${e}. Retrying with 'force' flag set." )
          ensClient.revealBid( privateKey, hash, force=true )
        }
      }
    }

    val hashOrName = bidHashOrNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993

    hashOrName match {
      case Left( hash ) => {
        revealBidForHash( hash )
        log.info( s"Bid with hash 0x${hash.hex} was successfully revealed." )
      }
      case Right( name ) => {
        val simpleName = ens.normalizeNameForTld( name, tld )
        val bidderAddress = privateKey.address
        val bidBidStates = bidStore.findByNameBidderAddress( simpleName, bidderAddress )
        bidBidStates.length match {
          case 0 => println( s"No bids were found to reveal with name '${name}' from address 0x${bidderAddress.hex}." )
          case 1 => {
            val ( bid, _ ) = bidBidStates.head
            revealBidForHash( bid.bidHash )
            log.info( s"Bid with name '${name}' was successfully revealed." )
          }
          case _ => {
            log.warn( s"Found multiple bids with name '${name}':" )
            bidBidStates.foreach { case ( bid, bidState ) =>
              log.warn( s"  Bid Hash: 0x${bid.bidHash.hex}, Bid State: ${bidState}" )
            }
            val revealAll = is.readLine(s"Reveal all bids? ", mask = false).getOrElse(throw new Exception("Failed to read a confirmation")) // fail if we can't get an answer
            revealAll.toUpperCase match {
              case "Y"| "YES" => {
                bidBidStates.foreach { case ( bid, _ ) =>
                  try { revealBidForHash( bid.bidHash ) }
                  catch {
                    case e : Exception => log.warn( s"Failed to reveal bid with hash 0x${bid.bidHash.hex}: ${e}" )
                  }
                }
                log.info( s"Bids with name '${name}' were successfully revealed." )
              }
              case _ => log.warn("Aborted. NO BIDS WERE REVEALED.")
            }
          }
        }
      }
    }
  }

  private def ensAuctionFinalizeTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val privateKey = findPrivateKeyTask( config ).value
    val name       = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val ensClient  = ( config / xensClient).value
    ensClient.finalizeAuction( privateKey, name )
  }

  private def ensAuctionStartTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val privateKey                = findPrivateKeyTask( config ).value
    val ( name, mbNumDiversions ) = ensNameNumDiversionParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val ensClient                 = ( config / xensClient).value

    mbNumDiversions match {
      case Some( numDiversions ) => {
        ensClient.startAuction( privateKey, name, numDiversions )
        println( s"Auction started for name '${name}', along with ${numDiversions} diversion auctions." )
      }
      case None => {
        ensClient.startAuction( privateKey, name )
        println( s"Auction started for name '${name}'." )
      }
    }
  }

  private def ensNameStatusTask( config : Configuration ) : Initialize[InputTask[ens.NameStatus]] = Def.inputTask {
    import ens.NameStatus._

    val ensClient = ( config / xensClient).value
    val name      = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val status    = ensClient.nameStatus( name )

    println( s"The current status of ENS name '${name}' is '${status}'." )

    status match {
      case Auction | Reveal => {
        for {
          auctionEnd <- ensClient.auctionEnd( name )
          revealStart <- ensClient.revealStart( name )
        } {
          if ( status == Auction ) {
            println( s"Bidding ends, and the reveal phase will begin on ${formatInstant(revealStart)}." )
          }
          println( s"The reveal phase will end, and the auction can be finalized on ${formatInstant(auctionEnd)}." )
        }
      }
      case Owned => {
        ensClient.owner( name ) match {
          case Some( address ) => println( s"The owner is '0x${address.hex}'." )
          case None            => println(  "However, the completed auction has not yet been finalized, so no owner is set." )
        }
      }
      case _ => /* ignore, no special messages */
    }

    status
  }

  private def ensOwnerLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = Def.inputTask {
    val chainId   = (ethcfgChainId in config).value
    val ensClient = ( config / xensClient).value
    val name      = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val mbOwner   = ensClient.owner( name )

    mbOwner match {
      case Some( address ) => println( s"The name '${name}' is owned by address ${verboseAddress(chainId, address)}." )
      case None            => println( s"No owner has been assigned to the name '${name}'." )
    }

    mbOwner
  }

  private def ensOwnerSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsNameOwnerAddressParser )

    Def.inputTask {
      val log        = streams.value.log
      val privateKey = findPrivateKeyTask( config ).value
      val chainId    = (config / ethcfgChainId).value
      val ensClient  = ( config / xensClient).value
      val ( ensName, ownerAddress ) = parser.parsed
      ensClient.setOwner( privateKey, ensName, ownerAddress )
      log.info( s"The name '${ensName}' is now owned by ${verboseAddress(chainId, ownerAddress)}. (However, this has not affected the Deed owner associated with the name!)" )
    }
  }

  private def ensResolverLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = Def.inputTask {
    val chainId    = (ethcfgChainId in config).value
    val ensClient  = ( config / xensClient).value
    val name       = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val mbResolver = ensClient.resolver( name )

    mbResolver match {
      case Some( address ) => println( s"The name '${name}' is associated with a resolver at address ${verboseAddress(chainId, address)}'." )
      case None            => println( s"No resolver has been associated with the name '${name}'." )
    }

    mbResolver
  }

  private def ensResolverSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsNameResolverAddressParser )

    Def.inputTask {
      val log        = streams.value.log
      val privateKey = findPrivateKeyTask( config ).value
      val chainId    = (config / ethcfgChainId).value
      val ensClient  = ( config / xensClient).value
      val ( ensName, resolverAddress ) = parser.parsed
      ensClient.setResolver( privateKey, ensName, resolverAddress )
      log.info( s"The name '${ensName}' is now set to be resolved by a contract at ${verboseAddress(chainId, resolverAddress)}." )
    }
  }

  private def ensSubnodeCreateTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsSubnodeParser )

    Def.inputTaskDyn {
      val sender = (xethFindCurrentSender in config).value.get
      val ( subname, parentName ) = parser.parsed
      ( config / ensSubnodeOwnerSet ).toTask( s" ${subname}.${parentName} 0x${sender.hex}" )
    }
  }

  private def ensSubnodeOwnerSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheRichParserInfo)( genEnsSubnodeOwnerSetParser )

    Def.inputTask {
      val log        = streams.value.log
      val privateKey = findPrivateKeyTask( config ).value
      val chainId    = (config / ethcfgChainId).value
      val ensClient  = ( config / xensClient).value
      val ( subname, parentName, newOwnerAddress) = parser.parsed
      ensClient.setSubnodeOwner( privateKey, parentName, subname, newOwnerAddress )
      log.info( s"The name '${subname}.${parentName}' now exists, with owner ${verboseAddress(chainId, newOwnerAddress)}." )
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

  private def etherscanApiKeyImportTask : Initialize[Task[Unit]] = Def.task {
    val is = interactionService.value
    val apiKey = is.readLine( "Please enter your Etherscan API key: ", mask = true ).getOrElse( throw new Exception( CantReadInteraction ) ).trim()
    if ( apiKey.isEmpty ) throw nst( new SbtEthereumException( "Invalid empty key provided. Etherscan API key import aborted." ) )
    shoebox.Database.setEtherscanApiKey( apiKey ).assert
    println("Etherscan API key successfully set.")
  }

  private def etherscanApiKeyRevealTask : Initialize[Task[Unit]] = Def.task {
    val is = interactionService.value
    val confirmation = {
      is.readLine(s"Are you sure you want to reveal your Etherscan API key on this very insecure console? [Type YES exactly to continue, anything else aborts]: ", mask = false)
        .getOrElse(throw new Exception("Failed to read a confirmation")) // fail if we can't get a credential
    }
    if ( confirmation == "YES" ) {
      val mbApiKey = shoebox.Database.getEtherscanApiKey().assert
      mbApiKey match {
        case Some( apiKey ) => println( s"The currently set Etherscan API key is ${apiKey}" )
        case None           => println(  "No Etherscan API key has been set." )
      }
    } else {
      throw notConfirmedByUser
    }
  }

  // eth tasks

  private def ethAddressAliasDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressAliasParser )

    Def.inputTaskDyn {
      val log = streams.value.log
      val chainId = (ethcfgChainId in config).value

      // not sure why, but without this xethFindCacheRichParserInfo, which should be triggered by the parser,
      // sometimes fails initialize the parser
      val ensureAliases = (xethFindCacheRichParserInfo in config)

      val alias = parser.parsed
      val check = shoebox.Database.dropAddressAlias( chainId, alias ).get // assert no database problem
      if (check) log.info( s"Alias '${alias}' successfully dropped (for chain with ID ${chainId}).")
      else log.warn( s"Alias '${alias}' is not defined (on blockchain '${chainId}'), and so could not be dropped." )

      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethAddressAliasListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log      = streams.value.log
    val chainId  = (ethcfgChainId in config).value
    val faliases = shoebox.Database.findAllAddressAliases( chainId )
    faliases.fold( _ => log.warn("Could not read aliases from shoebox database.") ) { aliases =>
      aliases.foreach { case (alias, address) => println( s"${alias} -> 0x${address.hex}" ) }
    }
  }

  private def ethAddressAliasPrintTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genPermissiveAddressAliasOrAddressAsStringParser )

    Def.inputTask {
      val log = streams.value.log
      val chainId = (ethcfgChainId in config).value
      val aliasOrAddress = parser.parsed
      val mbAddressForAlias = shoebox.Database.findAddressByAddressAlias( chainId, aliasOrAddress ).assert
      val mbEntryAsAddress = Failable( EthAddress( aliasOrAddress ) ).toOption
      val aliasesForAddress = mbEntryAsAddress.toSeq.flatMap( addr => shoebox.Database.findAddressAliasesByAddress( chainId, addr ).get )

      mbAddressForAlias.foreach( addressForAlias => println( s"The alias '${aliasOrAddress}' points to address '${hexString(addressForAlias)}'." ) )

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

  private def ethAddressAliasSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genNewAddressAliasParser )

    Def.inputTaskDyn {
      val log = streams.value.log
      val chainId = (ethcfgChainId in config).value
      val ( alias, address ) = parser.parsed
      if (! Failable( EthAddress( alias ) ).isFailed ) {
        throw new SbtEthereumException( s"You cannot use what would be a legitimate Ethereum address as an alias. Bad attempted alias: '${alias}'" )
      }
      val check = shoebox.Database.createUpdateAddressAlias( chainId, alias, address )
      check.fold( _.vomit ){ _ => 
        log.info( s"Alias '${alias}' now points to address '0x${address.hex}' (for chain with ID ${chainId})." )
      }

      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethAddressBalanceTask( config : Configuration ) : Initialize[InputTask[BigDecimal]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genOptionalGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val jsonRpcUrl       = findNodeJsonRpcUrlTask(warn=true)(config).value
      val timeout          = xethcfgAsyncOperationTimeout.value
      val baseCurrencyCode = ethcfgBaseCurrencyCode.value
      val mbAddress        = parser.parsed
      val address          = mbAddress.getOrElse( (xethFindCurrentSender in config).value.get )

      val exchangerConfig = findExchangerConfigTask( config ).value

      val result           = doPrintingGetBalance( exchangerConfig, log, timeout, address, jsonrpc.Client.BlockNumber.Latest, Denominations.Ether )
      val ethValue         = result.denominated

      priceFeed.ethPriceInCurrency( baseCurrencyCode ).foreach { datum =>
        val value = ethValue * datum.price
        val roundedValue = value.setScale(2, BigDecimal.RoundingMode.HALF_UP )
        println( s"This corresponds to approximately ${roundedValue} ${baseCurrencyCode} (at a rate of ${datum.price} ${baseCurrencyCode} per ETH, retrieved at ${ formatTime( datum.timestamp ) } from ${priceFeed.source})" )
      }

      ethValue
    }
  }

  private def ethAddressSenderEffectiveTask( config : Configuration ) : Initialize[Task[Failable[EthAddress]]] = _xethFindCurrentSenderTask( printEffectiveSender = true )( config )

  private def ethAddressSenderOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val configSenderOverride = senderOverrideForConfig( config )
    configSenderOverride.synchronized {
      val log = streams.value.log
      Mutables.SenderOverride.set( None )
      log.info("No sender override is now set. Effective sender will be determined by 'ethcfgSender' setting, the System property 'eth.sender', the environment variable 'ETH_SENDER', or a 'defaultSender' alias.")
    }
  }

  private def ethAddressSenderOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    val chainId = (ethcfgChainId in config).value

    val mbSenderOverride = getSenderOverrideAddress( config )( log, chainId )

    val message = mbSenderOverride.fold( s"No sender override is currently set (for configuration '${config}')." ) { address =>
      val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "" )( _.fold("")( str => s", aliases $str)" ) )
      s"A sender override is set, address '${address.hex}' (on chain with ID ${chainId}${aliasesPart}, configuration '${config}')."
    }

    log.info( message )
  }

  private def ethAddressSenderOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )
    val configSenderOverride = senderOverrideForConfig( config )

    Def.inputTask {
      configSenderOverride.synchronized {
        val log = streams.value.log
        val chainId = (ethcfgChainId in config).value
        val address = parser.parsed
        val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "")( _.fold("")( str => s", aliases $str)" ) )

        configSenderOverride.set( Some( ( chainId, address ) ) )

        log.info( s"Sender override set to '0x${address.hex}' (on chain with ID ${chainId}${aliasesPart})." )
      }
    }
  }

  private def ethContractAbiAliasDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genExistingAbiAliasParser )

    Def.inputTaskDyn {
      val chainId = (ethcfgChainId in config).value
      val log = streams.value.log
      val dropMeAbiAlias = parser.parsed
      shoebox.Database.dropAbiAlias( chainId, dropMeAbiAlias )
      log.info( s"Abi alias 'abi:${dropMeAbiAlias}' successfully dropped." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethContractAbiAliasListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val chainId = (ethcfgChainId in config).value
    val abiAliases = shoebox.Database.findAllAbiAliases( chainId ).assert
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
      val chainId = (ethcfgChainId in config).value
      val log = streams.value.log
      val ( newAbiAlias, abiSource ) = parser.parsed
      val ( abi : Abi, sourceDesc : String) = standardSortAbiAndSourceDesc( log, abiSource )
      shoebox.Database.createUpdateAbiAlias( chainId, newAbiAlias, abi )
      log.info( s"Abi alias 'abi:${newAbiAlias}' successfully bound to ABI found via ${sourceDesc}." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethContractAbiDecodeTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAnyAbiSourceHexBytesParser )

    Def.inputTask {
      val chainId = (ethcfgChainId in config).value
      val log = streams.value.log
      val ( abiSource, bytes ) = parser.parsed
      loggedAbiFromAbiSource( log, abiSource ).fold( throw nst( new AbiUnknownException( s"Can't find ABI for ${abiSource.sourceDesc}" ) ) ) { abi =>
        val ( fcn, values ) = ethabi.decodeFunctionCall( abi, bytes ).assert
        println( s"Function called: ${ethabi.signatureForAbiFunction(fcn)}" )
          (values.zip( Stream.from(1) )).foreach { case (value, index) =>
            println( s"   Arg 1 [name=${value.parameter.name}, type=${value.parameter.`type`}]: ${value.stringRep}" )
          }
      }
    }
  }

  private def ethContractAbiEncodeTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAbiMaybeWarningFunctionInputsParser( restrictedToConstants = false ) )

    Def.inputTask {
      val chainId = (ethcfgChainId in config).value
      val log = streams.value.log
      val ( abi, mbWarning, function, inputs ) = parser.parsed
      mbWarning.foreach( warning => log.warn( warning ) )
      val bytes = ethabi.callDataForAbiFunctionFromStringArgs( inputs, function ).assert
      println( "Encoded data:" )
      println( hexString(bytes) )
    }
  }

  private def ethContractAbiForgetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val chainId = (ethcfgChainId in config).value
      val log = streams.value.log
      val address = parser.parsed
      val found = shoebox.Database.deleteMemorizedContractAbi( chainId, address ).get // throw an Exception if there's a database issue
      if ( found ) {
        log.info( s"Previously imported or matched ABI for contract with address '0x${address.hex}' (on chain with ID ${chainId}) has been forgotten." )
      } else {
        val mbDeployment = shoebox.Database.deployedContractInfoForAddress( chainId, address ).get  // throw an Exception if there's a database issue
        mbDeployment match {
          case Some( _ ) => throw new SbtEthereumException( s"Contract at address '0x${address.hex}' (on chain with ID ${chainId}) is not an imported ABI but our own deployment. Cannot drop." )
          case None      => throw new SbtEthereumException( s"We have not memorized an ABI for the contract at address '0x${address.hex}' (on chain with ID ${chainId})." )
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

  private def ethContractAbiMatchTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressAnyAbiSourceParser )

    Def.inputTaskDyn {
      val chainId = (ethcfgChainId in config).value
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
          val ignoreOverride = queryYN( is, s"An ABI override is set in the present session that differs from the ABI you wish to associate with '${hexString(toLinkAddress)}'. Continue? [y/n] " )
          if (! ignoreOverride ) throw new OperationAbortedByUserException( "User aborted ethContractAbiMatch due to discrepancy between linked ABI and current override." )
        }
      }

      def finishUpdate = {
        log.info( s"The ABI previously associated with ${sourceDesc} ABI has been associated with address ${hexString(toLinkAddress)}." )
        if (! shoebox.Database.hasAddressAliases( chainId, toLinkAddress ).assert ) {
          interactiveSetAliasForAddress( chainId )( s, log, is, s"the address '${hexString(toLinkAddress)}', now associated with the newly matched ABI", toLinkAddress )
        }
        Def.taskDyn {
          xethTriggerDirtyAliasCache
        }
      }
      def noop = Def.task{}

      val lookupExisting = abiLookupForAddress( chainId, toLinkAddress, currentAbiOverrides )
      lookupExisting match {
        case AbiLookup( toLinkAddress, _, Some( `abi` ), _, _ ) => {
          log.info( s"The ABI you have tried to link is already associated with '${hexString(toLinkAddress)}'. No changes were made." )
          noop
        }
        case AbiLookup( toLinkAddress, _, Some( memorizedAbi ), Some( `abi` ), _ ) => {
          val deleteMemorized = queryYN( is, s"The ABI you have tried to link is the origial compilation ABI associated with ${hexString(toLinkAddress)}. Remove shadowing ABI to restore? [y/n] " )
          if (! deleteMemorized ) {
            throw new OperationAbortedByUserException( "User chose not to delete a currently associated ABI which shadows an original compilation-derived ABI)." )
          }
          else {
            shoebox.Database.deleteMemorizedContractAbi( chainId, toLinkAddress ).assert // throw an Exception if there's a database issue
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
            throw new OperationAbortedByUserException( "User aborted ethContractAbiMatch in order not to replace an existing association (which itself shadowed an original compilation-derived ABI)." )
          }
          else {
            shoebox.Database.resetMemorizedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
            finishUpdate
          }
        }
        case AbiLookup( toLinkAddress, _, Some( memorizedAbi ), None, _ ) => {
          val overwrite = queryYN( is, s"This operation would overwrite an existing ABI associated with '${hexString(toLinkAddress)}'. Continue? [y/n] " )
          if (! overwrite ) {
            throw new OperationAbortedByUserException( "User aborted ethContractAbiMatch in order not to replace an existing association." )
          }
          else {
            shoebox.Database.resetMemorizedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
            finishUpdate
          }
        }
        case AbiLookup( toLinkAddress, _, None, Some( compilationAbi_ ), _ ) => {
          val shadow = queryYN( is, "This operation would shadow an original compilation-derived ABI. Continue? [y/n] " )
          if (! shadow ) {
            throw new OperationAbortedByUserException( "User aborted ethContractAbiMatch in order not to replace an existing association (which itself overrode an original compilation-derived ABI)." )
          }
          else {
            shoebox.Database.setMemorizedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
            finishUpdate
          }
        }
        case AbiLookup( toLinkAddress, _, None, None, _ ) => {
          shoebox.Database.setMemorizedContractAbi( chainId, toLinkAddress, abi ).assert // throw an Exception if there's a database issue
          finishUpdate
        }
        case unexpected => throw new SbtEthereumException( s"Unexpected AbiLookup: ${unexpected}" )
      }
    }
  }

  private def ethContractAbiOverrideAddTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressAnyAbiSourceParser )

    Def.inputTaskDyn {
      val chainId = (ethcfgChainId in config).value
      val s = state.value
      val log = streams.value.log
      val ( toLinkAddress, abiSource ) = parser.parsed
      val ( abi, mbLookup ) = abiFromAbiSource( abiSource ).getOrElse( throw nst( new AbiUnknownException( s"Can't find ABI for ${abiSource.sourceDesc}" ) ) )
      mbLookup.foreach( _.logGenericShadowWarning( log ) )
      addAbiOverrideForChain( chainId, toLinkAddress, abi )
      log.info( s"ABI override successfully added." )
      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethContractAbiOverrideListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val chainId = (ethcfgChainId in config).value
    val s = state.value
    val log = streams.value.log
    val currentAbiOverrides = abiOverridesForChain( chainId )

    val columns = immutable.Vector( "ABI Override Addresses" ).map( texttable.Column.apply( _ ) )
    def extract( address : EthAddress ) : Seq[String] = hexString(address) :: Nil
    texttable.printTable( columns, extract )( currentAbiOverrides.keySet.toSeq.map( addr => texttable.Row(addr, leftwardAliasesArrowOrEmpty(chainId, addr).assert) ) )
  }

  private def ethContractAbiOverrideClearTask( config : Configuration ) : Initialize[Task[Unit]] = Def.taskDyn {
    val chainId = (ethcfgChainId in config).value
    val s = state.value
    val log = streams.value.log
    val out = clearAbiOverrideForChain( chainId )
    if ( out ) {
      log.info( s"ABI overrides on chain with ID ${chainId} successfully cleared." )
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
      val chainId = (ethcfgChainId in config).value
      val s = state.value
      val log = streams.value.log
      val address = parser.parsed
      val currentAbiOverrides = abiOverridesForChain( chainId )
      currentAbiOverrides.get( address ) match {
        case Some( abi ) => {
          println( s"Contract ABI for address '0x${address.hex}':" )
          val json = Json.toJson( abi )
          println( Json.prettyPrint( json ) )
        }
        case None => {
          log.warn( s"No ABI override set for address '${hexString(address)}' (for chain with ID ${chainId})." )
        }
      }
    }
  }

  private def ethContractAbiOverrideRemoveTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTaskDyn {
      val chainId = (ethcfgChainId in config).value
      val s = state.value
      val log = streams.value.log
      val address = parser.parsed
      val out = removeAbiOverrideForChain( chainId, address )
      if ( out ) {
        log.info( s"ABI override successfully removed." )
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
      val chainId = (ethcfgChainId in config).value
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
    trait Source
    case object Memorized extends Source
    case class Deployed( mbContractName : Option[String] ) extends Source
  }
  private final case class AbiListRecord( address : EthAddress, source : AbiListRecord.Source, aliases : immutable.Seq[String] ) {
    def matches( regex : Regex ) = {
      aliases.exists( alias => regex.findFirstIn( alias ) != None ) ||
      regex.findFirstIn( s"0x${address.hex}" ) != None ||
      ( source.isInstanceOf[AbiListRecord.Deployed] && source.asInstanceOf[AbiListRecord.Deployed].mbContractName.exists( contractName => regex.findFirstIn( contractName ) != None ) )
    }
  }

  private def ethContractAbiListTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val chainId = (ethcfgChainId in config).value
    val log = streams.value.log

    val mbRegex = regexParser( defaultToCaseInsensitive = true ).parsed

    val memorizedAddresses = shoebox.Database.getMemorizedContractAbiAddresses( chainId ).get
    val deployedContracts = shoebox.Database.allDeployedContractInfosForChainId( chainId ).get

    val allRecords = {
      val memorizedRecords = memorizedAddresses.map( address => AbiListRecord( address, AbiListRecord.Memorized, shoebox.Database.findAddressAliasesByAddress( chainId, address ).get ) )
      val deployedRecords  = {
        deployedContracts
          .filter( _.mbAbi.nonEmpty )
          .map( dci => AbiListRecord( dci.contractAddress, AbiListRecord.Deployed( dci.mbName ), shoebox.Database.findAddressAliasesByAddress( chainId, dci.contractAddress ).get ) )
      }
      memorizedRecords ++ deployedRecords
    }

    val filteredRecords = mbRegex.fold( allRecords )( regex => allRecords.filter( _.matches( regex ) ) )

    val cap = "+" + span(44) + "+" + span(11) + "+"
    val addressHeader = "Address"
    val sourceHeader = "Source"
    println( cap )
    println( f"| $addressHeader%-42s | $sourceHeader%-9s |" )
    println( cap )
    filteredRecords.foreach { record =>
      val ka = s"0x${record.address.hex}"
      val source = if ( record.source == AbiListRecord.Memorized ) "Memorized" else "Deployed"
      val aliasesPart = {
        record.aliases match {
          case Seq( alias ) => s"""alias "${alias}""""
          case Seq()        => ""
          case aliases      => {
            val quoted = aliases.map( "\"" + _ + "\"" )
            s"""aliases ${quoted.mkString(", ")}"""
          }
        }
      }
      val annotation = record match {
        case AbiListRecord( address, AbiListRecord.Memorized, aliases ) if aliases.isEmpty                => ""
        case AbiListRecord( address, AbiListRecord.Memorized, aliases )                                   => s""" <-- ${aliasesPart}"""
        case AbiListRecord( address, AbiListRecord.Deployed( None ), aliases ) if aliases.isEmpty         => ""
        case AbiListRecord( address, AbiListRecord.Deployed( None ), aliases )                            => s""" <-- ${aliasesPart}"""
        case AbiListRecord( address, AbiListRecord.Deployed( Some( name ) ), aliases ) if aliases.isEmpty => s""" <-- contract name "${name}""""
        case AbiListRecord( address, AbiListRecord.Deployed( Some( name ) ), aliases )                    => s""" <-- contract name "${name}", ${aliasesPart}"""
      }
      println( f"| $ka%-42s | $source%-9s |" +  annotation )
    }
    println( cap )

  }

  private def queryYN( is : InteractionService, query : String ) : Boolean = {
    def prompt = is.readLine( query, mask = false ).get
    def doPrompt : Boolean = {
      def redo = {
        println( "Please enter 'y' or 'n'." )
        doPrompt
      }
      prompt.toLowerCase match {
        case ""                     => redo
        case s if s.startsWith("y") => true
        case s if s.startsWith("n") => false
        case _                      => redo
      }
    }
    doPrompt
  }

  private def queryIntOrNone( is : InteractionService, query : String, min : Int, max : Int ) : Option[Int] = {
    require( min >= 0, "Implementation limitation, only positive numbers are supported for now." )
    require( max >= min, s"max ${max} cannot be smaller than min ${min}." )

    // -1 could not be interpreted as Int, None means empty String
    // this is why we don't support negatives, -1 is out-of-band
    def fetchNum : Option[Int] = { 
      val line = is.readLine( query, mask = false ).getOrElse( throw new SbtEthereumException( CantReadInteraction ) ).trim
      if ( line.isEmpty ) {
        None
      }
      else {
        try {
          Some( line.toInt )
        }
        catch {
          case nfe : NumberFormatException => {
            println( s"Bad entry... '${line}'. Try again." )
            Some(-1)
          }
        }
      }
    }

    def checkRange( num : Int ) = {
      if ( num < min || num > max ) {
        println( s"${num} is out of range. Try again." )
        false
      }
      else {
        true
      }
    }

    @tailrec
    def doFetchNum : Option[Int] = {
      fetchNum match {
        case Some(-1)                          => doFetchNum
        case Some( num ) if !checkRange( num ) => doFetchNum
        case ok                                => ok
      }
    }

    doFetchNum
  }

  private def ethContractAbiImportTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTaskDyn {
      val chainId = (ethcfgChainId in config).value
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
            val overwrite = queryYN( is, s"An ABI for '${hexString(address)}' on chain with ID ${chainId} has already been memorized. Overwrite? [y/n] " )
            if (! overwrite) throw new OperationAbortedByUserException( "User chose not to overwrite already memorized contract ABI." )
          }
          else if ( abiLookup.compilationAbi.nonEmpty ) {
            val shadow = queryYN( is, s"A compilation deployed at '${hexString(address)}' on chain with ID ${chainId} has a built-in ABI. Do you wish to shadow it? [y/n] " )
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
                    println( "Attempting to fetch ABI for address '${hexString(address)}' from Etherscan." )
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
                  log.warn("Consider acquiring an API key from Etherscan, and setting it via 'etherscanApiKeyImport'.")
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
          case None                 => parseAbi( is.readLine( "Contract ABI: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
        }
      }
      shoebox.Database.resetMemorizedContractAbi( chainId, address, abi  ).get // throw an Exception if there's a database issue
      log.info( s"ABI is now known for the contract at address ${hexString(address)}" )
      if (! shoebox.Database.hasAddressAliases( chainId, address ).assert ) {
        interactiveSetAliasForAddress( chainId )( s, log, is, s"the address '${hexString(address)}', now associated with the newly imported ABI", address )
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
      val chainId = (ethcfgChainId in config).value
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
          mbinfo.fold( println( s"Contract with address '$address' not found." ) ) { info =>
            section( s"Contract Address (on blockchain '${info.chainId}')", Some( info.contractAddress.hex ), true )
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

  private def ethDebugGanacheStopTask : Initialize[Task[Unit]] = Def.task {
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
    val chainId     = (ethcfgChainId in config).value

    val combined = combinedKeystoresMultiMap( keystoresV3 )

    val out = {
      def aliasesSet( address : EthAddress ) : immutable.SortedSet[String] = immutable.TreeSet( shoebox.Database.findAddressAliasesByAddress( chainId, address ).get : _* )
      immutable.TreeMap( combined.map { case ( address : EthAddress, _ ) => ( address, aliasesSet( address ) ) }.toSeq : _* )( Ordering.by( _.hex ) )
    }
    val cap = "+" + span(44) + "+"
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

      val credential = readCredential( is, address )
      val privateKey = findPrivateKey( log, wallets, credential )
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

  private def ethKeystoreWalletV3FromJsonImportTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val is = interactionService.value
    val w = readV3Wallet( is )
    val address = w.address // a very cursory check of the wallet, NOT full validation
    shoebox.Keystore.V3.storeWallet( w ).get // asserts success
    log.info( s"Imported JSON wallet for address '0x${address.hex}', but have not validated it.")
    log.info( s"Consider validating the JSON using 'ethKeystoreWalletV3Validate 0x${address.hex}'." )
  }

  private def ethKeystoreWalletV3FromPrivateKeyImportTask : Initialize[Task[wallet.V3]] = Def.task {
    val log   = streams.value.log
    val c     = xethcfgWalletV3Pbkdf2C.value
    val dklen = xethcfgWalletV3Pbkdf2DkLen.value

    val is = interactionService.value
    val entropySource = ethcfgEntropySource.value

    val privateKeyStr = {
      val raw = is.readLine( "Please enter the private key you would like to import (as 32 hex bytes): ", mask = true ).getOrElse( throw new Exception( CantReadInteraction ) ).trim()
      if ( raw.startsWith( "0x" ) ) raw.substring(2) else raw
    }
    val privateKey = EthPrivateKey( privateKeyStr )

    val confirm = {
      is.readLine( s"The imported private key corresponds to address '${hexString( privateKey.address )}'. Is this correct? [y/n] ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ).trim().equalsIgnoreCase("y")
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
      w
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
        val credential = readCredential( is, inputAddress )

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

    Def.taskDyn {
      xethTriggerDirtySolidityCompilerList // causes parse cache and SessionSolidityCompilers to get updated
    }
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

  private def ethNodeJsonRpcUrlDefaultPrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = (config/ethcfgChainId).value
    val mbUrl = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
    mbUrl match {
      case Some( url ) => {
        log.info( s"The default ethNodeJsonRpcUrl on chain with ID ${chainId} is '${url}'." )
      }
      case None => {
        log.info( s"No default ethNodeJsonRpcUrl on chain with ID ${chainId} has been set." )
      }
    }
  }

  private def ethNodeJsonRpcUrlDefaultSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val is = interactionService.value
    val chainId = (config/ethcfgChainId).value
    val newUrl = urlParser( "<json-rpc-url>" ).parsed
    val oldValue = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
    oldValue.foreach { url =>
      println( s"A default ethNodeJsonRpcUrl has already been set for chain with ID ${chainId}: '${url}'." )
      val overwrite = queryYN( is, s"Do you wish to replace it? [y/n] " )
      if ( overwrite ) {
        shoebox.Database.dropDefaultJsonRpcUrl( chainId ).assert
      }
      else {
        throw new OperationAbortedByUserException( "User chose not to replace previously set default ethNodeJsonRpcUrl for chain with ID ${chainId}, which remains '${url}'." )
      }
    }
    shoebox.Database.setDefaultJsonRpcUrl( chainId, newUrl ).assert
    log.info( s"Successfully set default ethNodeJsonRpcUrl for chain with ID ${chainId} to ${newUrl}." )
  }

  private def ethNodeJsonRpcUrlDefaultDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = (config/ethcfgChainId).value
    val oldValue = shoebox.Database.findDefaultJsonRpcUrl( chainId ).assert
    oldValue match {
      case Some( url ) => {
        val check = shoebox.Database.dropDefaultJsonRpcUrl( chainId ).assert
        assert( check, "Huh? We had a an old jsonRpcUrl value, but trying to delete it failed to delete any rows?" )
        log.info( s"The default ethNodeJsonRpcUrl for chain with ID ${chainId} was '${url}', but it has now been successfully dropped." )
      }
      case None => {
        log.info( s"No ethNodeJsonRpcUrl for chain with ID ${chainId} has been set. Nothing to do here." )
      }
    }
  }

  private def ethNodeJsonRpcUrlOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = (config/ethcfgChainId).value
    Mutables.NodeJsonRpcUrlOverride.synchronized {
      val map = Mutables.NodeJsonRpcUrlOverride.get
      map.get( chainId ) match {
        case Some( url ) => {
          log.info( s"The default Ethereum json-rpc service URL for chain with ID ${chainId} has been overridden. The overridden value '${url}' will be used for all tasks." )
        }
        case None => {
          log.info( "The default Ethereum json-rpc service URL for chain with ID ${chainId} has not been overridden, and will be used for all tasks. Try 'ethNodeJsonRpcUrlPrint' to see that URL." )
        }
      }
    }
  }

  private def ethNodeJsonRpcUrlOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val chainId = (config/ethcfgChainId).value
    val overrideUrl = urlParser( "<override-json-rpc-url>" ).parsed
    Mutables.NodeJsonRpcUrlOverride.synchronized {
      val map = Mutables.NodeJsonRpcUrlOverride.get
      Mutables.NodeJsonRpcUrlOverride.set( map + Tuple2( chainId, overrideUrl ) )
    }
    log.info( s"The default Ethereum json-rpc service URL for chain with ID ${chainId} has been overridden. The new overridden value '${overrideUrl}' will be used for all tasks." )
  }

  private def ethNodeJsonRpcUrlOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val chainId = (config/ethcfgChainId).value
    Mutables.NodeJsonRpcUrlOverride.synchronized {
      val map = Mutables.NodeJsonRpcUrlOverride.get
      Mutables.NodeJsonRpcUrlOverride.set( map - chainId )
    }
    log.info( s"Any override has been dropped. The default Ethereum json-rpc service URL for chain with ID ${chainId} will be used for all tasks." )
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
      val mbNumber = queryIntOrNone( is, "Which database dump should we restore? (Enter a number, or hit enter to abort) ", 1, dumps.size )
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
      val rawPath = is.readLine( "Enter the path of the directory into which you wish to create a backup: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) )
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

  private def ethShoeboxRestoreTask : Initialize[Task[Unit]] = Def.task {
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
        val mbNumber = queryIntOrNone( is, "Which backup should we restore? (Enter a number, or hit enter to abort) ", 1, backupFiles.size )
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
            val mbNum = queryIntOrNone( is, "Enter a number, or hit return to abort: ", 1, 3 )
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
          .getOrElse( throw new Exception( CantReadInteraction ) )
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
  }

  private def ethTransactionDeployTask( config : Configuration ) : Initialize[InputTask[immutable.Seq[Tuple2[String,Either[EthHash,Client.TransactionReceipt]]]]] = {
    val parser = Defaults.loadForParser(xethFindCacheSeeds in config)( genContractSpawnParser )

    Def.inputTaskDyn {
      val s = state.value
      val is = interactionService.value
      val log = streams.value.log
      val chainId = (ethcfgChainId in config).value
      val ephemeralDeployment = chainId <= 0

      val sender = (xethFindCurrentSender in config).value.get
      val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value

      // lazy so if we have nothing to sign, we don't bother to prompt for passcode
      lazy val privateKey = findCachePrivateKey( s, log, is, chainId, sender, autoRelockSeconds, true )

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

      def createQuartetFull( full : SpawnInstruction.Full ) : (String, String, immutable.Seq[String], Abi ) = {
        if ( full.seed.currentCompilation ) {
          assert(
            full.deploymentAlias == full.seed.contractName,
            s"For current compilations, we expect deployment aliases and contract names to be identical! [deployment alias: ${full.deploymentAlias}, contract name: ${full.seed.contractName}]"
          )
          val compilation = currentCompilationsMap( full.seed.contractName ) // use the most recent compilation, in case source changed after the seed was cached
          ( full.deploymentAlias, compilation.code, full.args, compilation.info.mbAbi.get ) // asserts that we've generated an ABI, but note that args may not be consistent with this latest ABI
        }
        else {
          ( full.deploymentAlias, full.seed.codeHex, full.args, full.seed.abi )
        }
      }

      def createQuartetUncompiled( uncompiledName : SpawnInstruction.UncompiledName ) : (String, String, immutable.Seq[String], Abi ) = {
        val deploymentAlias = uncompiledName.name
        val seed = anySourceFreshSeed( deploymentAlias ) // use the most recent compilation, in case source changed after the seed was cached
        ( deploymentAlias, seed.codeHex, Nil, seed.abi ) // we can only handle uncompiled names if there are no constructor inputs
      }

      def createAutoQuartets() : immutable.Seq[(String, String, immutable.Seq[String], Abi )] = {
        mbAutoNameInputs match {
          case None => {
            log.warn("No contract name or compilation alias provided. No 'ethcfgAutoDeployContracts' set, so no automatic contracts to deploy.")
            Nil
          }
          case Some ( autoNameInputs ) => {
            autoNameInputs.toList.map { nameAndArgs =>
              val words = nameAndArgs.split("""\s+""")
              require( words.length >= 1, s"Each element of 'ethcfgAutoDeployContracts' must contain at least a contract name! [word length: ${words.length}")
              val deploymentAlias = words.head
              val args = words.tail.toList
              val seed = anySourceFreshSeed( deploymentAlias )
              ( deploymentAlias, seed.codeHex, args, seed.abi )
            }
          }
        }
      }

      val instruction = parser.parsed
      val quartets = {
        instruction match {
          case SpawnInstruction.Auto                        => createAutoQuartets()
          case uncompiled : SpawnInstruction.UncompiledName => immutable.Seq( createQuartetUncompiled( uncompiled ) )
          case full : SpawnInstruction.Full                 => immutable.Seq( createQuartetFull( full ) )
        }
      }

      val interactive = instruction != SpawnInstruction.Auto

      implicit val invokerContext = (xethInvokerContext in config).value

      val nonceOverride = {
        unwrapNonceOverride( Some( log ) ) match {
          case noverride @ Some( _ ) if (quartets.length <= 1) => {
            noverride
          }
          case Some( u256 ) => {
            throw new SbtEthereumException( s"""Cannot create multiple contracts with a fixed nonce override ${u256.widen} set. Contract creations requested: ${quartets.map( _._1 ).mkString(", ")}""" )
          }
          case None => {
            None
          }
        }
      }

      def doSpawn( deploymentAlias : String, codeHex : String, inputs : immutable.Seq[String], abi : Abi ) : ( String, Either[EthHash,Client.TransactionReceipt] ) = {

        val inputsBytes = ethabi.constructorCallData( inputs, abi ).get // asserts that we've found a meaningful ABI, and can parse the constructor inputs
        val inputsHex = inputsBytes.hex
        val dataHex = codeHex ++ inputsHex

        if ( inputsHex.nonEmpty ) {
          log.debug( s"Contract constructor inputs encoded to the following hex: '${inputsHex}'" )
        }

        val f_txnHash = Invoker.transaction.createContract( privateKey, Zero256, dataHex.decodeHexAsSeq, nonceOverride )

        log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." )
        val f_out = {
          for {
            txnHash <- f_txnHash
            receipt <- Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(abi), txnHash, invokerContext.pollTimeout, _ ) )
          } yield {
            log.info( s"Contract '${deploymentAlias}' deployed in transaction '0x${txnHash.hex}'." )
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
              t match {
                case timeout : Poller.TimeoutException => {
                  log.warn( s"Timeout after ${invokerContext.pollTimeout}!!! -- ${timeout}" )
                }
                case whatev => {
                  log.warn( whatev.toString )
                  whatev.printStackTrace()
                }
              }
              log.warn( s"Failed to retrieve a transaction receipt for the creation of contract '${deploymentAlias}'!" )
              log.warn(  "The contract may have been created, but without a receipt, the compilation and ABI could not be associated with an address.")
              log.warn( s"You may wish to check sender adddress '0x${sender.hex}' in a blockchain explorer (e.g. etherscan), and manually associate the ABI with the address of the transaction succeeded." )
              log.warn(  "Contract ABI" )
              log.warn(  "============" )
              log.warn( Json.stringify( Json.toJson( abi ) ) )
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

      val result = quartets.map( (doSpawn _).tupled )

      Def.taskDyn {
        Def.task {
          val force = xethTriggerDirtyAliasCache.value
          result
        }
      }
    }
  }

  private def ethTransactionGasLimitOverrideDropTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasLimitOverride.set( None )
    log.info("No gas override is now set. Quantities of gas will be automatically computed.")
  }

  private def ethTransactionGasLimitOverrideSetTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val amount = bigIntParser("<gas override>").parsed
    Mutables.GasLimitOverride.set( Some( amount ) )
    log.info( s"Gas override set to ${amount}." )
  }

  private def ethTransactionGasLimitOverridePrintTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasLimitOverride.get match {
      case Some( value ) => log.info( s"A gas override is set, with value ${value}." )
      case None          => log.info( "No gas override is currently set." )
    }
  }

  private def ethTransactionGasPriceOverrideDropTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasPriceOverride.set( None )
    log.info("No gas price override is now set. Gas price will be automatically marked-up from your ethereum node's current default value.")
  }

  private def ethTransactionGasPriceOverridePrintTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasPriceOverride.get match {
      case Some( value ) => log.info( s"A gas price override is set, with value ${value}." )
      case None          => log.info( "No gas price override is currently set." )
    }
  }

  private def ethTransactionGasPriceOverrideSetTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val amount = valueInWeiParser("<gas price override>").parsed
    Mutables.GasPriceOverride.set( Some( amount ) )
    log.info( s"Gas price override set to ${amount}." )
  }

  private def ethTransactionInvokeTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = (ethcfgChainId in config).value
      val caller = (xethFindCurrentSender in config).value.get
      val nonceOverride = unwrapNonceOverride( Some( log ) )
      val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
      val privateKey = findCachePrivateKey(s, log, is, chainId, caller, autoRelockSeconds, true )

      val ( ( contractAddress, function, args, abi, abiLookup ), mbWei ) = parser.parsed
      abiLookup.logGenericShadowWarning( log )

      val amount = mbWei.getOrElse( Zero )
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      log.debug( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
      log.debug( s"Call data for function call: ${callData.hex}" )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendMessage( privateKey, contractAddress, Unsigned256( amount ), callData, nonceOverride ) flatMap { txnHash =>
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei ${mbWithNonceClause(nonceOverride)}to address '0x${contractAddress.hex}' in transaction '0x${txnHash.hex}'.""" )
        log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(abi), txnHash, invokerContext.pollTimeout, _ ) )
      }
      Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will throw a TimeoutException internally on time out
    }
  }

  private def ethTransactionLookupTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = ethHashParser( "<transaction-hash>" )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val chainId = (ethcfgChainId in config).value
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

  private def ethTransactionNonceOverrideDropTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.NonceOverride.set( None )
    log.info("Any nonce override has been unset. The nonces for any new transactions will be automatically computed.")
  }

  private def ethTransactionNonceOverrideSetTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val amount = bigIntParser("<nonce override>").parsed
    Mutables.NonceOverride.set( Some( amount ) )
    log.info( s"Nonce override set to ${amount}. Future transactions will use this value as a fixed nonce, until this override is explcitly unset with 'ethTransactionNonceOverrideDrop'." )
  }

  private def ethTransactionNonceOverridePrintTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.NonceOverride.get match {
      case Some( value ) => log.info( s"A nonce override is set, with value ${value}. Future transactions will use this value as a fixed nonce, until this override is explcitly unset with 'ethTransactionNonceOverrideDrop'." )
      case None          => log.info( "No nonce override is currently set. The nonces for any new transactions will be automatically computed." )
    }
  }

  private def ethTransactionPingTask( config : Configuration ) : Initialize[InputTask[Option[Client.TransactionReceipt]]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genOptionalGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val from = (xethFindCurrentSender in config).value.get
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
        val (_, result) = extract.runInputTask(ethTransactionSend in config, sendArgs, s)

        log.info( "Ping succeeded!" )
        log.info( s"Sent 0 ether from '${from.hex}' to ${ recipientStr } in transaction '0x${result.transactionHash.hex}'" )
        Some( result )
      }
      catch {
        case t : Poller.TimeoutException => {
          log.warn( s"""Ping failed! Our attempt to send 0 ether from '0x${from.hex}' to ${ recipientStr } may or may not eventually succeed, but we've timed out before hearing back.""" )
          None
        }
        case inc @ Incomplete( _, _, mbMsg, _, mbCause ) => {
          mbMsg.foreach( msg => log.warn( s"sbt.Incomplete - Message: ${msg}" ) )
          mbCause.foreach( _.printStackTrace() )
          log.warn( s"""Ping failed! Our attempt to send 0 ether from '0x${from.hex}' to ${ recipientStr } yielded an sbt.Incomplete: ${inc}""")
          None
        }
        case NonFatal(t) => {
          t.printStackTrace()
          log.warn( s"""Ping failed! Our attempt to send 0 ether from '0x${from.hex}' to ${ recipientStr } yielded an Exception: ${t}""")
          None
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
      val chainId = (ethcfgChainId in config).value
      val from = (xethFindCurrentSender in config).value.get
      val (to, data, amount) = parser.parsed
      val nonceOverride = unwrapNonceOverride( Some( log ) )
      val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
      val privateKey = findCachePrivateKey( s, log, is, chainId, from, autoRelockSeconds, true )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendMessage( privateKey, to, Unsigned256( amount ), data, nonceOverride ) flatMap { txnHash =>
        log.info( s"""Sending data '0x${data.hex}' with ${amount} wei to address '0x${to.hex}' ${mbWithNonceClause(nonceOverride)}in transaction '0x${txnHash.hex}'.""" )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, None, txnHash, invokerContext.pollTimeout, _ ) )
      }
      val out = Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout
      log.info("Transaction mined.")
      out
    }
  }

  private def ethTransactionSendTask( config : Configuration ) : Initialize[InputTask[Client.TransactionReceipt]] = {
    val parser = Defaults.loadForParser( xethFindCacheRichParserInfo in config )( genEthSendEtherParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val chainId = (ethcfgChainId in config).value
      val from = (xethFindCurrentSender in config).value.get
      val (to, amount) = parser.parsed
      val nonceOverride = unwrapNonceOverride( Some( log ) )
      val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
      val privateKey = findCachePrivateKey( s, log, is, chainId, from, autoRelockSeconds, true )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendWei( privateKey, to, Unsigned256( amount ), nonceOverride ) flatMap { txnHash =>
        log.info( s"Sending ${amount} wei to address '0x${to.hex}' ${mbWithNonceClause(nonceOverride)}in transaction '0x${txnHash.hex}'." )
        log.info( s"Waiting for the transaction to be mined (will wait up to ${invokerContext.pollTimeout})." )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, None, txnHash, invokerContext.pollTimeout, _ ) )
      }
      val out = Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout
      log.info("Ether sent.")
      out
    }
  }

  private def ethTransactionViewTask( config : Configuration ) : Initialize[InputTask[(Abi.Function,immutable.Seq[Decoded.Value])]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = true ) )

    Def.inputTask {
      val log = streams.value.log
      val timeout = xethcfgAsyncOperationTimeout.value

      val from = (xethFindCurrentSender in config).value.recover { failed =>
        log.info( s"Failed to find a current sender, using the zero address as a default.\nCause: ${failed}" )
        EthAddress.Zero
      }.get

      val ( ( contractAddress, function, args, abi, abiLookup), mbWei ) = parser.parsed
      abiLookup.logGenericShadowWarning( log )
      if (! function.constant ) {
        log.warn( s"Function '${function.name}' is not marked constant! An ephemeral call may not succeed, and in any case, no changes to the state of the blockchain will be preserved." )
      }
      val amount = mbWei.getOrElse( Zero )
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      log.debug( s"Call data for function call: ${callData.hex}" )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.constant.sendMessage( from, contractAddress, Unsigned256(amount), callData ) map { rawResult =>
        log.debug( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.debug( s"Raw result of call to function '${function.name}': 0x${rawResult.hex}" )
        val results = decodeReturnValuesForFunction( rawResult, abiFunction ).get // throw an Exception is we can't get results
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

  // xens task definitions

  private def xensClientTask( config : Configuration ) : Initialize[Task[ens.Client]] = Def.task {
    val nameServiceAddress = (config / enscfgNameServiceAddress).value
    val tld                = (config / enscfgNameServiceTld).value
    val reverseTld         = (config / enscfgNameServiceReverseTld).value

    val icontext = (xethInvokerContext in config).value

    // for now, we'll hard-code the stub context defaults 
    // we can make this stuff configurable someday if it seems useful
    implicit val scontext = stub.Context( icontext, stub.Context.Default.EventConfirmations, stub.Context.Default.Scheduler )

    new ens.Client( nameServiceAddress, tld, reverseTld )
  }

  // xeth task definitions

  private def xethDefaultGasPriceTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
    val log        = streams.value.log
    val timeout    = xethcfgAsyncOperationTimeout.value

    val exchangerConfig = findExchangerConfigTask( config ).value

    doGetDefaultGasPrice( exchangerConfig, log, timeout )
  }

  private def xethFindCacheRichParserInfoTask( config : Configuration ) : Initialize[Task[RichParserInfo]] = Def.task {
    val chainId            = (config / ethcfgChainId).value
    val jsonRpcUrl         = findNodeJsonRpcUrlTask(warn=false)(config).value
    val addressAliases     = shoebox.Database.findAllAddressAliases( chainId ).assert
    val abiAliases         = shoebox.Database.findAllAbiAliases( chainId ).assert
    val abiOverrides       = abiOverridesForChain( chainId )
    val nameServiceAddress = (config / enscfgNameServiceAddress).value
    val tld                = (config / enscfgNameServiceTld).value
    val reverseTld         = (config / enscfgNameServiceReverseTld).value
    RichParserInfo( chainId, jsonRpcUrl, addressAliases, abiAliases, abiOverrides, nameServiceAddress, tld, reverseTld )
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

  private def xethFindCurrentSenderTask( config : Configuration ) : Initialize[Task[Failable[EthAddress]]] = _xethFindCurrentSenderTask( printEffectiveSender = false )( config )

  private def _xethFindCurrentSenderTask( printEffectiveSender : Boolean )( config : Configuration ) : Initialize[Task[Failable[EthAddress]]] = Def.task {
    def ifPrint( msg : => String ) = if ( printEffectiveSender ) println( msg )
    Failable {
      val log = streams.value.log
      val chainId = (ethcfgChainId in config).value

      val mbSenderOverride = getSenderOverrideAddress( config )( log, chainId )
      mbSenderOverride match {
        case Some( address ) => {
          ifPrint(
            s"""|The current effective sender is ${verboseAddress(chainId, address)}.
                |It has been set as a sender override.""".stripMargin
          )
          address
        }
        case None => {
          val mbAddrStr = (ethcfgSender in config).?.value
          mbAddrStr match {
            case Some( addrStr ) => {
              val address = EthAddress( addrStr )
              ifPrint (
                s"""|The current effective sender is ${verboseAddress(chainId, address)}.
                    |It has been set by build setting 'ethcfgSender'.
                    | + No sender override has been set.""".stripMargin
              )
              address
            }
            case None => {
              ExternalValue.EthSender.map( EthAddress.apply ) match {
                case Some( address ) => {
                  ifPrint (
                    s"""|The current effective sender is ${verboseAddress(chainId, address)}.
                        |It has been set by an external value -- either System property 'eth.sender' or environment variable 'ETH_SENDER'.
                        | + No sender override has been set.
                        | + Build setting 'ethcfgSender has not been defined.""".stripMargin
                  )
                  address
                }
                case None => {
                  val mbDefaultSenderAddress = shoebox.Database.findAddressByAddressAlias( chainId, DefaultSenderAlias ).get

                  mbDefaultSenderAddress match {
                    case Some( address ) => {
                      ifPrint (
                        s"""|The current effective sender is ${verboseAddress(chainId, address)}.
                            |It has been set by the special alias '${DefaultSenderAlias}'.
                            | + No sender override has been set.
                            | + Build setting 'ethcfgSender has not been defined. 
                            | + Neither the System property 'eth.sender' nor the environment variable 'ETH_SENDER' are defined.""".stripMargin
                      )
                      address
                    }
                    case None => {
                      if ( config == Test ) {
                        val defaultTestAddress = testing.Default.Faucet.Address
                        ifPrint (
                          s"""|The current effective sender is the default testing address, '0x${defaultTestAddress.hex}' 
                              |(with widely known private key '0x${testing.Default.Faucet.PrivateKey.hex}')."
                              |It has been set because you are in the Test configuration, and no address has been defined for this configuration.
                              | + No sender override has been set.
                              | + Build setting 'ethcfgSender has not been defined. 
                              | + Neither the System property 'eth.sender' nor the environment variable 'ETH_SENDER' are defined.
                              | + No '${DefaultSenderAlias}' address alias has been defined for chain with ID ${chainId}.""".stripMargin
                        )
                        defaultTestAddress
                      }
                      else {
                        val msg ={
                          s"""|No effective sender is defined. (chain with ID ${chainId}, configuration '${config}')
                              |Cannot find any of...
                              | + a sender override
                              | + a value for build setting 'ethcfgSender' 
                              | + an 'eth.sender' System property
                              | + an 'ETH_SENDER' environment variable
                              | + a '${DefaultSenderAlias}' address alias defined for chain with ID ${chainId}""".stripMargin
                        }
                        ifPrint( msg )
                        throw new SenderNotAvailableException( msg )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private def xethFindCurrentSolidityCompilerTask : Initialize[Task[Compiler.Solidity]] = Def.task {
    import Compiler.Solidity._

    // val compilerKeys = xethFindCacheSessionSolidityCompilerKeys.value
    val sessionCompilers = Mutables.SessionSolidityCompilers.get.getOrElse( throw new Exception("Internal error -- caching compiler keys during onLoad should have forced sessionCompilers to be set, but it's not." ) )
    val compilerKeys = sessionCompilers.keySet

    val mbJsonRpcUrl = Some( findNodeJsonRpcUrlTask(warn=false)(Compile).value )

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
    val log        = streams.value.log
    val jsonRpcUrl = findNodeJsonRpcUrlTask(warn=true)(config).value

    val markup          = ethcfgGasPriceMarkup.value
    val defaultGasPrice = (xethDefaultGasPrice in config).value

    Mutables.GasPriceOverride.get match {
      case Some( gasPriceOverride ) => gasPriceOverride
      case None                     => rounded( BigDecimal(defaultGasPrice) * BigDecimal(1 + markup) )
    }
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
    val testingEthJsonRpcUrl = findNodeJsonRpcUrlTask(warn=true)(Test).value

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
                        srcFile.replaceContents( sourceCode, scala.io.Codec.UTF8 )
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
                    val gensrc = testing.TestingResourcesGenerator.generateTestingResources( testingResourcesObjectName, testingEthJsonRpcUrl, stubPackage )
                    val testingResourcesFile = new File( stubsDir, s"${testingResourcesObjectName}.scala" )
                    Files.write( testingResourcesFile.toPath, gensrc.getBytes( scala.io.Codec.UTF8.charSet ) )
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

    val jsonRpcUrl      = findNodeJsonRpcUrlTask(warn=true)(config).value

    val pollPeriod      = ethcfgTransactionReceiptPollPeriod.value
    val timeout         = ethcfgTransactionReceiptTimeout.value

    val httpTimeout     = xethcfgAsyncOperationTimeout.value

    val gasLimitMarkup  = ethcfgGasLimitMarkup.value
    val gasLimitCap     = ethcfgGasLimitCap.?.value
    val gasLimitFloor   = ethcfgGasLimitFloor.?.value

    val gasPriceMarkup  = ethcfgGasPriceMarkup.value
    val gasPriceCap     = ethcfgGasPriceCap.?.value
    val gasPriceFloor   = ethcfgGasPriceFloor.?.value

    val is              = interactionService.value
    val currencyCode    = ethcfgBaseCurrencyCode.value

    val useReplayAttackProtection = ethcfgUseReplayAttackProtection.value

    val chainId = {
      val raw = (ethcfgChainId in config).value
      if ( raw <= 0 || !useReplayAttackProtection ) None else Some( EthChainId( raw ) )
    }

    val gasLimitTweak = {
      Mutables.GasLimitOverride.get match {
        case Some( overrideValue ) => {
          log.info( s"Gas limit override set: ${overrideValue}")
          Invoker.Override( overrideValue )
        }
        case None => {
          Invoker.Markup( gasLimitMarkup, gasLimitCap, gasLimitFloor )
        }
      }
    }
    val gasPriceTweak = {
      Mutables.GasPriceOverride.get match {
        case Some( overrideValue ) => {
          log.info( s"Gas price override set: ${overrideValue}")
          Invoker.Override( overrideValue )
        }
        case None => {
          Invoker.Markup( gasPriceMarkup, gasPriceCap, gasPriceFloor )
        }
      }
    }

    val transactionLogger = findTransactionLoggerTask( config ).value

    val approver = transactionApprover( is, currencyCode, config )

    Invoker.Context.fromUrl(
      jsonRpcUrl = jsonRpcUrl,
      chainId = chainId,
      gasPriceTweak = gasPriceTweak,
      gasLimitTweak = gasLimitTweak,
      pollPeriod = pollPeriod,
      pollTimeout = timeout,
      httpTimeout = httpTimeout,
      transactionApprover = approver,
      transactionLogger = transactionLogger
    )
  }

  private def xethKeystoreWalletV3CreatePbkdf2Task : Initialize[Task[wallet.V3]] = Def.task {
    val log   = streams.value.log
    val c     = xethcfgWalletV3Pbkdf2C.value
    val dklen = xethcfgWalletV3Pbkdf2DkLen.value

    val is = interactionService.value
    val keyPair = xethGenKeyPair.value
    val entropySource = ethcfgEntropySource.value

    log.info( s"Generating V3 wallet, alogorithm=pbkdf2, c=${c}, dklen=${dklen}" )
    val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
    val w = wallet.V3.generatePbkdf2( passphrase = passphrase, c = c, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
    val out = shoebox.Keystore.V3.storeWallet( w ).get // asserts success
    log.info( s"Wallet generated into sbt-ethereum shoebox: '${shoebox.Directory.assert}'. Please backup, via 'ethShoeboxBackup' or manually." )
    log.info( s"Consider validating the wallet using 'ethKeystoreWalletV3Validate 0x${w.address.hex}'." )
    out
  }

  private def xethKeystoreWalletV3CreateScryptTask : Initialize[Task[wallet.V3]] = Def.task {
    val log   = streams.value.log
    val n     = xethcfgWalletV3ScryptN.value
    val r     = xethcfgWalletV3ScryptR.value
    val p     = xethcfgWalletV3ScryptP.value
    val dklen = xethcfgWalletV3ScryptDkLen.value

    val is = interactionService.value
    val keyPair = xethGenKeyPair.value
    val entropySource = ethcfgEntropySource.value

    log.info( s"Generating V3 wallet, alogorithm=scrypt, n=${n}, r=${r}, p=${p}, dklen=${dklen}" )
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
      val chainId = (ethcfgChainId in config).value
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
    val addressStr = (xethFindCurrentSender in config).value.get.hex
    val extract = Project.extract(s)
    val (_, result) = extract.runInputTask((xethLoadWalletsV3For in config), addressStr, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias
    result
  }

  private def xethLoadWalletsV3ForTask( config : Configuration ) : Initialize[InputTask[immutable.Set[wallet.V3]]] = {
    val parser = Defaults.loadForParser(xethFindCacheRichParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val keystoresV3 = OnlyShoeboxKeystoreV3
      val log         = streams.value.log

      val chainId = (ethcfgChainId in config).value

      val address = parser.parsed

      val combined = combinedKeystoresMultiMap( keystoresV3 )
      val out = combined.get( address ).getOrElse( immutable.Set.empty )

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
    val jsonRpcUrl = findNodeJsonRpcUrlTask(warn=true)(config).value
    val timeout    = xethcfgAsyncOperationTimeout.value
    val sender     = (xethFindCurrentSender in config).value.get

    val exchangerConfig = findExchangerConfigTask( config ).value

    doGetTransactionCount( exchangerConfig, log, timeout, sender, jsonrpc.Client.BlockNumber.Pending )
  }

  private def xethOnLoadBannerTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    // val mainEthJsonRpcUrl = findNodeJsonRpcUrlTask(warn=false)(Compile).value
    // val testEthJsonRpcUrl = findNodeJsonRpcUrlTask(warn=false)(Test).value
    // val chainId           = (ethcfgChainId in Compile).value
    // val mbCurrentSender   = (xethFindCurrentSender in Compile).value
    
    log.info( s"sbt-ethereum-${generated.SbtEthereum.Version} successfully initialized (built ${SbtEthereum.BuildTimestamp})" )
    // log.info( s"sbt-ethereum shoebox: '${shoebox.Directory.assert}' <-- Please backup, via 'ethShoeboxBackup' or manually" )
    // log.info( s"sbt-ethereum main json-rpc endpoint configured to '${mainEthJsonRpcUrl}'" )
    // log.info( s"sbt-ethereum test json-rpc endpoint configured to '${testEthJsonRpcUrl}'" )
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
    val keystoresV3  = OnlyShoeboxKeystoreV3
    val nontestConfig = Compile                                      // XXX: if you change this, change the hardcoded Compile value in the line below!
    val nontestChainId = ( Compile / ethcfgChainId ).value // XXX: note the hardcoding of Compile! illegal dynamic reference if i use nontestConfig
    val combined = combinedKeystoresMultiMap( keystoresV3 )
    if ( combined.isEmpty ) {
      def prompt : Option[String] = is.readLine( s"There are no wallets in the sbt-ethereum keystore. Would you like to generate one? [y/n] ", mask = false )

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

      if ( checkInstall ) {
        val extract = Project.extract(s)
        val (_, result) = extract.runTask(ethKeystoreWalletV3Create, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias

        if ( mbDefaultSender( nontestChainId ).isEmpty ) {
          val address = result.address
          def prompt2 : Option[String] = is.readLine( s"Would you like the new address '${hexString(address)}' to be the default sender on chain with ID ${nontestChainId}? [y/n] ", mask = false )

          @tailrec
          def checkSetDefault : Boolean = {
            prompt2 match {
              case None                                  => false
              case Some( str ) if str.toLowerCase == "y" => true
              case Some( str ) if str.toLowerCase == "n" => false
              case _                                     => {
                println( "Please type 'y' or 'n'." )
                checkSetDefault
              }
            }
          }

          if ( checkSetDefault ) {
            extract.runInputTask( nontestConfig / ethAddressAliasSet, s"${DefaultSenderAlias} ${address.hex}", s )
          }
          else {
            println(s"No '${DefaultSenderAlias}' alias defined. To create one later, use the command 'ethAddressAliasSet ${DefaultSenderAlias} <address>'.")
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
    val jsonRpcUrl    = findNodeJsonRpcUrlTask(warn=false)(Compile).value // we use the main (compile) configuration, don't bother with a test json-rpc for compilation

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
      check( s"${EthJsonRpc.KeyPrefix}${jsonRpcUrl}", Compiler.Solidity.EthJsonRpc( jsonRpcUrl ) )
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

  private def combinedKeystoresMultiMap( keystoresV3 : Seq[File] ) : immutable.Map[EthAddress, immutable.Set[wallet.V3]] = {
    def combineMultiMaps( base : immutable.Map[EthAddress, immutable.Set[wallet.V3]], next : immutable.Map[EthAddress, immutable.Set[wallet.V3]] ) : immutable.Map[EthAddress, immutable.Set[wallet.V3]] = {
      val newTuples = next.map { case ( key, valueSet ) =>
        Tuple2( key, valueSet ++ base.get(key).getOrElse( immutable.Set.empty ) )
      }

      (base ++ newTuples)
    }

    keystoresV3
      .map( dir => Failable( wallet.V3.keyStoreMultiMap(dir) ).xdebug( "Failed to read keystore directory: ${dir}" ).recoverWith( immutable.Map.empty[EthAddress,immutable.Set[wallet.V3]] ).get )
      .foldLeft( immutable.Map.empty[EthAddress,immutable.Set[wallet.V3]] )( combineMultiMaps )
  }

  private def mbDefaultSender( chainId : Int ) = shoebox.Database.findAddressByAddressAlias( chainId, DefaultSenderAlias ).get

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

  private def findPrivateKey( log : sbt.Logger, gethWallets : immutable.Set[wallet.V3], credential : String ) : EthPrivateKey = {
    def forceKey = {
      try {
        EthPrivateKey( credential )
      }
      catch {
        case NonFatal(e) => {
          DEBUG.log( s"Converting an Exception that occurred while trying to interpret a credential as hex into a BadCredentialException.", e )
          throw new BadCredentialException()
        }
      }
    }

    if ( gethWallets.isEmpty ) {
      log.info( "No wallet available. Trying passphrase as hex private key." )
      forceKey
    }
    else {
      def tryWallet( gethWallet : wallet.V3 ) : Failable[EthPrivateKey] = Failable {
        val desiredAddress = gethWallet.address
        try {
          wallet.V3.decodePrivateKey( gethWallet, credential )
         } catch {
          case v3e : wallet.V3.Exception => {
            DEBUG.log( s"Converting an Exception that occurred while trying to decode the private key of a geth wallet into a BadCredentialException.", v3e )
            val maybe = forceKey
            if (maybe.toPublicKey.toAddress != desiredAddress) {
              throw new BadCredentialException( desiredAddress )
            } else {
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

  private final val CantReadInteraction = "InteractionService failed to read"

  private final def notConfirmedByUser  = nst( new Exception("Not confirmed by user. Aborted.") )

  private def findCachePrivateKey(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    chainId              : Int,
    address              : EthAddress,
    autoRelockSeconds    : Int,
    userValidateIfCached : Boolean
  ) : EthPrivateKey = {

    def updateCached : EthPrivateKey = {
      // this is ugly and awkward, but it gives time for any log messages to get emitted before prompting for a credential
      // it also slows down automated attempts to guess passwords, i guess...
      Thread.sleep(1000)

      val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "" )( _.fold("")( commasep => s", aliases $commasep" ) )

      log.info( s"Unlocking address '0x${address.hex}' (on chain with ID ${chainId}$aliasesPart)" )

      val credential = readCredential( is, address )

      val extract = Project.extract(state)
      val (_, wallets) = extract.runInputTask(xethLoadWalletsV3For in Compile, address.hex, state) // the config scope of xethLoadWalletV3For doesn't matter here, since we provide hex, not an alias

      val privateKey = findPrivateKey( log, wallets, credential )
      Mutables.CurrentAddress.set( UnlockedAddress( chainId, address, privateKey, System.currentTimeMillis + (autoRelockSeconds * 1000) ) )
      privateKey
    }
    def goodCached : Option[EthPrivateKey] = {
      // caps for value matches rather than variable names
      val ChainId = chainId
      val Address = address
      val now = System.currentTimeMillis
      Mutables.CurrentAddress.get match {
        case UnlockedAddress( ChainId, Address, privateKey, autoRelockTime ) if (now < autoRelockTime ) => { // if chainId and/or ethcfgSender has changed, this will no longer match
          val aliasesPart = commaSepAliasesForAddress( ChainId, Address ).fold( _ => "")( _.fold("")( commasep => s", aliases $commasep" ) )
          val ok = {
            if ( userValidateIfCached ) {
              is.readLine( s"Using sender address '0x${address.hex}' (on chain with ID ${chainId}${aliasesPart}). OK? [y/n] ", false ).getOrElse( throw new Exception( CantReadInteraction ) ).trim().equalsIgnoreCase("y")
            } else {
              true
            }
          }
          if ( ok ) {
            Some( privateKey )
          } else {
            Mutables.CurrentAddress.set( NoAddress )
            throw nst(new SenderNotAvailableException( s"Use of sender address '0x${address.hex}' (on chain with ID ${chainId}${aliasesPart}) vetoed by user." ))
          }
        }
        case _ => { // if we don't match, we reset / forget the cached private key
          Mutables.CurrentAddress.set( NoAddress )
          None
        }
      }
    }

    // special case for testing...
    if ( address == testing.Default.Faucet.Address ) {
      testing.Default.Faucet.PrivateKey
    } else {
      Mutables.CurrentAddress.synchronized {
        goodCached.getOrElse( updateCached )
      }
    }
  }

  private def readConfirmCredential(  log : sbt.Logger, is : sbt.InteractionService, readPrompt : String, confirmPrompt: String = "Please retype to confirm: ", maxAttempts : Int = 3, attempt : Int = 0 ) : String = {
    if ( attempt < maxAttempts ) {
      val credential = is.readLine( readPrompt, mask = true ).getOrElse( throw new Exception( CantReadInteraction ) )
      val confirmation = is.readLine( confirmPrompt, mask = true ).getOrElse( throw new Exception( CantReadInteraction ) )
      if ( credential == confirmation ) {
        credential
      } else {
        log.warn("Entries did not match! Retrying.")
        readConfirmCredential( log, is, readPrompt, confirmPrompt, maxAttempts, attempt + 1 )
      }
    } else {
      throw new Exception( s"After ${attempt} attempts, provided credential could not be confirmed. Bailing." )
    }
  }

  private def transactionApprover( is : sbt.InteractionService, currencyCode : String, config : Configuration )( implicit ec : ExecutionContext ) : EthTransaction.Signed => Future[Unit] = {
    config match {
      case Test => testTransactionApprover( is, currencyCode )( ec )
      case _    => normalTransactionApprover( is, currencyCode )( ec )
    }
  }

  // when using the open test address in Test config, don't bother getting user approval
  private def testTransactionApprover( is : sbt.InteractionService, currencyCode : String )( implicit ec : ExecutionContext ) : EthTransaction.Signed => Future[Unit] = txn => {
    if (txn.sender == testing.Default.Faucet.Address) Future.successful( () ) else normalTransactionApprover( is, currencyCode )( ec )( txn )
  }

  private def normalTransactionApprover( is : sbt.InteractionService, currencyCode : String )( implicit ec : ExecutionContext ) : EthTransaction.Signed => Future[Unit] = txn => Future {

    val gasPrice   = txn.gasPrice.widen
    val gasLimit   = txn.gasLimit.widen
    val valueInWei = txn.value.widen

    val nonce = txn.nonce.widen

    println( s"The transaction you have requested could use up to ${gasLimit} units of gas." )

    val mbEthPrice = priceFeed.ethPriceInCurrency( currencyCode, forceRefresh = true )

    val gweiPerGas = Denominations.GWei.fromWei(gasPrice)
    val gasCostInWei = gasLimit * gasPrice
    val gasCostInEth = Denominations.Ether.fromWei( gasCostInWei )
    val gasCostMessage = {
      val sb = new StringBuilder
      sb.append( s"You would pay ${ gweiPerGas } gwei for each unit of gas, for a maximum cost of ${ gasCostInEth } ether" )
      mbEthPrice match {
        case Some( PriceFeed.Datum( ethPrice, timestamp ) ) => {
          sb.append( s", which is worth ${ gasCostInEth * ethPrice } ${currencyCode} (according to ${priceFeed.source} at ${formatTime( timestamp )})." )
        }
        case None => {
          sb.append( "." )
        }
      }
      sb.toString
    }
    println( gasCostMessage )

    if ( valueInWei != 0 ) {
      val xferInEth = Denominations.Ether.fromWei( valueInWei )
      val maxTotalCostInEth = xferInEth + gasCostInEth
      print( s"You would also send ${xferInEth} ether" )
      mbEthPrice match {
        case Some( PriceFeed.Datum( ethPrice, timestamp ) ) => {
          println( s" (${ xferInEth * ethPrice } ${currencyCode}), for a maximum total cost of ${ maxTotalCostInEth } ether (${maxTotalCostInEth * ethPrice} ${currencyCode})." )
        }
        case None => {
          println( s"for a maximum total cost of ${ maxTotalCostInEth } ether." )
        }
      }
    }

    println( s"The nonce of the transaction would be ${nonce}." )

    @tailrec
    def check : Boolean = {
      val response = is.readLine( "Would you like to submit this transaction? [y/n] ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) )
      response.toLowerCase match {
        case "y" | "yes" => true
        case "n" | "no"  => false
        case _           => {
          println( s"Invalid response '${response}'." )
          check
        }
      }
    }

    if ( check ) {
      val txnHash = hashRLP[EthTransaction]( txn )
      println( s"A transaction with hash '${hexString(txnHash)}' will be submitted. Please wait." )
    }
    else {
      Invoker.throwDisapproved( txn, keepStackTrace = false )
    }
  }( ec )

  private def parseAbi( abiString : String ) = Json.parse( abiString ).as[Abi]

  private def interactiveSetAliasForAddress( chainId : Int )( state : State, log : sbt.Logger, is : sbt.InteractionService, describedAddress : String, address : EthAddress ) : Unit = {
    def rawFetch : String = is.readLine( s"Enter an optional alias for ${describedAddress} (or [return] for none): ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ).trim()
    def validate( alias : String ) : Boolean = parsesAsAddressAlias( alias )

    @tailrec
    def query : Option[String] = {
      rawFetch match {
        case ""                         => None
        case alias if validate( alias ) => Some( alias )
        case alias                      => {
          println( s"'${alias}' is not a valid alias. Please try again." )
          query
        }
      }
    }

    query match {
      case Some( alias ) => {
        val check = shoebox.Database.createUpdateAddressAlias( chainId, alias, address )
        check.fold( _.vomit ){ _ => 
          log.info( s"Alias '${alias}' now points to address '0x${address.hex}' (for chain with ID ${chainId})." )
        }
      }
      case None => log.info( s"No alias set for ${describedAddress}." )
    }
  }

  private def readV3Wallet( is : sbt.InteractionService ) : wallet.V3 = {
    val jsonStr = is.readLine( "V3 Wallet JSON: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) )
    val jsv = Json.parse( jsonStr )
    wallet.V3( jsv.as[JsObject] )
  }

  private def readCredential( is : sbt.InteractionService, address : EthAddress ) : String = {
    is.readLine(s"Enter passphrase or hex private key for address '0x${address.hex}': ", mask = true).getOrElse(throw new Exception("Failed to read a credential")) // fail if we can't get a credential
  }

  private def unknownWallet( loadDirs : Seq[File] ) : Nothing = {
    val dirs = loadDirs.map( _.getAbsolutePath() ).map( "'" + _ + "'" ).mkString(", ")
    throw new Exception( s"Could not find V3 wallet for the specified address in availble keystore directories: ${dirs}" )
  }

  private def assertSomeSender( log : Logger, fsender : Failable[EthAddress] ) : Option[EthAddress] = {
    val onFailed : Failed[EthAddress] => Nothing = failed => {
      val errMsg = {
        val base = "No sender found. Please define a 'defaultSender' alias, or the setting 'ethcfgSender', or use 'ethAddressSenderOverrideSet' for a temporary sender."
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

  // some formatting functions for ascii tables
  private def emptyOrHex( str : String ) = if (str == null) "" else s"0x$str"
  private def blankNull( str : String ) = if (str == null) "" else str
  private def span( len : Int ) = (0 until len).map(_ => "-").mkString

  private def mbWithNonceClause( nonceOverride : Option[Unsigned256] ) = nonceOverride.fold("")( n => s"with nonce ${n.widen} " )

  private def commaSepAliasesForAddress( chainId : Int, address : EthAddress ) : Failable[Option[String]] = {
    shoebox.Database.findAddressAliasesByAddress( chainId, address ).map( seq => if ( seq.isEmpty ) None else Some( seq.mkString( "['","','", "']" ) ) )
  }
  private def leftwardAliasesArrowOrEmpty( chainId : Int, address : EthAddress ) : Failable[String] = {
    commaSepAliasesForAddress( chainId, address ).map( _.fold("")( aliasesStr => s" <-- ${aliasesStr}" ) )
  }

  private def verboseAddress( chainId : Int, address : EthAddress ) : String = {
    val aliasesPart = commaSepAliasesForAddress( chainId, address ).fold( _ => "" )( _.fold("")( str => s"with aliases $str " ) )
    s"'0x${address.hex}' (${aliasesPart}on chain with ID $chainId)"
  }

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
