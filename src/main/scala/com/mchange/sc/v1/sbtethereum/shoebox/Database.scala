package com.mchange.sc.v1.sbtethereum.shoebox

import java.io.File
import java.sql.{Connection, Timestamp}
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import javax.sql.DataSource
import scala.collection._
import scala.util.matching.Regex.Match
import com.mchange.v2.c3p0.ComboPooledDataSource
import com.mchange.sc.v1.sbtethereum.shoebox
import com.mchange.sc.v1.sbtethereum.util.BaseCodeAndSuffix
import com.mchange.sc.v2.sql._
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.io._
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, jsonrpc}
import jsonrpc.{Abi, Compilation}
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import com.mchange.sc.v1.sbtethereum.util.Abi.{abiHash, abiTextHash}
import play.api.libs.json.Json

import com.mchange.sc.v1.log.MLevel._

private [sbtethereum]
object Database {
  private implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )

  private [sbtethereum]
  case class CompilationInfo (
    codeHash          : EthHash,
    code              : String,
    mbName            : Option[String],
    mbSource          : Option[String],
    mbLanguage        : Option[String],
    mbLanguageVersion : Option[String],
    mbCompilerVersion : Option[String],
    mbCompilerOptions : Option[String],
    mbAbiHash         : Option[EthHash],
    mbAbi             : Option[Abi],
    mbUserDoc         : Option[Compilation.Doc.User],
    mbDeveloperDoc    : Option[Compilation.Doc.Developer],
    mbMetadata        : Option[String],
    mbAst             : Option[String],
    mbProjectName     : Option[String]
  )

  private [sbtethereum]
  final case class ContractsSummaryRow( mb_chain_id : Option[Int], contract_address : String, name : String, deployer_address : String, code_hash : String, txn_hash : String, timestamp : String )

  private [sbtethereum]
  final case class Dump( timestamp : Long, schemaVersion : Int, file : File )
}
private [sbtethereum]
class Database( parent : Shoebox ) extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {
  import Database._

  import Schema_h2._

  private val DirName = "database"

