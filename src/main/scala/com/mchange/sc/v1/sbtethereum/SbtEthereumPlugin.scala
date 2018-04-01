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
import generated._

import java.io.{BufferedInputStream, File, FileInputStream, FilenameFilter}
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import play.api.libs.json.{JsObject, Json}
import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.io._
import com.mchange.sc.v2.util.Platform
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._
import jsonrpc.{Abi,Client,MapStringCompilationContractFormat}
import specification.Denominations
import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations._
import com.mchange.sc.v1.consuela.ethereum.ethabi.{Decoded, Encoder, abiFunctionForFunctionNameAndArgs, callDataForAbiFunctionFromStringArgs, decodeReturnValuesForFunction}
import com.mchange.sc.v1.consuela.ethereum.stub
import com.mchange.sc.v1.consuela.ethereum.jsonrpc.Invoker
import com.mchange.sc.v2.ens
import com.mchange.sc.v1.log.MLogger
import com.mchange.sc.v1.texttable
import scala.annotation.tailrec
import scala.collection._
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.sys.process.{Process, ProcessLogger}
import scala.io.Source
import scala.util.matching.Regex

import scala.concurrent.ExecutionContext
import com.mchange.sc.v2.jsonrpc.Exchanger.Factory.Default

// global implicits
import scala.concurrent.ExecutionContext.Implicits.global
import com.mchange.sc.v2.concurrent.Poller

object SbtEthereumPlugin extends AutoPlugin {

  initializeLoggingConfig()

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally we should try to log through sbt loggers
  private implicit val logger = mlogger( this )

  private trait AddressInfo
  private final case object NoAddress                                                                                                         extends AddressInfo
  private final case class  UnlockedAddress( blockchainId : String, address : EthAddress, privateKey : EthPrivateKey, autoRelockTime : Long ) extends AddressInfo

  private final object Mutables {
    // MT: protected by CurrentAddress' lock
    val CurrentAddress = new AtomicReference[AddressInfo]( NoAddress )

