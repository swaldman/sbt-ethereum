package com.mchange.sc.v1.sbtethereum

import util.{BaseCodeAndSuffix}
import compile.{Compiler, ResolveCompileSolidity, SemanticVersion, SolcJInstaller, SourceFile}

import util.EthJsonRpc._
import util.Parsers._
import util.SBinaryFormats._

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.Def.Initialize
import sbt.InteractionServiceKeys.interactionService

import sbinary._
import sbinary.DefaultProtocol._

import java.io.{BufferedInputStream,File,FileInputStream,FilenameFilter}
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

import play.api.libs.json.{Json, JsObject}

import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._
import jsonrpc20.{Abi,ClientTransactionReceipt,MapStringCompilationContractFormat}
import specification.Denominations

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations._
import com.mchange.sc.v1.consuela.ethereum.ethabi.{abiFunctionForFunctionNameAndArgs,callDataForAbiFunction,decodeReturnValuesForFunction,DecodedReturnValue,Encoder}
import scala.collection._

// XXX: provisionally, for now... but what sort of ExecutionContext would be best when?
import scala.concurrent.ExecutionContext.Implicits.global

object SbtEthereumPlugin extends AutoPlugin {

  private val EthSenderSystemProperty      = "eth.sender"
  private val EthSenderEnvironmentVariable = "ETH_SENDER"

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally we should try to log through sbt loggers
  private implicit val logger = mlogger( this )

  private trait AddressInfo;
  private final case object NoAddress                                                                                  extends AddressInfo
  private final case class  UnlockedAddress( blockchainId : String, address : EthAddress, privateKey : EthPrivateKey ) extends AddressInfo

  // MT: protected by CurrentAddress' lock
  private val CurrentAddress = new AtomicReference[AddressInfo]( NoAddress )