  private [shoebox] lazy val DirectoryManager = AutoResource.UserOnlyDirectory( rawParent=parent.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => parent.Directory), dirName=DirName )

  private val UncheckedDataSourceManager = AutoResource[Unit,Failable[ComboPooledDataSource]]( (), _ => h2.initializeDataSource( false ), _.map( _.close() ) )
  private val CheckedDataSourceManager   = AutoResource[Unit,Failable[ComboPooledDataSource]]( (), _ => h2.initializeDataSource( true ),  _.map( _.close() ) )

  private [sbtethereum]
  def DataSource : Failable[ComboPooledDataSource] = CheckedDataSourceManager.active 

  private [sbtethereum]
  def UncheckedDataSource : Failable[ComboPooledDataSource] = UncheckedDataSourceManager.active

  val TargetSchemaVersion = h2.SchemaVersion

  private [sbtethereum]
  def reset() : Unit = this.synchronized {
    DirectoryManager.reset()
    UncheckedDataSourceManager.reset()
    CheckedDataSourceManager.reset()
    h2.reset()
  }

  def userReadOnlyFiles  : immutable.Set[File] = h2.userReadOnlyFiles
  def userExecutableFiles : immutable.Set[File] = h2.userExecutableFiles

  private val GoodAliasRegex = """^[A-Za-z][A-Za-z0-9_\055]*$""".r // \055 is the '-' character (and is provided as \055 to the regex due to """), note a match means the whole String matches due to the ^...$ construction

  private def sneakyEthAddressHex( otherwiseGoodAlias : String ) : Boolean = {
    try {
      EthAddress( otherwiseGoodAlias )
      true
    }
    catch {
      case e : Exception => false
    }
  }

  private def isGoodAlias( alias : String ) : Boolean = (GoodAliasRegex.findFirstIn( alias )) != None && !sneakyEthAddressHex( alias )

  private def requireGoodAlias( alias : String ) = {
    require(
      isGoodAlias( alias ),
      s"'${alias}' is not a valid alias. Should begin with ascii alphabetic, then contain ascii alphanumeric, underscore (_) or dash (-), and must not be interpretable as a valid 20-byte hex address."
    )
  }

  private [sbtethereum]
  def insertCompilation(
    code              : String,
    mbName            : Option[String] = None,
    mbSource          : Option[String] = None,
    mbLanguage        : Option[String] = None,
    mbLanguageVersion : Option[String] = None,
    mbCompilerVersion : Option[String] = None,
    mbCompilerOptions : Option[String] = None,
    mbAbi             : Option[String] = None,
    mbUserDoc         : Option[String] = None,
    mbDeveloperDoc    : Option[String] = None,
    mbMetadata        : Option[String] = None,
    mbAst             : Option[String] = None,
    mbProjectName     : Option[String] = None
  ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        val bcas = BaseCodeAndSuffix( code )
        borrowTransact( ds.getConnection() ) { conn =>
          Table.KnownCode.upsert( conn, bcas.baseCodeHex )
          val mbAbiHash = mbAbi.map { abiText =>
            val abi = Json.parse( abiText ).as[Abi]
            val (abiHash, _ ) = Table.NormalizedAbis.upsert( conn, abi )
            abiHash
          }
          Table.KnownCompilations.upsert(
            conn,
            bcas.baseCodeHash,
            bcas.fullCodeHash,
            bcas.codeSuffixHex,
            mbName,
            mbSource,
            mbLanguage,
            mbLanguageVersion,
            mbCompilerVersion,
            mbCompilerOptions,
            mbAbiHash,
            mbUserDoc.map( userDoc => Json.parse( userDoc ).as[Compilation.Doc.User] ),
            mbDeveloperDoc.map( developerDoc => Json.parse( developerDoc ).as[Compilation.Doc.Developer] ),
            mbMetadata,
            mbAst,
            mbProjectName
          )
        }
      }
    }
  }

  private [sbtethereum]
  def insertNewDeployment( chainId : Int, contractAddress : EthAddress, code : String, deployerAddress : EthAddress, transactionHash : EthHash, constructorInputs : immutable.Seq[Byte] ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.DeployedCompilations.insertNewDeployment( conn, chainId, contractAddress, code, deployerAddress, transactionHash, constructorInputs )
        }
      }
    }
  }

  private [sbtethereum]
  def setUnattachedImportedContractAbi( abi : Abi ) : Failable[EthHash] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          val ( abiHash, _ ) = Table.NormalizedAbis.upsert( conn, abi )
          abiHash
        }
      }
    }
  }

  private [sbtethereum]
  def setImportedContractAbi( chainId : Int, contractAddress : EthAddress, abi : Abi ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          val ( abiHash, _ ) = Table.NormalizedAbis.upsert( conn, abi )
          Table.MemorizedAbis.insert( conn, chainId, contractAddress, abiHash )
        }
      }
    }
  }

  private [sbtethereum]
  def resetImportedContractAbi( chainId : Int, contractAddress : EthAddress, abi : Abi ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        borrowTransact( ds.getConnection() ){ conn =>
          val ( abiHash, _ ) = Table.NormalizedAbis.upsert( conn, abi )
          Table.MemorizedAbis.delete( conn, chainId, contractAddress )
          Table.MemorizedAbis.insert( conn, chainId, contractAddress, abiHash )
        }
      }
    }
  }


  private [sbtethereum]
  def deleteImportedContractAbi( chainId : Int, contractAddress : EthAddress ) : Failable[Boolean] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.delete( conn, chainId, contractAddress )
        }
      }
    }
  }

  private [sbtethereum]
  def getImportedContractAbiAddresses( chainId : Int ) : Failable[immutable.Seq[EthAddress]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.selectAddressesForChainId( conn, chainId )
        }
      }
    }
  }

  private [sbtethereum]
  def getImportedContractAbiHash( chainId : Int, contractAddress : EthAddress ) : Failable[Option[EthHash]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.select( conn, chainId, contractAddress )
        }
      }
    }
  }

  // we don't just start from getImportedContractAbiHash(...) because we are gonna need the Database connection
  private [sbtethereum]
  def getImportedContractAbi( chainId : Int, contractAddress : EthAddress ) : Failable[Option[Abi]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.select( conn, chainId, contractAddress ).flatMap( hash => Table.NormalizedAbis.select( conn, hash ) )
        }
      }
    }
  }

  private [sbtethereum]
  def updateContractDatabase( compilations : Iterable[(String,jsonrpc.Compilation.Contract)], mbProjectName : Option[String] ) : Failable[Boolean] = {
    val ( compiledContracts, stubsWithDups ) = compilations.partition { case ( name, compilation ) => compilation.code.decodeHex.length > 0 }

    stubsWithDups.foreach { case ( name, compilation ) =>
      DEBUG.log( s"Contract '$name' is a stub or abstract contract, and so has not been incorporated into shoebox compilations." )
    }

    def updateKnownContracts( conn : Connection ) : Failable[Boolean] = {
      def doUpdate( conn : Connection, contractTuple : Tuple2[String,jsonrpc.Compilation.Contract] ) : Failable[Boolean] = Failable {
        val ( name, compilation ) = contractTuple

        val code = compilation.code
        val bcas = BaseCodeAndSuffix( code )


        import compilation.info._

        val mbAbiHash = mbAbi.map { abi =>
          val (abiHash, _ ) = Table.NormalizedAbis.upsert( conn, abi )
          abiHash
        }

        Table.KnownCode.upsert( conn, bcas.baseCodeHex )

        val newCompilation = Table.KnownCompilations.KnownCompilation(
          fullCodeHash      = bcas.fullCodeHash,
          baseCodeHash      = bcas.baseCodeHash,
          codeSuffix        = bcas.codeSuffixHex,
          mbName            = Some( name ),
          mbSource          = mbSource,
          mbLanguage        = mbLanguage,
          mbLanguageVersion = mbLanguageVersion,
          mbCompilerVersion = mbCompilerVersion,
          mbCompilerOptions = mbCompilerOptions,
          mbAbiHash         = mbAbiHash,
          mbUserDoc         = mbUserDoc,
          mbDeveloperDoc    = mbDeveloperDoc,
          mbMetadata        = mbMetadata,
          mbAst             = mbAst,
          mbProjectName     = mbProjectName
        )

        // println( s"newCompilation -- fullCodeHash: ${newCompilation.fullCodeHash}, baseCodeHash: ${newCompilation.baseCodeHash}" );

        val mbKnownCompilation = Table.KnownCompilations.select( conn, bcas.fullCodeHash )

        mbKnownCompilation match {
          case Some( kc ) => {
            if ( kc != newCompilation ) {
              Table.KnownCompilations.upsert( conn, newCompilation reconcileOver kc )
              true
            }
            else false
          }
          case None => {
            Table.KnownCompilations.upsert( conn, newCompilation )
            true
          }
        }
      }
      compiledContracts.toSeq.foldLeft( Failable.succeed( false ) )( ( failable, tup ) => failable.flatMap( last => doUpdate( conn, tup ).map( next => last || next ) ) )
    }

    DataSource.flatMap { ds =>
      borrowTransact( ds.getConnection() )( updateKnownContracts )
    }
  }

  private [sbtethereum]
  case class DeployedContractInfo (
    chainId             : Int,
    contractAddress     : EthAddress,
    codeHash            : EthHash,
    code                : String,
    mbDeployerAddress   : Option[EthAddress],
    mbTransactionHash   : Option[EthHash],
    mbDeployedWhen      : Option[Long],
    mbConstructorInputs : Option[immutable.Seq[Byte]],
    mbName              : Option[String],
    mbSource            : Option[String],
    mbLanguage          : Option[String],
    mbLanguageVersion   : Option[String],
    mbCompilerVersion   : Option[String],
    mbCompilerOptions   : Option[String],
    mbAbiHash           : Option[EthHash],
    mbAbi               : Option[Abi],
    mbUserDoc           : Option[Compilation.Doc.User],
    mbDeveloperDoc      : Option[Compilation.Doc.Developer],
    mbMetadata          : Option[String],
    mbAst               : Option[String],
    mbProjectName       : Option[String]
  )

  private [sbtethereum]
  def deployedContractInfoForAddress( chainId : Int, address : EthAddress ) : Failable[Option[DeployedContractInfo]] =  {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection ) { conn =>
          for {
            deployedCompilation <- Table.DeployedCompilations.select( conn, chainId, address )
            knownCode           <- Table.KnownCode.select( conn, deployedCompilation.baseCodeHash )
            knownCompilation    <- Table.KnownCompilations.select( conn, deployedCompilation.fullCodeHash )
          } yield {
            DeployedContractInfo (
              chainId              = deployedCompilation.chainId,
              contractAddress      = deployedCompilation.contractAddress,
              codeHash             = deployedCompilation.fullCodeHash,
              code                 = knownCode ++ knownCompilation.codeSuffix,
              mbDeployerAddress    = deployedCompilation.mbDeployerAddress,
              mbTransactionHash    = deployedCompilation.mbTransactionHash,
              mbDeployedWhen       = deployedCompilation.mbDeployedWhen,
              mbConstructorInputs  = deployedCompilation.mbConstructorInputs,
              mbName               = knownCompilation.mbName,
              mbSource             = knownCompilation.mbSource,
              mbLanguage           = knownCompilation.mbLanguage,
              mbLanguageVersion    = knownCompilation.mbLanguageVersion,
              mbCompilerVersion    = knownCompilation.mbCompilerVersion,
              mbCompilerOptions    = knownCompilation.mbCompilerOptions,
              mbAbiHash            = knownCompilation.mbAbiHash,
              mbAbi                = knownCompilation.mbAbiHash.flatMap( Table.NormalizedAbis.select( conn, _ ) ),
              mbUserDoc            = knownCompilation.mbUserDoc,
              mbDeveloperDoc       = knownCompilation.mbDeveloperDoc,
              mbMetadata           = knownCompilation.mbMetadata,
              mbAst                = knownCompilation.mbAst,
              mbProjectName        = knownCompilation.mbProjectName
            )
          }
        }
      }
    }
  }

  private [sbtethereum]
  def allDeployedContractInfosForChainId( chainId : Int ) : Failable[immutable.Seq[DeployedContractInfo]] = {

    def deployedContractInfosForAddresses( addresses : immutable.Seq[EthAddress] ) : Failable[immutable.Seq[DeployedContractInfo]] = {
      Failable.sequence( addresses.map( deployedContractInfoForAddress( chainId, _ ) ) ).map( optSeq => optSeq.map( opt => opt.get ) ) // asserts that all deploymens are known compilations
    }

    DataSource.flatMap { ds =>
      borrow( ds.getConnection ) { conn =>
        Failable( Table.DeployedCompilations.allAddressesForChainIdSeq( conn, chainId ) ) flatMap { addresses =>
          deployedContractInfosForAddresses( addresses )
        }
      }
    }
  }

  private [sbtethereum]
  def compilationInfoForCodeHash( codeHash : EthHash ) : Failable[Option[CompilationInfo]] =  {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection ) { conn =>
          for {
            knownCompilation <- Table.KnownCompilations.select( conn, codeHash )
            knownCodeHex <- Table.KnownCode.select( conn, knownCompilation.baseCodeHash )
          } yield {
            CompilationInfo (
              codeHash          = codeHash,
              code              = knownCodeHex,
              mbName            = knownCompilation.mbName,
              mbSource          = knownCompilation.mbSource,
              mbLanguage        = knownCompilation.mbLanguage,
              mbLanguageVersion = knownCompilation.mbLanguageVersion,
              mbCompilerVersion = knownCompilation.mbCompilerVersion,
              mbCompilerOptions = knownCompilation.mbCompilerOptions,
              mbAbiHash         = knownCompilation.mbAbiHash,
              mbAbi             = knownCompilation.mbAbiHash.flatMap( Table.NormalizedAbis.select( conn, _ ) ),
              mbUserDoc         = knownCompilation.mbUserDoc,
              mbDeveloperDoc    = knownCompilation.mbDeveloperDoc,
              mbMetadata        = knownCompilation.mbMetadata,
              mbAst             = knownCompilation.mbAst,
              mbProjectName     = knownCompilation.mbProjectName
            )
          }
        }
      }
    }
  }

  private [sbtethereum]
  def contractAddressesForCodeHash( chainId : Int, codeHash : EthHash ) : Failable[immutable.Set[EthAddress]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ) { conn =>
          Table.DeployedCompilations.allForFullCodeHash( conn, chainId, codeHash ).map( _.contractAddress )
        }
      }
    }
  }

  private [sbtethereum]
  def chainIdContractAddressesForCodeHash( codeHash : EthHash ) : Failable[immutable.Set[(Int,EthAddress)]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ) { conn =>
          Table.DeployedCompilations.allForFullCodeHashAnyChainId( conn, codeHash ).map( dc => ( dc.chainId, dc.contractAddress ) )
        }
      }
    }
  }

  private [sbtethereum]
  def contractsSummary : Failable[immutable.Seq[ContractsSummaryRow]] = {
    import ContractsSummary._

    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ) { conn =>
          borrow( conn.createStatement() ) { stmt =>
            borrow( stmt.executeQuery( ContractsSummary.Sql ) ) { rs =>
              val buffer = new mutable.ArrayBuffer[ContractsSummaryRow]
              val df = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss.SSSZ" )
              def mbformat( ts : Timestamp ) : String = if ( ts == null ) null else df.format( ts )
              while( rs.next() ) {
                buffer += ContractsSummaryRow(
                  Option( rs.getString(Column.chain_id) ).map( _.toInt ),
                  rs.getString(Column.contract_address),
                  rs.getString(Column.name),
                  rs.getString(Column.deployer_address),
                  rs.getString(Column.full_code_hash),
                  rs.getString(Column.txn_hash),
                  mbformat( rs.getTimestamp( Column.deployed_when ) )
                )
              }
              buffer.toVector
            }
          }
        }
      }
    }
  }

  private [sbtethereum]
  def cullUndeployedCompilations() : Failable[Int] = DataSource.flatMap { ds =>
    Failable {
      borrow( ds.getConnection() ) { conn =>
        borrow( conn.createStatement() ) { stmt =>
          stmt.executeUpdate( CullUndeployedCompilationsSql )
        }
      }
    }
  }

  private [shoebox] // external clients use AddressAliasManager
  def createUpdateAddressAlias( chainId : Int, alias : String, address : EthAddress ) : Failable[Unit] = DataSource.flatMap { ds =>
    requireGoodAlias( alias )
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.upsert( _, chainId, alias, address ) ) )
  }

  private [shoebox] // external clients use AddressAliasManager
  def insertAddressAlias( chainId : Int, alias : String, address : EthAddress ) : Failable[Unit] = DataSource.flatMap { ds =>
    requireGoodAlias( alias )
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.insert( _, chainId, alias, address ) ) )
  }

  private [shoebox] // external clients use AddressAliasManager
  def findAllAddressAliases( chainId : Int ) : Failable[immutable.SortedMap[String,EthAddress]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectAllForChainId( _, chainId ) ) )
  }

  private [shoebox] // external clients use AddressAliasManager
  def findAddressByAddressAlias( chainId : Int, alias : String ) : Failable[Option[EthAddress]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectByAlias( _, chainId, alias ) ) )
  }

  private [shoebox] // external clients use AddressAliasManager
  def findAddressAliasesByAddress( chainId : Int, address : EthAddress ) : Failable[immutable.Seq[String]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectByAddress( _, chainId, address ) ) )
  }

  private [shoebox] // external clients use AddressAliasManager
  def dropAddressAlias( chainId : Int, alias : String ) : Failable[Boolean] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.delete( _, chainId, alias ) ) )
  }

  private [shoebox] // external clients use AbiAliasHashManager
  def createUpdateAbiAlias( chainId : Int, alias : String, abiHash : EthHash ) : Failable[Unit] = DataSource.flatMap { ds =>
    requireGoodAlias( alias )
    Failable( borrow( ds.getConnection() )( Table.AbiAliases.upsert( _, chainId, alias, abiHash ) ) )
  }

  private [shoebox] // external clients use AbiAliasHashManager
  def createUpdateAbiAlias( chainId : Int, alias : String, abi : Abi ) : Failable[Unit] = createUpdateAbiAlias( chainId, alias, abiHash( abi ) )

  private [shoebox] // external clients use AbiAliasHashManager
  def findAllAbiAliases( chainId : Int ) : Failable[immutable.SortedMap[String,EthHash]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AbiAliases.selectAllForChainId( _, chainId ) ) )
  }

  private [shoebox] // external clients use AbiAliasHashManager
  def findAbiHashByAbiAlias( chainId : Int, alias : String ) : Failable[Option[EthHash]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AbiAliases.selectByAlias( _, chainId, alias ) ) )
  }

  private [shoebox] // external clients use AbiAliasHashManager
  def findAbiByAbiAlias( chainId : Int, alias : String ) : Failable[Option[Abi]] = DataSource.flatMap { ds =>
    Failable {
      borrow( ds.getConnection() ) { conn =>
        val mbHash = Table.AbiAliases.selectByAlias( conn, chainId, alias )
        mbHash.flatMap( hash => Table.NormalizedAbis.select( conn, hash ) )
      }
    }
  }

  private [shoebox] // external clients use AbiAliasHashManager
  def findAbiAliasesByAbiHash( chainId : Int, abiHash : EthHash ) : Failable[immutable.Seq[String]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AbiAliases.selectByAbiHash( _, chainId, abiHash ) ) )
  }

  private [shoebox] // external clients use AbiAliasHashManager
  def findAbiAliasesByAbi( chainId : Int, abi : Abi ) : Failable[immutable.Seq[String]] = findAbiAliasesByAbiHash( chainId, abiHash( abi ) )

  private [shoebox] // external clients use AbiAliasHashManager
  def hasAbiAliases( chainId : Int, abiHash : EthHash ) : Failable[Boolean] = findAbiAliasesByAbiHash( chainId, abiHash ).map( _.nonEmpty )

  private [shoebox] // external clients use AbiAliasHashManager
  def hasAbiAliases( chainId : Int, abi : Abi ) : Failable[Boolean] = hasAbiAliases( chainId, abiHash( abi ) )

  private [shoebox] // external clients use AbiAliasHashManager
  def dropAbiAlias( chainId : Int, alias : String ) : Failable[Boolean] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AbiAliases.delete( _, chainId, alias ) ) )
  }

  private [shoebox] // external clients use AbiAliasHashManager
  def findAbiByAbiHash( abiHash : EthHash ) : Failable[Option[jsonrpc.Abi]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.NormalizedAbis.select( _, abiHash ) ) )
  }

  private [sbtethereum]
  def findDefaultJsonRpcUrl( chainId : Int ) : Failable[Option[String]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.ChainDefaultJsonRpcUrls.selectDefaultJsonRpcUrl( _, chainId ) ) )
  }

  private [sbtethereum]
  def setDefaultJsonRpcUrl( chainId : Int, jsonRpcUrl : String ) : Failable[Unit] = DataSource.flatMap { ds =>
    require( jsonRpcUrl == jsonRpcUrl.trim(), s"Bad jsonRpcUrl '${jsonRpcUrl}', should not have whitespace trailing or leading." )
    require( chainId >= 0, "Negative Chain IDs mean no Chain ID specified. Cannot store default values for unspecified chain. [chainId: ${chainId}]" )
    Failable( borrow( ds.getConnection() )( Table.ChainDefaultJsonRpcUrls.insertDefaultJsonRpcUrl( _, chainId, jsonRpcUrl ) ) )
  }

  private [sbtethereum]
  def dropDefaultJsonRpcUrl( chainId : Int ) : Failable[Boolean] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.ChainDefaultJsonRpcUrls.deleteDefaultJsonRpcUrl( _, chainId ) ) )
  }

  private [sbtethereum]
  def findDefaultSenderAddress( chainId : Int ) : Failable[Option[EthAddress]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.ChainDefaultSenderAddresses.selectDefaultSenderAddress( _, chainId ) ) )
  }

  private [sbtethereum]
  def setDefaultSenderAddress( chainId : Int, senderAddress : EthAddress ) : Failable[Unit] = DataSource.flatMap { ds =>
    require( chainId >= 0, "Negative Chain IDs mean no Chain ID specified. Cannot store default values for unspecified chain. [chainId: ${chainId}]" )
    Failable( borrow( ds.getConnection() )( Table.ChainDefaultSenderAddresses.insertDefaultSenderAddress( _, chainId, senderAddress ) ) )
  }

  private [sbtethereum]
  def dropDefaultSenderAddress( chainId : Int ) : Failable[Boolean] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.ChainDefaultSenderAddresses.deleteDefaultSenderAddress( _, chainId ) ) )
  }

  private [sbtethereum]
  def deleteEtherscanApiKey() : Failable[Boolean] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.delete( _, Table.Metadata.Key.EtherscanApiKey ) ) )
  }

  private [sbtethereum]
  def getEtherscanApiKey() : Failable[Option[String]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.select( _, Table.Metadata.Key.EtherscanApiKey ) ) )
  }

  private [sbtethereum]
  def setEtherscanApiKey( apiKey : String ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.upsert( _, Table.Metadata.Key.EtherscanApiKey, apiKey ) ) )
  }

  private [sbtethereum]
  def deleteDefaultChainId() : Failable[Boolean] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.delete( _, Table.Metadata.Key.DefaultChainId ) ) )
  }

  private [sbtethereum]
  def getDefaultChainId() : Failable[Option[Int]] = DataSource.flatMap { ds =>
    Failable {
      borrow( ds.getConnection() ){ conn =>
        Table.Metadata.select( conn, Table.Metadata.Key.DefaultChainId ).flatMap { str =>
          try {
            Some( str.toInt )
          }
          catch {
            case nfe : NumberFormatException => {
              WARNING.log( s"Default chain ID in database, '${str}', cannot be parsed as an Int, and will be ignored." )
              None
            }
          }
        }
      }
    }
  }

  private [sbtethereum]
  def setDefaultChainId( chainId : Int ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.upsert( _, Table.Metadata.Key.DefaultChainId, chainId.toString ) ) )
  }

  private [sbtethereum]
  def getShoeboxBackupDir() : Failable[Option[String]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.select( _, Table.Metadata.Key.ShoeboxBackupDir ) ) )
  }

  private [sbtethereum]
  def setShoeboxBackupDir( absolutePath : String ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.upsert( _, Table.Metadata.Key.ShoeboxBackupDir, absolutePath ) ) )
  }

  private [sbtethereum]
  def getSchemaVersionUnchecked() : Failable[Option[Int]] = UncheckedDataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.select( _, Table.Metadata.Key.SchemaVersion ).map( _.toInt ) ) )
  }

  private [sbtethereum]
  def getLastSuccessfulSbtEthereumVersionUnchecked() : Failable[Option[String]] = UncheckedDataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.Metadata.select( _, Table.Metadata.Key.LastSuccessfulSbtEthereumVersion ) ) )
  }

  private [sbtethereum]
  def schemaVersionInconsistentUnchecked : Failable[Boolean] = getSchemaVersionUnchecked().map( _.get == Schema_h2.InconsistentSchemaVersion )

  def dumpDatabaseH2( conn : Connection, schemaVersion : Int ) : Failable[Dump] = h2.dump( conn, schemaVersion )

  def dump() : Failable[Dump] = {
    DataSource.flatMap { ds =>
      borrow( ds.getConnection() ) { conn =>
        Table.Metadata.select( conn, Table.Metadata.Key.SchemaVersion ) match {
          case None                  => Failable.fail( "Could not find the database schema version to dump the database!" )
          case Some( schemaVersion ) => dumpDatabaseH2( conn, schemaVersion.toInt )
        }
      }
    }
  }

  def restoreFromDump( dump : Dump ) : Failable[Unit] = restoreFromDump( dump.file )

  def restoreFromDump( file : File ) : Failable[Unit] = UncheckedDataSource.map { ds =>
    borrow( ds.getConnection() ){ conn =>
      conn.setAutoCommit( false )
      h2.restoreFromDump( conn, file )
      conn.commit()
    }
  }

  def latestDumpIfAny : Failable[Option[Dump]] = h2.mostRecentDump

  def restoreLatestDump() : Failable[Unit] = latestDumpIfAny.map( _.fold( Failable.fail( s"There are no dumps available to restore." ) : Failable[Unit] )( restoreFromDump ) )

  def dumpsOrderedByMostRecent : Failable[immutable.SortedSet[Dump]] = h2.dumpsOrderedByMostRecent

  def supersededByDumpDirectory : Failable[File] = h2.SupersededDir

  private final object h2 extends PermissionsOverrideSource with AutoResource.UserOnlyDirectory.Owner {
    val DirName           = "h2"
    val DbName            = "sbt-ethereum"
    val DumpsDirName      = "h2-dumps"
    val SupersededDirName = "h2-superseded"

    val SchemaVersion = Schema_h2.SchemaVersion

    private [shoebox]
    lazy val DirectoryManager           = AutoResource.UserOnlyDirectory( rawParent=Database.this.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => Database.this.Directory), dirName=DirName )

    private [shoebox]
    lazy val DumpsDirectoryManager      = AutoResource.UserOnlyDirectory( rawParent=Database.this.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => Database.this.Directory), dirName=DumpsDirName )

    private [shoebox]
    lazy val SupersededDirectoryManager = AutoResource.UserOnlyDirectory( rawParent=Database.this.Directory_ExistenceAndPermissionsUnenforced, enforcedParent=(() => Database.this.Directory), dirName=SupersededDirName )

    private [shoebox]
    lazy val DumpsDir_ExistenceAndPermissionsUnenforced : Failable[File] = DumpsDirectoryManager.existenceAndPermissionsUnenforced

    lazy val DumpsDir                                   : Failable[File] = DumpsDirectoryManager.existenceAndPermissionsEnforced

    private [shoebox]
    lazy val SupersededDir_ExistenceAndPermissionsUnenforced : Failable[File] = SupersededDirectoryManager.existenceAndPermissionsUnenforced

    lazy val SupersededDir                                   : Failable[File] = SupersededDirectoryManager.existenceAndPermissionsEnforced

    def reset() : Unit = {
      DirectoryManager.reset()
      DumpsDirectoryManager.reset()
      SupersededDirectoryManager.reset()
    }

    lazy val DbAsFile : Failable[File] = Directory.map( dir => new File( dir, DbName ) ) // the db will make files of this name, with various suffixes appended

    lazy val JdbcUrl : Failable[String] = DbAsFile.map( f => s"jdbc:h2:${f.getAbsolutePath};AUTO_SERVER=TRUE" )

    def userReadOnlyFiles  : immutable.Set[File] = {
      DumpsDir_ExistenceAndPermissionsUnenforced.map { dumpsDir =>
        if ( dumpsDir.exists() && dumpsDir.canRead() ) dumpsDir.listFiles.toSet else immutable.Set.empty[File]
      }.xwarn( "Failed to read h2-dumps dir.").getOrElse( immutable.Set.empty[File] )
    }
    val userExecutableFiles : immutable.Set[File] = immutable.Set.empty[File]

    def initializeDataSource( ensureSchema : Boolean ) : Failable[ComboPooledDataSource] = {
      // ensure the h2 database is bound to localhost, not some visible interface
      System.setProperty("h2.bindAddress", "127.0.0.1")

      val out = {
        for {
          dir     <- Directory
          jdbcUrl <- JdbcUrl
        } yield {
          val ds = new ComboPooledDataSource
          try {
            ds.setDriverClass( "org.h2.Driver" )
            ds.setJdbcUrl( jdbcUrl )
            ds.setTestConnectionOnCheckout( true )
            if ( ensureSchema ) Schema_h2.ensureSchema( Database.this, ds )
            ds
          } catch {
            case t : Throwable =>
              try ds.close() catch suppressInto(t)
              throw t
          }
          finally {
            dir.listFiles.filter( _.isFile ).foreach( setUserOnlyFilePermissions )
          }
        }
      }
      
      out.xwarn("Failure while initializing DataSource" + (if (ensureSchema) " (with schema check/migration):" else ":"))
    }

    private def suppressInto( original : Throwable ) : PartialFunction[Throwable,Unit] = {
      case t : Throwable => original.addSuppressed( t )
    }

    def dump( conn : Connection, schemaVersion : Int ) : Failable[Dump] = {
      DumpsDir.map { pmbDir =>
        val now = Instant.now()
        val ts = InFilenameTimestamp.generate( now )
        val targetFileName = s"$DbName-v$schemaVersion-$ts.sql"
        val targetFile = new File( pmbDir, targetFileName )
        try {
          borrow( conn.prepareStatement( s"SCRIPT TO '${targetFile.getCanonicalPath}' CHARSET 'UTF8'" ) )( _.executeQuery().close() ) // we don't need the result set, just want the file
          setUserReadOnlyFilePermissions( targetFile )
          Dump( now.toEpochMilli, schemaVersion, targetFile )
        }
        catch {
          case t : Throwable => {
            try if ( targetFile.exists() ) targetFile.renameTo( new File( targetFile.getCanonicalPath + "-INCOMPLETE" ) ) catch suppressInto( t )
            throw t
          }
        }
      }
    }

    // careful! this will delete the current database, then attempt to restore!
    def restoreFromDump( conn : Connection, dump : Dump ) : Failable[Unit] = restoreFromDump( conn, dump.file )

    // careful! this will delete the current database, then attempt to restore!
    def restoreFromDump( conn : Connection, file : File ) : Failable[Unit] = Failable {
      val cf = file.getCanonicalFile
      assert( !conn.getAutoCommit(), "Please execute restore from dump within a transactional context, and commit on success." )
      require ( cf.exists() && cf.canRead() && cf.length > 0, "A dump file should exist, be readable, and contain data." )

      // create a zip file of the superseded database before overwriting
      val superseded = new File( SupersededDir.assert, s"h2-superseded-${InFilenameTimestamp.generate()}.zip" )
      parent.backupManager.zip( superseded, h2.Directory.assert, _ => true )

      borrow( conn.createStatement() ) { stmt =>
        stmt.executeUpdate("DROP ALL OBJECTS")
        stmt.execute( s"RUNSCRIPT FROM '${cf.getCanonicalPath}'" )
      }
    }

    // not sure why this doesn't work...
    // adding string interpolation to """ strings seems to restore escaping
    // val DumpFileRegex = s"""${DbName}-v(\d+)-(\p{Alnum}+)\.sql$$""".r

    val DumpFileRegex = s"${DbName}-v(\\d+)-(\\p{Alnum}+)\\.sql$$".r

    private def createDump( path : String, m : Match ) : Dump = {
      Dump( InFilenameTimestamp.parse( m.group(2) ).toEpochMilli, m.group(1).toInt, new File( path ) )
    }

    def dumpsOrderedByMostRecent : Failable[immutable.SortedSet[Dump]] = {
      DumpsDir.map { dd =>
        val tuples = {
          dd.listFiles
            .map( _.getCanonicalPath )
            .map( path => ( path, DumpFileRegex.findFirstMatchIn(path) ) )
            .filter { case ( path, mbMatch ) => mbMatch.nonEmpty }
            .map { case ( path, mbMatch ) => createDump( path, mbMatch.get ) }
        }
        immutable.TreeSet.empty[Dump]( Ordering.by[Dump,Long]( _.timestamp ).reverse ) ++ tuples
      }
    }

    def mostRecentDump : Failable[Option[Dump]] = {
      dumpsOrderedByMostRecent.map { dumps =>
        if ( dumps.isEmpty ) None else Some( dumps.head )
      }
    }
  }
}
