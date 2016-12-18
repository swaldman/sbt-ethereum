package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.complete.{FixedSetExamples,Parser}
import sbt.complete.DefaultParsers._
import sbt.Def.Initialize
import sbt.InteractionServiceKeys.interactionService
import sbinary._
import sbinary.DefaultProtocol._

import java.io.{BufferedInputStream,File,FileInputStream,FilenameFilter}
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

import play.api.libs.json.Json

import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._
import ethabi.Encoder
import jsonrpc20.{Abi,ClientTransactionReceipt,MapStringCompilationContractFormat}
import specification.Denominations

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations._
import com.mchange.sc.v1.consuela.ethereum.ethabi.callDataForFunctionNameAndArgs
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

  private val ZWSP = "\u200B" // we add zero-width space to parser examples lists where we don't want autocomplete to apply to unique examples

  private val GenericAddressParser = createAddressParser("<address-hex>")

  private val RecipientAddressParser = createAddressParser("<recipient-address-hex>")

  private val AmountParser = token(Space.* ~> (Digit|literal('.')).+, "<amount>").map( chars => BigDecimal( chars.mkString ) )

  private val UnitParser = {
    val ( w, s, f, e ) = ( "wei", "szabo", "finney", "ether" );
    //(Space.* ~>(literal(w) | literal(s) | literal(f) | literal(e))).examples(w, s, f, e)
    Space.* ~> token(literal(w) | literal(s) | literal(f) | literal(e))
  }

  private val ValueInWeiParser = (AmountParser ~ UnitParser).map { case ( amount, unit ) => rounded(amount * BigDecimal(Denominations.Multiplier.BigInt( unit ))).toBigInt }

  private val EthSendEtherParser : Parser[( EthAddress, BigInt )] = {
    RecipientAddressParser ~ ValueInWeiParser
  }

  private def functionParser( abi : Abi.Definition ) : Parser[Abi.Function] = {
    val namesToFunctions           = abi.functions.groupBy( _.name )

    // println( s"namesToFunctions: ${namesToFunctions}" )

    val overloadedNamesToFunctions = namesToFunctions.filter( _._2.length > 1 )
    val nonoverloadedNamesToFunctions : Map[String,Abi.Function] = (namesToFunctions -- overloadedNamesToFunctions.keySet).map( tup => ( tup._1, tup._2.head ) )

    // println( s"overloadedNamesToFunctions: ${overloadedNamesToFunctions}" )
    // println( s"nonoverloadedNamesToFunctions: ${nonoverloadedNamesToFunctions}" )

    def createQualifiedNameForOverload( function : Abi.Function ) : String = function.name + "(" + function.inputs.map( _.`type` ).mkString(",") + ")"

    def createOverloadBinding( function : Abi.Function ) : ( String, Abi.Function ) = ( createQualifiedNameForOverload( function ), function )

    val qualifiedOverloadedNamesToFunctions : Map[String, Abi.Function] = overloadedNamesToFunctions.values.flatMap( _.map( createOverloadBinding ) ).toMap

    // println( s"qualifiedOverloadedNamesToFunctions: ${qualifiedOverloadedNamesToFunctions}" )

    val processedNamesToFunctions = (qualifiedOverloadedNamesToFunctions ++ nonoverloadedNamesToFunctions).toMap

    // println( s"processedNamesToFunctions: ${processedNamesToFunctions}" )

    val baseParser = processedNamesToFunctions.keySet.foldLeft( failure("not a function name") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )

    baseParser.map( processedNamesToFunctions )
  }

  private def inputParser( input : Abi.Function.Parameter, unique : Boolean ) : Parser[String] = {
    val displayName = if ( input.name.length == 0 ) "mapping key" else input.name
    (StringEscapable.map( str => s""""${str}"""") | NotQuoted).examples( FixedSetExamples( immutable.Set( s"<${displayName}, of type ${input.`type`}>", ZWSP ) ) )
  }

  private def inputsParser( inputs : immutable.Seq[Abi.Function.Parameter] ) : Parser[immutable.Seq[String]] = {
    val unique = inputs.size <= 1
    val parserMaker : Abi.Function.Parameter => Parser[String] = param => inputParser( param, unique )
    inputs.map( parserMaker ).foldLeft( success( immutable.Seq.empty[String] ) )( (nascent, next) => nascent.flatMap( partial => Space ~> next.map( str => partial :+ str ) ) )
  }

  def functionAndInputsParser( abi : Abi.Definition ) : Parser[(Abi.Function, immutable.Seq[String])] = {
    token( functionParser( abi ) ).flatMap( function => inputsParser( function.inputs ).map( seq => ( function, seq ) ) )
  }

  val AddressFunctionInputsAbiParser : Parser[(EthAddress, Abi.Function, immutable.Seq[String], Abi.Definition)] = {
    GenericAddressParser.map( a => ( a, abiForAddress(a) ) ).flatMap { tup =>
      val address = tup._1
      val abi     = tup._2 
      ( Space ~> functionAndInputsParser( abi ) ).map { case ( function, inputs ) => ( address, function, inputs, abi ) }
    }
  }

  private val DbQueryParser : Parser[String] = (any.*).map( _.mkString.trim )

  private val Zero256 = Unsigned256( 0 )

  private val ZeroEthAddress = (0 until 40).map(_ => "0").mkString("")

  private def createAddressParser( tabHelp : String ) = token(Space.* ~> literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ), tabHelp).map( chars => EthAddress.apply( chars.mkString ) )

  /*
  implicit object CompilationMapSBinaryFormat extends sbinary.Format[immutable.Map[String,jsonrpc20.Compilation.Contract]]{
    def reads(in : Input) = Json.parse( StringFormat.reads( in ) ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]]
    def writes(out : Output, value : immutable.Map[String,jsonrpc20.Compilation.Contract]) = StringFormat.writes( out, Json.stringify( Json.toJson( value ) ) )
  }
   */

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

    val ethBalance = inputKey[BigDecimal]("Computes the balance in ether of the address set as 'ethAddress'")

    val ethBalanceFor = inputKey[BigDecimal]("Computes the balance in ether of a given address")

    val ethBalanceInWei = inputKey[BigInt]("Computes the balance in wei of the address set as 'ethAddress'")

    val ethBalanceInWeiFor = inputKey[BigInt]("Computes the balance in wei of a given address")

    val ethCallEphemeral = inputKey[Any]("Makes an ephemeral call against the local copy of the blockchain, usually to constant function. Returns the latest available result.")

    val ethCompileSolidity = taskKey[Unit]("Compiles solidity files")

    val ethCompiledContractNames = taskKey[immutable.Set[String]]("Finds compiled contract names")

    val ethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val ethDeployOnly = inputKey[Option[ClientTransactionReceipt]]("Deploys the specified named contract")

    val ethGasPrice = taskKey[BigInt]("Finds the current gas price, including any overrides or gas price markups")

    val ethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethEntropySource as a source of randomness")

    val ethGenWalletV3Pbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethEntropySource as a source of randomness")

    val ethGenWalletV3Scrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethEntropySource as a source of randomness")

    val ethGenWalletV3 = taskKey[wallet.V3]("Generates a new V3 wallet, using ethEntropySource as a source of randomness")

    val ethInvoke = inputKey[Option[ClientTransactionReceipt]]("Calls a function on a deployed smart contract")

    val ethInvokeData = inputKey[immutable.Seq[Byte]]("Reveals the data portion that would be sent in a message invoking a function and its arguments on a deployed smart contract")

    val ethListKeystoreAddresses = taskKey[immutable.Map[EthAddress,immutable.Set[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined.")

    val ethLoadCompilations = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts")

    val ethLoadWalletV3 = taskKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val ethLoadWalletV3For = inputKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val ethMemorizeAbi = taskKey[Unit]("Inserts an ABI definition for a contract into the sbt-ethereum database")

    val ethNextNonce = taskKey[BigInt]("Finds the next nonce for the address defined by setting 'ethAddress'")

    val ethRevealPrivateKeyFor = inputKey[Unit]("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")

    val ethQueryRepositoryDatabase = inputKey[Unit]("Primarily for debugging. Query the internal repository database.")

    val ethSelfPing = taskKey[Option[ClientTransactionReceipt]]("Sends 0 ether from ethAddress to itself")

    val ethSendEther = inputKey[Option[ClientTransactionReceipt]]("Sends ether from ethAddress to a specified account, format 'ethSendEther <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

    val ethUpdateContractDatabase = taskKey[Boolean]("Integrates newly compiled contracts and stubs (defined in ethKnownStubAddresses) into the contract database. Returns true if changes were made.")

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
        val credential = is.readLine(s"Enter passphrase or hex private key for address '${CurAddrStr}': ", mask = true).getOrElse(throw new Exception("Failed to read a credential")) // fail if we can't get a credential

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

      ethAbiForContractAddress := abiForAddress( GenericAddressParser.parsed ),

      ethBalance := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethBalanceFor, addressStr, s)
        result
      },

      ethBalanceFor := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val address = GenericAddressParser.parsed
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Ether )
        result.denominated
      },

      ethBalanceInWei := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethBalanceInWeiFor, addressStr, s)
        result
      },

      ethBalanceInWeiFor := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val address = GenericAddressParser.parsed
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Wei )
        result.wei
      },

      ethCallEphemeral := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val from = if ( ethAddress.value == ZeroEthAddress ) None else Some( EthAddress( ethAddress.value ) )
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = (AddressFunctionInputsAbiParser ~ ValueInWeiParser.?).parsed
        if (! function.constant ) {
          log.warn( s"Function '${function.name}' is not marked constant! An ephemeral call may not succeed, and in any case, no changes to the state of the blockchain will be preserved." )
        }
        val amount = mbWei.getOrElse( Zero )
        val callData = callDataForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the call data
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = markupEstimateGas( log, jsonRpcUrl, from, Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        val rawResult = doEthCallEphemeral( log, jsonRpcUrl, from, contractAddress, Some(gas), Some( gasPrice ), Some( amount ), Some( callData ), jsonrpc20.Client.BlockNumber.Latest )
        log.info( s"Raw result of call to function '${function.name}': 0x${rawResult.hex}" )
        val result : Any = { 
          function.outputs.length match {
            case 0 => {
              log.warn( s"No return type found for function '${function.name}'. Returning raw bytestring as result." )
              rawResult : Any
            }
            case 1 => {
              val tpe = function.outputs.head.`type`
              val mbEncoder = Encoder.encoderForSolidityType( tpe )
              mbEncoder.fold {
                log.warn( s"Could not find an encoder for solidity type '$tpe'. Returning raw bytestring as result.")
                rawResult : Any
              } { encoder =>
                val representation = encoder.decodeComplete( rawResult ).get // let the Exception fly if the decode fails
                encoder.formatUntyped( representation ).fold( fail => log.warn(s"Failed to format retrieved value. Failure ${fail}"), str => log.info(s"Decoded return value of type '$tpe': ${str}") )
                representation : Any
              }
            }
            case _ => {
              log.warn( s"Function '${function.name}' yields multiple return types, interpretation of which is not yet supported. Returning raw bytestring as result." )
              rawResult : Any
            }
          }
        }
        result
      },

      ethCompileSolidity in Compile := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value

        val solSource      = (ethSoliditySource in Compile).value
        val solDestination = (ethSolidityDestination in Compile).value

        doCompileSolidity( log, jsonRpcUrl, solSource, solDestination )
      },

      compile in Compile := {
        val dummy = (ethCompileSolidity in Compile).value
        (compile in Compile).value
      },

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

      ethInvoke := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val caller = EthAddress( ethAddress.value )
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = (AddressFunctionInputsAbiParser ~ ValueInWeiParser.?).parsed
        val amount = mbWei.getOrElse( Zero )
        val privateKey = findCachePrivateKey.value
        val callData = callDataForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the call data
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = markupEstimateGas( log, jsonRpcUrl, Some(caller), Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), contractAddress, Unsigned256( amount ), callData )
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei to address '0x${contractAddress.hex}' in transaction '0x${hash.hex}'.""" )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      },

      ethInvokeData := {
        val ( contractAddress, function, args, abi ) = AddressFunctionInputsAbiParser.parsed
        val callData = callDataForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the call data
        val log = streams.value.log
        log.info( s"Call data: ${callData.hex}" )
        callData
      },

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
        immutable.TreeSet( out.keySet.toSeq.map( address => s"0x${address.hex}" ) : _* ).foreach( println )
        out
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

      ethCompiledContractNames <<= ethCompiledContractNamesTask storeAs ethCompiledContractNames triggeredBy (ethCompileSolidity in Compile ),

      ethLoadWalletV3For := {
        val keystoresV3 = ethKeystoresV3.value
        val log         = streams.value.log

        val address = GenericAddressParser.parsed
        val out = {
          keystoresV3
            .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
            .foldLeft( None : Option[wallet.V3] ){ ( mb, nextKeystore ) =>
              if ( mb.isEmpty ) nextKeystore.get( address ) else mb
            }
        }
        log.info( out.fold( s"No V3 wallet found for '0x${address.hex}'" )( _ => s"V3 wallet found for '0x${address.hex}'" ) )
        out
      },

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

      ethDeployOnly <<= ethDeployOnlyTask,

      ethQueryRepositoryDatabase := {
        val log   = streams.value.log
        val query = DbQueryParser.parsed

        // XXX: should this be modified to be careful about DDL / inserts / updates / deletes etc?
        //      for now lets be conservative and restrict to SELECT
        if (! query.toLowerCase.startsWith("select")) {
          throw new Exception("For now, ethQueryRepositoryDatabase supports on SELECT statements. Sorry!")
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

      ethRevealPrivateKeyFor := {
        val is = interactionService.value
        val log = streams.value.log
        
        val addressStr = GenericAddressParser.parsed.hex

        val s = state.value
	val extract = Project.extract(s)
	val (_, mbWallet) = extract.runInputTask(ethLoadWalletV3For, addressStr, s)

        val credential = is.readLine(s"Enter passphrase for address '0x${addressStr}': ", mask = true).getOrElse(throw new Exception("Failed to read a credential")) // fail if we can't get a credential
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
      },

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

      ethSendEther := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val args = EthSendEtherParser.parsed
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

      watchSources ++= {
        val dir = (ethSoliditySource in Compile).value
        val filter = new FilenameFilter {
          def accept( dir : File, name : String ) = name.endsWith(".sol")
        }
        if ( dir.exists ) {
          dir.list( filter ).map( name => new File( dir, name ) ).toSeq
        } else {
          Nil
        }
      }
    )

    def ethDeployOnlyTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(ethCompiledContractNames) { (s, mbNamesSet) =>
        contractNamesParser(s, mbNamesSet.getOrElse( immutable.Set.empty ) )
      }
      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val contractName = parser.parsed
        val contractsMap = ethLoadCompilations.value
        val compilation = contractsMap( contractName )
        val hex = compilation.code
        val address = EthAddress( ethAddress.value )
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val gas = ethGasOverrides.value.getOrElse( contractName, markupEstimateGas( log, jsonRpcUrl, Some(address), None, hex.decodeHex.toImmutableSeq, jsonrpc20.Client.BlockNumber.Pending, markup ) )
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, hex.decodeHex.toImmutableSeq )
        val privateKey = findCachePrivateKey.value
        val updateChangedDb = ethUpdateContractDatabase.value
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Contract '${contractName}' deployed in transaction '0x${hash.hex}'." )
        val out = awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
        out.foreach { receipt =>
          receipt.contractAddress.foreach { ca =>
            log.info( s"Contract '${contractName}' has been assigned address '0x${ca.hex}'." )
            val dbCheck = {
              import compilation.info._
              Repository.Database.insertNewDeployment( ca, hex, address, hash )
            }
            dbCheck.xwarn("Could not insert information about deployed contract into the repository database")
          }
        }
        out
      }
    }

    def contractNamesParser : (State, immutable.Set[String]) => Parser[String] = {
      (state, contractNames) => {
        val exSet = if ( contractNames.isEmpty ) immutable.Set("<contract-name>", ZWSP) else contractNames // non-breaking space to prevent autocompletion to dummy example
        Space ~> token( NotSpace examples exSet )
      }
    }

    def ethCompiledContractNamesTask : Initialize[Task[immutable.Set[String]]] = Def.task {
      val map = ethLoadCompilations.value
      immutable.TreeSet( map.keys.toSeq : _* )( Ordering.comparatorToOrdering( String.CASE_INSENSITIVE_ORDER ) )
    }
  }


  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin && InteractionServicePlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