  private val SessionSolidityCompilers = new AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]]( None )

  private val CurrentSolidityCompiler = new AtomicReference[Option[( String, Compiler.Solidity )]]( None )

  private val BufferSize = 4096

  private val PollSeconds = 15

  private val PollAttempts = 9

  private val Zero = BigInt(0)

  private val Zero256 = Unsigned256( 0 )

  private val LatestSolcJVersion = "0.4.10"

  private val DefaultSenderAlias = "defaultSender"

  object autoImport {

    // settings

    val ethSender = settingKey[String]("The address from which transactions will be sent")

    val ethBlockchainId = settingKey[String]("A name for the network represented by ethJsonRpcUrl (e.g. 'mainnet', 'morden', 'ropsten')")

    val ethEntropySource = settingKey[SecureRandom]("The source of randomness that will be used for key generation")

    val ethIncludeLocations = settingKey[Seq[String]]("Directories or URLs that should be searched to resolve import directives, besides the source directory itself")

    val xethGasOverrides = settingKey[Map[String,BigInt]]("Map of contract names to gas limits for contract creation transactions, overriding automatic estimates")

    val ethGasMarkup = settingKey[Double]("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")

    val ethGasPriceMarkup = settingKey[Double]("Fraction by which automatically estimated gas price will be marked up (if not overridden) in executing transactions")

    val ethKeystoreLocationsV3 = settingKey[Seq[File]]("Directories from which V3 wallets can be loaded")

    val ethJsonRpcUrl = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val ethNetcompileUrl = settingKey[String]("Optional URL of an eth-netcompile service, for more reliabe network-based compilation than that available over json-rpc.")

    val ethTargetDir = settingKey[File]("Location in target directory where ethereum artifacts will be placed")

    val ethSoliditySource = settingKey[File]("Solidity source code directory")

    val ethSolidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    val xethWalletV3ScryptN = settingKey[Int]("The value to use for parameter N when generating Scrypt V3 wallets")

    val xethWalletV3ScryptR = settingKey[Int]("The value to use for parameter R when generating Scrypt V3 wallets")

    val xethWalletV3ScryptP = settingKey[Int]("The value to use for parameter P when generating Scrypt V3 wallets")

    val xethWalletV3ScryptDkLen = settingKey[Int]("The derived key length parameter used when generating Scrypt V3 wallets")

    val xethWalletV3Pbkdf2C = settingKey[Int]("The value to use for parameter C when generating pbkdf2 V3 wallets")

    val xethWalletV3Pbkdf2DkLen = settingKey[Int]("The derived key length parameter used when generating pbkdf2 V3 wallets")

    // tasks

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

    val ethDeployOnly = inputKey[Option[ClientTransactionReceipt]]("Deploys the specified named contract")

    val ethInvokeTransaction = inputKey[Option[ClientTransactionReceipt]]("Calls a function on a deployed smart contract")

    val ethKeystoreCreateWalletV3 = taskKey[wallet.V3]("Generates a new V3 wallet, using ethEntropySource as a source of randomness")

    val ethKeystoreInspectWalletV3 = inputKey[Unit]("Prints V3 wallet as JSON to the console.")

    val ethKeystoreList = taskKey[immutable.Map[EthAddress,immutable.Set[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined")

    val ethKeystoreMemorizeWalletV3 = taskKey[Unit]("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")

    val ethKeystoreRevealPrivateKey = inputKey[Unit]("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")

    val ethKeystoreValidateWalletV3 = inputKey[Unit]("Verifies that a V3 wallet can be decoded for an address, and decodes to the expected address.")

    val ethSelfPing = taskKey[Option[ClientTransactionReceipt]]("Sends 0 ether from current sender to itself")

    val ethSendEther = inputKey[Option[ClientTransactionReceipt]]("Sends ether from current sender to a specified account, format 'ethSendEther <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

    val ethSolidityCompile = taskKey[Unit]("Compiles solidity files")

    val ethSolidityInstallCompiler = inputKey[Unit]("Installs a best-attempt platform-specific solidity compiler into the sbt-ethereum repository (or choose a supported version)")

    val ethSolidityChooseCompiler = inputKey[Unit]("Manually select among solidity compilers available to this project")

    val ethSolidityShowCompiler = taskKey[Unit]("Displays currently active Solidity compiler")

    val xethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val xethFindCacheSessionSolidityCompilerKeys = taskKey[immutable.Set[String]]("Finds and caches keys for available compilers for use parser for ethSolidityCompilerSet")

    val xethFindCurrentSolidityCompiler = taskKey[Compiler.Solidity]("Finds and caches keys for available compilers for use parser for ethSolidityCompilerSet")

    val xethFindCacheAliasesIfAvailable = taskKey[Tuple2[String,Option[immutable.SortedMap[String,EthAddress]]]]("Finds and caches aliases for use by address parsers")

    val xethFindCacheOmitDupsCurrentCompilations = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Finds and caches compiled, deployable contract names, omitting ambiguous duplicates. Triggered by ethSolidityCompile")

    val xethFindCurrentSender = taskKey[Failable[EthAddress]]("Finds the address that should be used to send ether or messages")

    val xethGasPrice = taskKey[BigInt]("Finds the current gas price, including any overrides or gas price markups")

    val xethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethEntropySource as a source of randomness")

    val xethKeystoreCreateWalletV3Pbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethEntropySource as a source of randomness")

    val xethKeystoreCreateWalletV3Scrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethEntropySource as a source of randomness")

    val xethInvokeData = inputKey[immutable.Seq[Byte]]("Reveals the data portion that would be sent in a message invoking a function and its arguments on a deployed smart contract")

    val xethLoadAbiFor = inputKey[Abi.Definition]("Finds the ABI for a contract address, if known")

    val xethLoadCompilationsKeepDups = taskKey[immutable.Iterable[(String,jsonrpc20.Compilation.Contract)]]("Loads compiled solidity contracts, permitting multiple nonidentical contracts of the same name")

    val xethLoadCompilationsOmitDups = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts, omitting contracts with multiple nonidentical contracts of the same name")

    val xethLoadWalletV3 = taskKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3 for current sender")

    val xethLoadWalletV3For = inputKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

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

      ethBlockchainId   := MainnetIdentifier,

      ethEntropySource := new java.security.SecureRandom,

      ethGasMarkup := 0.2,

      ethGasPriceMarkup := 0.0, // by default, use conventional gas price

      ethIncludeLocations := Nil,

      ethJsonRpcUrl := "http://localhost:8545",

      ethKeystoreLocationsV3 := {
        def warning( location : String ) : String = s"Failed to find V3 keystore in ${location}"
        def listify( fd : Failable[File] ) = fd.fold( _ => Nil, f => List(f) )
        listify( repository.Keystore.V3.Directory.xwarn( warning("sbt-ethereum repository") ) ) ::: listify( clients.geth.KeyStore.Directory.xwarn( warning("geth home directory") ) ) ::: Nil
      },

      ethSoliditySource in Compile := (sourceDirectory in Compile).value / "solidity",

      ethSolidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      xethGasOverrides := Map.empty[String,BigInt],

      xethWalletV3Pbkdf2C := wallet.V3.Default.Pbkdf2.C,

      xethWalletV3Pbkdf2DkLen := wallet.V3.Default.Pbkdf2.DkLen,

      xethWalletV3ScryptDkLen := wallet.V3.Default.Scrypt.DkLen,

      xethWalletV3ScryptN := wallet.V3.Default.Scrypt.N,

      xethWalletV3ScryptR := wallet.V3.Default.Scrypt.R,

      xethWalletV3ScryptP := wallet.V3.Default.Scrypt.P,

      // tasks

      ethAbiMemorize <<= ethAbiMemorizeTask,

      ethAliasDrop <<= ethAliasDropTask,

      ethAliasList <<= ethAliasListTask,

      ethAliasSet <<= ethAliasSetTask,

      ethBalance <<= ethBalanceTask,

      ethBalanceInWei <<= ethBalanceInWeiTask,

      ethInvokeConstant <<= ethInvokeConstantTask,

      ethInvokeTransaction <<= ethInvokeTransactionTask,

      ethCompilationsCull <<= ethCompilationsCullTask,

      ethCompilationsInspect <<= ethCompilationsInspectTask,

      ethCompilationsList <<= ethCompilationsListTask,

      ethDeployOnly <<= ethDeployOnlyTask,

      ethKeystoreCreateWalletV3 := xethKeystoreCreateWalletV3Scrypt.value,

      ethKeystoreInspectWalletV3 <<= ethKeystoreInspectWalletV3Task,

      ethKeystoreList <<= ethKeystoreListTask,

      ethKeystoreMemorizeWalletV3 <<= ethKeystoreMemorizeWalletV3Task,

      ethKeystoreRevealPrivateKey <<= ethKeystoreRevealPrivateKeyTask,

      ethKeystoreValidateWalletV3 <<= ethKeystoreValidateWalletV3Task,

      ethSelfPing <<= ethSelfPingTask,

      ethSendEther <<= ethSendEtherTask,

      ethSolidityCompile in Compile <<= ethSolidityCompileTask,

      ethSolidityChooseCompiler <<= ethSolidityChooseCompilerTask,

      ethSolidityInstallCompiler <<= ethSolidityInstallCompilerTask,

      ethSolidityShowCompiler <<= ethSolidityShowCompilerTask,

      xethLoadAbiFor <<= xethLoadAbiForTask,

      xethDefaultGasPrice <<= xethDefaultGasPriceTask,

      xethFindCacheAliasesIfAvailable <<= xethFindCacheAliasesIfAvailableTask.storeAs( xethFindCacheAliasesIfAvailable ).triggeredBy( xethTriggerDirtyAliasCache ),

      xethFindCacheOmitDupsCurrentCompilations <<= xethFindCacheOmitDupsCurrentCompilationsTask storeAs xethFindCacheOmitDupsCurrentCompilations triggeredBy (ethSolidityCompile in Compile),

      xethFindCacheSessionSolidityCompilerKeys <<= xethFindCacheSessionSolidityCompilerKeysTask.storeAs( xethFindCacheSessionSolidityCompilerKeys ).triggeredBy( xethTriggerDirtySolidityCompilerList ),

      xethFindCurrentSender <<= xethFindCurrentSenderTask,

      xethFindCurrentSolidityCompiler <<= xethFindCurrentSolidityCompilerTask,

      xethGasPrice <<= xethGasPriceTask,

      xethGenKeyPair <<= xethGenKeyPairTask,

      xethKeystoreCreateWalletV3Pbkdf2 <<= xethKeystoreCreateWalletV3Pbkdf2Task,

      xethKeystoreCreateWalletV3Scrypt <<= xethKeystoreCreateWalletV3ScryptTask,

      xethInvokeData <<= xethInvokeDataTask,

      xethNextNonce <<= xethNextNonceTask,

      xethLoadCompilationsKeepDups <<= xethLoadCompilationsKeepDupsTask,

      xethLoadCompilationsOmitDups <<= xethLoadCompilationsOmitDupsTask,

      xethLoadWalletV3For <<= xethLoadWalletV3ForTask,

      xethLoadWalletV3 <<= xethLoadWalletV3Task,

      xethQueryRepositoryDatabase <<= xethQueryRepositoryDatabaseTask, 

      xethTriggerDirtyAliasCache <<= xethTriggerDirtyAliasCacheTask, // this is a no-op, its execution just triggers a re-caching of aliases

      xethTriggerDirtySolidityCompilerList <<= xethTriggerDirtySolidityCompilerListTask, // this is a no-op, its execution just triggers a re-caching of aliases

      xethUpdateContractDatabase <<= xethUpdateContractDatabaseTask, 

      xethUpdateRepositoryDatabase <<= xethUpdateRepositoryDatabaseTask,

      xethUpdateSessionSolidityCompilers <<= xethUpdateSessionSolidityCompilersTask,

      Keys.compile in Compile := {
        val dummy = (ethSolidityCompile in Compile).value
        (Keys.compile in Compile).value
      },

      // TODO: factor away repeated logic here. yuk.
      onLoad in Global := {
        val origF : State => State = (onLoad in Global).value
        val newF  : State => State = ( state : State ) => {
          val lastState = origF( state )
          val state1 = Project.runTask( xethFindCacheAliasesIfAvailable, lastState ) match {
            case None => {
              WARNING.log("Huh? Key 'xethFindCacheAliasesIfAvailable' was undefined in the original state. Ignoring attempt to run that task in onLoad.")
              lastState
            }
            case Some((newState, Inc(inc))) => {
              WARNING.log("Failed to run xethFindCacheAliasesIfAvailable on initialization: " + Incomplete.show(inc.tpe))
              lastState
            }
            case Some((newState, Value(_))) => {
              newState
            }
          }
          val state2 = Project.runTask( xethFindCacheSessionSolidityCompilerKeys, lastState ) match {
            case None => {
              WARNING.log("Huh? Key 'xethFindCacheSessionSolidityCompilerKeys' was undefined in the original state. Ignoring attempt to run that task in onLoad.")
              lastState
            }
            case Some((newState, Inc(inc))) => {
              WARNING.log("Failed to run xethFindCacheSessionSolidityCompilerKeys on initialization: " + Incomplete.show(inc.tpe))
              lastState
            }
            case Some((newState, Value(_))) => {
              newState
            }
          }
          state2
        }
        newF
      },

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

    // anonymous tasks

    // task definitions

    def ethAbiMemorizeTask : Initialize[Task[Unit]] = Def.task {
      val blockchainId = ethBlockchainId.value
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
          repository.Database.setMemorizedContractAbi( blockchainId, address, abi  ).get // thrown an Exception if there's a database issue
          log.info( s"ABI is now known for the contract at address ${address.hex}" )
        }
      }
    }

    def ethAliasDropTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genAliasParser )

      Def.inputTaskDyn {
        val log = streams.value.log
        val blockchainId = ethBlockchainId.value

        // not sure why, but without this xethFindCacheAliasesIfAvailable, which should be triggered by the parser,
        // sometimes fails initialize the parser
        val ensureAliases = xethFindCacheAliasesIfAvailable

        val alias = parser.parsed
        val check = repository.Database.dropAlias( blockchainId, alias ).get // assert no database problem
        if (check) log.info( s"Alias '${alias}' successfully dropped (for blockchain '${blockchainId}').")
        else log.warn( s"Alias '${alias}' is not defined (on blockchain '${blockchainId}'), and so could not be dropped." )

        Def.taskDyn {
          xethTriggerDirtyAliasCache
        }
      }
    }

    def ethAliasListTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      val blockchainId = ethBlockchainId.value
      val faliases = repository.Database.findAllAliases( blockchainId )
      faliases.fold(
        _ => log.warn("Could not read aliases from repository database."),
        aliases => aliases.foreach { case (alias, address) => println( s"${alias} -> 0x${address.hex}" ) }
      )
    }

    def ethAliasSetTask : Initialize[InputTask[Unit]] = Def.inputTaskDyn {
      val log = streams.value.log
      val blockchainId = ethBlockchainId.value
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

    def ethBalanceTask : Initialize[InputTask[BigDecimal]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genOptionalGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val mbAddress = parser.parsed
        val address = mbAddress.getOrElse( xethFindCurrentSender.value.get )
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Ether )
        result.denominated
      }
    }

    def ethBalanceInWeiTask : Initialize[InputTask[BigInt]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genOptionalGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val mbAddress = parser.parsed
        val address = mbAddress.getOrElse( xethFindCurrentSender.value.get )
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Wei )
        result.wei
      }
    }

    def ethCompilationsCullTask : Initialize[Task[Unit]] = Def.task {
      val log = streams.value.log
      val fcount = repository.Database.cullUndeployedCompilations()
      val count = fcount.get
      log.info( s"Removed $count undeployed compilations from the repository database." )
    }

    def ethCompilationsInspectTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genContractAddressOrCodeHashParser )

      Def.inputTask {
        val blockchainId = ethBlockchainId.value

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
              jsonSection( "ABI Definition", info.mbAbiDefinition )
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
              jsonSection( "ABI Definition", info.mbAbiDefinition )
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

    def ethDeployOnlyTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(xethFindCacheOmitDupsCurrentCompilations)( genContractNamesConstructorInputsParser )

      Def.inputTask {
        val s = state.value
        val is = interactionService.value
        val log = streams.value.log
        val blockchainId = ethBlockchainId.value
        val jsonRpcUrl = ethJsonRpcUrl.value
        val ( contractName, extraData ) = parser.parsed
        val ( compilation, inputsBytes ) = {
          extraData match {
            case None => { 
              // at the time of parsing, a compiled contract is not available. we'll force compilation now, but can't accept contructor arguments
              val contractsMap = xethLoadCompilationsOmitDups.value
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
        val sender = xethFindCurrentSender.value.get
        val nextNonce = xethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = xethGasPrice.value
        val gas = xethGasOverrides.value.getOrElse( contractName, doEstimateAndMarkupGas( log, jsonRpcUrl, Some(sender), None, dataHex.decodeHex.toImmutableSeq, jsonrpc20.Client.BlockNumber.Pending, markup ) )
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, dataHex.decodeHex.toImmutableSeq )
        val privateKey = findCachePrivateKey( s, log, is, blockchainId, sender, true )
        val updateChangedDb = xethUpdateContractDatabase.value
        val txnHash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Contract '${contractName}' deployed in transaction '0x${txnHash.hex}'." )
        val out = awaitTransactionReceipt( log, jsonRpcUrl, txnHash, PollSeconds, PollAttempts )
        out.foreach { receipt =>
          receipt.contractAddress.foreach { ca =>
            log.info( s"Contract '${contractName}' has been assigned address '0x${ca.hex}'." )
            val dbCheck = {
              import compilation.info._
              repository.Database.insertNewDeployment( blockchainId, ca, codeHex, sender, txnHash, inputsBytes )
            }
            dbCheck.xwarn("Could not insert information about deployed contract into the repository database")
          }
        }
        out
      }
    }

    def ethKeystoreInspectWalletV3Task : Initialize[InputTask[Unit]] = Def.inputTask {
      val keystoreDirs = ethKeystoreLocationsV3.value
      val w = xethLoadWalletV3For.evaluated.getOrElse( unknownWallet( keystoreDirs ) )
      println( Json.stringify( w.withLowerCaseKeys ) )
    }

    def ethInvokeConstantTask : Initialize[InputTask[(Abi.Function,immutable.Seq[DecodedReturnValue])]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = true ) )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val from = xethFindCurrentSender.value.toOption
        val markup = ethGasMarkup.value
        val gasPrice = xethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        if (! function.constant ) {
          log.warn( s"Function '${function.name}' is not marked constant! An ephemeral call may not succeed, and in any case, no changes to the state of the blockchain will be preserved." )
        }
        val amount = mbWei.getOrElse( Zero )
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = doEstimateAndMarkupGas( log, jsonRpcUrl, from, Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        val rawResult = doEthCallEphemeral( log, jsonRpcUrl, from, contractAddress, Some(gas), Some( gasPrice ), Some( amount ), Some( callData ), jsonrpc20.Client.BlockNumber.Latest )
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

    def ethInvokeTransactionTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val s = state.value
        val log = streams.value.log
        val is = interactionService.value
        val blockchainId = ethBlockchainId.value
        val jsonRpcUrl = ethJsonRpcUrl.value
        val caller = xethFindCurrentSender.value.get
        val nextNonce = xethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = xethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        val amount = mbWei.getOrElse( Zero )
        val privateKey = findCachePrivateKey(s, log, is, blockchainId, caller, true )
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = doEstimateAndMarkupGas( log, jsonRpcUrl, Some(caller), Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), contractAddress, Unsigned256( amount ), callData )
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei to address '0x${contractAddress.hex}' in transaction '0x${hash.hex}'.""" )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      }
    }

    def ethKeystoreListTask : Initialize[Task[immutable.Map[EthAddress,immutable.Set[String]]]] = Def.task {
      val keystoresV3 = ethKeystoreLocationsV3.value
      val log         = streams.value.log
      val combined = {
        keystoresV3
          .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
          .foldLeft( Map.empty[EthAddress,wallet.V3] )( ( accum, next ) => accum ++ next )
      }

      // TODO: Aliases as values
      val out = combined.map( tup => ( tup._1, immutable.Set.empty[String] ) )
      val cap = "+" + span(44) + "+"
      val KeystoreAddresses = "Keystore Addresses"
      println( cap )
      println( f"| $KeystoreAddresses%-42s |" )
      println( cap )
      immutable.TreeSet( out.keySet.toSeq.map( address => s"0x${address.hex}" ) : _* ).foreach { ka =>
        println( f"| $ka%-42s |" )
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

    def ethKeystoreRevealPrivateKeyTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val is = interactionService.value
        val log = streams.value.log
        
        val address = parser.parsed
        val addressStr = address.hex

        val s = state.value
	val extract = Project.extract(s)
	val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For, addressStr, s)

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

    def ethKeystoreValidateWalletV3Task : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val is = interactionService.value
        val keystoreDirs = ethKeystoreLocationsV3.value
        val s = state.value
	val extract = Project.extract(s)
        val inputAddress = parser.parsed
	val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For, inputAddress.hex, s)
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

    def ethSelfPingTask : Initialize[Task[Option[ClientTransactionReceipt]]] = Def.task {
      val address  = xethFindCurrentSender.value.get
      val sendArgs = s" ${address.hex} 0 wei"
      val log = streams.value.log

      val s = state.value
      val extract = Project.extract(s)
      val (_, result) = extract.runInputTask(ethSendEther, sendArgs, s)

      val out = result
      out.fold( log.warn("Ping failed! Our attempt to send 0 ether from '${address}' to itself may or may not eventually succeed, but we've timed out before hearing back." ) ) { receipt =>
        log.info( s"Ping succeeded! Sent 0 ether from '${address}' to itself in transaction '0x${receipt.transactionHash.hex}'" )
      }
      out
    }

    def ethSendEtherTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser( xethFindCacheAliasesIfAvailable )( genEthSendEtherParser )

      Def.inputTask {
        val s = state.value
        val log = streams.value.log
        val is = interactionService.value
        val blockchainId = ethBlockchainId.value
        val jsonRpcUrl = ethJsonRpcUrl.value
        val args = parser.parsed
        val from = xethFindCurrentSender.value.get
        val to = args._1
        val amount = args._2
        val nextNonce = xethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = xethGasPrice.value
        val gas = doEstimateAndMarkupGas( log, jsonRpcUrl, Some( from ), Some(to), Nil, jsonrpc20.Client.BlockNumber.Pending, markup )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), to, Unsigned256( amount ), List.empty[Byte] )
        val privateKey = findCachePrivateKey( s, log, is, blockchainId, from, true )
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Sent ${amount} wei to address '0x${to.hex}' in transaction '0x${hash.hex}'." )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      }
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
      val ensureSet = xethFindCurrentSolidityCompiler.value
      val ( key, compiler ) = CurrentSolidityCompiler.get.get
      log.info( s"Current solidity compiler '$key', which refers to $compiler." )
    }

    def xethDefaultGasPriceTask : Initialize[Task[BigInt]] = Def.task {
      val log        = streams.value.log
      val jsonRpcUrl = ethJsonRpcUrl.value
      doGetDefaultGasPrice( log, jsonRpcUrl )
    }

    def xethFindCacheAliasesIfAvailableTask : Initialize[Task[Tuple2[String,Option[immutable.SortedMap[String,EthAddress]]]]] = Def.task {
      val blockchainId = ethBlockchainId.value
      val mbAliases    = repository.Database.findAllAliases( blockchainId ).toOption
      ( blockchainId, mbAliases )
    }

    def xethFindCacheOmitDupsCurrentCompilationsTask : Initialize[Task[immutable.Map[String,jsonrpc20.Compilation.Contract]]] = Def.task {
      xethLoadCompilationsOmitDups.value
    }

    def xethFindCacheSessionSolidityCompilerKeysTask : Initialize[Task[immutable.Set[String]]] = Def.task {
      val log = streams.value.log
      log.info("Updating available solidity compiler set.")
      val currentSessionCompilers = xethUpdateSessionSolidityCompilers.value
      currentSessionCompilers.keySet
    }

    def xethFindCurrentSenderTask : Initialize[Task[Failable[EthAddress]]] = Def.task {
      Failable {
        val blockchainId = ethBlockchainId.value
        val mbAddrStr = ethSender.?.value
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
                case None => throw new SenderNotAvailableException("Cannot find an 'ethSender' or default sender")
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

      CurrentSolidityCompiler.get.map( _._2).getOrElse {
        def latestLocalInstallVersion : Option[SemanticVersion] = {
          val versions = (immutable.TreeSet.empty[SemanticVersion] ++ compilerKeys.map( LocalSolc.versionFromKey ).filter( _ != None ).map( _.get ))
          if ( versions.size > 0 ) Some(versions.last) else None
        }
        def latestLocalInstallKey : Option[String] = latestLocalInstallVersion.map( version => s"${LocalSolc.KeyPrefix}${version.versionString}" )

        val key = {
          compilerKeys.find( _.startsWith(EthNetcompile.KeyPrefix) ) orElse
          compilerKeys.find( _ == LocalPathSolcKey ) orElse
          latestLocalInstallKey orElse
          compilerKeys.find( _.startsWith( EthJsonRpc.KeyPrefix ) )

        }.getOrElse {
          throw new Exception( s"Cannot find a usable solidity compiler. compilerKeys: ${compilerKeys}, sessionCompilers: ${sessionCompilers}" )
        }
        val compiler = sessionCompilers.get( key ).getOrElse( throw new Exception( s"Could not find a solidity compiler for key '$key'. sessionCompilers: ${sessionCompilers}" ) )

        CurrentSolidityCompiler.set( Some( Tuple2( key, compiler ) ) )

        compiler
      }
    }

    def xethGasPriceTask : Initialize[Task[BigInt]] = Def.task {
      val log        = streams.value.log
      val jsonRpcUrl = ethJsonRpcUrl.value

      val markup          = ethGasPriceMarkup.value
      val defaultGasPrice = xethDefaultGasPrice.value

      rounded( BigDecimal(defaultGasPrice) * BigDecimal(1 + markup) ).toBigInt
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

    def xethInvokeDataTask : Initialize[InputTask[immutable.Seq[Byte]]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genAddressFunctionInputsAbiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val ( contractAddress, function, args, abi ) = parser.parsed
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
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

    def xethLoadAbiForTask : Initialize[InputTask[Abi.Definition]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val blockchainId = ethBlockchainId.value
        abiForAddress( blockchainId, parser.parsed )
      }
    }

    def xethLoadCompilationsKeepDupsTask : Initialize[Task[immutable.Iterable[(String,jsonrpc20.Compilation.Contract)]]] = Def.task {
      val log = streams.value.log

      val dummy = (ethSolidityCompile in Compile).value // ensure compilation has completed

      val dir = (ethSolidityDestination in Compile).value

      def addContracts( vec : immutable.Vector[(String,jsonrpc20.Compilation.Contract)], name : String ) = {
        val next = {
          val file = new File( dir, name )
          try {
            borrow( new BufferedInputStream( new FileInputStream( file ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]] )
          } catch {
            case e : Exception => {
              log.warn( s"Bad or unparseable solidity compilation: ${file.getPath}. Skipping." )
              log.warn( s"  --> cause: ${e.toString}" )
              Map.empty[String,jsonrpc20.Compilation.Contract]
            }
          }
        }
        vec ++ next
      }

      dir.list.filter( _.endsWith(".json") ).foldLeft( immutable.Vector.empty[(String,jsonrpc20.Compilation.Contract)] )( addContracts )
    }

    def xethLoadCompilationsOmitDupsTask : Initialize[Task[immutable.Map[String,jsonrpc20.Compilation.Contract]]] = Def.task {
      val log = streams.value.log

      val dummy = (ethSolidityCompile in Compile).value // ensure compilation has completed

      val dir = (ethSolidityDestination in Compile).value

      def addBindingKeepShorterSource( addTo : immutable.Map[String,jsonrpc20.Compilation.Contract], binding : (String,jsonrpc20.Compilation.Contract) ) = {
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
      def addAllKeepShorterSource( addTo : immutable.Map[String,jsonrpc20.Compilation.Contract], nextBindings : Iterable[(String,jsonrpc20.Compilation.Contract)] ) = {
        nextBindings.foldLeft( addTo )( ( accum, next ) => addBindingKeepShorterSource( accum, next ) )
      }
      def addContracts( tup : ( immutable.Map[String,jsonrpc20.Compilation.Contract], immutable.Set[String] ), name : String ) = {
        val ( addTo, overlaps ) = tup
        val next = {
          val file = new File( dir, name )
          try {
            borrow( new BufferedInputStream( new FileInputStream( file ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]] )
          } catch {
            case e : Exception => {
              log.warn( s"Bad or unparseable solidity compilation: ${file.getPath}. Skipping." )
              log.warn( s"  --> cause: ${e.toString}" )
              Map.empty[String,jsonrpc20.Compilation.Contract]
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

      val ( rawCompilations, duplicates ) = dir.list.filter( _.endsWith( ".json" ) ).foldLeft( ( immutable.Map.empty[String,jsonrpc20.Compilation.Contract], immutable.Set.empty[String] ) )( addContracts )
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

    def xethLoadWalletV3Task : Initialize[Task[Option[wallet.V3]]] = Def.task {
      val s = state.value
      val addressStr = xethFindCurrentSender.value.get.hex
      val extract = Project.extract(s)
      val (_, result) = extract.runInputTask(xethLoadWalletV3For, addressStr, s)
      result
    }

    def xethLoadWalletV3ForTask : Initialize[InputTask[Option[wallet.V3]]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val keystoresV3 = ethKeystoreLocationsV3.value
        val log         = streams.value.log

        val address = parser.parsed
        val out = {
          keystoresV3
            .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
            .foldLeft( None : Option[wallet.V3] ){ ( mb, nextKeystore ) =>
            if ( mb.isEmpty ) nextKeystore.get( address ) else mb
          }
        }
        log.info( out.fold( s"No V3 wallet found for '0x${address.hex}'" )( _ => s"V3 wallet found for '0x${address.hex}'" ) )
        out
      }
    }

    def xethNextNonceTask : Initialize[Task[BigInt]] = Def.task {
      val log        = streams.value.log
      val jsonRpcUrl = ethJsonRpcUrl.value
      doGetTransactionCount( log, jsonRpcUrl, xethFindCurrentSender.value.get , jsonrpc20.Client.BlockNumber.Pending )
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

    def xethUpdateContractDatabaseTask : Initialize[Task[Boolean]] = Def.task {
      val log          = streams.value.log
      val jsonRpcUrl   = ethJsonRpcUrl.value
      val compilations = xethLoadCompilationsKeepDups.value // we want to "know" every contract we've seen, which might include contracts with multiple names

      repository.Database.updateContractDatabase( compilations ).get
    }

    def xethUpdateRepositoryDatabaseTask : Initialize[InputTask[Unit]] = Def.inputTask {
      val log   = streams.value.log
      val query = DbQueryParser.parsed

      val checkDataSource = {
        repository.Database.DataSource.map { ds =>
          borrow( ds.getConnection() ) { conn =>
            borrow( conn.createStatement() ) { stmt =>
              val rows = stmt.executeUpdate( query )
              log.info( s"Query succeeded: $query" )
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
      val jsonRpcUrl    = ethJsonRpcUrl.value

      def check( key : String, compiler : Compiler.Solidity ) : Option[ ( String, Compiler.Solidity ) ] = {
        val test = Compiler.Solidity.test( compiler )
        if ( test ) {
          Some( Tuple2( key, compiler ) )
        } else {
          None
        }
      }
      def netcompile = {
        netcompileUrl.flatMap { ncu =>
          check( s"${EthNetcompile.KeyPrefix}${ncu}", Compiler.Solidity.EthNetcompile( ncu ) )
        }
      }
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

      val raw = checkLocalRepositorySolcs :+ localPath :+ ethJsonRpc :+ netcompile

      val out = immutable.SortedMap( raw.filter( _ != None ).map( _.get ) : _* )
      SessionSolidityCompilers.set( Some( out ) )
      out
    }

    // helper functions

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
            EthPrivateKey( credential )
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
      userValidateIfCached : Boolean
    ) : EthPrivateKey = {

      def updateCached : EthPrivateKey = {
        // this is ugly and awkward, but it gives time for any log messages to get emitted before prompting for a credential
        // it also slows down automated attempts to guess passwords, i guess...
        Thread.sleep(1000)

        val credential = readCredential( is, address )

        val extract = Project.extract(state)
        val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For, address.hex, state)

        val privateKey = findPrivateKey( log, mbWallet, credential )
        CurrentAddress.set( UnlockedAddress( blockchainId, address, privateKey ) )
        privateKey
      }
      def goodCached : Option[EthPrivateKey] = {
        // caps for value matches rather than variable names
        val BlockchainId = blockchainId
        val Address = address
        CurrentAddress.get match {
          case UnlockedAddress( BlockchainId, Address, privateKey ) => { // if blockchainId and/or ethSender has changed, this will no longer match
            val ok = {
              if ( userValidateIfCached ) {
                is.readLine( s"Using sender address '0x${address.hex}' (on blockchain '${blockchainId}'). OK? [y/n] ", false ).getOrElse( throw new Exception( CantReadInteraction ) ).trim().equalsIgnoreCase("y")
              } else {
                true
              }
            }
            if ( ok ) {
              Some( privateKey )
            } else {
              CurrentAddress.set( NoAddress )
              throw new SenderNotAvailableException( s"Use of sender address '0x${address.hex}' (on blockchain '${blockchainId}') vetoed by user." )
            }
          }
          case _ => { // if we don't match, we reset / forget the cached private key
            CurrentAddress.set( NoAddress )
            None
          }
        }
      }

      CurrentAddress.synchronized {
        goodCached.getOrElse( updateCached )
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

    private def parseAbi( abiString : String ) = Json.parse( abiString ).as[Abi.Definition]

    private def readAddressAndAbi( log : sbt.Logger, is : sbt.InteractionService ) : ( EthAddress, Abi.Definition ) = {
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

  } // end object autoImport

  // plug-in setup

  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin && InteractionServicePlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
