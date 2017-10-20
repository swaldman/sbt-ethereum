package com.mchange.sc.v1.sbtethereum

import util.BaseCodeAndSuffix
import compile.{Compiler, ResolveCompileSolidity, SemanticVersion, SolcJInstaller, SourceFile}
import util.EthJsonRpc._
import util.Parsers._
import util.SBinaryFormats._
import sbt._
import sbt.Keys._
import sbt.plugins.{InteractionServicePlugin, JvmPlugin}
import sbt.Def.Initialize
import sbt.InteractionServiceKeys.interactionService
import sbinary._
import sbinary.DefaultProtocol._
import java.io.{BufferedInputStream, File, FileInputStream, FilenameFilter}
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import play.api.libs.json.{JsObject, Json}
import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.io._
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._
import jsonrpc.{Abi, ClientTransactionReceipt, MapStringCompilationContractFormat}
import specification.Denominations
import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations._
import com.mchange.sc.v1.consuela.ethereum.ethabi.{DecodedReturnValue, Encoder, abiFunctionForFunctionNameAndArgs, callDataForAbiFunctionFromStringArgs, decodeReturnValuesForFunction}
import com.mchange.sc.v1.consuela.ethereum.stub
import com.mchange.sc.v1.log.MLogger
import scala.collection._
import scala.sys.process.{Process, ProcessLogger}
import scala.io.Source

// XXX: provisionally, for now... but what sort of ExecutionContext would be best when?
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

object SbtEthereumPlugin extends AutoPlugin {

  private val EthSenderSystemProperty      = "eth.sender"
  private val EthSenderEnvironmentVariable = "ETH_SENDER"

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally we should try to log through sbt loggers
  private implicit val logger: MLogger = mlogger( this )

  private trait AddressInfo
  private final case object NoAddress                                                                                                         extends AddressInfo
  private final case class  UnlockedAddress( blockchainId : String, address : EthAddress, privateKey : EthPrivateKey, autoRelockTime : Long ) extends AddressInfo

  // MT: protected by CurrentAddress' lock
  private val CurrentAddress = new AtomicReference[AddressInfo]( NoAddress )

