package com.mchange.sc.v1.sbtethereum

import Parsers._

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.Def.Initialize
import sbt.InteractionServiceKeys.interactionService

import sbinary._
import sbinary.DefaultProtocol._
import SBinaryFormats._

import java.io.{BufferedInputStream,File,FileInputStream,FilenameFilter}
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

import play.api.libs.json.Json

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

  private val EthAddressSystemProperty      = "eth.address"
  private val EthAddressEnvironmentVariable = "ETH_ADDRESS"

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally we should try to log through sbt loggers
  private implicit val logger = mlogger( this )

  private implicit val UnlockedKey = new AtomicReference[Option[(EthAddress,EthPrivateKey)]]( None )

  private val BufferSize = 4096

  private val PollSeconds = 15

  private val PollAttempts = 9

  private val Zero = BigInt(0)


  private val Zero256 = Unsigned256( 0 )

  private val ZeroEthAddress = (0 until 40).map(_ => "0").mkString("")

  object autoImport {

    // settings

    val ethAddress = settingKey[String]("The address from which transactions will be sent")

    val ethEntropySource = settingKey[SecureRandom]("The source of randomness that will be used for key generation")

    val ethGasOverrides = settingKey[Map[String,BigInt]]("Map of contract names to gas limits for contract creation transactions, overriding automatic estimates")

    val ethGasMarkup = settingKey[Double]("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")

    val ethGasPriceMarkup = settingKey[Double]("Fraction by which automatically estimated gas price will be marked up (if not overridden) in executing transactions")

    val ethKeystoresV3 = settingKey[Seq[File]]("Directories from which V3 wallets can be loaded")

    val ethKnownStubAddresses = settingKey[immutable.Map[String,immutable.Set[String]]]("Names of stubs that might be generated in compilation mapped to addresses known to conform to their ABIs.")

    val ethJsonRpcUrl = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val ethContractDatabaseUpdatePolicy = settingKey[IrreconcilableUpdatePolicy]("Defines how inconsistencies between newly compiled artifacts and items already in the contract database are resolved")

    val ethTargetDir = settingKey[File]("Location in target directory where ethereum artifacts will be placed")

    val ethSoliditySource = settingKey[File]("Solidity source code directory")

    val ethSolidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    val ethWalletV3ScryptN = settingKey[Int]("The value to use for parameter N when generating Scrypt V3 wallets")

    val ethWalletV3ScryptR = settingKey[Int]("The value to use for parameter R when generating Scrypt V3 wallets")

    val ethWalletV3ScryptP = settingKey[Int]("The value to use for parameter P when generating Scrypt V3 wallets")

    val ethWalletV3ScryptDkLen = settingKey[Int]("The derived key length parameter used when generating Scrypt V3 wallets")

    val ethWalletV3Pbkdf2C = settingKey[Int]("The value to use for parameter C when generating pbkdf2 V3 wallets")

    val ethWalletV3Pbkdf2DkLen = settingKey[Int]("The derived key length parameter used when generating pbkdf2 V3 wallets")

    // tasks

    val ethAbiForContractAddress = inputKey[Abi.Definition]("Finds the ABI for a contract address, if known")

    val ethAliasDrop = inputKey[Unit]("Drops an alias for an ethereum address from the sbt-ethereum repository database.")

    val ethAliasList = inputKey[Unit]("Lists aliases for ethereum addresses that can be used in place of the hex address in many tasks.")

    val ethAliasSet = inputKey[Unit]("Defines (or redefines) an alias for an ethereum address that can be used in place of the hex address in many tasks.")

    val ethBalance = inputKey[BigDecimal]("Computes the balance in ether of the address set as 'ethAddress'")

    val ethBalanceFor = inputKey[BigDecimal]("Computes the balance in ether of a given address")

    val ethBalanceInWei = inputKey[BigInt]("Computes the balance in wei of the address set as 'ethAddress'")

    val ethBalanceInWeiFor = inputKey[BigInt]("Computes the balance in wei of a given address")

    val ethCallConstant = inputKey[(Abi.Function,immutable.Seq[DecodedReturnValue])]("Makes a call to a constant function, consulting only the local copy of the blockchain. Burns no Ether. Returns the latest available result.")

    val ethCompileSolidity = taskKey[Unit]("Compiles solidity files")

    val ethFindCacheAliasesIfAvailable = taskKey[Option[immutable.SortedMap[String,EthAddress]]]("Finds and caches address aliases, if they are available. Triggered by ethAliasSet and ethAliasDrop.")

    val ethFindCacheCompilations = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Finds and caches compiled contract names, triggered by ethCompileSolidity")

    val ethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val ethDeployOnly = inputKey[Option[ClientTransactionReceipt]]("Deploys the specified named contract")

    val ethDumpContractInfo = inputKey[Unit]("Dumps to the console full information about a contract, based on either a code hash or contract address")

    val ethGasPrice = taskKey[BigInt]("Finds the current gas price, including any overrides or gas price markups")

    val ethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethEntropySource as a source of randomness")

    val ethGenWalletV3Pbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethEntropySource as a source of randomness")

    val ethGenWalletV3Scrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethEntropySource as a source of randomness")

    val ethGenWalletV3 = taskKey[wallet.V3]("Generates a new V3 wallet, using ethEntropySource as a source of randomness")

    val ethInvoke = inputKey[Option[ClientTransactionReceipt]]("Calls a function on a deployed smart contract")

    val ethInvokeData = inputKey[immutable.Seq[Byte]]("Reveals the data portion that would be sent in a message invoking a function and its arguments on a deployed smart contract")

    val ethListKeystoreAddresses = taskKey[immutable.Map[EthAddress,immutable.Set[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined")

    val ethListKnownContracts = taskKey[Unit]("Lists summary information about contracts known in the repository")

    val ethLoadCompilations = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts")

    val ethLoadWalletV3 = taskKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val ethLoadWalletV3For = inputKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val ethMemorizeAbi = taskKey[Unit]("Prompts for an ABI definition for a contract and inserts it into the sbt-ethereum database")

    val ethMemorizeWalletV3 = taskKey[Unit]("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")

    val ethNextNonce = taskKey[BigInt]("Finds the next nonce for the address defined by setting 'ethAddress'")

    val ethRevealPrivateKeyFor = inputKey[Unit]("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")

    val ethQueryRepositoryDatabase = inputKey[Unit]("Primarily for debugging. Query the internal repository database.")

    val ethTriggerDirtyAliasCache = taskKey[Unit]("Indirectly provokes an update of the cache of aliases used for tab completions.")

    val ethSelfPing = taskKey[Option[ClientTransactionReceipt]]("Sends 0 ether from ethAddress to itself")

    val ethSendEther = inputKey[Option[ClientTransactionReceipt]]("Sends ether from ethAddress to a specified account, format 'ethSendEther <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

    val ethShowWalletV3For = inputKey[Unit]("Prints for a V3 wallet to the console the JSON.")

    val ethUpdateContractDatabase = taskKey[Boolean]("Integrates newly compiled contracts and stubs (defined in ethKnownStubAddresses) into the contract database. Returns true if changes were made.")

    val ethValidateWalletV3For = inputKey[Unit]("Verifies that a V3 wallet can be decoded for an address, and decodes to the expected address.")

    // anonymous tasks

    val warnOnZeroAddress = Def.task {
      val current = ethAddress.value

      if ( current == ZeroEthAddress ) {
        throw new Exception(s"""No valid EthAddress set. Please use 'set ethAddress := "<your ethereum address>"'""")
      }

      true
    }

    val findCachePrivateKey = Def.task {
      val checked = warnOnZeroAddress.value

      val CurAddrStr = ethAddress.value
      val CurAddress = EthAddress(CurAddrStr)
      val log = streams.value.log
      val is = interactionService.value
      val mbWallet = ethLoadWalletV3.value

      def updateCached : EthPrivateKey = {
        val credential = readCredential( is, CurAddress )

        val privateKey = findPrivateKey( log, mbWallet, credential )
        UnlockedKey.set( Some( (CurAddress, privateKey) ) )
        privateKey
      }
      def goodCached : Option[EthPrivateKey] = {
        UnlockedKey.get match {
          case Some( ( CurAddress, privateKey ) ) => Some( privateKey )
          case _                                  => None
        }
      }

      goodCached.getOrElse( updateCached )
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

      // Settings

      ethJsonRpcUrl     := "http://localhost:8545",

      ethEntropySource := new java.security.SecureRandom,

      ethGasMarkup := 0.2,

      ethGasOverrides := Map.empty[String,BigInt],

      ethGasPriceMarkup := 0.0, // by default, use conventional gas price

      ethKeystoresV3 := {
        def warning( location : String ) : String = s"Failed to find V3 keystore in ${location}"
        def listify( fd : Failable[File] ) = fd.fold( _ => Nil, f => List(f) )
        listify( Repository.KeyStore.V3.Directory.xwarn( warning("sbt-ethereum repository") ) ) ::: listify( clients.geth.KeyStore.Directory.xwarn( warning("geth home directory") ) ) ::: Nil
      },

      ethKnownStubAddresses := Map.empty,

      ethAddress := {
        val mbProperty = Option( System.getProperty( EthAddressSystemProperty ) )
        val mbEnvVar   = Option( System.getenv( EthAddressEnvironmentVariable ) )


        (mbProperty orElse mbEnvVar).getOrElse( ZeroEthAddress )
      },

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      ethContractDatabaseUpdatePolicy := PrioritizeNewer,

      ethSoliditySource in Compile := (sourceDirectory in Compile).value / "solidity",

      ethSolidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      ethWalletV3ScryptN := wallet.V3.Default.Scrypt.N,

      ethWalletV3ScryptR := wallet.V3.Default.Scrypt.R,

      ethWalletV3ScryptP := wallet.V3.Default.Scrypt.P,

      ethWalletV3ScryptDkLen := wallet.V3.Default.Scrypt.DkLen,

      ethWalletV3Pbkdf2C := wallet.V3.Default.Pbkdf2.C,

      ethWalletV3Pbkdf2DkLen := wallet.V3.Default.Pbkdf2.DkLen,

      // tasks

      compile in Compile := {
        val dummy = (ethCompileSolidity in Compile).value
        (compile in Compile).value
      },

      ethAbiForContractAddress <<= ethAbiForContractAddressTask,

      ethAliasDrop <<= ethAliasDropTask,

      ethAliasList := {
        val log = streams.value.log
        val faliases = Repository.Database.findAllAliases
        faliases.fold(
          _ => log.warn("Could not read aliases from repository database."),
          aliases => aliases.foreach { case (alias, address) => println( s"${alias} -> 0x${address.hex}" ) }
        )
      },

      ethAliasSet <<= ethAliasSetTask,

      ethBalance := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethBalanceFor, addressStr, s)
        result
      },

      ethBalanceFor <<= ethBalanceForTask,

      ethBalanceInWei := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethBalanceInWeiFor, addressStr, s)
        result
      },

      ethBalanceInWeiFor <<= ethBalanceInWeiForTask,

      ethFindCacheAliasesIfAvailable <<= ethFindCacheAliasesIfAvailableTask.storeAs( ethFindCacheAliasesIfAvailable ).triggeredBy( ethTriggerDirtyAliasCache ),

      ethFindCacheCompilations <<= ethFindCacheCompilationsTask storeAs ethFindCacheCompilations triggeredBy (ethCompileSolidity in Compile ),

      ethCallConstant <<= ethCallConstantTask,

      ethCompileSolidity in Compile := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value

        val solSource      = (ethSoliditySource in Compile).value
        val solDestination = (ethSolidityDestination in Compile).value

        doCompileSolidity( log, jsonRpcUrl, solSource, solDestination )
      },

      ethDumpContractInfo <<= ethDumpContractInfoTask, 

      ethDefaultGasPrice := {
        val log        = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        doGetDefaultGasPrice( log, jsonRpcUrl )
      },

      ethGasPrice := {
        val log        = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value

        val markup          = ethGasPriceMarkup.value
        val defaultGasPrice = ethDefaultGasPrice.value

        rounded( BigDecimal(defaultGasPrice) * BigDecimal(1 + markup) ).toBigInt 
      },

      ethGenKeyPair := {
        val log = streams.value.log
        val out = EthKeyPair( ethEntropySource.value )

        // a ridiculous overabundance of caution
        assert {
          val checkpub = out.pvt.toPublicKey
          checkpub == out.pub && checkpub.toAddress == out.address
        }

        log.info( s"Generated keypair for address '0x${out.address.hex}'" )

        out
      },

      ethGenWalletV3Pbkdf2 := {
        val log   = streams.value.log
        val c     = ethWalletV3Pbkdf2C.value
        val dklen = ethWalletV3Pbkdf2DkLen.value

        val is = interactionService.value
        val keyPair = ethGenKeyPair.value
        val entropySource = ethEntropySource.value

        log.info( s"Generating V3 wallet, alogorithm=pbkdf2, c=${c}, dklen=${dklen}" )
        val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
        val w = wallet.V3.generatePbkdf2( passphrase = passphrase, c = c, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
        Repository.KeyStore.V3.storeWallet( w ).get // asserts success
      },

      ethGenWalletV3Scrypt := {
        val log   = streams.value.log
        val n     = ethWalletV3ScryptN.value
        val r     = ethWalletV3ScryptR.value
        val p     = ethWalletV3ScryptP.value
        val dklen = ethWalletV3ScryptDkLen.value

        val is = interactionService.value
        val keyPair = ethGenKeyPair.value
        val entropySource = ethEntropySource.value

        log.info( s"Generating V3 wallet, alogorithm=scrypt, n=${n}, r=${r}, p=${p}, dklen=${dklen}" )
        val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
        val w = wallet.V3.generateScrypt( passphrase = passphrase, n = n, r = r, p = p, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
        Repository.KeyStore.V3.storeWallet( w ).get // asserts success
      },

      ethGenWalletV3 := ethGenWalletV3Scrypt.value,

      ethInvoke <<= ethInvokeTask,

      ethInvokeData <<= ethInvokeDataTask, 

      ethNextNonce := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value
        doGetTransactionCount( log, jsonRpcUrl, EthAddress( ethAddress.value ), jsonrpc20.Client.BlockNumber.Pending )
      },

      ethListKeystoreAddresses := {
        val keystoresV3 = ethKeystoresV3.value
        val log         = streams.value.log
        val combined = {
          keystoresV3
            .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
            .foldLeft( Map.empty[EthAddress,wallet.V3] )( ( accum, next ) => accum ++ next )
        }

        // TODO: Aliases as values
        val out = combined.map( tup => ( tup._1, immutable.Set.empty[String] ) )
        val cap = "+" + span(44) + "+"
        val KeystoreAddress = "Keystore Address"
        println( cap )
        println( f"| $KeystoreAddress%-42s |" )
        println( cap )
        immutable.TreeSet( out.keySet.toSeq.map( address => s"0x${address.hex}" ) : _* ).foreach { ka =>
          println( f"| $ka%-42s |" )
        }
        println( cap )
        out
      },

      ethListKnownContracts := {
        val contractsSummary = Repository.Database.contractsSummary.get // throw for any db problem

        val Address   = "Address"
        val Name      = "Name"
        val CodeHash  = "Code Hash"
        val Timestamp = "Timestamp"

        val cap = "+" + span(44) + "+" + span(22) + "+" + span(68) + "+" + span(30) + "+"
        println( cap )
        println( f"| $Address%-42s | $Name%-20s | $CodeHash%-66s | $Timestamp%-28s |" )
        println( cap )

        contractsSummary.foreach { row =>
          import row._
          val ca = emptyOrHex( contract_address )
          val nm = blankNull( name )
          val ch = emptyOrHex( code_hash )
          val ts = blankNull( timestamp )
          println( f"| $ca%-42s | $nm%-20s | $ch%-66s | $ts%-28s |" )
        }
        println( cap )
      },

      ethLoadCompilations := { 
        val dummy = (ethCompileSolidity in Compile).value // ensure compilation has completed

        val dir = (ethSolidityDestination in Compile).value

        def addContracts( addTo : immutable.Map[String,jsonrpc20.Compilation.Contract], name : String ) = {
          val next = borrow( new BufferedInputStream( new FileInputStream( new File( dir, name ) ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]] )
          addTo ++ next
        }

        dir.list.foldLeft( immutable.Map.empty[String,jsonrpc20.Compilation.Contract] )( addContracts )
      },

      ethLoadWalletV3For <<= ethLoadWalletV3ForTask,

      ethLoadWalletV3 := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethLoadWalletV3For, addressStr, s)
        result
      },

      ethMemorizeAbi := {
        val jsonRpcUrl = ethJsonRpcUrl.value
        val log = streams.value.log
        val is = interactionService.value
        val ( address, abi ) = readAddressAndAbi( log, is )
        val code = doCodeForAddress( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest )

        val check = Repository.Database.setContractAbi( code, Json.stringify( Json.toJson( abi ) ) ).get // thrown an Exception if there's a database issue
        if (!check) {
          log.info( s"The contract code at address '$address' was already associated with an ABI, which has not been overwritten." )
          log.info( s"Associating address with the known ABI.")
        }
        Repository.Database.insertExistingDeployment( address, code.hex ).get // thrown an Exception if there's a database issue

        log.info( s"ABI is now known for the contract at address ${address.hex}" )
      },

      ethMemorizeWalletV3 := {
        val log = streams.value.log
        val is = interactionService.value
        val w = readV3Wallet( is )
        val address = w.address // a very cursory check of the wallet, NOT full validation
        Repository.KeyStore.V3.storeWallet( w ).get // asserts success
        log.info( s"Imported JSON wallet for address '0x${address.hex}', but have not validated it.")
        log.info( s"Consider validating the JSON using 'ethValidateWalletV3For 0x${address.hex}." )
      },

      ethDeployOnly <<= ethDeployOnlyTask,

      ethQueryRepositoryDatabase := {
        val log   = streams.value.log
        val query = DbQueryParser.parsed

        // XXX: should this be modified to be careful about DDL / inserts / updates / deletes etc?
        //      for now lets be conservative and restrict to SELECT
        if (! query.toLowerCase.startsWith("select")) {
          throw new Exception("For now, ethQueryRepositoryDatabase supports only SELECT statements. Sorry!")
        }

        val foundDataSource = {
          Repository.Database.DataSource.map { ds =>
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
        if ( foundDataSource.isFailed ) {
          log.warn("Failed to find DataSource!")
          log.warn( foundDataSource.fail.toString )
        }
      },

      ethRevealPrivateKeyFor <<= ethRevealPrivateKeyForTask,

      ethSelfPing := {
        val checked  = warnOnZeroAddress.value
        val address  = ethAddress.value
        val sendArgs = s" ${address} 0 wei"
        val log = streams.value.log

        val s = state.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethSendEther, sendArgs, s)

        val out = result
        out.fold( log.warn("Ping failed! Our attempt to send 0 ether from '${address}' to itself may or may not eventually succeed, but we've timed out before hearing back." ) ) { receipt =>
          log.info( s"Ping succeeded! Sent 0 ether from '${address}' to itself in transaction '0x${receipt.transactionHash.hex}'" )
        }
        out
      },

      ethShowWalletV3For := {
        val keystoreDirs = ethKeystoresV3.value
        val w = ethLoadWalletV3For.evaluated.getOrElse( unknownWallet( keystoreDirs ) )
        println( Json.stringify( w.withLowerCaseKeys ) )
      },

      ethSendEther := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val args = { WARNING.log("ETHSENDETHERPARSER"); ethSendEtherParser.parsed }
        val to = args._1
        val amount = args._2
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val gas = markupEstimateGas( log, jsonRpcUrl, Some( EthAddress( ethAddress.value ) ), Some(to), Nil, jsonrpc20.Client.BlockNumber.Pending, markup )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), to, Unsigned256( amount ), List.empty[Byte] )
        val privateKey = findCachePrivateKey.value
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Sent ${amount} wei to address '0x${to.hex}' in transaction '0x${hash.hex}'." )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      },

      ethTriggerDirtyAliasCache := {
      },

      ethUpdateContractDatabase := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val policy                 = ethContractDatabaseUpdatePolicy.value
        val compilations           = ethLoadCompilations.value
        val stubNameToAddresses    = ethKnownStubAddresses.value.mapValues( stringSet => stringSet.map( EthAddress.apply ) )
        val stubNameToAddressCodes  = {
          stubNameToAddresses.map { case ( name, addresses ) =>
            ( name, immutable.Map( addresses.map( address => ( address, doCodeForAddress( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Pending ).hex ) ).toSeq : _* ) )
          }
        }
        Repository.Database.updateContractDatabase( compilations, stubNameToAddressCodes, policy ).get
      },

      ethValidateWalletV3For <<= ethValidateWalletV3ForTask,

      onLoad := {
        val origF : State => State = onLoad.value
        val newF  : State => State = ( state : State ) => {
          val lastState = origF( state )
          Project.runTask( ethTriggerDirtyAliasCache, lastState ) match {
            case None                       => lastState
            case Some((newState, Inc(inc))) => {
              println("Failed to run ethTriggerDirtyAliasCache on initialization: " + Incomplete.show(inc.tpe))
              lastState
            }
            case Some((newState, Value(_))) => newState
          }
        }
        newF
      },

      watchSources ++= {
        val dir = (ethSoliditySource in Compile).value
        val filter = new FilenameFilter {
          def accept( dir : File, name : String ) = goodSolidityFileName( name )
        }
        if ( dir.exists ) {
          dir.list( filter ).map( name => new File( dir, name ) ).toSeq
        } else {
          Nil
        }
      }
    )

    def ethAliasDropTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genAliasParser )

      Def.inputTaskDyn {
        val log = streams.value.log

        // not sure why, but without this ethFindCacheAliasesIfAvailable, which should be triggered by the parser,
        // sometimes fails initialize te parser
        val ensureAliases = ethFindCacheAliasesIfAvailable

        val alias = parser.parsed
        val check = Repository.Database.dropAlias( alias ).get // assert success
        if (check) log.info( s"Alias '${alias}' successfully dropped.")
        else log.warn( s"Alias '${alias}' is not defined, and so could not be dropped." )

        Def.taskDyn {
          ethTriggerDirtyAliasCache
        }
      }
    }

    def ethAliasSetTask : Initialize[InputTask[Unit]] = Def.inputTaskDyn {
      val log = streams.value.log
      val ( alias, address ) = NewAliasParser.parsed
      val check = Repository.Database.createUpdateAlias( alias, address )
      check.fold(
        _.vomit,
        _ => {
          log.info( s"Alias '${alias}' now points to address '${address.hex}'." )
        }
      )

      Def.taskDyn {
        ethTriggerDirtyAliasCache
      }
    }

    def ethAbiForContractAddressTask : Initialize[InputTask[Abi.Definition]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        abiForAddress( parser.parsed )
      }
    }

    def ethBalanceForTask : Initialize[InputTask[BigDecimal]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val address = parser.parsed
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Ether )
        result.denominated
      }
    }

    def ethBalanceInWeiForTask : Initialize[InputTask[BigInt]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val address = parser.parsed
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Wei )
        result.wei
      }
    }

    def ethCallConstantTask : Initialize[InputTask[(Abi.Function,immutable.Seq[DecodedReturnValue])]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = true ) )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val from = if ( ethAddress.value == ZeroEthAddress ) None else Some( EthAddress( ethAddress.value ) )
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        if (! function.constant ) {
          log.warn( s"Function '${function.name}' is not marked constant! An ephemeral call may not succeed, and in any case, no changes to the state of the blockchain will be preserved." )
        }
        val amount = mbWei.getOrElse( Zero )
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = markupEstimateGas( log, jsonRpcUrl, from, Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
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

    def ethDeployOnlyTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(ethFindCacheCompilations)( genContractNamesConstructorInputsParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val ( contractName, extraData ) = parser.parsed
        val ( compilation, inputsHex ) = {
          extraData match {
            case None => { 
              // at the time of parsing, a compiled contract is not available. we'll force compilation now, but can't accept contructor arguments
              val contractsMap = ethLoadCompilations.value
              val compilation = contractsMap( contractName )
              ( compilation, "" )
            }
            case Some( ( inputs, abi, compilation ) ) => {
              // at the time of parsing, a compiled contract is available, so we've decoded constructor inputs( if any )
              ( compilation, ethabi.constructorCallData( inputs, abi ).get.hex ) // asserts successful encoding of params
            }
          }
        }
        val codeHex = compilation.code
        val dataHex = codeHex ++ inputsHex
        val address = EthAddress( ethAddress.value )
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val gas = ethGasOverrides.value.getOrElse( contractName, markupEstimateGas( log, jsonRpcUrl, Some(address), None, dataHex.decodeHex.toImmutableSeq, jsonrpc20.Client.BlockNumber.Pending, markup ) )
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, dataHex.decodeHex.toImmutableSeq )
        val privateKey = findCachePrivateKey.value
        val updateChangedDb = ethUpdateContractDatabase.value
        val txnHash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Contract '${contractName}' deployed in transaction '0x${txnHash.hex}'." )
        val out = awaitTransactionReceipt( log, jsonRpcUrl, txnHash, PollSeconds, PollAttempts )
        out.foreach { receipt =>
          receipt.contractAddress.foreach { ca =>
            log.info( s"Contract '${contractName}' has been assigned address '0x${ca.hex}'." )
            val dbCheck = {
              import compilation.info._
              Repository.Database.insertNewDeployment( ca, codeHex, address, txnHash )
            }
            dbCheck.xwarn("Could not insert information about deployed contract into the repository database")
          }
        }
        out
      }
    }

    def ethDumpContractInfoTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genContractAddressOrCodeHashParser )

      Def.inputTask {
        println()
        val cap =     "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-="
        val minicap = "------------------------------------------------------------------------"
        println( cap )
        println("                       CONTRACT INFO DUMP")
        println( cap )

        def section( title : String, body : Option[String], hex : Boolean = false ) = body.foreach { b =>
          println( minicap )
          println( s"${title}:")
          println();
          println( (if ( hex ) "0x" else "") + b )
        }
        val source = parser.parsed
        source match {
          case Left( address ) => {
            val mbinfo = Repository.Database.deployedContractInfoForAddress( address ).get // throw any db problem
            mbinfo.fold( println( s"Contract with address '$address' not found." ) ) { info =>
              section( "Contract Address", Some( info.address.hex ), true )
              section( "Deployer Address", info.deployerAddress.map( _.hex ), true )
              section( "Transaction Hash", info.transactionHash.map( _.hex ), true )
              section( "Deployment Timestamp", info.deployedWhen.map( l => (new Date(l)).toString ) )
              section( "Code Hash", Some( EthHash.hash( info.code.decodeHex ).hex ), true )
              section( "Code", Some( info.code ), true )
              section( "Contract Name", info.name )
              section( "Contract Source", info.source )
              section( "Contract Language", info.language )
              section( "Language Version", info.languageVersion )
              section( "Compiler Version", info.compilerVersion )
              section( "Compiler Options", info.compilerOptions )
              section( "ABI Definition", info.abiDefinition )
              section( "User Documentation", info.userDoc )
              section( "Developer Documentation", info.developerDoc )
            }
          }
          case Right( hash ) => {
            val mbinfo = Repository.Database.deployedContractInfoForCodeHash( hash ).get // throw any db problem
            mbinfo.fold( println( s"Contract with code hash '$hash' not found." ) ) { info =>
              section( "Code Hash", Some( hash.hex ), true )
              section( "Code", Some( info.code ), true )
              section( "Contract Name", info.name )
              section( "Contract Source", info.source )
              section( "Contract Language", info.language )
              section( "Language Version", info.languageVersion )
              section( "Compiler Version", info.compilerVersion )
              section( "Compiler Options", info.compilerOptions )
              section( "ABI Definition", info.abiDefinition )
              section( "User Documentation", info.userDoc )
              section( "Developer Documentation", info.developerDoc )
            }
          }
        }
        println( cap )
        println()
      }
    }

    def ethInvokeDataTask : Initialize[InputTask[immutable.Seq[Byte]]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genAddressFunctionInputsAbiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val ( contractAddress, function, args, abi ) = parser.parsed
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        val log = streams.value.log
        log.info( s"Call data: ${callData.hex}" )
        callData
      }
    }

    def ethInvokeTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val caller = EthAddress( ethAddress.value )
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        val amount = mbWei.getOrElse( Zero )
        val privateKey = findCachePrivateKey.value
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = markupEstimateGas( log, jsonRpcUrl, Some(caller), Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), contractAddress, Unsigned256( amount ), callData )
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei to address '0x${contractAddress.hex}' in transaction '0x${hash.hex}'.""" )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      }
    }

    def ethLoadWalletV3ForTask : Initialize[InputTask[Option[wallet.V3]]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val keystoresV3 = ethKeystoresV3.value
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

    def ethRevealPrivateKeyForTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val is = interactionService.value
        val log = streams.value.log
        
        val address = parser.parsed
        val addressStr = address.hex

        val s = state.value
	val extract = Project.extract(s)
	val (_, mbWallet) = extract.runInputTask(ethLoadWalletV3For, addressStr, s)

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

    def ethValidateWalletV3ForTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(ethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val is = interactionService.value
        val keystoreDirs = ethKeystoresV3.value
        val s = state.value
	val extract = Project.extract(s)
        val inputAddress = parser.parsed
	val (_, mbWallet) = extract.runInputTask(ethLoadWalletV3For, inputAddress.hex, s)
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

    /*
     * Things that need to be defined as tasks so that parsers can load them dynamically...
     */ 

    def ethFindCacheCompilationsTask : Initialize[Task[immutable.Map[String,jsonrpc20.Compilation.Contract]]] = Def.task {
      ethLoadCompilations.value
    }
    
    def ethFindCacheAliasesIfAvailableTask : Initialize[Task[Option[immutable.SortedMap[String,EthAddress]]]] = Def.task {
      Repository.Database.findAllAliases.toOption
    }
  }


  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin && InteractionServicePlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
