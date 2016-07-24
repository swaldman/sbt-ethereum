package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.complete.Parser
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
import jsonrpc20.ClientTransactionReceipt
import jsonrpc20.MapStringCompilationContractFormat
import specification.Denominations

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._

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

  private val SendGasAmount = G.transaction

  private val GenericAddressParser = createAddressParser("<address-hex>")

  private val RecipientAddressParser = createAddressParser("<recipient-address-hex>")

  private val AmountParser = token(Space.* ~> (Digit|literal('.')).+, "<amount>").map( chars => BigDecimal( chars.mkString ) )

  private val UnitParser = {
    val ( w, s, f, e ) = ( "wei", "szabo", "finney", "ether" );
    //(Space.* ~>(literal(w) | literal(s) | literal(f) | literal(e))).examples(w, s, f, e)
    Space.* ~> token(literal(w) | literal(s) | literal(f) | literal(e))
  }

  private val EthSendEtherParser : Parser[( EthAddress, BigInt )] = {
    def rounded( bd : BigDecimal ) = bd.round( bd.mc ) // work around absence of default rounded method in scala 2.10 BigDecimal
    def tupToTup( tup : ( ( EthAddress, BigDecimal ), String ) ) = ( tup._1._1, rounded(tup._1._2 * BigDecimal(Denominations.Multiplier.BigInt( tup._2 ))).toBigInt )
    (RecipientAddressParser ~ AmountParser ~ UnitParser).map( tupToTup )
  }
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

    val ethKeystoresV3 = settingKey[Seq[File]]("Directories from which V3 wallets can be loaded")

    val ethJsonRpcVersion = settingKey[String]("Version of Ethereum's JSON-RPC spec the build should work with")

    val ethJsonRpcUrl = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

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

    val ethCompileSolidity = taskKey[Unit]("Compiles solidity files")

    val ethDeployOnly = inputKey[Option[ClientTransactionReceipt]]("Deploys the specified named contract")

    val ethGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val ethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethEntropySource as a source of randomness")

    val ethGenWalletV3Pbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethEntropySource as a source of randomness")

    val ethGenWalletV3Scrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethEntropySource as a source of randomness")

    val ethGenWalletV3 = taskKey[wallet.V3]("Generates a new V3 wallet, using ethEntropySource as a source of randomness")

    val ethLoadCompilations = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts")

    val ethLoadWalletV3 = taskKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val ethLoadWalletV3ForAddress = inputKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val ethCompiledContractNames = taskKey[immutable.Set[String]]("Finds compiled contract names")

    val ethNextNonce = taskKey[BigInt]("Finds the next nonce for the address defined by setting 'ethAddress'")

    val ethRevealPrivateKeyForAddress = inputKey[Unit]("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")

    val ethSelfPing = taskKey[Option[ClientTransactionReceipt]]("Sends 0 ether from ethAddress to itself")

    val ethSendEther = inputKey[Option[ClientTransactionReceipt]]("Sends ether from ethAddress to a specified account, format 'ethSendEther <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

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

      ethJsonRpcVersion := "2.0",
      ethJsonRpcUrl     := "http://localhost:8545",

      ethEntropySource := new java.security.SecureRandom,

      ethGasMarkup := 0.2,

      ethGasOverrides := Map.empty[String,BigInt],

      ethKeystoresV3 := {
        def warning( location : String ) : String = s"Failed to find V3 keystore in ${location}"
        def listify( fd : Failable[File] ) = fd.fold( _ => Nil, f => List(f) )
        listify( Repository.KeyStore.V3.Directory.xwarn( warning("sbt-ethereum repository") ) ) ::: listify( clients.geth.KeyStore.Directory.xwarn( warning("geth home directory") ) ) ::: Nil
      },

      ethAddress := {
        val mbProperty = Option( System.getProperty( EthAddressSystemProperty ) )
        val mbEnvVar   = Option( System.getenv( EthAddressEnvironmentVariable ) )


        (mbProperty orElse mbEnvVar).getOrElse( ZeroEthAddress )
      },

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      ethSoliditySource in Compile      := (sourceDirectory in Compile).value / "solidity",

      ethSolidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      ethWalletV3ScryptN := wallet.V3.Default.Scrypt.N,

      ethWalletV3ScryptR := wallet.V3.Default.Scrypt.R,

      ethWalletV3ScryptP := wallet.V3.Default.Scrypt.P,

      ethWalletV3ScryptDkLen := wallet.V3.Default.Scrypt.DkLen,

      ethWalletV3Pbkdf2C := wallet.V3.Default.Pbkdf2.C,

      ethWalletV3Pbkdf2DkLen := wallet.V3.Default.Pbkdf2.DkLen,

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

      ethGasPrice := {
        val log        = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        doGetDefaultGasPrice( log, jsonRpcUrl )
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

      ethNextNonce := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value
        doGetTransactionCount( log, jsonRpcUrl, EthAddress( ethAddress.value ), jsonrpc20.Client.BlockNumber.Pending )
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

      ethLoadWalletV3ForAddress := {
        val keystoresV3 = ethKeystoresV3.value
        val log         = streams.value.log

        val address = GenericAddressParser.parsed
        val out = {
          keystoresV3
            .map( wallet.V3.keyStoreMap )
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
	val (_, result) = extract.runInputTask(ethLoadWalletV3ForAddress, addressStr, s)
        result
      },

      ethDeployOnly <<= ethDeployOnlyTask,

      ethRevealPrivateKeyForAddress := {
        val is = interactionService.value
        val log = streams.value.log
        
        val addressStr = GenericAddressParser.parsed.hex

        val s = state.value
	val extract = Project.extract(s)
	val (_, mbWallet) = extract.runInputTask(ethLoadWalletV3ForAddress, addressStr, s)

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
        val gasPrice = ethGasPrice.value
        val gas = SendGasAmount
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), to, Unsigned256( amount ), List.empty[Byte] )
        val privateKey = findCachePrivateKey.value
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Sent ${amount} wei to address '0x${to.hex}' in transaction '0x${hash.hex}'." )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
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
        val hex = contractsMap( contractName ).code
        val nextNonce = ethNextNonce.value
        val gasPrice = ethGasPrice.value
        val gas = ethGasOverrides.value.getOrElse( contractName, doEstimateGas( log, jsonRpcUrl, EthAddress( ethAddress.value ), hex.decodeHex.toImmutableSeq, jsonrpc20.Client.BlockNumber.Pending ) )
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, hex.decodeHex.toImmutableSeq )
        val privateKey = findCachePrivateKey.value
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Contract '${contractName}' deployed in transaction '0x${hash.hex}'." )
        val out = awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
        out.foreach( receipt => log.info( s"Contract '${contractName}' has been assigned address '0x${receipt.contractAddress.get.bytes.widen.hex}'." ) )
        out
      }
    }

    def contractNamesParser : (State, immutable.Set[String]) => Parser[String] = {
      (state, contractNames) => {
        Space ~> token(NotSpace examples contractNames )
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
