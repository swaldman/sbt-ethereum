package com.mchange.sc.v1.sbtethereum

import java.io.{BufferedOutputStream,File,FileOutputStream,OutputStreamWriter,PrintWriter}
import java.sql.{Connection,Timestamp}
import java.text.SimpleDateFormat
import java.util.Date

import scala.io.Codec
import scala.util.control.NonFatal
import scala.collection._

import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.sql._
import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v2.util.Platform

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{clients,jsonrpc20,wallet,EthHash,EthAddress,EthTransaction}
import com.mchange.sc.v1.consuela.ethereum.jsonrpc20._
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory

import com.mchange.v2.c3p0.ComboPooledDataSource

import play.api.libs.json._

object Repository {
  private implicit lazy val logger : com.mchange.sc.v1.log.MLogger = mlogger( this )

  private val SystemProperty      = "sbt.ethereum.repository"
  private val EnvironmentVariable = "SBT_ETHEREUM_REPOSITORY"

  def logTransaction( transaction : EthTransaction.Signed, transactionHash : EthHash ) : Unit = {
    TransactionLog.File.flatMap { file =>
      Failable {
        val entry = TransactionLog.Entry( new Date(), transaction, transactionHash ) 
        borrow( new PrintWriter( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( file, true ) ), Codec.UTF8.charSet ) ) )( _.println( entry ) )
      }
    }.get // Unit or vomit Exception
  }

  final object TransactionLog {
    private val TimestampPattern = "yyyy-MM-dd'T'HH-mm-ssZ"

    lazy val File = Directory.map( dir => new java.io.File(dir, "transaction-log") )

    case class Entry( timestamp : Date, txn : EthTransaction.Signed, transactionHash : EthHash ) {
      override def toString() = {
        val ( ttype, payloadKey, payload ) = txn match {
          case m  : EthTransaction.Signed.Message          => ("Message", "data", m.data)
          case cc : EthTransaction.Signed.ContractCreation => ("ContractCreation", "init", cc.init)
        }
        val df = new SimpleDateFormat(TimestampPattern)
        val ts = df.format( timestamp )
        val first  = s"${ts}:type=${ttype},nonce=${txn.nonce.widen},gasPrice=${txn.gasPrice.widen},gasLimit=${txn.gasLimit.widen},value=${txn.value.widen},"
        val middle = if ( payload.length > 0 ) s"${payloadKey}=${payload.hex}," else ""
        val last   = s"v=${txn.v.widen},r=${txn.r.widen},s=${txn.s.widen},transactionHash=${transactionHash.bytes.hex}"
        first + middle + last
      }
    }
  }

  final object KeyStore {
    val DirName = "keystore"
    lazy val Directory : Failable[File] = Repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

    final object V3 {
      val DirName = "V3"
      lazy val Directory : Failable[File] = KeyStore.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

      def storeWallet( w : wallet.V3 ) : Failable[wallet.V3] = Directory.flatMap( clients.geth.KeyStore.add( _, w ) )
    }
  }

  final object Database {
    import Schema_h2._

    val DirName = "database"
    lazy val Directory : Failable[File] = Repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

    def insertCompilation(
      code              : String,
      mbName            : Option[String] = None,
      mbSource          : Option[String] = None,
      mbLanguage        : Option[String] = None,
      mbLanguageVersion : Option[String] = None,
      mbCompilerVersion : Option[String] = None,
      mbCompilerOptions : Option[String] = None,
      mbAbiDefinition   : Option[String] = None,
      mbUserDoc         : Option[String] = None,
      mbDeveloperDoc    : Option[String] = None,
      mbMetadata        : Option[String] = None
    ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          val bcas = BaseCodeAndSuffix( code )
          borrowTransact( ds.getConnection() ) { conn =>
            Table.KnownCode.upsert( conn, bcas.baseCodeHex )
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
              mbAbiDefinition.map( abiStr => Json.parse( abiStr ).as[Abi.Definition] ),
              mbUserDoc.map( userDoc => Json.parse( userDoc ).as[Doc.User] ),
              mbDeveloperDoc.map( developerDoc => Json.parse( developerDoc ).as[Doc.Developer] ),
              mbMetadata
            )
          }
        }
      }
    }

    def insertNewDeployment( blockchainId : String, contractAddress : EthAddress, code : String, deployerAddress : EthAddress, transactionHash : EthHash, constructorInputs : immutable.Seq[Byte] ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.DeployedCompilations.insertNewDeployment( conn, blockchainId, contractAddress, code, deployerAddress, transactionHash, constructorInputs )
          }
        }
      }
    }

    def insertExistingDeployment( blockchainId : String, contractAddress : EthAddress, code : String ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.DeployedCompilations.insertExistingDeployment( conn, blockchainId, contractAddress, code )
          }
        }
      }
    }

    def setMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress, abiDefinition : Abi.Definition ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.MemorizedAbis.insert( conn, blockchainId, contractAddress, abiDefinition )
          }
        }
      }
    }

    def deleteMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.MemorizedAbis.delete( conn, blockchainId, contractAddress )
          }
        }
      }
    }

    def getMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress ) : Failable[Option[Abi.Definition]] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.MemorizedAbis.select( conn, blockchainId, contractAddress )
          }
        }
      }
    }

    def updateContractDatabase( compilations : Iterable[(String,jsonrpc20.Compilation.Contract)] ) : Failable[Boolean] = {
      val ( compiledContracts, stubsWithDups ) = compilations.partition { case ( name, compilation ) => compilation.code.decodeHex.length > 0 }

      stubsWithDups.foreach { case ( name, compilation ) =>
        DEBUG.log( s"Contract '$name' is a stub or abstract contract, and so has not been incorporated into Repository compilations." )( Repository.logger )
      }

      def updateKnownContracts( conn : Connection ) : Failable[Boolean] = {
        def doUpdate( conn : Connection, contractTuple : Tuple2[String,jsonrpc20.Compilation.Contract] ) : Failable[Boolean] = Failable {
          val ( name, compilation ) = contractTuple

          val code = compilation.code
          val bcas = BaseCodeAndSuffix( code )


          import compilation.info._

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
            mbAbiDefinition   = mbAbiDefinition.map( a => Json.parse( a ).as[Abi.Definition] ),
            mbUserDoc         = mbUserDoc.map( ud => Json.parse( ud ).as[Doc.User] ),
            mbDeveloperDoc    = mbDeveloperDoc.map( dd => Json.parse( dd ).as[Doc.Developer] ),
            mbMetadata        = mbMetadata
          )

          val mbKnownCompilation = Table.KnownCompilations.select( conn, bcas.fullCodeHash )

          mbKnownCompilation match {
            case Some( kc ) => {
              if ( kc != newCompilation ) {
                Table.KnownCompilations.upsert( conn, newCompilation reconcileOver kc )
                true
              } else {
                false
              }
            }
            case None => {
              Table.KnownCompilations.upsert( conn, newCompilation )
              true
            }
          }
        }
        compiledContracts.toSeq.foldLeft( succeed( false ) )( ( failable, tup ) => failable.flatMap( last => doUpdate( conn, tup ).map( next => last || next ) ) )
      }

      DataSource.flatMap { ds =>
        borrowTransact( ds.getConnection() )( updateKnownContracts )
      }
    }

    case class DeployedContractInfo (
      blockchainId        : String,
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
      mbAbiDefinition     : Option[Abi.Definition],
      mbUserDoc           : Option[Doc.User],
      mbDeveloperDoc      : Option[Doc.Developer],
      mbMetadata          : Option[String]
    )

    def deployedContractInfoForAddress( blockchainId : String, address : EthAddress ) : Failable[Option[DeployedContractInfo]] =  {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection ) { conn =>
            for {
              deployedCompilation <- Table.DeployedCompilations.select( conn, blockchainId, address )
              knownCode           <- Table.KnownCode.select( conn, deployedCompilation.baseCodeHash )
              knownCompilation    <- Table.KnownCompilations.select( conn, deployedCompilation.fullCodeHash )
            } yield {
              DeployedContractInfo (
                blockchainId         = deployedCompilation.blockchainId,
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
                mbAbiDefinition      = knownCompilation.mbAbiDefinition,
                mbUserDoc            = knownCompilation.mbUserDoc,
                mbDeveloperDoc       = knownCompilation.mbDeveloperDoc,
                mbMetadata           = knownCompilation.mbMetadata
              )
            }
          }
        }
      }
    }

    case class CompilationInfo (
      codeHash          : EthHash,
      code              : String,
      mbName            : Option[String],
      mbSource          : Option[String],
      mbLanguage        : Option[String],
      mbLanguageVersion : Option[String],
      mbCompilerVersion : Option[String],
      mbCompilerOptions : Option[String],
      mbAbiDefinition   : Option[Abi.Definition],
      mbUserDoc         : Option[Doc.User],
      mbDeveloperDoc    : Option[Doc.Developer],
      mbMetadata        : Option[String]
    )

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
                mbAbiDefinition   = knownCompilation.mbAbiDefinition,
                mbUserDoc         = knownCompilation.mbUserDoc,
                mbDeveloperDoc    = knownCompilation.mbDeveloperDoc,
                mbMetadata        = knownCompilation.mbMetadata
              )
            }
          }
        }
      }
    }

    def contractAddressesForCodeHash( blockchainId : String, codeHash : EthHash ) : Failable[immutable.Set[EthAddress]] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ) { conn =>
            Table.DeployedCompilations.allForFullCodeHash( conn, blockchainId, codeHash ).map( _.contractAddress )
          }
        }
      }
    }

    def blockchainIdContractAddressesForCodeHash( codeHash : EthHash ) : Failable[immutable.Set[(String,EthAddress)]] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ) { conn =>
            Table.DeployedCompilations.allForFullCodeHashAnyBlockchainId( conn, codeHash ).map( dc => ( dc.blockchainId, dc.contractAddress ) )
          }
        }
      }
    }

    case class ContractsSummaryRow( blockchain_id : String, contract_address : String, name : String, deployer_address : String, code_hash : String, txn_hash : String, timestamp : String )

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
                    rs.getString(Column.blockchain_id),
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
    def cullUndeployedCompilations() : Failable[Int] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ) { conn =>
            borrow( conn.createStatement() ) { stmt =>
              stmt.executeUpdate( CullUndeployedCompilationsSql ) 
            }
          }
        }
      }
    }
    def createUpdateAlias( alias : String, address : EthAddress ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable( borrow( ds.getConnection() )( Table.AddressAliases.upsert( _, alias, address ) ) )
      }
    }
    def findAllAliases : Failable[immutable.SortedMap[String,EthAddress]] = {
      DataSource.flatMap { ds =>
        Failable( borrow( ds.getConnection() )( Table.AddressAliases.select ) )
      }
    }
    def findAddressByAlias( alias : String ) : Failable[Option[EthAddress]] = {
      DataSource.flatMap { ds =>
        Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectByAlias( _, alias ) ) )
      }
    }
    def dropAlias( alias : String ) : Failable[Boolean] = {
      DataSource.flatMap { ds =>
        Failable( borrow( ds.getConnection() )( Table.AddressAliases.delete( _, alias ) ) )
      }
    }

    lazy val DataSource = h2.DataSource

    final object h2 {
      val DirName = "h2"
      val DbName  = "sbt-ethereum"

      val BackupsDirName = "h2-backups"

      lazy val Directory : Failable[File] = Database.Directory.flatMap( dbDir => ensureUserOnlyDirectory( new File( dbDir, DirName ) ) )

      lazy val DbAsFile : Failable[File] = Directory.map( dir => new File( dir, DbName ) ) // the db will make files of this name, with various suffixes appended

      lazy val BackupsDir : Failable[File] = Database.Directory.flatMap( dbDir => ensureUserOnlyDirectory( new File( dbDir, BackupsDirName ) ) ) 

      lazy val JdbcUrl : Failable[String] = h2.DbAsFile.map( f => s"jdbc:h2:${f.getAbsolutePath};AUTO_SERVER=TRUE" )

      lazy val DataSource : Failable[javax.sql.DataSource] = {
        for {
          _       <- Directory
          jdbcUrl <- JdbcUrl
        } yield {
          val ds = new ComboPooledDataSource
          ds.setDriverClass( "org.h2.Driver" )
          ds.setJdbcUrl( jdbcUrl )
          ds.setTestConnectionOnCheckout( true )
          Schema_h2.ensureSchema( ds )
          ds
        }
      }

      def makeBackup( conn : Connection, schemaVersion : Int ) : Failable[Unit] = {
        BackupsDir.map{ pmbDir =>
          val df = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ")
          val ts = df.format( new Date() )
          val targetFile = new File( pmbDir, s"${DbName}-v${schemaVersion}-$ts.sql" )
          borrow( conn.prepareStatement( s"SCRIPT TO '${targetFile.getAbsolutePath}' CHARSET 'UTF8'" ) )( _.executeQuery().close() ) // we don't need the result set, just want the file
        }
      }
    }
  }

  lazy val Directory : Failable[File] = {
    def defaultLocation = {
      Platform.Current
        .toFailable( "Could not detect the platform to determine the repository directory" )
        .flatMap( _.appSupportDirectory( "sbt-ethereum" ) )
    }

    val out = {
      val mbProperty = Option( System.getProperty( Repository.SystemProperty ) )
      val mbEnvVar   = Option( System.getenv( Repository.EnvironmentVariable ) )

      (mbProperty orElse mbEnvVar).fold( defaultLocation )( dir => succeed( new File( dir ) ) )
    }

    def prepareDir( dir : File ) : Failable[File] = {
      try {
        dir.mkdirs()
        if ( !dir.exists() || !dir.isDirectory ) {
          fail(s"Specified sbt-ethereum repository directory '${dir}' must be a directory or must be creatable as a directory!")
        } else if ( !dir.canRead || !dir.canWrite ) {
          fail(s"Specified sbt-ethereum repository directory '${dir}' is must be readable and writable!")
        } else {
          succeed( dir )
        }
      } catch ToFailable
    }

    // ensure that the directory exists once it is referenced
    out.flatMap( prepareDir )
  }
}