    val SessionSolidityCompilers = new AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]]( None )

    val CurrentSolidityCompiler = new AtomicReference[Option[( String, Compiler.Solidity )]]( None )

    val GasLimitOverride = new AtomicReference[Option[BigInt]]( None )

    val GasPriceOverride = new AtomicReference[Option[BigInt]]( None )

    // MT: protected by SenderOverride's lock
    val SenderOverride = new AtomicReference[Option[ ( String, EthAddress ) ]]( None )

    // MT: protected by TestSenderOverride's lock
    val TestSenderOverride = new AtomicReference[Option[ ( String, EthAddress ) ]]( None )

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

  private val BufferSize = 4096

  private val PollSeconds = 15

  private val PollAttempts = 9

  private val Zero = BigInt(0)

  private val Zero256 = Unsigned256( 0 )

  private val EmptyBytes = List.empty[Byte]

  private val DefaultEthJsonRpcUrl = "http://ethjsonrpc.mchange.com:8545"

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

  object autoImport {

    // settings
    val enscfgNameServiceAddress           = settingKey[EthAddress]   ("The address of the ENS name service smart contract")
    val enscfgNameServiceTld               = settingKey[String]       ("The top-level domain associated with the ENS name service smart contract at 'enscfgNameServiceAddress'.")
    val enscfgNameServiceReverseTld        = settingKey[String]       ("The top-level domain under which reverse lookups are supported in the ENS name service smart contract at 'enscfgNameServiceAddress'.")
    val enscfgNameServicePublicResolver    = settingKey[EthAddress]   ("The address of a publically accessible resolver (if any is available) that can be used to map names to addresses.")

    val ethcfgAutoSpawnContracts           = settingKey[Seq[String]]  ("Names (and optional space-separated constructor args) of contracts compiled within this project that should be deployed automatically.")
    val ethcfgBaseCurrencyCode             = settingKey[String]       ("Currency code for currency in which prices of ETH and other tokens should be displayed.")
    val ethcfgBlockchainId                 = settingKey[String]       ("A name for the network represented by ethJsonRpcUrl (e.g. 'mainnet', 'morden', 'ropsten')")
    val ethcfgEntropySource                = settingKey[SecureRandom] ("The source of randomness that will be used for key generation")
    val ethcfgGasLimitCap                  = settingKey[BigInt]       ("Maximum gas limit to use in transactions")
    val ethcfgGasLimitFloor                = settingKey[BigInt]       ("Minimum gas limit to use in transactions (usually left unset)")
    val ethcfgGasLimitMarkup               = settingKey[Double]       ("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")
    val ethcfgGasPriceCap                  = settingKey[BigInt]       ("Maximum gas limit to use in transactions")
    val ethcfgGasPriceFloor                = settingKey[BigInt]       ("Minimum gas limit to use in transactions (usually left unset)")
    val ethcfgGasPriceMarkup               = settingKey[Double]       ("Fraction by which automatically estimated gas price will be marked up (if not overridden) in executing transactions")
    val ethcfgIncludeLocations             = settingKey[Seq[String]]  ("Directories or URLs that should be searched to resolve import directives, besides the source directory itself")
    val ethcfgJsonRpcUrl                   = settingKey[String]       ("URL of the Ethereum JSON-RPC service build should work with")
    val ethcfgKeystoreAutoRelockSeconds    = settingKey[Int]          ("Number of seconds after which an unlocked private key should automatically relock")
    val ethcfgKeystoreLocationsV3          = settingKey[Seq[File]]    ("Directories from which V3 wallets can be loaded")
    val ethcfgNetcompileUrl                = settingKey[String]       ("Optional URL of an eth-netcompile service, for more reliabe network-based compilation than that available over json-rpc.")
    val ethcfgScalaStubsPackage            = settingKey[String]       ("Package into which Scala stubs of Solidity compilations should be generated")
    val ethcfgSender                       = settingKey[String]       ("The address from which transactions will be sent")
    val ethcfgSoliditySource               = settingKey[File]         ("Solidity source code directory")
    val ethcfgSolidityDestination          = settingKey[File]         ("Location for compiled solidity code and metadata")
    val ethcfgTargetDir                    = settingKey[File]         ("Location in target directory where ethereum artifacts will be placed")
    val ethcfgTransactionReceiptPollPeriod = settingKey[Duration]     ("Length of period after which sbt-ethereum will poll and repoll for a Client.TransactionReceipt after a transaction")
    val ethcfgTransactionReceiptTimeout    = settingKey[Duration]     ("Length of period after which sbt-ethereum will give up on polling for a Client.TransactionReceipt after a transaction")

    val xethcfgEphemeralBlockchains       = settingKey[Seq[String]] ("IDs of blockchains that should be considered ephemeral (so their deployments should not be retained).")
    val xethcfgNamedAbiSource             = settingKey[File]        ("Location where files containing json files containing ABIs for which stubs should be generated. Each as '<stubname>.json'.")
    val xethcfgTestingResourcesObjectName = settingKey[String]      ("The name of the Scala object that will be automatically generated with resources for tests.")
    val xethcfgWalletV3ScryptDkLen        = settingKey[Int]         ("The derived key length parameter used when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptN            = settingKey[Int]         ("The value to use for parameter N when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptR            = settingKey[Int]         ("The value to use for parameter R when generating Scrypt V3 wallets")
    val xethcfgWalletV3ScryptP            = settingKey[Int]         ("The value to use for parameter P when generating Scrypt V3 wallets")
    val xethcfgWalletV3Pbkdf2DkLen        = settingKey[Int]         ("The derived key length parameter used when generating pbkdf2 V3 wallets")
    val xethcfgWalletV3Pbkdf2C            = settingKey[Int]         ("The value to use for parameter C when generating pbkdf2 V3 wallets")

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

    val ethAddressAliasDrop           = inputKey[Unit]                             ("Drops an alias for an ethereum address from the sbt-ethereum repository database.")
    val ethAddressAliasList           = taskKey [Unit]                             ("Lists aliases for ethereum addresses that can be used in place of the hex address in many tasks.")
    val ethAddressAliasSet            = inputKey[Unit]                             ("Defines (or redefines) an alias for an ethereum address that can be used in place of the hex address in many tasks.")
    val ethAddressBalance             = inputKey[BigDecimal]                       ("Computes the balance in ether of a given address, or of current sender if no address is supplied")
    val ethAddressPing                = inputKey[Option[Client.TransactionReceipt]]("Sends 0 ether from current sender to an address, by default the senser address itself")
    val ethAddressSenderEffective     = taskKey[Failable[EthAddress]]              ("Prints the address that will be used to send ether or messages, and explains where and how it has ben set.")
    val ethAddressSenderOverrideDrop  = taskKey [Unit]                             ("Removes any sender override, reverting to any 'ethcfgSender' or defaultSender that may be set.")
    val ethAddressSenderOverrideSet   = inputKey[Unit]                             ("Sets an ethereum address to be used as sender in prefernce to any 'ethcfgSender' or defaultSender that may be set.")
    val ethAddressSenderOverridePrint = taskKey [Unit]                             ("Displays any sender override, if set.")

    val ethContractAbiForget           = inputKey[Unit] ("Removes an ABI definition that was added to the sbt-ethereum database via ethContractAbiMemorize")
    val ethContractAbiList             = inputKey[Unit] ("Lists the addresses for which ABI definitions have been memorized. (Does not include our own deployed compilations, see 'ethContractCompilationsList'")
    val ethContractAbiMemorize         = taskKey [Unit] ("Prompts for an ABI definition for a contract and inserts it into the sbt-ethereum database")
    val ethContractAbiPrint            = inputKey[Unit] ("Prints the contract ABI associated with a provided address, if known.")
    val ethContractAbiPrintPretty      = inputKey[Unit] ("Pretty prints the contract ABI associated with a provided address, if known.")
    val ethContractAbiPrintCompact     = inputKey[Unit] ("Compactly prints the contract ABI associated with a provided address, if known.")
    val ethContractCompilationsCull    = taskKey [Unit] ("Removes never-deployed compilations from the repository database.")
    val ethContractCompilationsInspect = inputKey[Unit] ("Dumps to the console full information about a compilation, based on either a code hash or contract address")
    val ethContractCompilationsList    = taskKey [Unit] ("Lists summary information about compilations known in the repository")
    val ethContractSpawn               = inputKey[immutable.Seq[Tuple2[String,Either[EthHash,Client.TransactionReceipt]]]](""""Spawns" (deploys) the specified named contract, or contracts via 'ethcfgAutoSpawnContracts'""")

    val ethDebugGanacheStart = taskKey[Unit] (s"Starts a local ganache environment (if the command '${testing.Default.Ganache.Executable}' is in your PATH)")
    val ethDebugGanacheStop  = taskKey[Unit] ("Stops any local ganache environment that may have been started previously")

    val ethGasLimitOverrideSet   = inputKey[Unit] ("Defines a value which overrides the usual automatic marked-up estimation of gas required for a transaction.")
    val ethGasLimitOverrideDrop  = taskKey [Unit] ("Removes any previously set gas override, reverting to the usual automatic marked-up estimation of gas required for a transaction.")
    val ethGasLimitOverridePrint = taskKey [Unit] ("Displays the current gas override, if set.")
    val ethGasPriceOverrideSet   = inputKey[Unit] ("Defines a value which overrides the usual automatic marked-up default gas price that will be paid for a transaction.")
    val ethGasPriceOverrideDrop  = taskKey [Unit] ("Removes any previously set gas price override, reverting to the usual automatic marked-up default.")
    val ethGasPriceOverridePrint = taskKey [Unit] ("Displays the current gas price override, if set.")

    val ethKeystoreList = taskKey[immutable.SortedMap[EthAddress,immutable.SortedSet[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined")
    val ethKeystorePrivateKeyReveal = inputKey[Unit]      ("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")
    val ethKeystoreWalletV3Create   = taskKey [wallet.V3] ("Generates a new V3 wallet, using ethcfgEntropySource as a source of randomness")
    val ethKeystoreWalletV3Memorize = taskKey [Unit]      ("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")
    val ethKeystoreWalletV3Print    = inputKey[Unit]      ("Prints V3 wallet as JSON to the console.")
    val ethKeystoreWalletV3Validate = inputKey[Unit]      ("Verifies that a V3 wallet can be decoded for an address, and decodes to the expected address.")

    val ethSolidityCompilerInstall = inputKey[Unit] ("Installs a best-attempt platform-specific solidity compiler into the sbt-ethereum repository (or choose a supported version)")
    val ethSolidityCompilerPrint   = taskKey [Unit] ("Displays currently active Solidity compiler")
    val ethSolidityCompilerSelect  = inputKey[Unit] ("Manually select among solidity compilers available to this project")

    val ethTransactionInvoke = inputKey[Option[Client.TransactionReceipt]]           ("Calls a function on a deployed smart contract")
    val ethTransactionSend   = inputKey[Option[Client.TransactionReceipt]]           ("Sends ether from current sender to a specified account, format 'ethTransactionSend <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")
    val ethTransactionView   = inputKey[(Abi.Function,immutable.Seq[Decoded.Value])] ("Makes a call to a constant function, consulting only the local copy of the blockchain. Burns no Ether. Returns the latest available result.")

    // xeth tasks

    val xethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")
    val xethFindCacheAddressParserInfo = taskKey[AddressParserInfo]("Finds and caches information (aliases, ens info) needed by address parsers")
    val xethFindCacheSessionSolidityCompilerKeys = taskKey[immutable.Set[String]]("Finds and caches keys for available compilers for use by the parser for ethSolidityCompilerSelect")
    val xethFindCacheSeeds = taskKey[immutable.Map[String,MaybeSpawnable.Seed]]("Finds and caches compiled, deployable contracts, omitting ambiguous duplicates. Triggered by compileSolidity")
    val xethFindCurrentSender = taskKey[Failable[EthAddress]]("Finds the address that should be used to send ether or messages")
    val xethFindCurrentSolidityCompiler = taskKey[Compiler.Solidity]("Finds and caches keys for available compilers for use parser for ethSolidityCompilerSelect")
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
    val xethLoadWalletV3 = taskKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3 for current sender")
    val xethLoadWalletV3For = inputKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")
    val xethNamedAbis = taskKey[immutable.Map[String,Abi]]("Loads any named ABIs from the 'xethcfgNamedAbiSource' directory")
    val xensClient = taskKey[ens.Client]("Loads an ENS client instance.")
    val xethNextNonce = taskKey[BigInt]("Finds the next nonce for the current sender")
    val xethSqlQueryRepositoryDatabase = inputKey[Unit]("Primarily for debugging. Query the internal repository database.")
    val xethSqlUpdateRepositoryDatabase = inputKey[Unit]("Primarily for development and debugging. Update the internal repository database with arbitrary SQL.")
    val xethTriggerDirtyAliasCache = taskKey[Unit]("Indirectly provokes an update of the cache of aliases used for tab completions.")
    val xethTriggerDirtySolidityCompilerList = taskKey[Unit]("Indirectly provokes an update of the cache of aavailable solidity compilers used for tab completions.")
    val xethUpdateContractDatabase = taskKey[Boolean]("Integrates newly compiled contracts into the contract database. Returns true if changes were made.")
    val xethUpdateSessionSolidityCompilers = taskKey[immutable.SortedMap[String,Compiler.Solidity]]("Finds and tests potential Solidity compilers to see which is available.")

    // unprefixed keys

    val compileSolidity = taskKey[Unit]("Compiles solidity files")

  } // end object autoImport


  import autoImport._

  // commands

  private val ethDebugGanacheRestartCommand = Command.command( "ethDebugGanacheRestart" ) { state =>
    "ethDebugGanacheStop" :: "ethDebugGanacheStart" :: state
  }

  private val ethDebugGanacheTestCommand = Command.command( "ethDebugGanacheTest" ) { state =>
    "Test/compile" :: "ethDebugGanacheStop" :: "ethDebugGanacheStart" :: "Test/ethContractSpawn" :: "test" :: "ethDebugGanacheStop" :: state
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

    ethcfgBlockchainId in Compile := MainnetIdentifier,

    ethcfgBlockchainId in Test := GanacheIdentifier,

    ethcfgEntropySource := new java.security.SecureRandom,

    ethcfgGasLimitMarkup := 0.2,

    ethcfgGasPriceMarkup := 0.0, // by default, use conventional gas price

    ethcfgIncludeLocations := Nil,

    // thanks to Mike Slinn for suggesting these external defaults
    ethcfgJsonRpcUrl := {
      def mbInfura               = ExternalValue.EthInfuraToken.map(token => s"https://mainnet.infura.io/$token")
      def mbSpecifiedDefaultNode = ExternalValue.EthDefaultNode

      (mbInfura orElse mbSpecifiedDefaultNode).getOrElse( DefaultEthJsonRpcUrl )
    },

    ethcfgJsonRpcUrl in Test := DefaultTestEthJsonRpcUrl,

    ethcfgKeystoreAutoRelockSeconds := 300,

    ethcfgKeystoreLocationsV3 := {
      def debug( location : String ) : String = s"Failed to find V3 keystore in ${location}"
      def listify( fd : Failable[File] ) = fd.fold( _ => Nil, f => List(f) )
      listify( repository.Keystore.V3.Directory.xdebug( debug("sbt-ethereum repository") ) ) ::: listify( clients.geth.KeyStore.Directory.xdebug( debug("geth home directory") ) ) ::: Nil
    },

    ethcfgSoliditySource in Compile := (sourceDirectory in Compile).value / "solidity",

    ethcfgSoliditySource in Test := (sourceDirectory in Test).value / "solidity",

    ethcfgSolidityDestination in Compile := (ethcfgTargetDir in Compile).value / "solidity",

    ethcfgSolidityDestination in Test := (ethcfgTargetDir in Test).value / "solidity",

    ethcfgTargetDir in Compile := (target in Compile).value / "ethereum",

    ethcfgTargetDir in Test := (ethcfgTargetDir in Compile).value / "test",

    ethcfgTransactionReceiptPollPeriod := 3.seconds,

    ethcfgTransactionReceiptTimeout := 2.minutes,

    // xeth settings

    xethcfgEphemeralBlockchains := immutable.Seq( GanacheIdentifier ),

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

    ethAddressAliasDrop in Compile := { ethAddressAliasDropTask( Compile ).evaluated },

    ethAddressAliasDrop in Test := { ethAddressAliasDropTask( Test ).evaluated },

    ethAddressAliasList in Compile := { ethAddressAliasListTask( Compile ).value },

    ethAddressAliasList in Test := { ethAddressAliasListTask( Test ).value },

    ethAddressAliasSet in Compile := { ethAddressAliasSetTask( Compile ).evaluated },

    ethAddressAliasSet in Test := { ethAddressAliasSetTask( Test ).evaluated },

    ethAddressBalance in Compile := { ethAddressBalanceTask( Compile ).evaluated },

    ethAddressBalance in Test := { ethAddressBalanceTask( Test ).evaluated },

    ethAddressPing in Compile := { ethAddressPingTask( Compile ).evaluated },

    ethAddressPing in Test := { ethAddressPingTask( Test ).evaluated },

    ethAddressSenderEffective in Compile := { ethAddressSenderEffectiveTask( Compile ).value },

    ethAddressSenderEffective in Test := { ethAddressSenderEffectiveTask( Test ).value },

    ethAddressSenderOverrideDrop in Compile := { ethAddressSenderOverrideDropTask( Compile ).value },

    ethAddressSenderOverrideDrop in Test := { ethAddressSenderOverrideDropTask( Test ).value },

    ethAddressSenderOverridePrint in Compile := { ethAddressSenderOverridePrintTask( Compile ).value },

    ethAddressSenderOverridePrint in Test := { ethAddressSenderOverridePrintTask( Test ).value },

    ethAddressSenderOverrideSet in Compile := { ethAddressSenderOverrideSetTask( Compile ).evaluated },

    ethAddressSenderOverrideSet in Test := { ethAddressSenderOverrideSetTask( Test ).evaluated },

    ethContractAbiForget in Compile := { ethContractAbiForgetTask( Compile ).evaluated },

    ethContractAbiForget in Test := { ethContractAbiForgetTask( Test ).evaluated },

    ethContractAbiList in Compile := { ethContractAbiListTask( Compile ).evaluated },

    ethContractAbiList in Test := { ethContractAbiListTask( Test ).evaluated },

    ethContractAbiMemorize in Compile := { ethContractAbiMemorizeTask( Compile ).value },

    ethContractAbiMemorize in Test := { ethContractAbiMemorizeTask( Test ).value },

    ethContractAbiPrint in Compile := { ethContractAbiPrintTask( Compile ).evaluated },

    ethContractAbiPrint in Test := { ethContractAbiPrintTask( Test ).evaluated },

    ethContractAbiPrintCompact in Compile := { ethContractAbiPrintCompactTask( Compile ).evaluated },

    ethContractAbiPrintCompact in Test := { ethContractAbiPrintCompactTask( Test ).evaluated },

    ethContractAbiPrintPretty in Compile := { ethContractAbiPrintPrettyTask( Compile ).evaluated },

    ethContractAbiPrintPretty in Test := { ethContractAbiPrintPrettyTask( Test ).evaluated },

    ethContractCompilationsCull := { ethContractCompilationsCullTask.value },

    ethContractCompilationsInspect in Compile := { ethContractCompilationsInspectTask( Compile ).evaluated },

    ethContractCompilationsInspect in Test := { ethContractCompilationsInspectTask( Test ).evaluated },

    ethContractCompilationsList := { ethContractCompilationsListTask.value },

    ethContractSpawn in Compile := { ethContractSpawnTask( Compile ).evaluated },

    ethContractSpawn in Test := { ethContractSpawnTask( Test ).evaluated },

    ethDebugGanacheStart in Test := { ethDebugGanacheStartTask.value },

    ethDebugGanacheStop in Test := { ethDebugGanacheStopTask.value },

    // we don't scope the gas override tasks for now
    // since any gas override gets used in tests as well as other contexts
    // we may bifurcate and scope this in the future
    ethGasLimitOverrideSet := { ethGasLimitOverrideSetTask.evaluated },

    ethGasLimitOverrideDrop := { ethGasLimitOverrideDropTask.value },

    ethGasLimitOverridePrint := { ethGasLimitOverridePrintTask.value },

    ethGasPriceOverrideSet := { ethGasPriceOverrideSetTask.evaluated },

    ethGasPriceOverrideDrop := { ethGasPriceOverrideDropTask.value },

    ethGasPriceOverridePrint := { ethGasPriceOverridePrintTask.value },

    ethKeystoreList in Compile := { ethKeystoreListTask( Compile ).value },

    ethKeystoreList in Test := { ethKeystoreListTask( Test ).value },

    ethKeystorePrivateKeyReveal in Compile := { ethKeystorePrivateKeyRevealTask( Compile ).evaluated },

    ethKeystorePrivateKeyReveal in Test := { ethKeystorePrivateKeyRevealTask( Test ).evaluated },

    ethKeystoreWalletV3Create := { xethKeystoreWalletV3CreateScrypt.value },

    ethKeystoreWalletV3Memorize := { ethKeystoreWalletV3MemorizeTask.value },

    ethKeystoreWalletV3Print in Compile := { ethKeystoreWalletV3PrintTask( Compile ).evaluated },

    ethKeystoreWalletV3Print in Test := { ethKeystoreWalletV3PrintTask( Test ).evaluated },

    ethKeystoreWalletV3Validate in Compile := { ethKeystoreWalletV3ValidateTask( Compile ).evaluated },

    ethKeystoreWalletV3Validate in Test := { ethKeystoreWalletV3ValidateTask( Test ).evaluated },

    ethSolidityCompilerSelect in Compile := { ethSolidityCompilerSelectTask.evaluated },

    ethSolidityCompilerInstall in Compile := { ethSolidityCompilerInstallTask.evaluated },

    ethSolidityCompilerPrint in Compile := { ethSolidityCompilerPrintTask.value },

    ethTransactionInvoke in Compile := { ethTransactionInvokeTask( Compile ).evaluated },

    ethTransactionInvoke in Test := { ethTransactionInvokeTask( Test ).evaluated },

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

    xethFindCacheAddressParserInfo in Compile := { (xethFindCacheAddressParserInfoTask( Compile ).storeAs( xethFindCacheAddressParserInfo in Compile ).triggeredBy( xethTriggerDirtyAliasCache )).value },

    xethFindCacheAddressParserInfo in Test := { (xethFindCacheAddressParserInfoTask( Test ).storeAs( xethFindCacheAddressParserInfo in Test ).triggeredBy( xethTriggerDirtyAliasCache )).value },

    xethFindCacheSeeds in Compile := { (xethFindCacheSeedsTask( Compile ).storeAs( xethFindCacheSeeds in Compile ).triggeredBy( compileSolidity in Compile )).value },

    xethFindCacheSeeds in Test := { (xethFindCacheSeedsTask( Test ).storeAs( xethFindCacheSeeds in Test ).triggeredBy( compileSolidity in Test )).value },

    xethFindCacheSessionSolidityCompilerKeys in Compile := { (xethFindCacheSessionSolidityCompilerKeysTask.storeAs( xethFindCacheSessionSolidityCompilerKeys in Compile ).triggeredBy( xethTriggerDirtySolidityCompilerList )).value },

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

    xethLoadWalletV3 in Compile := { xethLoadWalletV3Task( Compile ).value },

    xethLoadWalletV3 in Test := { xethLoadWalletV3Task( Test ).value },

    xethLoadWalletV3For in Compile := { xethLoadWalletV3ForTask( Compile ).evaluated },

    xethLoadWalletV3For in Test := { xethLoadWalletV3ForTask( Test ).evaluated },

    xethNamedAbis in Compile := { xethNamedAbisTask( Compile ).value },

    xethNamedAbis in Test := { xethNamedAbisTask( Test ).value },

    xethNextNonce in Compile := { xethNextNonceTask( Compile ).value },

    xethNextNonce in Test := { xethNextNonceTask( Test ).value },

    xethSqlQueryRepositoryDatabase := { xethSqlQueryRepositoryDatabaseTask.evaluated }, // we leave this unscoped, just because scoping it to Compile seems weird

    xethSqlUpdateRepositoryDatabase := { xethSqlUpdateRepositoryDatabaseTask.evaluated }, // we leave this unscoped, just because scoping it to Compile seems weird

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
        val state1 = attemptAdvanceStateWithTask( xethFindCacheAddressParserInfo in Compile,          lastState )
        val state2 = attemptAdvanceStateWithTask( xethFindCacheAddressParserInfo in Test,             state1    )
        val state3 = attemptAdvanceStateWithTask( xethFindCacheSessionSolidityCompilerKeys in Compile, state2    )
        state3
      }
      newF
    },

    onUnload in Global := {
      val origF : State => State = (onUnload in Global).value
      val newF  : State => State = ( state : State ) => {
        val lastState = origF( state )
        Mutables.reset()
        repository.Database.reset()
        util.Parsers.reset()
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

  private def findPrivateKeyTask( config : Configuration ) : Initialize[Task[EthPrivateKey]] = Def.task {
    val s = state.value
    val log = streams.value.log
    val is = interactionService.value
    val blockchainId = (ethcfgBlockchainId in config).value
    val caller = (xethFindCurrentSender in config).value.get
    val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
    findCachePrivateKey(s, log, is, blockchainId, caller, autoRelockSeconds, true )
  }

  private def findTransactionLoggerTask( config : Configuration ) : Initialize[Task[Invoker.TransactionLogger]] = Def.task {
    val blockchainId = (ethcfgBlockchainId in config).value

    ( tle : Invoker.TransactionLogEntry, ec : ExecutionContext ) => Future (
      repository.TransactionLog.logTransaction( blockchainId, tle.jsonRpcUrl, tle.transaction, tle.transactionHash ).get
    )( ec )
  }

  // task definitions

  // ens tasks

  private def ensAddressLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = Def.inputTask {
    val blockchainId = (ethcfgBlockchainId in config).value
    val ensClient    = ( config / xensClient).value
    val name         = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val mbAddress      = ensClient.address( name )

    mbAddress match {
      case Some( address ) => println( s"The name '${name}' resolves to address ${verboseAddress(blockchainId, address)}." )
      case None            => println( s"The name '${name}' does not currently resolve to any address." )
    }

    mbAddress
  }

  private def ensAddressSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheAddressParserInfo)( genEnsNameAddressParser )

    Def.inputTask {
      val log               = streams.value.log
      val privateKey        = findPrivateKeyTask( config ).value
      val blockchainId      = ( config / ethcfgBlockchainId ).value
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
      log.info( s"The name '${ensName}' now resolves to ${verboseAddress(blockchainId, address)}." )
    }
  }

  private def ensAuctionBidListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    import repository.Schema_h2.Table.EnsBidStore.RawBid

    val blockchainId = (ethcfgBlockchainId in config).value
    val rawBids = repository.Database.ensAllRawBidsForBlockchainId( blockchainId ).get
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
    val blockchainId = (config / ethcfgBlockchainId).value
    val ensClient = ( config / xensClient ).value
    val privateKey = findPrivateKeyTask( config ).value

    implicit val bidStore = repository.Database.ensBidStore( blockchainId, ensClient.tld, ensClient.nameServiceAddress )

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

      repository.BidLog.logBid( bid, blockchainId, ensClient.tld, ensClient.nameServiceAddress )

      log.warn( s"A bid has been placed on name '${name}' for ${valueInWei} wei." )
      mbOverpaymentInWei.foreach( opw => log.warn( s"An additional ${opw} wei was transmitted to obscure the value of your bid." ) )
      log.warn( s"YOU MUST REVEAL THIS BID BETWEEN ${ formatInstant(revealStart) } AND ${ formatInstant(auctionFinalized) }. IF YOU DO NOT, YOUR FUNDS WILL BE LOST!" )
      log.warn(  "Bid details, which are required to reveal, have been automatically stored in the sbt-ethereum repository," )
      log.warn( s"and will be provided automatically if revealed by this client, configured with blockchain ID '${blockchainId}'." )
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
    val blockchainId = (config / ethcfgBlockchainId).value
    val ensClient = ( config / xensClient).value
    val privateKey = findPrivateKeyTask( config ).value
    val tld = ensClient.tld
    val ensAddress = ensClient.nameServiceAddress
    val is = interactionService.value

    implicit val bidStore = repository.Database.ensBidStore( blockchainId, tld, ensAddress )

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
    val blockchainId = (ethcfgBlockchainId in config).value
    val ensClient    = ( config / xensClient).value
    val name         = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val mbOwner      = ensClient.owner( name )

    mbOwner match {
      case Some( address ) => println( s"The name '${name}' is owned by address ${verboseAddress(blockchainId, address)}'." )
      case None            => println( s"No owner has been assigned to the name '${name}'." )
    }

    mbOwner
  }

  private def ensOwnerSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheAddressParserInfo)( genEnsNameOwnerAddressParser )

    Def.inputTask {
      val log          = streams.value.log
      val privateKey   = findPrivateKeyTask( config ).value
      val blockchainId = (config / ethcfgBlockchainId).value
      val ensClient    = ( config / xensClient).value
      val ( ensName, ownerAddress ) = parser.parsed
      ensClient.setOwner( privateKey, ensName, ownerAddress )
      log.info( s"The name '${ensName}' is now owned by ${verboseAddress(blockchainId, ownerAddress)}. (However, this has not affected the Deed owner associated with the name!)" )
    }
  }

  private def ensResolverLookupTask( config : Configuration ) : Initialize[InputTask[Option[EthAddress]]] = Def.inputTask {
    val blockchainId = (ethcfgBlockchainId in config).value
    val ensClient    = ( config / xensClient).value
    val name         = ensNameParser( (config / enscfgNameServiceTld).value ).parsed // see https://github.com/sbt/sbt/issues/1993
    val mbResolver   = ensClient.resolver( name )

    mbResolver match {
      case Some( address ) => println( s"The name '${name}' is associated with a resolver at address ${verboseAddress(blockchainId, address)}'." )
      case None            => println( s"No resolver has been associated with the name '${name}'." )
    }

    mbResolver
  }

  private def ensResolverSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(config / xethFindCacheAddressParserInfo)( genEnsNameResolverAddressParser )

    Def.inputTask {
      val log          = streams.value.log
      val privateKey   = findPrivateKeyTask( config ).value
      val blockchainId = (config / ethcfgBlockchainId).value
      val ensClient    = ( config / xensClient).value
      val ( ensName, resolverAddress ) = parser.parsed
      ensClient.setResolver( privateKey, ensName, resolverAddress )
      log.info( s"The name '${ensName}' is now set to be resolved by a contract at ${verboseAddress(blockchainId, resolverAddress)}." )
    }
  }

  // eth tasks

  private def ethAddressAliasDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genAliasParser )

    Def.inputTaskDyn {
      val log = streams.value.log
      val blockchainId = (ethcfgBlockchainId in config).value

      // not sure why, but without this xethFindCacheAddressParserInfo, which should be triggered by the parser,
      // sometimes fails initialize the parser
      val ensureAliases = (xethFindCacheAddressParserInfo in config)

      val alias = parser.parsed
      val check = repository.Database.dropAlias( blockchainId, alias ).get // assert no database problem
      if (check) log.info( s"Alias '${alias}' successfully dropped (for blockchain '${blockchainId}').")
      else log.warn( s"Alias '${alias}' is not defined (on blockchain '${blockchainId}'), and so could not be dropped." )

      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethAddressAliasListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val blockchainId = (ethcfgBlockchainId in config).value
    val faliases = repository.Database.findAllAliases( blockchainId )
    faliases.fold(
      _ => log.warn("Could not read aliases from repository database."),
      aliases => aliases.foreach { case (alias, address) => println( s"${alias} -> 0x${address.hex}" ) }
    )
  }

  private def ethAddressAliasSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genNewAliasParser )

    Def.inputTaskDyn {
      val log = streams.value.log
      val blockchainId = (ethcfgBlockchainId in config).value
      val ( alias, address ) = parser.parsed
      val check = repository.Database.createUpdateAlias( blockchainId, alias, address )
      check.fold(
        _.vomit,
        _ => {
          log.info( s"Alias '${alias}' now points to address '${address.hex}' (for blockchain '${blockchainId}')." )
        }
      )

      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }
  }

  private def ethAddressBalanceTask( config : Configuration ) : Initialize[InputTask[BigDecimal]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genOptionalGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val jsonRpcUrl       = (ethcfgJsonRpcUrl in config).value
      val baseCurrencyCode = ethcfgBaseCurrencyCode.value
      val mbAddress        = parser.parsed
      val address          = mbAddress.getOrElse( (xethFindCurrentSender in config).value.get )
      val result           = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc.Client.BlockNumber.Latest, Denominations.Ether )
      val ethValue         = result.denominated

      priceFeed.ethPriceInCurrency( baseCurrencyCode ).foreach { datum =>
        val value = ethValue * datum.price
        val roundedValue = value.setScale(2, BigDecimal.RoundingMode.HALF_UP )
        println( s"This corresponds to approximately ${roundedValue} ${baseCurrencyCode} (at a rate of ${datum.price} ${baseCurrencyCode} per ETH, retrieved at ${ formatTime( datum.timestamp ) } from ${priceFeed.source})" )
      }

      ethValue
    }
  }

  private def ethAddressPingTask( config : Configuration ) : Initialize[InputTask[Option[Client.TransactionReceipt]]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genOptionalGenericAddressParser )

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
      val (_, result) = extract.runInputTask(ethTransactionSend in config, sendArgs, s)

      val recipientStr =  mbTo.fold( "itself" )( addr => "'0x${addr.hex}'" )

      val out = result
      out.fold( log.warn( s"""Ping failed! Our attempt to send 0 ether from '0x${from.hex}' to ${ recipientStr } may or may not eventually succeed, but we've timed out before hearing back.""" ) ) { receipt =>
        log.info( "Ping succeeded!" )
        log.info( s"Sent 0 ether from '${from.hex}' to ${ recipientStr } in transaction '0x${receipt.transactionHash.hex}'" )
      }
      out
    }
  }

  private def ethAddressSenderEffectiveTask( config : Configuration ) : Initialize[Task[Failable[EthAddress]]] = _xethFindCurrentSenderTask( printEffectiveSender = true )( config )

  private def ethAddressSenderOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val configSenderOverride = senderOverride( config )
    configSenderOverride.synchronized {
      val log = streams.value.log
      Mutables.SenderOverride.set( None )
      log.info("No sender override is now set. Effective sender will be determined by 'ethcfgSender' setting, the System property 'eth.sender', the environment variable 'ETH_SENDER', or a 'defaultSender' alias.")
    }
  }

  private def ethAddressSenderOverridePrintTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    val blockchainId = (ethcfgBlockchainId in config).value

    val mbSenderOverride = getSenderOverride( config )( log, blockchainId )

    val message = mbSenderOverride.fold( s"No sender override is currently set (for configuration '${config}')." ) { address =>
      val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s", aliases $str)" ) )
      s"A sender override is set, address '${address.hex}' (on blockchain '$blockchainId'${aliasesPart}, configuration '${config}')."
    }

    log.info( message )
  }

  private def ethAddressSenderOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genGenericAddressParser )
    val configSenderOverride = senderOverride( config )

    Def.inputTask {
      configSenderOverride.synchronized {
        val log = streams.value.log
        val blockchainId = (ethcfgBlockchainId in config).value
        val address = parser.parsed
        val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s", aliases $str)" ) )

        configSenderOverride.set( Some( ( blockchainId, address ) ) )

        log.info( s"Sender override set to '0x${address.hex}' (on blockchain '$blockchainId'${aliasesPart})." )
      }
    }
  }

  private def ethContractAbiForgetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val blockchainId = (ethcfgBlockchainId in config).value
      val log = streams.value.log
      val address = parser.parsed
      val found = repository.Database.deleteMemorizedContractAbi( blockchainId, address ).get // throw an Exception if there's a database issue
      if ( found ) {
        log.info( s"Previously memorized ABI for contract with address '0x${address.hex}' (on blockchain '${blockchainId}') has been forgotten." )
      } else {
        val mbDeployment = repository.Database.deployedContractInfoForAddress( blockchainId, address ).get  // throw an Exception if there's a database issue
        mbDeployment match {
          case Some( _ ) => throw new SbtEthereumException( s"Contract at address '0x${address.hex}' (on blockchain '${blockchainId}') is not a memorized ABI but our own deployment. Cannot forget." )
          case None      => throw new SbtEthereumException( s"We have not memorized an ABI for the contract at address '0x${address.hex}' (on blockchain '${blockchainId}')." )
        }
      }
    }
  }

  private def ethContractAbiAnyPrintTask( pretty : Boolean )( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo)( genGenericAddressParser )

    Def.inputTask {
      val blockchainId = (ethcfgBlockchainId in config).value
      val log = streams.value.log
      val address = parser.parsed
      val mbAbi = mbAbiForAddress( blockchainId, address )
      mbAbi match {
        case None        => println( s"No contract ABI known for address '0x${address.hex}'." )
        case Some( abi ) => {
          println( s"Contract ABI for address '0x${address.hex}':" )
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
    val blockchainId = (ethcfgBlockchainId in config).value
    val log = streams.value.log

    val mbRegex = regexParser( defaultToCaseInsensitive = true ).parsed

    val memorizedAddresses = repository.Database.getMemorizedContractAbiAddresses( blockchainId ).get
    val deployedContracts = repository.Database.allDeployedContractInfosForBlockchainId( blockchainId ).get

    val allRecords = {
      val memorizedRecords = memorizedAddresses.map( address => AbiListRecord( address, AbiListRecord.Memorized, repository.Database.findAliasesByAddress( blockchainId, address ).get ) )
      val deployedRecords  = {
        deployedContracts
          .filter( _.mbAbi.nonEmpty )
          .map( dci => AbiListRecord( dci.contractAddress, AbiListRecord.Deployed( dci.mbName ), repository.Database.findAliasesByAddress( blockchainId, dci.contractAddress ).get ) )
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
      val source = if ( record.source == AbiListRecord.Memorized ) "Memorized" else "Spawned"
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

  private def ethContractAbiMemorizeTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val blockchainId = (ethcfgBlockchainId in config).value
    val log = streams.value.log
    val is = interactionService.value
    val ( address, abi ) = readAddressAndAbi( log, is )
    val mbKnownCompilation = repository.Database.deployedContractInfoForAddress( blockchainId, address ).get
    mbKnownCompilation match {
      case Some( knownCompilation ) => {
        log.info( s"The contract at address '$address' was already associated with a deployed compilation." )
        // TODO, maybe, check if the deployed compilation includes a non-null ABI
      }
      case None => {
        repository.Database.setMemorizedContractAbi( blockchainId, address, abi  ).get // throw an Exception if there's a database issue
        log.info( s"ABI is now known for the contract at address ${address.hex}" )
      }
    }
  }

  private def ethContractCompilationsCullTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val fcount = repository.Database.cullUndeployedCompilations()
    val count = fcount.get
    log.info( s"Removed $count undeployed compilations from the repository database." )
  }

  private def ethContractCompilationsInspectTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genContractAddressOrCodeHashParser )

    Def.inputTask {
      val blockchainId = (ethcfgBlockchainId in config).value
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
      def addressSection( title : String, body : Set[ (String,EthAddress) ] ) : Unit = {
        val ordered = immutable.SortedSet.empty[String] ++ body.map { tup =>
          val ( blockchainId, address ) = tup
          s"0x${address.hex} (on blockchain '${blockchainId}')"
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
          val mbinfo = repository.Database.deployedContractInfoForAddress( blockchainId, address ).get // throw any db problem
          mbinfo.fold( println( s"Contract with address '$address' not found." ) ) { info =>
            section( s"Contract Address (on blockchain '${info.blockchainId}')", Some( info.contractAddress.hex ), true )
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
            jsonSection( "ABI Definition", info.mbAbi )
            jsonSection( "User Documentation", info.mbUserDoc )
            jsonSection( "Developer Documentation", info.mbDeveloperDoc )
            section( "Metadata", info.mbMetadata )
          }
        }
        case Right( hash ) => {
          val mbinfo = repository.Database.compilationInfoForCodeHash( hash ).get // throw any db problem
          mbinfo.fold( println( s"Contract with code hash '$hash' not found." ) ) { info =>
            section( "Code Hash", Some( hash.hex ), true )
            section( "Code", Some( info.code ), true )
            section( "Contract Name", info.mbName )
            section( "Contract Source", info.mbSource )
            section( "Contract Language", info.mbLanguage )
            section( "Language Version", info.mbLanguageVersion )
            section( "Compiler Version", info.mbCompilerVersion )
            section( "Compiler Options", info.mbCompilerOptions )
            jsonSection( "ABI Definition", info.mbAbi )
            jsonSection( "User Documentation", info.mbUserDoc )
            jsonSection( "Developer Documentation", info.mbDeveloperDoc )
            section( "Metadata", info.mbMetadata )
            addressSection( "Deployments", repository.Database.blockchainIdContractAddressesForCodeHash( hash ).get )
          }
        }
      }
      println( cap )
      println()
    }
  }

  private def ethContractCompilationsListTask : Initialize[Task[Unit]] = Def.task {
    val contractsSummary = repository.Database.contractsSummary.get // throw for any db problem

    val Blockchain = "Blockchain"
    val Address    = "Contract Address"
    val Name       = "Name"
    val CodeHash   = "Code Hash"
    val Timestamp  = "Deployment Timestamp"

    val cap = "+" + span(12) + "+" + span(44) + "+" + span(22) + "+" + span(68) + "+" + span(30) + "+"
    println( cap )
    println( f"| $Blockchain%-10s | $Address%-42s | $Name%-20s | $CodeHash%-66s | $Timestamp%-28s |" )
    println( cap )

    contractsSummary.foreach { row =>
      import row._
      val id = blankNull( blockchain_id )
      val ca = emptyOrHex( contract_address )
      val nm = blankNull( name )
      val ch = emptyOrHex( code_hash )
      val ts = blankNull( timestamp )
      println( f"| $id%-10s | $ca%-42s | $nm%-20s | $ch%-66s | $ts%-28s |" )
    }
    println( cap )
  }

  private def ethContractSpawnTask( config : Configuration ) : Initialize[InputTask[immutable.Seq[Tuple2[String,Either[EthHash,Client.TransactionReceipt]]]]] = {
    val parser = Defaults.loadForParser(xethFindCacheSeeds in config)( genContractSpawnParser )

    Def.inputTask {
      val s = state.value
      val is = interactionService.value
      val log = streams.value.log
      val blockchainId = (ethcfgBlockchainId in config).value
      val ephemeralBlockchains = xethcfgEphemeralBlockchains.value

      val sender = (xethFindCurrentSender in config).value.get
      val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value

      // lazy so if we have nothing to sign, we don't bother to prompt for passcode
      lazy val privateKey = findCachePrivateKey( s, log, is, blockchainId, sender, autoRelockSeconds, true )

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

      val mbAutoNameInputs = (ethcfgAutoSpawnContracts in config).?.value

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
            log.warn("No contract name or compilation alias provided. No 'ethcfgAutoSpawnContracts' set, so no automatic contracts to spawn.")
            Nil
          }
          case Some ( autoNameInputs ) => {
            autoNameInputs.toList.map { nameAndArgs =>
              val words = nameAndArgs.split("""\s+""")
              require( words.length >= 1, s"Each element of 'ethcfgAutoSpawnContracts' must contain at least a contract name! [word length: ${words.length}")
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

      implicit val invokerContext = (xethInvokerContext in config).value

      def doSpawn( deploymentAlias : String, codeHex : String, inputs : immutable.Seq[String], abi : Abi ) : ( String, Either[EthHash,Client.TransactionReceipt] ) = {

        val inputsBytes = ethabi.constructorCallData( inputs, abi ).get // asserts that we've found a meaningful ABI, and can parse the constructor inputs
        val inputsHex = inputsBytes.hex
        val dataHex = codeHex ++ inputsHex

        if ( inputsHex.nonEmpty ) {
          log.debug( s"Contract constructor inputs encoded to the following hex: '${inputsHex}'" )
        }

        val f_out = {
          for {
            txnHash <- Invoker.transaction.createContract( privateKey, Zero256, dataHex.decodeHexAsSeq )
            mbReceipt <- Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(abi), txnHash, invokerContext.pollTimeout, _ ) )
          } yield {
            log.info( s"Contract '${deploymentAlias}' deployed in transaction '0x${txnHash.hex}'." )
            mbReceipt match {
              case Some( receipt ) => {
                receipt.contractAddress.foreach { ca =>
                  log.info( s"Contract '${deploymentAlias}' has been assigned address '0x${ca.hex}'." )

                  if (! ephemeralBlockchains.contains( blockchainId ) ) {
                    val dbCheck = {
                      repository.Database.insertNewDeployment( blockchainId, ca, codeHex, sender, txnHash, inputsBytes )
                    }
                    if ( dbCheck.isFailed ) {
                      dbCheck.xwarn("Could not insert information about deployed contract into the repository database.")
                      log.warn("Could not insert information about deployed contract into the repository database. See 'sbt-ethereum.log' for more information.")
                    }
                  }
                }
                Right( receipt ) : Either[EthHash,Client.TransactionReceipt]
              }
              case None => Left( txnHash )
            }
          }
        }
        val out = Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout

        ( deploymentAlias, out )
      }
      quartets.map( (doSpawn _).tupled )
    }
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

    val cfactory = implicitly[jsonrpc.Client.Factory]
    val poller = implicitly[Poller]
    borrow( cfactory( testing.Default.EthJsonRpc.Url ) ) { client =>
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

  private def ethGasLimitOverrideDropTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasLimitOverride.set( None )
    log.info("No gas override is now set. Quantities of gas will be automatically computed.")
  }

  private def ethGasLimitOverrideSetTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val amount = bigIntParser("<gas override>").parsed
    Mutables.GasLimitOverride.set( Some( amount ) )
    log.info( s"Gas override set to ${amount}." )
  }

  private def ethGasLimitOverridePrintTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasLimitOverride.get match {
      case Some( value ) => log.info( s"A gas override is set, with value ${value}." )
      case None          => log.info( "No gas override is currently set." )
    }
  }

  private def ethGasPriceOverrideDropTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasPriceOverride.set( None )
    log.info("No gas price override is now set. Gas price will be automatically marked-up from your ethereum node's current default value.")
  }

  private def ethGasPriceOverridePrintTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    Mutables.GasPriceOverride.get match {
      case Some( value ) => log.info( s"A gas price override is set, with value ${value}." )
      case None          => log.info( "No gas price override is currently set." )
    }
  }

  private def ethGasPriceOverrideSetTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log = streams.value.log
    val amount = valueInWeiParser("<gas price override>").parsed
    Mutables.GasPriceOverride.set( Some( amount ) )
    log.info( s"Gas price override set to ${amount}." )
  }

  private def ethKeystoreListTask( config : Configuration ) : Initialize[Task[immutable.SortedMap[EthAddress,immutable.SortedSet[String]]]] = Def.task {
    val keystoresV3  = ethcfgKeystoreLocationsV3.value
    val log          = streams.value.log
    val blockchainId = (ethcfgBlockchainId in config).value
    val combined = {
      keystoresV3
        .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
        .foldLeft( Map.empty[EthAddress,wallet.V3] )( ( accum, next ) => accum ++ next )
    }

    val out = {
      def aliasesSet( address : EthAddress ) : immutable.SortedSet[String] = immutable.TreeSet( repository.Database.findAliasesByAddress( blockchainId, address ).get : _* )
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
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val is = interactionService.value
      val log = streams.value.log

      val address = parser.parsed
      val addressStr = address.hex

      val s = state.value
      val extract = Project.extract(s)
      val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For in config, addressStr, s) // config doesn't really matter here, since we provide hex, not a config dependent alias

      val credential = readCredential( is, address )
      val privateKey = findPrivateKey( log, mbWallet, credential )
      val confirmation = {
        is.readLine(s"Are you sure you want to reveal the unencrypted private key on this very insecure console? [Type YES exactly to continue, anything else aborts]: ", mask = false)
          .getOrElse(throw new Exception("Failed to read a confirmation")) // fail if we can't get a credential
      }
      if ( confirmation == "YES" ) {
        println( s"0x${privateKey.bytes.widen.hex}" )
      } else {
        throw new Exception("Not confirmed by user. Aborted.")
      }
    }
  }

  private def ethKeystoreWalletV3MemorizeTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    val is = interactionService.value
    val w = readV3Wallet( is )
    val address = w.address // a very cursory check of the wallet, NOT full validation
    repository.Keystore.V3.storeWallet( w ).get // asserts success
    log.info( s"Imported JSON wallet for address '0x${address.hex}', but have not validated it.")
    log.info( s"Consider validating the JSON using 'ethKeystoreWalletV3Validate 0x${address.hex}." )
  }

  private def ethKeystoreWalletV3PrintTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
    val keystoreDirs = ethcfgKeystoreLocationsV3.value
    val w = (xethLoadWalletV3For in config).evaluated.getOrElse( unknownWallet( keystoreDirs ) )
    println( Json.stringify( w.withLowerCaseKeys ) )
  }

  private def ethKeystoreWalletV3ValidateTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val log = streams.value.log
      val is = interactionService.value
      val keystoreDirs = ethcfgKeystoreLocationsV3.value
      val s = state.value
      val extract = Project.extract(s)
      val inputAddress = parser.parsed
      val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For in config, inputAddress.hex, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias
      val w = mbWallet.getOrElse( unknownWallet( keystoreDirs ) )
      val credential = readCredential( is, inputAddress )
      val privateKey = wallet.V3.decodePrivateKey( w, credential )
      val derivedAddress = privateKey.toPublicKey.toAddress
      if ( derivedAddress != inputAddress ) {
        throw new Exception(
          s"The wallet loaded for '0x${inputAddress.hex}' decodes with the credential given, but to a private key associated with a different address, 0x${derivedAddress}! Keystore files may be mislabeled or corrupted."
        )
      }
      log.info( s"A wallet for address '0x${derivedAddress.hex}' is valid and decodable with the credential supplied." )
    }
  }

  private def ethSolidityCompilerInstallTask : Initialize[InputTask[Unit]] = Def.inputTaskDyn {
    val log = streams.value.log

    val mbVersion = SolcJVersionParser.parsed

    val versionToInstall = mbVersion.getOrElse( SolcJInstaller.DefaultSolcJVersion )

    log.info( s"Installing local solidity compiler into the sbt-ethereum repository. This may take a few minutes." )
    val check = repository.SolcJ.Directory.flatMap { rootSolcJDir =>
      Failable {
        val versionDir = new File( rootSolcJDir, versionToInstall )
        if ( versionDir.exists() ) {
          log.warn( s"Directory '${versionDir.getAbsolutePath}' already exists. If you would like to reinstall this version, please delete this directory by hand." )
          throw new Exception( s"Cannot overwrite existing installation in '${versionDir.getAbsolutePath}'. Please delete this directory by hand if you wish to reinstall." )
        } else {
          SolcJInstaller.installLocalSolcJ( rootSolcJDir.toPath, versionToInstall )
          log.info( s"Installed local solcJ compiler, version ${versionToInstall} in '${rootSolcJDir}'." )
          val test = Compiler.Solidity.test( new Compiler.Solidity.LocalSolc( Some( versionDir ) ) )
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
      }
    }
    check.get // throw if a failure occurred

    Def.taskDyn {
      xethTriggerDirtySolidityCompilerList // causes parse cache and SessionSolidityCompilers to get updated
    }
  }

  private def ethSolidityCompilerPrintTask : Initialize[Task[Unit]] = Def.task {
    val log       = streams.value.log
    val ensureSet = (xethFindCurrentSolidityCompiler in Compile).value
    val ( key, compiler ) = Mutables.CurrentSolidityCompiler.get.get
    log.info( s"Current solidity compiler '$key', which refers to $compiler." )
  }

  private def ethSolidityCompilerSelectTask : Initialize[InputTask[Unit]] = {
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

  private def ethTransactionInvokeTask( config : Configuration ) : Initialize[InputTask[Option[Client.TransactionReceipt]]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val blockchainId = (ethcfgBlockchainId in config).value
      val caller = (xethFindCurrentSender in config).value.get
      val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
      val privateKey = findCachePrivateKey(s, log, is, blockchainId, caller, autoRelockSeconds, true )
      val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
      val amount = mbWei.getOrElse( Zero )
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      log.debug( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
      log.debug( s"Call data for function call: ${callData.hex}" )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendMessage( privateKey, contractAddress, Unsigned256( amount ), callData ) flatMap { txnHash =>
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei to address '0x${contractAddress.hex}' in transaction '0x${txnHash.hex}'.""" )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, Some(abi), txnHash, invokerContext.pollTimeout, _ ) )
      }
      Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete as None on time out
    }
  }

  private def ethTransactionSendTask( config : Configuration ) : Initialize[InputTask[Option[Client.TransactionReceipt]]] = {
    val parser = Defaults.loadForParser( xethFindCacheAddressParserInfo in config )( genEthSendEtherParser )

    Def.inputTask {
      val s = state.value
      val log = streams.value.log
      val is = interactionService.value
      val blockchainId = (ethcfgBlockchainId in config).value
      val from = (xethFindCurrentSender in config).value.get
      val (to, amount) = parser.parsed
      val autoRelockSeconds = ethcfgKeystoreAutoRelockSeconds.value
      val privateKey = findCachePrivateKey( s, log, is, blockchainId, from, autoRelockSeconds, true )

      implicit val invokerContext = (xethInvokerContext in config).value

      val f_out = Invoker.transaction.sendWei( privateKey, to, Unsigned256( amount ) ) flatMap { txnHash =>
        log.info( s"Sending ${amount} wei to address '0x${to.hex}' in transaction '0x${txnHash.hex}'." )
        Invoker.futureTransactionReceipt( txnHash ).map( prettyPrintEval( log, None, txnHash, invokerContext.pollTimeout, _ ) )
      }
      val out = Await.result( f_out, Duration.Inf ) // we use Duration.Inf because the Future will complete with failure on a timeout

      out.foreach { _ =>
        log.info("Ether sent.")
      }

      out
    }
  }

  private def ethTransactionViewTask( config : Configuration ) : Initialize[InputTask[(Abi.Function,immutable.Seq[Decoded.Value])]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = true ) )

    Def.inputTask {
      val log = streams.value.log

      val from = (xethFindCurrentSender in config).value.recover { fail : Fail =>
        log.info( s"Failed to find a current sender, using the zero address as a default.\nCause: ${fail}" )
        EthAddress.Zero
      }.get

      val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
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
      Await.result( f_out, Duration.Inf )
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
    val jsonRpcUrl = (ethcfgJsonRpcUrl in config).value
    doGetDefaultGasPrice( log, jsonRpcUrl )
  }

  private def xethFindCacheAddressParserInfoTask( config : Configuration ) : Initialize[Task[AddressParserInfo]] = Def.task {
    val blockchainId       = (config / ethcfgBlockchainId).value
    val jsonRpcUrl         = (config / ethcfgJsonRpcUrl).value
    val mbAliases          = repository.Database.findAllAliases( blockchainId ).toOption
    val nameServiceAddress = (config / enscfgNameServiceAddress).value
    val tld                = (config / enscfgNameServiceTld).value
    val reverseTld         = (config / enscfgNameServiceReverseTld).value
    AddressParserInfo( blockchainId, jsonRpcUrl, mbAliases, nameServiceAddress, tld, reverseTld )
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
      val blockchainId = (ethcfgBlockchainId in config).value

      val mbSenderOverride = getSenderOverride( config )( log, blockchainId )
      mbSenderOverride match {
        case Some( address ) => {
          ifPrint(
            s"""|The current effective sender is ${verboseAddress(blockchainId, address)}.
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
                s"""|The current effective sender is ${verboseAddress(blockchainId, address)}.
                    |It has been set by build setting 'ethcfgSender'.
                    | + No sender override has been set.""".stripMargin
              )
              address
            }
            case None => {
              ExternalValue.EthSender.map( EthAddress.apply ) match {
                case Some( address ) => {
                  ifPrint (
                    s"""|The current effective sender is ${verboseAddress(blockchainId, address)}.
                        |It has been set by an external value -- either System property 'eth.sender' or environment variable 'ETH_SENDER'.
                        | + No sender override has been set.
                        | + Build setting 'ethcfgSender has not been defined.""".stripMargin
                  )
                  address
                }
                case None => {
                  val mbDefaultSenderAddress = repository.Database.findAddressByAlias( blockchainId, DefaultSenderAlias ).get

                  mbDefaultSenderAddress match {
                    case Some( address ) => {
                      ifPrint (
                        s"""|The current effective sender is ${verboseAddress(blockchainId, address)}.
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
                              | + No '${DefaultSenderAlias}' address alias has been defined for blockchain '${blockchainId}'.""".stripMargin
                        )
                        defaultTestAddress
                      }
                      else {
                        val msg ={
                          s"""|No effective sender is defined. (blockchain '${blockchainId}', configuration '${config}')
                              |Cannot find any of...
                              | + a sender override
                              | + a value for build setting 'ethcfgSender' 
                              | + an 'eth.sender' System property
                              | + an 'ETH_SENDER' environment variable
                              | + a '${DefaultSenderAlias}' address alias defined for blockchain '${blockchainId}'""".stripMargin
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

    val mbExplicitJsonRpcUrl = ethcfgJsonRpcUrl.?.value

    Mutables.CurrentSolidityCompiler.get.map( _._2).getOrElse {
      def latestLocalInstallVersion : Option[SemanticVersion] = {
        val versions = (immutable.TreeSet.empty[SemanticVersion] ++ compilerKeys.map( LocalSolc.versionFromKey ).filter( _ != None ).map( _.get ))
        if ( versions.size > 0 ) Some(versions.last) else None
      }
      def latestLocalInstallKey : Option[String] = latestLocalInstallVersion.map( version => s"${LocalSolc.KeyPrefix}${version.versionString}" )

      val key = {
        mbExplicitJsonRpcUrl.flatMap( ejru => compilerKeys.find( key => key.startsWith(EthNetcompile.KeyPrefix) && key.endsWith( ejru ) ) ) orElse // use an explicitly set netcompile
        compilerKeys.find( _ == LocalPathSolcKey ) orElse                                                                                          // use a local compiler on the path
        latestLocalInstallKey orElse                                                                                                               // use the latest local compiler in the repository
        compilerKeys.find( _.startsWith(EthNetcompile.KeyPrefix) ) orElse                                                                          // use the default eth-netcompile
        compilerKeys.find( _.startsWith( EthJsonRpc.KeyPrefix ) )                                                                                  // use the (deprecated, mostly disappeared) json-rpc eth_CompileSolidity
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
    val jsonRpcUrl = (ethcfgJsonRpcUrl in config).value

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
    val testingEthJsonRpcUrl = (ethcfgJsonRpcUrl in Test).value

    // Sensitive to config
    val scalaStubsTarget = (sourceManaged in config).value

    // merge ABI sources
    val overlappingNames = currentCompilations.keySet.intersect( namedAbis.keySet )
    if ( !overlappingNames.isEmpty ) {
      throw new SbtEthereumException( s"""Names conflict (overlap) between compilations and named ABIs. Conflicting names: ${overlappingNames.mkString(", ")}""" )
    }
    val allMbAbis = {
      val sureNamedAbis = namedAbis map { case ( name, abi ) => ( name, Some( abi ) ) }
      val mbCompilationAbis = currentCompilations map { case ( name, contract ) =>
        ( name,  contract.info.mbAbi )
      }
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
              val mbFileSets = allMbAbis map { case ( className, mbAbi ) =>
                mbAbi map { abi =>
                  stub.Generator.generateStubClasses( className, abi, stubPackage ) map { generated =>
                    val srcFile = new File( stubsDir, s"${generated.className}.scala" )
                    srcFile.replaceContents( generated.sourceCode, scala.io.Codec.UTF8 )
                    srcFile
                  }
                } orElse {
                  log.warn( s"No ABI definition found for contract '${className}'. Skipping Scala stub generation." )
                  None
                }
              }
              val stubFiles = mbFileSets.filter( _.nonEmpty ).map( _.get ).foldLeft( Vector.empty[File])( _ ++ _ ).toVector
              val testingResourceFiles : immutable.Seq[File] = {
                if ( config == Test ) {
                  if ( allMbAbis.contains( testingResourcesObjectName ) ) { // TODO: A case insensitive check
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
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genAddressFunctionInputsAbiParser( restrictedToConstants = false ) )

    Def.inputTask {
      val ( contractAddress, function, args, abi ) = parser.parsed
      val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
      val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
      val log = streams.value.log
      log.info( s"Call data: ${callData.hex}" )
      callData
    }
  }

  private def xethInvokerContextTask( config : Configuration ) : Initialize[Task[Invoker.Context]] = Def.task {

    val log = streams.value.log

    val jsonRpcUrl      = (ethcfgJsonRpcUrl in config).value

    val pollPeriod      = ethcfgTransactionReceiptPollPeriod.value
    val timeout         = ethcfgTransactionReceiptTimeout.value

    val gasLimitMarkup  = ethcfgGasLimitMarkup.value
    val gasLimitCap     = ethcfgGasLimitCap.?.value
    val gasLimitFloor   = ethcfgGasLimitFloor.?.value

    val gasPriceMarkup  = ethcfgGasPriceMarkup.value
    val gasPriceCap     = ethcfgGasPriceCap.?.value
    val gasPriceFloor   = ethcfgGasPriceFloor.?.value

    val is              = interactionService.value
    val currencyCode    = ethcfgBaseCurrencyCode.value

    val gasLimitTweak = {
      Mutables.GasLimitOverride.get match {
        case Some( overrideValue ) => {
          log.info( s"Gas limit override set: ${overrideValue}")
          log.info( "Using gas limit override.")
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
          log.info( "Using gas price override.")
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
      gasPriceTweak = gasPriceTweak, gasLimitTweak = gasLimitTweak,
      pollPeriod = pollPeriod,
      pollTimeout = timeout,
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
    repository.Keystore.V3.storeWallet( w ).get // asserts success
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
    repository.Keystore.V3.storeWallet( w ).get // asserts success
  }

  private def xethLoadAbiForTask( config : Configuration ) : Initialize[InputTask[Abi]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val blockchainId = (ethcfgBlockchainId in config).value
      abiForAddress( blockchainId, parser.parsed )
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
  // no way to unambigous select one of these compilations to spawn

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


  private def xethLoadWalletV3Task( config : Configuration ) : Initialize[Task[Option[wallet.V3]]] = Def.task {
    val s = state.value
    val addressStr = (xethFindCurrentSender in config).value.get.hex
    val extract = Project.extract(s)
    val (_, result) = extract.runInputTask((xethLoadWalletV3For in config), addressStr, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias
    result
  }

  private def xethLoadWalletV3ForTask( config : Configuration ) : Initialize[InputTask[Option[wallet.V3]]] = {
    val parser = Defaults.loadForParser(xethFindCacheAddressParserInfo in config)( genGenericAddressParser )

    Def.inputTask {
      val keystoresV3 = ethcfgKeystoreLocationsV3.value
      val log         = streams.value.log

      val blockchainId = (ethcfgBlockchainId in config).value

      val address = parser.parsed
      val out = {
        keystoresV3
          .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xdebug( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
          .foldLeft( None : Option[wallet.V3] ){ ( mb, nextKeystore ) =>
          if ( mb.isEmpty ) nextKeystore.get( address ) else mb
        }
      }
      val message = {
        val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s" (aliases $str)" ) )
        out.fold( s"""No V3 wallet found for '0x${address.hex}'${aliasesPart}. Directories checked: ${keystoresV3.mkString(", ")}""" )( _ => s"V3 wallet found for '0x${address.hex}'${aliasesPart}" )
      }
      log.info( message )
      out
    }
  }

  private def xethNamedAbisTask( config : Configuration ) : Initialize[Task[immutable.Map[String,Abi]]] = Def.task {
    val log    = streams.value.log
    val srcDir = (xethcfgNamedAbiSource in config).value

    def empty = immutable.Map.empty[String,Abi]

    if ( srcDir.exists) {
      if ( srcDir.isDirectory ) {
        val files = srcDir.listFiles( JsonFilter )

        def toTuple( f : File ) : ( String, Abi ) = {
          val filename = f.getName()
          val name = filename.take( filename.length - JsonFilter.DotSuffix.length ) // the filter ensures they do have the suffix
          val json = borrow ( Source.fromFile( f ) )( _.mkString ) // is there a better way
          ( name, Json.parse( json ).as[Abi] )
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

  private def xethNextNonceTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
    val log        = streams.value.log
    val jsonRpcUrl = (ethcfgJsonRpcUrl in config).value
    doGetTransactionCount( log, jsonRpcUrl, (xethFindCurrentSender in config).value.get , jsonrpc.Client.BlockNumber.Pending )
  }

  private def xethSqlQueryRepositoryDatabaseTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log   = streams.value.log
    val query = DbQueryParser.parsed

    // removed guard of query (restriction to select),
    // since updating SQL issued via executeQuery(...)
    // usefully fails in h2

    try {
      val check = {
        repository.Database.UncheckedDataSource.map { ds =>
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

  private def xethSqlUpdateRepositoryDatabaseTask : Initialize[InputTask[Unit]] = Def.inputTask {
    val log   = streams.value.log
    val update = DbQueryParser.parsed

    try {
      val check = {
        repository.Database.UncheckedDataSource.map { ds =>
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
    log.info( "Refreshing alias cache." )
  }

  // this is a no-op, its execution just triggers a re-caching of aliases
  private def xethTriggerDirtySolidityCompilerListTask : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log
    log.info( "Refreshing compiler list." )
  }

  private def xethUpdateContractDatabaseTask( config : Configuration ) : Initialize[Task[Boolean]] = Def.task {
    val log          = streams.value.log
    val compilations = (xethLoadCurrentCompilationsKeepDups in Compile).value // we want to "know" every contract we've seen, which might include contracts with multiple names

    repository.Database.updateContractDatabase( compilations ).get
  }

  private def xethUpdateSessionSolidityCompilersTask : Initialize[Task[immutable.SortedMap[String,Compiler.Solidity]]] = Def.task {
    import Compiler.Solidity._

    val netcompileUrl = ethcfgNetcompileUrl.?.value
    val jsonRpcUrl    = (ethcfgJsonRpcUrl in Compile).value // we use the main (compile) configuration, don't bother with a test json-rpc for compilation

    def check( key : String, compiler : Compiler.Solidity ) : Option[ ( String, Compiler.Solidity ) ] = {
      val test = Compiler.Solidity.test( compiler )
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
    def checkLocalRepositorySolc( version : String ) = {
      repository.SolcJ.Directory.toOption.flatMap { rootSolcJDir =>
        check( s"${LocalSolc.KeyPrefix}${version}", Compiler.Solidity.LocalSolc( Some( new File( rootSolcJDir, version ) ) ) )
      }
    }
    def checkLocalRepositorySolcs = {
      SolcJInstaller.SupportedVersions.map( checkLocalRepositorySolc ).toSeq
    }

    val raw = checkLocalRepositorySolcs :+ localPath :+ ethJsonRpc :+ netcompile :+ defaultNetcompile

    val out = immutable.SortedMap( raw.filter( _ != None ).map( _.get ) : _* )
    Mutables.SessionSolidityCompilers.set( Some( out ) )
    out
  }

  // unprefixed tasks

  private def compileSolidityTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
    val log = streams.value.log

    val compiler = (xethFindCurrentSolidityCompiler in Compile).value

    val includeStrings = ethcfgIncludeLocations.value

    val solSource      = (ethcfgSoliditySource in config).value
    val solDestination = (ethcfgSolidityDestination in config).value

    val baseDir = baseDirectory.value

    val includeLocations = includeStrings.map( SourceFile.Location.apply( baseDir, _ ) )

    ResolveCompileSolidity.doResolveCompile( log, compiler, includeLocations, solSource, solDestination )
  }

  // helper functions

  private def allUnitsValue( valueInWei : BigInt ) = s"${valueInWei} wei (${Denominations.Ether.fromWei(valueInWei)} ether, ${Denominations.Finney.fromWei(valueInWei)} finney, ${Denominations.Szabo.fromWei(valueInWei)} szabo)"

  private def senderOverride( config : Configuration ) = if ( config == Test ) Mutables.TestSenderOverride else Mutables.SenderOverride

  private def getSenderOverride( config : Configuration )( log : sbt.Logger, blockchainId : String ) : Option[EthAddress] = {
    val configSenderOverride = senderOverride( config )

    configSenderOverride.synchronized {
      val BlockchainId = blockchainId

      configSenderOverride.get match {
        case Some( ( BlockchainId, address ) ) => Some( address )
        case Some( (badBlockchainId, _) ) => {
          log.info( s"A sender override was set for the blockchain '$badBlockchainId', but that is no longer the current 'ethcfgBlockchainId'. The sender override is stale and will be dropped." )
          configSenderOverride.set( None )
          None
        }
        case None => None
      }
    }
  }

  private def computeGas(
    log         : sbt.Logger,
    jsonRpcUrl  : String,
    from        : Option[EthAddress],
    to          : Option[EthAddress],
    value       : Option[BigInt],
    data        : Option[Seq[Byte]],
    blockNumber : jsonrpc.Client.BlockNumber,
    markup      : Double
  )( implicit clientFactory : jsonrpc.Client.Factory, ec : ExecutionContext ) : BigInt = {
    Mutables.GasLimitOverride.get match {
      case Some( overrideValue ) => {
        log.info( s"Gas override set: ${overrideValue}")
        log.info( "Using gas override.")
        overrideValue
      }
      case None => {
        doEstimateAndMarkupGas( log, jsonRpcUrl, from, to, value, data, blockNumber, markup )( clientFactory, ec )
      }
    }
  }

  private def findPrivateKey( log : sbt.Logger, mbGethWallet : Option[wallet.V3], credential : String ) : EthPrivateKey = {
    def forceKey = {
      try {
        EthPrivateKey( credential )
      }
      catch {
        case _ : NumberFormatException | _ : IllegalArgumentException => throw new BadCredentialException()
      }
    }
    mbGethWallet.fold {
      log.info( "No wallet available. Trying passphrase as hex private key." )
      forceKey
    }{ gethWallet =>
      try {
        wallet.V3.decodePrivateKey( gethWallet, credential )
      } catch {
        case v3e : wallet.V3.Exception => {
          log.warn("Credential is not correct geth wallet passphrase. Trying as hex private key.")
          val maybe = forceKey
          val desiredAddress = gethWallet.address
          if (maybe.toPublicKey.toAddress != desiredAddress) {
            throw new BadCredentialException( desiredAddress )
          } else {
            maybe
          }
        }
      }
    }
  }

  private final val CantReadInteraction = "InteractionService failed to read"

  private def findCachePrivateKey(
    state                : sbt.State,
    log                  : sbt.Logger,
    is                   : sbt.InteractionService,
    blockchainId         : String,
    address              : EthAddress,
    autoRelockSeconds    : Int,
    userValidateIfCached : Boolean
  ) : EthPrivateKey = {

    def updateCached : EthPrivateKey = {
      // this is ugly and awkward, but it gives time for any log messages to get emitted before prompting for a credential
      // it also slows down automated attempts to guess passwords, i guess...
      Thread.sleep(1000)

      val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( commasep => s", aliases $commasep" ) )

      log.info( s"Unlocking address '0x${address.hex}' (on blockchain '$blockchainId'$aliasesPart)" )

      val credential = readCredential( is, address )

      val extract = Project.extract(state)
      val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For in Compile, address.hex, state) // the config scope of xethLoadWalletV3For doesn't matter here, since we provide hex, not an alias

      val privateKey = findPrivateKey( log, mbWallet, credential )
      Mutables.CurrentAddress.set( UnlockedAddress( blockchainId, address, privateKey, System.currentTimeMillis + (autoRelockSeconds * 1000) ) )
      privateKey
    }
    def goodCached : Option[EthPrivateKey] = {
      // caps for value matches rather than variable names
      val BlockchainId = blockchainId
      val Address = address
      val now = System.currentTimeMillis
      Mutables.CurrentAddress.get match {
        case UnlockedAddress( BlockchainId, Address, privateKey, autoRelockTime ) if (now < autoRelockTime ) => { // if blockchainId and/or ethcfgSender has changed, this will no longer match
          val aliasesPart = commaSepAliasesForAddress( BlockchainId, Address ).fold( _ => "", _.fold("")( commasep => s", aliases $commasep" ) )
          val ok = {
            if ( userValidateIfCached ) {
              is.readLine( s"Using sender address '0x${address.hex}' (on blockchain '${blockchainId}'${aliasesPart}). OK? [y/n] ", false ).getOrElse( throw new Exception( CantReadInteraction ) ).trim().equalsIgnoreCase("y")
            } else {
              true
            }
          }
          if ( ok ) {
            Some( privateKey )
          } else {
            Mutables.CurrentAddress.set( NoAddress )
            throw new SenderNotAvailableException( s"Use of sender address '0x${address.hex}' (on blockchain '${blockchainId}'${aliasesPart}) vetoed by user." )
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
      Invoker.throwDisapproved( txn )
    }
  }( ec )

  private def parseAbi( abiString : String ) = Json.parse( abiString ).as[Abi]

  private def readAddressAndAbi( log : sbt.Logger, is : sbt.InteractionService ) : ( EthAddress, Abi ) = {
    val address = EthAddress( is.readLine( "Contract address in hex: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    val abi = parseAbi( is.readLine( "Contract ABI: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    ( address, abi )
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
    val dirs = loadDirs.map( _.getAbsolutePath() ).mkString(", ")
    throw new Exception( s"Could not find V3 wallet for the specified address in the specified keystore directories: ${dirs}}" )
  }

  private def assertSomeSender( log : Logger, fsender : Failable[EthAddress] ) : Option[EthAddress] = {
    val onFail : Fail => Nothing = fail => {
      val errMsg = {
        val base = "No sender found. Please define a 'defaultSender' alias, or the setting 'ethcfgSender', or use 'ethAddressSenderOverrideSet' for a temporary sender."
        val extra = fail.source match {
          case _ : SenderNotAvailableException => ""
          case _                               => s" [Cause: ${fail.message}]"
        }
        base + extra
      }
      log.error( errMsg )
      throw new SenderNotAvailableException( errMsg )
    }
    fsender.xmap( onFail ).toOption
  }

  // some formatting functions for ascii tables
  private def emptyOrHex( str : String ) = if (str == null) "" else s"0x$str"
  private def blankNull( str : String ) = if (str == null) "" else str
  private def span( len : Int ) = (0 until len).map(_ => "-").mkString

  private def commaSepAliasesForAddress( blockchainId : String, address : EthAddress ) : Failable[Option[String]] = {
    repository.Database.findAliasesByAddress( blockchainId, address ).map( seq => if ( seq.isEmpty ) None else Some( seq.mkString( "['","','", "']" ) ) )
  }
  private def leftwardAliasesArrowOrEmpty( blockchainId : String, address : EthAddress ) : Failable[String] = {
    commaSepAliasesForAddress( blockchainId, address ).map( _.fold("")( aliasesStr => s" <-- ${aliasesStr}" ) )
  }

  private def verboseAddress( blockchainId : String, address : EthAddress ) : String = {
    val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s"with aliases $str " ) )
    s"'0x${address.hex}' (${aliasesPart}on blockchain '$blockchainId')"
  }

  private def attemptAdvanceStateWithTask[T]( taskKey : Def.ScopedKey[Task[T]], startState : State ) : State = {
    Project.runTask( taskKey, startState ) match {
      case None => {
        WARNING.log(s"Huh? Key '${taskKey}' was undefined in the original state. Ignoring attempt to run that task in onLoad/onUnload.")
        startState
      }
      case Some((newState, Inc(inc))) => {
        WARNING.log("Failed to run '${taskKey}' on initialization: " + Incomplete.show(inc.tpe))
        startState
      }
      case Some((newState, Value(_))) => {
        newState
      }
    }
  }


  // plug-in setup

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