  private val SessionSolidityCompilers = new AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]]( None )

  private val CurrentSolidityCompiler = new AtomicReference[Option[( String, Compiler.Solidity )]]( None )

  private val GasOverride = new AtomicReference[Option[BigInt]]( None )

  private val GasPriceOverride = new AtomicReference[Option[BigInt]]( None )

  // MT: protected by SenderOverride's lock
  private val SenderOverride = new AtomicReference[Option[ ( String, EthAddress ) ]]( None )

  // MT: protected by TestSenderOverride's lock
  private val TestSenderOverride = new AtomicReference[Option[ ( String, EthAddress ) ]]( None )

  // MT: protected by LocalTestrpc's lock
  private val LocalTestrpc = new AtomicReference[Option[Process]]( None )

  private val BufferSize = 4096

  private val PollSeconds = 15

  private val PollAttempts = 9

  private val Zero = BigInt(0)

  private val Zero256 = Unsigned256( 0 )

  private val EmptyBytes = List.empty[Byte]

  private val LatestSolcJVersion = "0.4.10"

  private val DefaultEthJsonRpcUrl = "http://ethjsonrpc.mchange.com:8545"

  private val DefaultTestEthJsonRpcUrl = testing.Default.EthJsonRpc.Url

  private val DefaultEthNetcompileUrl = "http://ethjsonrpc.mchange.com:8456"

  final object JsonFilter extends FilenameFilter {
    val DotSuffix = ".json"
    def accept( dir : File, name : String ) : Boolean = {
      val lcName = name.toLowerCase
      lcName != DotSuffix && lcName.endsWith( DotSuffix )
    }
  }

  // if we've started a child test process,
  // kill it on exit
  val TestrpcDestroyer: Thread = new Thread {
    override def run() : Unit = {
      LocalTestrpc synchronized {
        LocalTestrpc.get.foreach ( _.destroy )
      }
    }
  }

  java.lang.Runtime.getRuntime.addShutdownHook( TestrpcDestroyer )


  object autoImport {// settings
    val ethSender = settingKey[String]("The address from which transactions will be sent")

    val ethBlockchainId = settingKey[String]("A name for the network represented by ethJsonRpcUrl (e.g. 'mainnet', 'morden', 'ropsten')")

    val ethDeployAutoContracts = settingKey[Seq[String]]("Names (and optional space-separated constructor args) of contracts compiled within this project that should be deployed automatically.")

    val ethEntropySource = settingKey[SecureRandom]("The source of randomness that will be used for key generation")

    val ethIncludeLocations = settingKey[Seq[String]]("Directories or URLs that should be searched to resolve import directives, besides the source directory itself")

    val ethGasMarkup = settingKey[Double]("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")

    val ethGasPriceMarkup = settingKey[Double]("Fraction by which automatically estimated gas price will be marked up (if not overridden) in executing transactions")

    val ethKeystoreAutoRelockSeconds = settingKey[Int]("Number of seconds after which an unlocked private key should automatically relock")

    val ethKeystoreLocationsV3 = settingKey[Seq[File]]("Directories from which V3 wallets can be loaded")

    val ethJsonRpcUrl = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val ethNetcompileUrl = settingKey[String]("Optional URL of an eth-netcompile service, for more reliabe network-based compilation than that available over json-rpc.")

    val ethPackageScalaStubs = settingKey[String]("Package into which Scala stubs of Solidity compilations should be generated")

    val ethTargetDir = settingKey[File]("Location in target directory where ethereum artifacts will be placed")

    val ethSoliditySource = settingKey[File]("Solidity source code directory")

    val ethSolidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    val xethEphemeralBlockchains = settingKey[Seq[String]]("IDs of blockchains that should be considered ephemeral (so their deployments should not be retained).")

    val xethNamedAbiSource = settingKey[File]("Location where files containing json files containing ABIs for which stubs should be generated. Each as '<stubname>.json'.")

    val xethTestingResourcesObjectName = settingKey[String]("The name of the Scala object that will be automatically generated with resources for tests.")

    val xethWalletV3ScryptN = settingKey[Int]("The value to use for parameter N when generating Scrypt V3 wallets")

    val xethWalletV3ScryptR = settingKey[Int]("The value to use for parameter R when generating Scrypt V3 wallets")

    val xethWalletV3ScryptP = settingKey[Int]("The value to use for parameter P when generating Scrypt V3 wallets")

    val xethWalletV3ScryptDkLen = settingKey[Int]("The derived key length parameter used when generating Scrypt V3 wallets")

    val xethWalletV3Pbkdf2C = settingKey[Int]("The value to use for parameter C when generating pbkdf2 V3 wallets")

    val xethWalletV3Pbkdf2DkLen = settingKey[Int]("The derived key length parameter used when generating pbkdf2 V3 wallets")

    // tasks

    val ethAbiForget = inputKey[Unit]("Removes an ABI definition that was added to the sbt-ethereum database via ethAbiMemorize")

    val ethAbiList = taskKey[Unit]("Lists the addresses for which ABI definitions have been memorized. (Does not include our own deployed compilations, see 'ethCompilationsList'")

    val ethAbiMemorize = taskKey[Unit]("Prompts for an ABI definition for a contract and inserts it into the sbt-ethereum database")

    val ethAliasDrop = inputKey[Unit]("Drops an alias for an ethereum address from the sbt-ethereum repository database.")

    val ethAliasList = taskKey[Unit]("Lists aliases for ethereum addresses that can be used in place of the hex address in many tasks.")

    val ethAliasSet = inputKey[Unit]("Defines (or redefines) an alias for an ethereum address that can be used in place of the hex address in many tasks.")

    val ethBalance = inputKey[BigDecimal]("Computes the balance in ether of a given address, or of current sender if no address is supplied")

    val ethBalanceInWei = inputKey[BigInt]("Computes the balance in wei of a given address, or of current sender if no address is supplied")

    val ethInvokeConstant = inputKey[(Abi.Function,immutable.Seq[DecodedReturnValue])]("Makes a call to a constant function, consulting only the local copy of the blockchain. Burns no Ether. Returns the latest available result.")

    val ethCompilationsCull = taskKey[Unit]("Removes never-deployed compilations from the repository database.")

    val ethCompilationsInspect = inputKey[Unit]("Dumps to the console full information about a compilation, based on either a code hash or contract address")

    val ethCompilationsList = taskKey[Unit]("Lists summary information about compilations known in the repository")

    val ethDeployAuto = taskKey[immutable.Map[String,Either[EthHash,ClientTransactionReceipt]]]("Deploys contracts named in 'ethDeployAutoContracts'.")

    val ethDeployOnly = inputKey[Either[EthHash,ClientTransactionReceipt]]("Deploys the specified named contract")

    val ethInvokeTransaction = inputKey[Option[ClientTransactionReceipt]]("Calls a function on a deployed smart contract")

    val ethKeystoreCreateWalletV3 = taskKey[wallet.V3]("Generates a new V3 wallet, using ethEntropySource as a source of randomness")

    val ethKeystoreInspectWalletV3 = inputKey[Unit]("Prints V3 wallet as JSON to the console.")

    val ethKeystoreList = taskKey[immutable.SortedMap[EthAddress,immutable.SortedSet[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined")

    val ethKeystoreMemorizeWalletV3 = taskKey[Unit]("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")

    val ethKeystoreRevealPrivateKey = inputKey[Unit]("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")

    val ethKeystoreValidateWalletV3 = inputKey[Unit]("Verifies that a V3 wallet can be decoded for an address, and decodes to the expected address.")

    val ethSelfPing = taskKey[Option[ClientTransactionReceipt]]("Sends 0 ether from current sender to itself")

    val ethSenderOverrideSet = inputKey[Unit]("Sets an ethereum address to be used as sender in prefernce to any 'ethSender' or defaultSender that may be set.")

    val ethSenderOverrideDrop = taskKey[Unit]("Removes any sender override, reverting to any 'ethSender' or defaultSender that may be set.")

    val ethSenderOverrideShow = taskKey[Unit]("Displays any sender override, if set.")

    val ethSendEther = inputKey[Option[ClientTransactionReceipt]]("Sends ether from current sender to a specified account, format 'ethSendEther <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

    val ethSolidityCompile = taskKey[Unit]("Compiles solidity files")

    val ethSolidityInstallCompiler = inputKey[Unit]("Installs a best-attempt platform-specific solidity compiler into the sbt-ethereum repository (or choose a supported version)")

    val ethSolidityChooseCompiler = inputKey[Unit]("Manually select among solidity compilers available to this project")

    val ethSolidityShowCompiler = taskKey[Unit]("Displays currently active Solidity compiler")

    val ethTestrpcLocalStart = taskKey[Unit]("Starts a local testrpc environment (if the command 'testrpc' is in your PATH)")

    val ethTestrpcLocalStop = taskKey[Unit]("Stops any local testrpc environment that may have been started previously")

    val xethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val xethFindCacheSessionSolidityCompilerKeys = taskKey[immutable.Set[String]]("Finds and caches keys for available compilers for use parser for ethSolidityCompilerSet")

    val xethFindCurrentSolidityCompiler = taskKey[Compiler.Solidity]("Finds and caches keys for available compilers for use parser for ethSolidityCompilerSet")

    val xethFindCacheAliasesIfAvailable = taskKey[Tuple2[String,Option[immutable.SortedMap[String,EthAddress]]]]("Finds and caches aliases for use by address parsers")

    val xethFindCacheOmitDupsCurrentCompilations = taskKey[immutable.Map[String,jsonrpc.Compilation.Contract]]("Finds and caches compiled, deployable contract names, omitting ambiguous duplicates. Triggered by ethSolidityCompile")

    val xethFindCurrentSender = taskKey[Failable[EthAddress]]("Finds the address that should be used to send ether or messages")

    val xethGasOverrideSet = inputKey[Unit]("Defines a value which overrides the usual automatic marked-up estimation of gas required for a transaction.")

    val xethGasOverrideDrop = taskKey[Unit]("Removes any previously set gas override, reverting to the usual automatic marked-up estimation of gas required for a transaction.")

    val xethGasOverrideShow = taskKey[Unit]("Displays the current gas override, if set.")

    val xethGasPrice = taskKey[BigInt]("Finds the current gas price, including any overrides or gas price markups")

    val xethGasPriceOverrideSet = inputKey[Unit]("Defines a value which overrides the usual automatic marked-up default gas price that will be paid for a transaction.")

    val xethGasPriceOverrideDrop = taskKey[Unit]("Removes any previously set gas price override, reverting to the usual automatic marked-up default.")

    val xethGasPriceOverrideShow = taskKey[Unit]("Displays the current gas price override, if set.")

    val xethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethEntropySource as a source of randomness")

    val xethGenScalaStubsAndTestingResources = taskKey[immutable.Seq[File]]("Generates stubs for compiled Solidity contracts, and resources helpful in testing them.")

    val xethKeystoreCreateWalletV3Pbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethEntropySource as a source of randomness")

    val xethKeystoreCreateWalletV3Scrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethEntropySource as a source of randomness")

    val xethInvokeData = inputKey[immutable.Seq[Byte]]("Reveals the data portion that would be sent in a message invoking a function and its arguments on a deployed smart contract")

    val xethLoadAbiFor = inputKey[Abi]("Finds the ABI for a contract address, if known")

    val xethLoadCompilationsKeepDups = taskKey[immutable.Iterable[(String,jsonrpc.Compilation.Contract)]]("Loads compiled solidity contracts, permitting multiple nonidentical contracts of the same name")

    val xethLoadCompilationsOmitDups = taskKey[immutable.Map[String,jsonrpc.Compilation.Contract]]("Loads compiled solidity contracts, omitting contracts with multiple nonidentical contracts of the same name")

    val xethLoadWalletV3 = taskKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3 for current sender")

    val xethLoadWalletV3For = inputKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val xethNamedAbis = taskKey[immutable.Map[String,Abi]]("Loads any named ABIs from the 'xethNamedAbiSource' directory")

    val xethNextNonce = taskKey[BigInt]("Finds the next nonce for the current sender")

    val xethQueryRepositoryDatabase = inputKey[Unit]("Primarily for debugging. Query the internal repository database.")

    val xethTriggerDirtyAliasCache = taskKey[Unit]("Indirectly provokes an update of the cache of aliases used for tab completions.")

    val xethTriggerDirtySolidityCompilerList = taskKey[Unit]("Indirectly provokes an update of the cache of aavailable solidity compilers used for tab completions.")

    val xethUpdateContractDatabase = taskKey[Boolean]("Integrates newly compiled contracts into the contract database. Returns true if changes were made.")

    val xethUpdateRepositoryDatabase = inputKey[Unit]("Primarily for development and debugging. Update the internal repository database with arbitrary SQL.")

    val xethUpdateSessionSolidityCompilers = taskKey[immutable.SortedMap[String,Compiler.Solidity]]("Finds and tests potential Solidity compilers to see which is available.")

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

      // Settings

      ethBlockchainId in Compile := MainnetIdentifier,

      ethBlockchainId in Test := TestrpcIdentifier,

      ethEntropySource := new java.security.SecureRandom,

      ethGasMarkup := 0.2,

      ethGasPriceMarkup := 0.0, // by default, use conventional gas price

      ethIncludeLocations := Nil,

      ethJsonRpcUrl in Compile := DefaultEthJsonRpcUrl,

      ethJsonRpcUrl in Test := DefaultTestEthJsonRpcUrl,

      ethKeystoreAutoRelockSeconds := 300,

      ethKeystoreLocationsV3 := {
        def warning( location : String ) : String = s"Failed to find V3 keystore in ${location}"
        def listify( fd : Failable[File] ) = fd.fold( _ => Nil, f => List(f) )
        listify( repository.Keystore.V3.Directory.xwarn( warning("sbt-ethereum repository") ) ) ::: listify( clients.geth.KeyStore.Directory.xwarn( warning("geth home directory") ) ) ::: Nil
      },

      ethSoliditySource in Compile := (sourceDirectory in Compile).value / "solidity",

      ethSolidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      xethNamedAbiSource in Compile := (sourceDirectory in Compile).value / "ethabi",

      xethTestingResourcesObjectName in Test := "Testing",

      xethEphemeralBlockchains := immutable.Seq( TestrpcIdentifier ),

      xethWalletV3Pbkdf2C := wallet.V3.Default.Pbkdf2.C,

      xethWalletV3Pbkdf2DkLen := wallet.V3.Default.Pbkdf2.DkLen,

      xethWalletV3ScryptDkLen := wallet.V3.Default.Scrypt.DkLen,

      xethWalletV3ScryptN := wallet.V3.Default.Scrypt.N,

      xethWalletV3ScryptR := wallet.V3.Default.Scrypt.R,

      xethWalletV3ScryptP := wallet.V3.Default.Scrypt.P,

      // tasks

      ethAbiForget in Compile <<= ethAbiForgetTask( Compile ),

      ethAbiForget in Test <<= ethAbiForgetTask( Test ),

      ethAbiList in Compile <<= ethAbiListTask( Compile ),

      ethAbiList in Test <<= ethAbiListTask( Test ),

      ethAbiMemorize in Compile <<= ethAbiMemorizeTask( Compile ),

      ethAbiMemorize in Test <<= ethAbiMemorizeTask( Test ),

      ethAliasDrop in Compile <<= ethAliasDropTask( Compile ),

      ethAliasDrop in Test <<= ethAliasDropTask( Test ),

      ethAliasList in Compile <<= ethAliasListTask( Compile ),

      ethAliasList in Test <<= ethAliasListTask( Test ),

      ethAliasSet in Compile <<= ethAliasSetTask( Compile ),

      ethAliasSet in Test <<= ethAliasSetTask( Test ),

      ethBalance in Compile <<= ethBalanceTask( Compile ),

      ethBalance in Test <<= ethBalanceTask( Test ),

      ethBalanceInWei in Compile <<= ethBalanceInWeiTask( Compile ),

      ethBalanceInWei in Test <<= ethBalanceInWeiTask( Test ),

      ethCompilationsCull <<= ethCompilationsCullTask,

      ethCompilationsInspect in Compile <<= ethCompilationsInspectTask( Compile ),

      ethCompilationsInspect in Test <<= ethCompilationsInspectTask( Test ),

      ethCompilationsList <<= ethCompilationsListTask,

      ethDeployAuto in Compile <<= ethDeployAutoTask( Compile ),

      ethDeployAuto in Test <<= ethDeployAutoTask( Test ),

      ethDeployOnly in Compile <<= ethDeployOnlyTask( Compile ),

      ethDeployOnly in Test <<= ethDeployOnlyTask( Test ),

      ethInvokeConstant in Compile <<= ethInvokeConstantTask( Compile ),

      ethInvokeConstant in Test <<= ethInvokeConstantTask( Test ),

      ethInvokeTransaction in Compile <<= ethInvokeTransactionTask( Compile ),

      ethInvokeTransaction in Test <<= ethInvokeTransactionTask( Test ),

      ethKeystoreCreateWalletV3 := xethKeystoreCreateWalletV3Scrypt.value,

      ethKeystoreInspectWalletV3 in Compile <<= ethKeystoreInspectWalletV3Task( Compile ),

      ethKeystoreInspectWalletV3 in Test <<= ethKeystoreInspectWalletV3Task( Test ),

      ethKeystoreList in Compile <<= ethKeystoreListTask( Compile ),

      ethKeystoreList in Test <<= ethKeystoreListTask( Test ),

      ethKeystoreMemorizeWalletV3 <<= ethKeystoreMemorizeWalletV3Task,

      ethKeystoreRevealPrivateKey in Compile <<= ethKeystoreRevealPrivateKeyTask( Compile ),

      ethKeystoreRevealPrivateKey in Test <<= ethKeystoreRevealPrivateKeyTask( Test ),

      ethKeystoreValidateWalletV3 in Compile <<= ethKeystoreValidateWalletV3Task( Compile ),

      ethKeystoreValidateWalletV3 in Test <<= ethKeystoreValidateWalletV3Task( Test ),

      ethSelfPing in Compile <<= ethSelfPingTask( Compile ),

      ethSelfPing in Test <<= ethSelfPingTask( Test ),

      ethSenderOverrideSet in Compile <<= ethSenderOverrideSetTask( Compile ),

      ethSenderOverrideSet in Test <<= ethSenderOverrideSetTask( Test ),

      ethSenderOverrideDrop in Compile <<= ethSenderOverrideDropTask( Compile ),

      ethSenderOverrideDrop in Test <<= ethSenderOverrideDropTask( Test ),

      ethSenderOverrideShow in Compile <<= ethSenderOverrideShowTask( Compile ),

      ethSenderOverrideShow in Test <<= ethSenderOverrideShowTask( Test ),

      ethSendEther in Compile <<= ethSendEtherTask( Compile ),

      ethSendEther in Test <<= ethSendEtherTask( Test ),

      ethSolidityCompile in Compile <<= ethSolidityCompileTask,

      ethSolidityChooseCompiler in Compile <<= ethSolidityChooseCompilerTask,

      ethSolidityInstallCompiler in Compile <<= ethSolidityInstallCompilerTask,

      ethSolidityShowCompiler in Compile <<= ethSolidityShowCompilerTask,

      ethTestrpcLocalStart in Test <<= ethTestrpcLocalStartTask,

      ethTestrpcLocalStop in Test <<= ethTestrpcLocalStopTask,

      xethDefaultGasPrice in Compile <<= xethDefaultGasPriceTask( Compile ),

      xethDefaultGasPrice in Test <<= xethDefaultGasPriceTask( Test ),

      xethFindCacheAliasesIfAvailable in Compile <<= xethFindCacheAliasesIfAvailableTask( Compile ).storeAs( xethFindCacheAliasesIfAvailable in Compile ).triggeredBy( xethTriggerDirtyAliasCache ),

      xethFindCacheAliasesIfAvailable in Test <<= xethFindCacheAliasesIfAvailableTask( Test ).storeAs( xethFindCacheAliasesIfAvailable in Test ).triggeredBy( xethTriggerDirtyAliasCache ),

      xethFindCacheOmitDupsCurrentCompilations in Compile <<= xethFindCacheOmitDupsCurrentCompilationsTask storeAs( xethFindCacheOmitDupsCurrentCompilations in Compile ) triggeredBy( ethSolidityCompile in Compile ),

      xethFindCacheSessionSolidityCompilerKeys in Compile <<= xethFindCacheSessionSolidityCompilerKeysTask.storeAs( xethFindCacheSessionSolidityCompilerKeys in Compile ).triggeredBy( xethTriggerDirtySolidityCompilerList ),

      xethFindCurrentSender in Compile <<= xethFindCurrentSenderTask( Compile ),

      xethFindCurrentSender in Test <<= xethFindCurrentSenderTask( Test ),

      xethFindCurrentSolidityCompiler in Compile <<= xethFindCurrentSolidityCompilerTask,

      // we don't scope the gas override tasks for now
      // since any gas override gets used in tests as well as other contexts
      // we may bifurcate and scope this in the future
      xethGasOverrideSet <<= xethGasOverrideSetTask,

      xethGasOverrideDrop <<= xethGasOverrideDropTask,

      xethGasOverrideShow <<= xethGasOverrideShowTask,

      xethGasPrice in Compile <<= xethGasPriceTask( Compile ),

      xethGasPrice in Test <<= xethGasPriceTask( Test ),

      xethGasPriceOverrideSet <<= xethGasPriceOverrideSetTask,

      xethGasPriceOverrideDrop <<= xethGasPriceOverrideDropTask,

      xethGasPriceOverrideShow <<= xethGasPriceOverrideShowTask,

      xethGenKeyPair <<= xethGenKeyPairTask, // global config scope seems appropriate

      xethGenScalaStubsAndTestingResources in Compile <<= xethGenScalaStubsAndTestingResourcesTask( Compile ),

      xethGenScalaStubsAndTestingResources in Test <<= xethGenScalaStubsAndTestingResourcesTask( Test ).dependsOn( Keys.compile in Compile ),

      xethInvokeData in Compile <<= xethInvokeDataTask( Compile ),

      xethInvokeData in Test <<= xethInvokeDataTask( Test ),

      xethKeystoreCreateWalletV3Pbkdf2 <<= xethKeystoreCreateWalletV3Pbkdf2Task, // global config scope seems appropriate

      xethKeystoreCreateWalletV3Scrypt <<= xethKeystoreCreateWalletV3ScryptTask, // global config scope seems appropriate

      xethLoadAbiFor in Compile <<= xethLoadAbiForTask( Compile ),

      xethLoadAbiFor in Test <<= xethLoadAbiForTask( Test ),

      xethLoadCompilationsKeepDups in Compile <<= xethLoadCompilationsKeepDupsTask,

      xethLoadCompilationsOmitDups in Compile <<= xethLoadCompilationsOmitDupsTask,

      xethLoadWalletV3 in Compile <<= xethLoadWalletV3Task( Compile ),

      xethLoadWalletV3 in Test <<= xethLoadWalletV3Task( Test ),

      xethLoadWalletV3For in Compile <<= xethLoadWalletV3ForTask( Compile ),

      xethLoadWalletV3For in Test <<= xethLoadWalletV3ForTask( Test ),

      xethNamedAbis in Compile <<= xethNamedAbisTask,

      xethNextNonce in Compile <<= xethNextNonceTask( Compile ),

      xethNextNonce in Test <<= xethNextNonceTask( Test ),

      xethQueryRepositoryDatabase <<= xethQueryRepositoryDatabaseTask, // we leave this unscoped, just because scoping it to Compile seems weird

      // we leave triggers unscoped, not for any particular reason
      // (we haven't tried scoping them and seen a problem)
      xethTriggerDirtyAliasCache <<= xethTriggerDirtyAliasCacheTask, // this is a no-op, its execution just triggers a re-caching of aliases

      xethTriggerDirtySolidityCompilerList <<= xethTriggerDirtySolidityCompilerListTask, // this is a no-op, its execution just triggers a re-caching of aliases

      xethUpdateContractDatabase in Compile <<= xethUpdateContractDatabaseTask( Compile ),

      xethUpdateContractDatabase in Test <<= xethUpdateContractDatabaseTask( Test ),

      xethUpdateRepositoryDatabase <<= xethUpdateRepositoryDatabaseTask, // we leave this unscoped, just because scoping it to Compile seems weird

      xethUpdateSessionSolidityCompilers in Compile <<= xethUpdateSessionSolidityCompilersTask,

      commands += ethTestrpcLocalRestartCommand,

      Keys.compile in Compile := (Keys.compile in Compile).dependsOn(ethSolidityCompile in Compile).value,

      onLoad in Global := {
        val origF : State => State = (onLoad in Global).value
        val newF  : State => State = ( state : State ) => {
          def attemptAdvanceStateWithTask[T]( taskKey : Def.ScopedKey[Task[T]], startState : State ) : State = {
            Project.runTask( taskKey, startState ) match {
              case None => {
                WARNING.log(s"Huh? Key '${taskKey}' was undefined in the original state. Ignoring attempt to run that task in onLoad.")
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

          val lastState = origF( state )
          val state1 = attemptAdvanceStateWithTask( xethFindCacheAliasesIfAvailable in Compile,          lastState )
          val state2 = attemptAdvanceStateWithTask( xethFindCacheAliasesIfAvailable in Test,             state1    )
          val state3 = attemptAdvanceStateWithTask( xethFindCacheSessionSolidityCompilerKeys in Compile, state2    )
          state3
        }
        newF
      },

      sourceGenerators in Compile += (xethGenScalaStubsAndTestingResources in Compile).taskValue,

      sourceGenerators in Test += (xethGenScalaStubsAndTestingResources in Test).taskValue,

      watchSources ++= {
        val dir = (ethSoliditySource in Compile).value
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

    // commands

    val ethTestrpcLocalRestartCommand = Command.command( "ethTestrpcLocalRestart" ) { state =>
      "ethTestrpcLocalStop" :: "ethTestrpcLocalStart" :: state
    }

    // task definitions

    def ethAbiForgetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val blockchainId = (ethBlockchainId in config).value
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

    def ethAbiListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
      val blockchainId = (ethBlockchainId in config).value
      val log = streams.value.log

      val addresses = repository.Database.getMemorizedContractAbiAddresses( blockchainId ).get

      val cap = "+" + span(44) + "+"
      val header = "Contracts with Memorized ABIs"
      println( cap )
      println( f"| $header%-42s |" )
      println( cap )
      addresses.foreach { address =>
        val ka = s"0x${address.hex}"
        val aliasesArrow = leftwardAliasesArrowOrEmpty( blockchainId, address ).get
        println( f"| $ka%-42s |" +  aliasesArrow )
      }
      println( cap )

    }

    def ethAbiMemorizeTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
      val blockchainId = (ethBlockchainId in config).value
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

    def ethAliasDropTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genAliasParser )

      Def.inputTaskDyn {
        val log = streams.value.log
        val blockchainId = (ethBlockchainId in config).value

        // not sure why, but without this xethFindCacheAliasesIfAvailable, which should be triggered by the parser,
        // sometimes fails initialize the parser
        val ensureAliases = (xethFindCacheAliasesIfAvailable in config)

        val alias = parser.parsed
        val check = repository.Database.dropAlias( blockchainId, alias ).get // assert no database problem
        if (check) log.info( s"Alias '${alias}' successfully dropped (for blockchain '${blockchainId}').")
        else log.warn( s"Alias '${alias}' is not defined (on blockchain '${blockchainId}'), and so could not be dropped." )

        Def.taskDyn {
          xethTriggerDirtyAliasCache
        }
      }
    }

    def ethAliasListTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      val blockchainId = (ethBlockchainId in config).value
      val faliases = repository.Database.findAllAliases( blockchainId )
      faliases.fold(
        _ => log.warn("Could not read aliases from repository database."),
        aliases => aliases.foreach { case (alias, address) => println( s"${alias} -> 0x${address.hex}" ) }
      )
    }

    def ethAliasSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTaskDyn {
      val log = streams.value.log
      val blockchainId = (ethBlockchainId in config).value
      val ( alias, address ) = NewAliasParser.parsed
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

    def ethBalanceTask( config : Configuration ) : Initialize[InputTask[BigDecimal]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genOptionalGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = (ethJsonRpcUrl in config).value
        val mbAddress = parser.parsed
        val address = mbAddress.getOrElse( (xethFindCurrentSender in config).value.get )
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc.Client.BlockNumber.Latest, Denominations.Ether )
        result.denominated
      }
    }

    def ethBalanceInWeiTask( config : Configuration ) : Initialize[InputTask[BigInt]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genOptionalGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = (ethJsonRpcUrl in config).value
        val mbAddress = parser.parsed
        val address = mbAddress.getOrElse( (xethFindCurrentSender in config).value.get )
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc.Client.BlockNumber.Latest, Denominations.Wei )
        result.wei
      }
    }

    def ethCompilationsCullTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      val fcount = repository.Database.cullUndeployedCompilations()
      val count = fcount.get
      log.info( s"Removed $count undeployed compilations from the repository database." )
    }

    def ethCompilationsInspectTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genContractAddressOrCodeHashParser )

      Def.inputTask {
        val blockchainId = (ethBlockchainId in config).value

        println()
        val cap =     "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-="
        val minicap = "------------------------------------------------------------------------"
        println( cap )
        println("                       CONTRACT INFO DUMP")
        println( cap )

        def section( title : String, body : Option[String], hex : Boolean = false ) : Unit = body.foreach { b =>
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

    def ethCompilationsListTask : Initialize[Task[Unit]] = Def.task {
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

    def ethDeployAutoTask( config : Configuration ) : Initialize[Task[immutable.Map[String,Either[EthHash,ClientTransactionReceipt]]]] = Def.task {
      val s = state.value
      val autoDeployContracts = (ethDeployAutoContracts in config).?.value
      autoDeployContracts.fold( immutable.Map.empty[String,Either[EthHash,ClientTransactionReceipt]] ) { seq =>
        val tuples = {
          seq map { nameAndArgs =>
	    val extract = Project.extract(s)
	    val (_, eitherHashOrReceipt) = extract.runInputTask(ethDeployOnly in config, nameAndArgs, s)
            val name = nameAndArgs.split("""\s+""").head // we should already have died if this would fail
            ( name, eitherHashOrReceipt )
          }
        }
        tuples.toMap
      }
    }

    def ethDeployOnlyTask( config : Configuration ) : Initialize[InputTask[Either[EthHash,ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(xethFindCacheOmitDupsCurrentCompilations)( genContractNamesConstructorInputsParser )

      Def.inputTask {
        val s = state.value
        val is = interactionService.value
        val log = streams.value.log
        val blockchainId = (ethBlockchainId in config).value
        val ephemeralBlockchains = xethEphemeralBlockchains.value
        val jsonRpcUrl = (ethJsonRpcUrl in config).value
        val ( contractName, extraData ) = parser.parsed
        val ( compilation, inputsBytes ) = {
          extraData match {
            case None => {
              // at the time of parsing, a compiled contract is not available. we'll force compilation now, but can't accept contructor arguments
              val contractsMap = (xethLoadCompilationsOmitDups in Compile).value
              val compilation = contractsMap( contractName )
              ( compilation, immutable.Seq.empty[Byte] )
            }
            case Some( ( inputs, abi, compilation ) ) => {
              // at the time of parsing, a compiled contract is available, so we've decoded constructor inputs( if any )
              ( compilation, ethabi.constructorCallData( inputs, abi ).get ) // asserts successful encoding of params
            }
          }
        }
        val inputsHex = inputsBytes.hex
        val codeHex = compilation.code
        val dataHex = codeHex ++ inputsHex
        val sender = (xethFindCurrentSender in config).value.get
        val nextNonce = (xethNextNonce in config).value
        val markup = ethGasMarkup.value
        val gasPrice = (xethGasPrice in config).value
        val gas = computeGas( log, jsonRpcUrl, Some(sender), None, None, Some( dataHex.decodeHex.toImmutableSeq ), jsonrpc.Client.BlockNumber.Pending, markup )
        val autoRelockSeconds = ethKeystoreAutoRelockSeconds.value
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, dataHex.decodeHex.toImmutableSeq )
        val privateKey = findCachePrivateKey( s, log, is, blockchainId, sender, autoRelockSeconds, true )
        val updateChangedDb = (xethUpdateContractDatabase in Compile).value
        val txnHash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Contract '${contractName}' deployed in transaction '0x${txnHash.hex}'." )
        val mbReceipt = awaitTransactionReceipt( log, jsonRpcUrl, txnHash, PollSeconds, PollAttempts )
        mbReceipt.fold( Left( txnHash ) : Either[EthHash,ClientTransactionReceipt] ) { receipt =>
          receipt.contractAddress.foreach { ca =>
            log.info( s"Contract '${contractName}' has been assigned address '0x${ca.hex}'." )

            if (! ephemeralBlockchains.contains( blockchainId ) ) {
              val dbCheck = {
                import compilation.info._
                repository.Database.insertNewDeployment( blockchainId, ca, codeHex, sender, txnHash, inputsBytes )
              }
              if ( dbCheck.isFailed ) {
                dbCheck.xwarn("Could not insert information about deployed contract into the repository database.")
                log.warn("Could not insert information about deployed contract into the repository database. See 'sbt-ethereum.log' for more information.")
              }
            }
          }

          Right( receipt ) : Either[EthHash,ClientTransactionReceipt]
        }
      }
    }

    def ethKeystoreInspectWalletV3Task( config : Configuration ) : Initialize[InputTask[Unit]] = Def.inputTask {
      val keystoreDirs = ethKeystoreLocationsV3.value
      val w = (xethLoadWalletV3For in config).evaluated.getOrElse( unknownWallet( keystoreDirs ) )
      println( Json.stringify( w.withLowerCaseKeys ) )
    }

    def ethInvokeConstantTask( config : Configuration ) : Initialize[InputTask[(Abi.Function,immutable.Seq[DecodedReturnValue])]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = true ) )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = (ethJsonRpcUrl in config).value
        val from = (xethFindCurrentSender in config).value.toOption
        val markup = ethGasMarkup.value
        val gasPrice = (xethGasPrice in config).value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        if (! function.constant ) {
          log.warn( s"Function '${function.name}' is not marked constant! An ephemeral call may not succeed, and in any case, no changes to the state of the blockchain will be preserved." )
        }
        val amount = mbWei.getOrElse( Zero )
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Call data for function call: ${callData.hex}" )

        val gas = computeGas( log, jsonRpcUrl, from, Some(contractAddress), Some( amount ), Some( callData ), jsonrpc.Client.BlockNumber.Pending, markup )

        val rawResult = doEthCallEphemeral( log, jsonRpcUrl, from, contractAddress, Some(gas), Some( gasPrice ), Some( amount ), Some( callData ), jsonrpc.Client.BlockNumber.Latest )
        log.info( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.info( s"Raw result of call to function '${function.name}': 0x${rawResult.hex}" )
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

            def formatResult( idx : Int, result : DecodedReturnValue ) : String = {
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
    }

    def ethInvokeTransactionTask( config : Configuration ) : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val s = state.value
        val log = streams.value.log
        val is = interactionService.value
        val blockchainId = (ethBlockchainId in config).value
        val jsonRpcUrl = (ethJsonRpcUrl in config).value
        val caller = (xethFindCurrentSender in config).value.get
        val nextNonce = (xethNextNonce in config).value
        val markup = ethGasMarkup.value
        val gasPrice = (xethGasPrice in config).value
        val autoRelockSeconds = ethKeystoreAutoRelockSeconds.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        val amount = mbWei.getOrElse( Zero )
        val privateKey = findCachePrivateKey(s, log, is, blockchainId, caller, autoRelockSeconds, true )
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = computeGas( log, jsonRpcUrl, Some(caller), Some(contractAddress), Some( amount ), Some( callData ), jsonrpc.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        log.info( s"Gas price set to ${gasPrice} wei" )
        val estdCost = gasPrice * gas
        log.info( s"Estimated transaction cost ${estdCost} wei (${Ether.fromWei( estdCost )} ether)." )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), contractAddress, Unsigned256( amount ), callData )
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei to address '0x${contractAddress.hex}' in transaction '0x${hash.hex}'.""" )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      }
    }

    def ethKeystoreListTask( config : Configuration ) : Initialize[Task[immutable.SortedMap[EthAddress,immutable.SortedSet[String]]]] = Def.task {
      val keystoresV3  = ethKeystoreLocationsV3.value
      val log          = streams.value.log
      val blockchainId = (ethBlockchainId in config).value
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

    def ethKeystoreMemorizeWalletV3Task : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      val is = interactionService.value
      val w = readV3Wallet( is )
      val address = w.address // a very cursory check of the wallet, NOT full validation
      repository.Keystore.V3.storeWallet( w ).get // asserts success
      log.info( s"Imported JSON wallet for address '0x${address.hex}', but have not validated it.")
      log.info( s"Consider validating the JSON using 'ethKeystoreValidateWalletV3 0x${address.hex}." )
    }

    def ethKeystoreRevealPrivateKeyTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genGenericAddressParser )

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

    def ethKeystoreValidateWalletV3Task( config : Configuration ) : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val is = interactionService.value
        val keystoreDirs = ethKeystoreLocationsV3.value
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

    def ethSelfPingTask( config : Configuration ) : Initialize[Task[Option[ClientTransactionReceipt]]] = Def.task {
      val address  = (xethFindCurrentSender in config).value.get
      val sendArgs = s" ${address.hex} 0 wei"
      val log = streams.value.log

      val s = state.value
      val extract = Project.extract(s)
      val (_, result) = extract.runInputTask(ethSendEther in config, sendArgs, s)

      val out = result
      out.fold( log.warn( s"Ping failed! Our attempt to send 0 ether from '${address.hex}' to itself may or may not eventually succeed, but we've timed out before hearing back." ) ) { receipt =>
        log.info( "Ping succeeded!" )
        log.info( s"Sent 0 ether from '${address.hex}' to itself in transaction '0x${receipt.transactionHash.hex}'" )
      }
      out
    }

    def ethSendEtherTask( config : Configuration ) : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser( xethFindCacheAliasesIfAvailable in config )( genEthSendEtherParser )

      Def.inputTask {
        val s = state.value
        val log = streams.value.log
        val is = interactionService.value
        val blockchainId = (ethBlockchainId in config).value
        val jsonRpcUrl = (ethJsonRpcUrl in config).value
        val from = (xethFindCurrentSender in config).value.get
        val (to, amount) = parser.parsed
        val nextNonce = (xethNextNonce in config).value
        val markup = ethGasMarkup.value
        val gasPrice = (xethGasPrice in config).value
        val autoRelockSeconds = ethKeystoreAutoRelockSeconds.value
        val gas = computeGas( log, jsonRpcUrl, Some(from), Some(to), Some(amount), Some( EmptyBytes ), jsonrpc.Client.BlockNumber.Pending, markup )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), to, Unsigned256( amount ), EmptyBytes )
        val privateKey = findCachePrivateKey( s, log, is, blockchainId, from, autoRelockSeconds, true )
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Sent ${amount} wei to address '0x${to.hex}' in transaction '0x${hash.hex}'." )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      }
    }

    def ethSenderOverrideSetTask( config : Configuration ) : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genGenericAddressParser )
      val configSenderOverride = senderOverride( config )

      Def.inputTask {
        configSenderOverride.synchronized {
          val log = streams.value.log
          val blockchainId = (ethBlockchainId in config).value
          val address = parser.parsed
          val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s", aliases '$str')" ) )

          configSenderOverride.set( Some( ( blockchainId, address ) ) )

          log.info( s"Sender override set to '0x${address.hex}' (on blockchain '$blockchainId'${aliasesPart})." )
        }
      }
    }

    def ethSenderOverrideDropTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
      val configSenderOverride = senderOverride( config )
      configSenderOverride.synchronized {
        val log = streams.value.log
        SenderOverride.set( None )
        log.info("No sender override is now set. Effective sender will be determined by 'ethSender' setting or 'defaultSender' alias.")
      }
    }

    def ethSenderOverrideShowTask( config : Configuration ) : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log

      val blockchainId = (ethBlockchainId in config).value

      val mbSenderOverride = getSenderOverride( config )( log, blockchainId )

      val message = mbSenderOverride.fold( s"No sender override is currently set (for configuration '${config}')." ) { address =>
        val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s", aliases '$str')" ) )
        s"A sender override is set, address '${address.hex}' (on blockchain '$blockchainId'${aliasesPart}, configuration '${config}')."
      }

      log.info( message )
    }

    def ethSolidityChooseCompilerTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheSessionSolidityCompilerKeys)( genLiteralSetParser )

      Def.inputTask {
        val log = streams.value.log
        val key = parser.parsed
        val mbNewCompiler = SessionSolidityCompilers.get.get.get( key )
        val newCompilerTuple = mbNewCompiler.map( nc => ( key, nc ) )
        CurrentSolidityCompiler.set( newCompilerTuple )
        log.info( s"Set compiler to '$key'" )
      }
    }

    def ethSolidityCompileTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log

      val compiler = (xethFindCurrentSolidityCompiler in Compile).value

      val includeStrings = ethIncludeLocations.value

      val solSource      = (ethSoliditySource in Compile).value
      val solDestination = (ethSolidityDestination in Compile).value

      val baseDir = baseDirectory.value

      val includeLocations = includeStrings.map( SourceFile.Location.apply( baseDir, _ ) )

      ResolveCompileSolidity.doResolveCompile( log, compiler, includeLocations, solSource, solDestination )
    }

    def ethSolidityInstallCompilerTask : Initialize[InputTask[Unit]] = Def.inputTaskDyn {
      val log = streams.value.log

      val mbVersion = SolcJVersionParser.parsed

      val versionToInstall = mbVersion.getOrElse( LatestSolcJVersion )

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
            }
          }
        }
      }
      check.get // throw if a failure occurred

      Def.taskDyn {
        xethTriggerDirtySolidityCompilerList // causes parse cache and SessionSolidityCompilers to get updated
      }
    }

    def ethSolidityShowCompilerTask : Initialize[Task[Unit]] = Def.task {
      val log       = streams.value.log
      val ensureSet = (xethFindCurrentSolidityCompiler in Compile).value
      val ( key, compiler ) = CurrentSolidityCompiler.get.get
      log.info( s"Current solidity compiler '$key', which refers to $compiler." )
    }

    def ethTestrpcLocalStartTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log

      def newTestrpcProcess = {
        try {
          val plogger = ProcessLogger(
            line => log.info( s"testrpc: ${line}" ),
            line => log.warn( s"testrpc: ${line}" )
          )
          log.info(s"Executing command '${testing.Default.TestrpcCommand}'")
          Process( testing.Default.TestrpcCommandParsed ).run( plogger )
        } catch {
          case t : Throwable => {
            log.error(s"Failed to start a local testrpc process with command '${testing.Default.TestrpcCommand}'.")
            throw t
          }
        }
      }

      LocalTestrpc synchronized {
        LocalTestrpc.get match {
          case Some( process ) => log.warn("A local testrpc environment is already running. To restart it, please try 'ethTestrpcLocalRestart'.")
          case _               => {
            LocalTestrpc.set( Some( newTestrpcProcess ) )
            log.info("A local testrpc process has been started.")
          }
        }
      }
    }

    def ethTestrpcLocalStopTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log

      LocalTestrpc synchronized {
        LocalTestrpc.get match {
          case Some( process ) => {
            LocalTestrpc.set( None )
            process.destroy()
            log.info("A local testrpc environment was running but has been stopped.")
          }
          case _                                  => {
            log.warn("No local testrpc process is running.")
          }
        }
      }
    }

    def xethDefaultGasPriceTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
      val log        = streams.value.log
      val jsonRpcUrl = (ethJsonRpcUrl in config).value
      doGetDefaultGasPrice( log, jsonRpcUrl )
    }

    def xethFindCacheAliasesIfAvailableTask( config : Configuration ) : Initialize[Task[Tuple2[String,Option[immutable.SortedMap[String,EthAddress]]]]] = Def.task {
      val blockchainId = (ethBlockchainId in config).value
      val mbAliases    = repository.Database.findAllAliases( blockchainId ).toOption
      ( blockchainId, mbAliases )
    }

    def xethFindCacheOmitDupsCurrentCompilationsTask : Initialize[Task[immutable.Map[String,jsonrpc.Compilation.Contract]]] = Def.task {
      (xethLoadCompilationsOmitDups in Compile).value
    }

    def xethFindCacheSessionSolidityCompilerKeysTask : Initialize[Task[immutable.Set[String]]] = Def.task {
      val log = streams.value.log
      log.info("Updating available solidity compiler set.")
      val currentSessionCompilers = (xethUpdateSessionSolidityCompilers in Compile).value
      currentSessionCompilers.keySet
    }

    def xethFindCurrentSenderTask( config : Configuration ) : Initialize[Task[Failable[EthAddress]]] = Def.task {
      Failable {
        val log = streams.value.log
        val blockchainId = (ethBlockchainId in config).value

        val mbSenderOverride = getSenderOverride( config )( log, blockchainId )
        mbSenderOverride match {
          case Some( address ) => {
            // val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s", aliases '$str')" ) )
            // log.info( s"Using sender override address '0x${address.hex}' (on blockchain $blockchainId${aliasesPart}" )
            address
          }
          case None => {
            val mbAddrStr = (ethSender in config).?.value
            mbAddrStr match {
              case Some( addrStr ) => EthAddress( addrStr )
              case None            => {
                val mbProperty = Option( System.getProperty( EthSenderSystemProperty ) )
                val mbEnvVar   = Option( System.getenv( EthSenderEnvironmentVariable ) )

                val mbExternalEthAddress = (mbProperty orElse mbEnvVar).map( EthAddress.apply )

                mbExternalEthAddress.getOrElse {
                  val mbDefaultSenderAddress = repository.Database.findAddressByAlias( blockchainId, DefaultSenderAlias ).get

                  mbDefaultSenderAddress match {
                    case Some( address ) => address
                    case None => throw new SenderNotAvailableException(s"Cannot find an 'ethSender' or default sender (blockchain '${blockchainId}, configuration '${config}')'")
                  }
                }
              }
            }
          }
        }
      }
    }

    def xethFindCurrentSolidityCompilerTask : Initialize[Task[Compiler.Solidity]] = Def.task {
      import Compiler.Solidity._

      // val compilerKeys = xethFindCacheSessionSolidityCompilerKeys.value
      val sessionCompilers = SessionSolidityCompilers.get.getOrElse( throw new Exception("Internal error -- caching compiler keys during onLoad should have forced sessionCompilers to be set, but it's not." ) )
      val compilerKeys = sessionCompilers.keySet

      val mbExplicitJsonRpcUrl = ethJsonRpcUrl.?.value

      CurrentSolidityCompiler.get.map( _._2).getOrElse {
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

        CurrentSolidityCompiler.set( Some( Tuple2( key, compiler ) ) )

        compiler
      }
    }

    def xethGasOverrideSetTask : Initialize[InputTask[Unit]] = Def.inputTask {
      val log = streams.value.log
      val amount = integerParser("<gas override>").parsed
      GasOverride.set( Some( amount ) )
      log.info( s"Gas override set to ${amount}." )
    }

    def xethGasOverrideDropTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      GasOverride.set( None )
      log.info("No gas override is now set. Quantities of gas will be automatically computed.")
    }

    def xethGasOverrideShowTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      GasOverride.get match {
        case Some( value ) => log.info( s"A gas override is set, with value ${value}." )
        case None          => log.info( "No gas override is currently set." )
      }
    }

    def xethGasPriceTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
      val log        = streams.value.log
      val jsonRpcUrl = (ethJsonRpcUrl in config).value

      val markup          = ethGasPriceMarkup.value
      val defaultGasPrice = (xethDefaultGasPrice in config).value

      GasPriceOverride.get match {
        case Some( gasPriceOverride ) => gasPriceOverride
        case None                     => rounded( BigDecimal(defaultGasPrice) * BigDecimal(1 + markup) ).toBigInt
      }
    }

    def xethGasPriceOverrideSetTask : Initialize[InputTask[Unit]] = Def.inputTask {
      val log = streams.value.log
      val amount = valueInWeiParser("<gas price override>").parsed
      GasPriceOverride.set( Some( amount ) )
      log.info( s"Gas price override set to ${amount}." )
    }

    def xethGasPriceOverrideDropTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      GasPriceOverride.set( None )
      log.info("No gas price override is now set. Gas price will be automatically marked-up from your ethereum node's current default value.")
    }

    def xethGasPriceOverrideShowTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      GasPriceOverride.get match {
        case Some( value ) => log.info( s"A gas price override is set, with value ${value}." )
        case None          => log.info( "No gas price override is currently set." )
      }
    }

    def xethGenKeyPairTask : Initialize[Task[EthKeyPair]] = Def.task {
      val log = streams.value.log
      val out = EthKeyPair( ethEntropySource.value )

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
     * TODO: Break faucet private key into a setting. Currently hardcoded.
     *
     */
    def xethGenScalaStubsAndTestingResourcesTask( config : Configuration ) : Initialize[Task[immutable.Seq[File]]] = Def.task {
      val log = streams.value.log

      // Used for both Compile and Test
      val mbStubPackage = ethPackageScalaStubs.?.value
      val currentCompilations = (xethFindCacheOmitDupsCurrentCompilations in Compile).value
      val namedAbis = (xethNamedAbis in Compile).value
      val dependencies = libraryDependencies.value

      // Used only for Test
      val testingResourcesObjectName = (xethTestingResourcesObjectName in Test).value
      val testingEthJsonRpcUrl = (ethJsonRpcUrl in Test).value

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
          ( name,  contract.info.mbAbi.map( abiStr => Json.parse( abiStr ).as[Abi] ) )
        }
        sureNamedAbis ++ mbCompilationAbis
      }

      def skipNoPackage : immutable.Seq[File] = {
        log.info("No Scala stubs will be generated as the setting 'ethPackageScalaStubs' has not ben set.")
        log.info("""If you'd like Scala stubs to be generated, please define 'ethPackageScalaStubs' and be sure to include a recent version of "com.mchange" %% "consuela"  in libraryDependencies.""")
        immutable.Seq.empty[File]
      }

      def findBadChar( packageStr : String ) = packageStr.find( c => !(Character.isJavaIdentifierPart(c) || c == '.') )

      def findEmptyPackage( packages : Iterable[String] ) = packages.find( _ == "" )

      def findConsuela = dependencies.find( mid => mid.organization == "com.mchange" && mid.name == "consuela" )

      mbStubPackage.fold( skipNoPackage ){ stubPackage =>
        if ( findConsuela == None ) {
          val shortMessage = """Scala stub generation has been requested ('ethPackageScalaStubs' is set), but 'libraryDependencies' do not include a recent version of "com.mchange" %% "consuela""""
          val fullMessage = {
            shortMessage + ", a dependency required by stubs."
          }
          log.error( shortMessage + '.' )
          log.error( """Please add a recent version of "com.mchange" %% "consuela" to 'libraryDependencies'.""" )

          throw new SbtEthereumException( fullMessage )
        }
        findBadChar( stubPackage ) match {
          case Some( c ) => throw new SbtEthereumException( s"'ethPackageScalaStubs' contains illegal character '${c}'. ('ethPackageScalaStubs' is set to ${stubPackage}.)" )
          case None => {
            val packages = stubPackage.split("""\.""")
            findEmptyPackage( packages ) match {
              case Some( oops ) => throw new SbtEthereumException( s"'ethPackageScalaStubs' contains an empty String as a package name. ('ethPackageScalaStubs' is set to ${stubPackage}.)" )
              case None => {
                val stubsDirFilePath = packages.mkString( File.separator )
                val stubsDir = new File( scalaStubsTarget, stubsDirFilePath )
                stubsDir.mkdirs()
                if ( config != Test ) {
                  val mbFiles = allMbAbis flatMap { case ( className, mbAbi ) =>
                    def genFile( async : Boolean ) : Option[File] = {
                      mbAbi match {
                        case Some( abi ) => {
                          val ( stubClassName, gensrc ) = stub.Generator.generateContractStub( className, abi, async, stubPackage )
                          val srcFile = new File( stubsDir, s"${stubClassName}.scala" )
                          srcFile.replaceContents( gensrc, scala.io.Codec.UTF8 )
                          Some( srcFile )
                        }
                        case None => {
                          log.warn( s"No ABI definition found for contract '${className}'. Skipping Scala stub generation." )
                          None
                        }
                      }
                    }
                    genFile( false ) :: genFile( true ) :: Nil
                  }
                  mbFiles.filter( _ != None ).map( _.get ).toVector
                } else {
                  if ( allMbAbis.contains( testingResourcesObjectName ) ) { // TODO: A case insensitive check
                    log.warn( s"The name of the requested testing resources object '${testingResourcesObjectName}' conflicts with the name of a contract." )
                    log.warn(  "The testing resources object '${testingResourcesObjectName}' will not be generated." )
                    immutable.Seq.empty[File]
                  } else {
                    val gensrc = testing.TestingResourcesGenerator.generateTestingResources( testingResourcesObjectName, testingEthJsonRpcUrl, testing.Default.Faucet.pvt, stubPackage )
                    val testingResourcesFile = new File( stubsDir, s"${testingResourcesObjectName}.scala" )
                    Files.write( testingResourcesFile.toPath, gensrc.getBytes( scala.io.Codec.UTF8.charSet ) )
                    immutable.Seq( testingResourcesFile )
                  }
                }
              }
            }
          }
        }
      }
    }

    def xethInvokeDataTask( config : Configuration ) : Initialize[InputTask[immutable.Seq[Byte]]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genAddressFunctionInputsAbiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val ( contractAddress, function, args, abi ) = parser.parsed
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunctionFromStringArgs( args, abiFunction ).get // throw an Exception if we can't get the call data
        val log = streams.value.log
        log.info( s"Call data: ${callData.hex}" )
        callData
      }
    }

    def xethKeystoreCreateWalletV3Pbkdf2Task : Initialize[Task[wallet.V3]] = Def.task {
      val log   = streams.value.log
      val c     = xethWalletV3Pbkdf2C.value
      val dklen = xethWalletV3Pbkdf2DkLen.value

      val is = interactionService.value
      val keyPair = xethGenKeyPair.value
      val entropySource = ethEntropySource.value

      log.info( s"Generating V3 wallet, alogorithm=pbkdf2, c=${c}, dklen=${dklen}" )
      val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
      val w = wallet.V3.generatePbkdf2( passphrase = passphrase, c = c, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
      repository.Keystore.V3.storeWallet( w ).get // asserts success
    }

    def xethKeystoreCreateWalletV3ScryptTask : Initialize[Task[wallet.V3]] = Def.task {
      val log   = streams.value.log
      val n     = xethWalletV3ScryptN.value
      val r     = xethWalletV3ScryptR.value
      val p     = xethWalletV3ScryptP.value
      val dklen = xethWalletV3ScryptDkLen.value

      val is = interactionService.value
      val keyPair = xethGenKeyPair.value
      val entropySource = ethEntropySource.value

      log.info( s"Generating V3 wallet, alogorithm=scrypt, n=${n}, r=${r}, p=${p}, dklen=${dklen}" )
      val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
      val w = wallet.V3.generateScrypt( passphrase = passphrase, n = n, r = r, p = p, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
      repository.Keystore.V3.storeWallet( w ).get // asserts success
    }

    def xethLoadAbiForTask( config : Configuration ) : Initialize[InputTask[Abi]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genGenericAddressParser )

      Def.inputTask {
        val blockchainId = (ethBlockchainId in config).value
        abiForAddress( blockchainId, parser.parsed )
      }
    }

    def xethLoadCompilationsKeepDupsTask : Initialize[Task[immutable.Iterable[(String,jsonrpc.Compilation.Contract)]]] = Def.task {
      val log = streams.value.log

      val dummy = (ethSolidityCompile in Compile).value // ensure compilation has completed

      val dir = (ethSolidityDestination in Compile).value

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

    def xethLoadCompilationsOmitDupsTask : Initialize[Task[immutable.Map[String,jsonrpc.Compilation.Contract]]] = Def.task {
      val log = streams.value.log

      val dummy = (ethSolidityCompile in Compile).value // ensure compilation has completed

      val dir = (ethSolidityDestination in Compile).value

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
      def addContracts( tup : ( immutable.Map[String,jsonrpc.Compilation.Contract], immutable.Set[String] ), name : String ) = {
        val ( addTo, overlaps ) = tup
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
        val rawNewOverlaps = next.keySet.intersect( addTo.keySet )
        val realNewOverlaps = rawNewOverlaps.foldLeft( immutable.Set.empty[String] ){ ( cur, key ) =>
          val origCodeBcas = BaseCodeAndSuffix( addTo( key ).code )
          val nextCodeBcas = BaseCodeAndSuffix( next( key ).code )

          if ( origCodeBcas.baseCodeHex != nextCodeBcas.baseCodeHex ) cur + key else cur
        }
        ( addAllKeepShorterSource( addTo, next ), overlaps ++ realNewOverlaps )
      }

      val ( rawCompilations, duplicates ) = dir.list.filter( _.endsWith( ".json" ) ).foldLeft( ( immutable.Map.empty[String,jsonrpc.Compilation.Contract], immutable.Set.empty[String] ) )( addContracts )
      if ( !duplicates.isEmpty ) {
        val dupsStr = duplicates.mkString(", ")
        log.warn( s"The project contains mutiple contracts and/or libraries that have identical names but compile to distinct code: $dupsStr" )
        if ( duplicates.size > 1 ) {
          log.warn( s"Units '$dupsStr' have been dropped from the deployable compilations list as references would be ambiguous." )
        } else {
          log.warn( s"Unit '$dupsStr' has been dropped from the deployable compilations list as references would be ambiguous." )
        }
        rawCompilations -- duplicates
      } else {
        rawCompilations
      }
    }

    def xethLoadWalletV3Task( config : Configuration ) : Initialize[Task[Option[wallet.V3]]] = Def.task {
      val s = state.value
      val addressStr = (xethFindCurrentSender in config).value.get.hex
      val extract = Project.extract(s)
      val (_, result) = extract.runInputTask((xethLoadWalletV3For in config), addressStr, s) // config doesn't really matter here, since we provide hex rather than a config-dependent alias
      result
    }

    def xethLoadWalletV3ForTask( config : Configuration ) : Initialize[InputTask[Option[wallet.V3]]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable in config)( genGenericAddressParser )

      Def.inputTask {
        val keystoresV3 = ethKeystoreLocationsV3.value
        val log         = streams.value.log

        val blockchainId = (ethBlockchainId in config).value

        val address = parser.parsed
        val out = {
          keystoresV3
            .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
            .foldLeft( None : Option[wallet.V3] ){ ( mb, nextKeystore ) =>
            if ( mb.isEmpty ) nextKeystore.get( address ) else mb
          }
        }
        val message = {
          val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( str => s" (aliases '$str')" ) )
          out.fold( s"No V3 wallet found for '0x${address.hex}'${aliasesPart}" )( _ => s"V3 wallet found for '0x${address.hex}'${aliasesPart}" )
        }
        log.info( message )
        out
      }
    }

    def xethNamedAbisTask : Initialize[Task[immutable.Map[String,Abi]]] = Def.task {
      val log    = streams.value.log
      val srcDir = (xethNamedAbiSource in Compile).value

      def empty = immutable.Map.empty[String,Abi]

      if ( srcDir.exists) {
        if ( srcDir.isDirectory ) {
          val files = srcDir.listFiles( JsonFilter )

          def toTuple( f : File ) : ( String, Abi ) = {
          val filename = f.getName()
            val name = filename.take( filename.length - JsonFilter.DotSuffix.length ) // the filter ensures they do have the suffix
            val json = borrow ( Source.fromFile( f ) )( _.close )( _.mkString ) // is there a better way
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

    def xethNextNonceTask( config : Configuration ) : Initialize[Task[BigInt]] = Def.task {
      val log        = streams.value.log
      val jsonRpcUrl = (ethJsonRpcUrl in config).value
      doGetTransactionCount( log, jsonRpcUrl, (xethFindCurrentSender in config).value.get , jsonrpc.Client.BlockNumber.Pending )
    }

    def xethQueryRepositoryDatabaseTask : Initialize[InputTask[Unit]] = Def.inputTask {
      val log   = streams.value.log
      val query = DbQueryParser.parsed

      // removed guard of query (restriction to select),
      // since updating SQL issued via executeQuery(...)
      // usefully fails in h3

      val checkDataSource = {
        repository.Database.DataSource.map { ds =>
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
      if ( checkDataSource.isFailed ) {
        log.warn("Failed to find DataSource!")
        log.warn( checkDataSource.fail.toString )
      }
    }

    // this is a no-op, its execution just triggers a re-caching of aliases
    def xethTriggerDirtyAliasCacheTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      log.info( "Refreshing alias cache." )
    }

    // this is a no-op, its execution just triggers a re-caching of aliases
    def xethTriggerDirtySolidityCompilerListTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      log.info( "Refreshing compiler list." )
    }

    def xethUpdateContractDatabaseTask( config : Configuration ) : Initialize[Task[Boolean]] = Def.task {
      val log          = streams.value.log
      val compilations = (xethLoadCompilationsKeepDups in Compile).value // we want to "know" every contract we've seen, which might include contracts with multiple names

      repository.Database.updateContractDatabase( compilations ).get
    }

    def xethUpdateRepositoryDatabaseTask : Initialize[InputTask[Unit]] = Def.inputTask {
      val log   = streams.value.log
      val update = DbQueryParser.parsed

      val checkDataSource = {
        repository.Database.DataSource.map { ds =>
          borrow( ds.getConnection() ) { conn =>
            borrow( conn.createStatement() ) { stmt =>
              val rows = stmt.executeUpdate( update )
              log.info( s"Update succeeded: $update" )
              log.info( s"$rows rows affected." )
            }
          }
        }
      }
      if ( checkDataSource.isFailed ) {
        log.warn("Failed to find DataSource!")
        log.warn( checkDataSource.fail.toString )
      }
    }

    def xethUpdateSessionSolidityCompilersTask : Initialize[Task[immutable.SortedMap[String,Compiler.Solidity]]] = Def.task {
      import Compiler.Solidity._

      val netcompileUrl = ethNetcompileUrl.?.value
      val jsonRpcUrl    = (ethJsonRpcUrl in Compile).value // we use the main (compile) configuration, don't bother with a test json-rpc for compilation

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
      SessionSolidityCompilers.set( Some( out ) )
      out
    }

    // helper functions

    private def allUnitsValue( valueInWei : BigInt ) = s"${valueInWei} wei (${Denominations.Ether.fromWei(valueInWei)} ether, ${Denominations.Finney.fromWei(valueInWei)} finney, ${Denominations.Szabo.fromWei(valueInWei)} szabo)"

    def senderOverride( config : Configuration ) = if ( config == Test ) TestSenderOverride else SenderOverride

    private def getSenderOverride( config : Configuration )( log : sbt.Logger, blockchainId : String ) : Option[EthAddress] = {
      val configSenderOverride = senderOverride( config )

      configSenderOverride.synchronized {
        val BlockchainId = blockchainId

        configSenderOverride.get match {
          case Some( ( BlockchainId, address ) ) => Some( address )
          case Some( (badBlockchainId, _) ) => {
            log.info( s"A sender override was set for the blockchain '$badBlockchainId', but that is no longer the current 'ethBlockchainId'. The sender override is stale and will be dropped." )
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
      GasOverride.get match {
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
      mbGethWallet.fold {
        log.info( "No wallet available. Trying passphrase as hex private key." )
        EthPrivateKey( credential )
      }{ gethWallet =>
        try {
          wallet.V3.decodePrivateKey( gethWallet, credential )
        } catch {
          case v3e : wallet.V3.Exception => {
            log.warn("Credential is not correct geth wallet passphrase. Trying as hex private key.")
            val maybe = EthPrivateKey( credential )
            val desiredAddress = gethWallet.address
            if (maybe.toPublicKey.toAddress != desiredAddress) {
              throw new SbtEthereumException( s"The hex private key supplied does not unlock the wallet for '0x${desiredAddress.hex}'" )
            } else {
              maybe
            }
          }
        }
      }
    }

    private final val CantReadInteraction = "InteractionService failed to read"

    def findCachePrivateKey(
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

        val aliasesPart = commaSepAliasesForAddress( blockchainId, address ).fold( _ => "", _.fold("")( commasep => s", aliases '$commasep'" ) )

        log.info( s"Unlocking address '0x${address.hex}' (on blockchain '$blockchainId'$aliasesPart)" )

        val credential = readCredential( is, address )

        val extract = Project.extract(state)
        val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For in Compile, address.hex, state) // the config scope of xethLoadWalletV3For doesn't matter here, since we provide hex, not an alias

        val privateKey = findPrivateKey( log, mbWallet, credential )
        CurrentAddress.set( UnlockedAddress( blockchainId, address, privateKey, System.currentTimeMillis + (autoRelockSeconds * 1000) ) )
        privateKey
      }
      def goodCached : Option[EthPrivateKey] = {
        // caps for value matches rather than variable names
        val BlockchainId = blockchainId
        val Address = address
        val now = System.currentTimeMillis
        CurrentAddress.get match {
          case UnlockedAddress( BlockchainId, Address, privateKey, autoRelockTime ) if (now < autoRelockTime ) => { // if blockchainId and/or ethSender has changed, this will no longer match
            val aliasesPart = commaSepAliasesForAddress( BlockchainId, Address ).fold( _ => "", _.fold("")( commasep => s", aliases '$commasep'" ) )
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
              CurrentAddress.set( NoAddress )
              throw new SenderNotAvailableException( s"Use of sender address '0x${address.hex}' (on blockchain '${blockchainId}'${aliasesPart}) vetoed by user." )
            }
          }
          case _ => { // if we don't match, we reset / forget the cached private key
            CurrentAddress.set( NoAddress )
            None
          }
        }
      }

      // special case for testing...
      if ( address == testing.Default.Faucet.address ) {
        testing.Default.Faucet.pvt
      } else {
        CurrentAddress.synchronized {
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

    // some formatting functions for ascii tables
    private def emptyOrHex( str : String ) = if (str == null) "" else s"0x$str"
    private def blankNull( str : String ) = if (str == null) "" else str
    private def span( len : Int ) = (0 until len).map(_ => "-").mkString

    private def commaSepAliasesForAddress( blockchainId : String, address : EthAddress ) : Failable[Option[String]] = {
      repository.Database.findAliasesByAddress( blockchainId, address ).map( seq => if ( seq.isEmpty ) None else Some( seq.mkString( ", " ) ) )
    }
    private def leftwardAliasesArrowOrEmpty( blockchainId : String, address : EthAddress ) : Failable[String] = {
      commaSepAliasesForAddress( blockchainId, address ).map( _.fold("")( aliasesStr => s" <-- ${aliasesStr}" ) )
    }

  } // end object autoImport

  // plug-in setup

  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin && InteractionServicePlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
