package com.mchange.sc.v1.sbtethereum.repository

import java.io.File
import java.sql.{Connection, Timestamp}
import java.text.SimpleDateFormat
import java.util.Date
import javax.sql.DataSource
import scala.collection._
import com.mchange.v2.c3p0.ComboPooledDataSource
import com.mchange.sc.v1.sbtethereum.repository
import com.mchange.sc.v1.sbtethereum.util.BaseCodeAndSuffix
import com.mchange.sc.v2.sql._
import com.mchange.sc.v2.failable._
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, jsonrpc}
import jsonrpc.{Abi, Doc}
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import play.api.libs.json.Json

object Database {
  import Schema_h2._

  val DirName = "database"
  lazy val Directory : Failable[File] = repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

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
            mbAbi.map( abiStr => Json.parse( abiStr ).as[Abi] ),
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

  def setMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress, abi : Abi ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.insert( conn, blockchainId, contractAddress, abi )
        }
      }
    }
  }

  def deleteMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress ) : Failable[Boolean] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.delete( conn, blockchainId, contractAddress )
        }
      }
    }
  }

  def getMemorizedContractAbiAddresses( blockchainId : String ) : Failable[immutable.Seq[EthAddress]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.selectAddressesForBlockchainId( conn, blockchainId )
        }
      }
    }
  }

  def getMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress ) : Failable[Option[Abi]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.select( conn, blockchainId, contractAddress )
        }
      }
    }
  }

  def updateContractDatabase( compilations : Iterable[(String,jsonrpc.Compilation.Contract)] ) : Failable[Boolean] = {
    val ( compiledContracts, stubsWithDups ) = compilations.partition { case ( name, compilation ) => compilation.code.decodeHex.length > 0 }

    stubsWithDups.foreach { case ( name, compilation ) =>
      DEBUG.log( s"Contract '$name' is a stub or abstract contract, and so has not been incorporated into repository compilations." )( repository.logger )
    }

    def updateKnownContracts( conn : Connection ) : Failable[Boolean] = {
      def doUpdate( conn : Connection, contractTuple : Tuple2[String,jsonrpc.Compilation.Contract] ) : Failable[Boolean] = Failable {
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
          mbAbi             = mbAbi.map( a => Json.parse( a ).as[Abi] ),
          mbUserDoc         = mbUserDoc.map( ud => Json.parse( ud ).as[Doc.User] ),
          mbDeveloperDoc    = mbDeveloperDoc.map( dd => Json.parse( dd ).as[Doc.Developer] ),
          mbMetadata        = mbMetadata
        )

        val mbKnownCompilation = Table.KnownCompilations.select( conn, bcas.fullCodeHash )

        mbKnownCompilation match {
          case Some( kc ) =>
            if ( kc != newCompilation ) {
              Table.KnownCompilations.upsert( conn, newCompilation reconcileOver kc )
              true
            } else false

          case None =>
            Table.KnownCompilations.upsert( conn, newCompilation )
            true
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
    mbAbi               : Option[Abi],
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
              mbAbi                = knownCompilation.mbAbi,
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
    mbAbi             : Option[Abi],
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
              mbAbi             = knownCompilation.mbAbi,
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
  def cullUndeployedCompilations() : Failable[Int] =
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ) { conn =>
          borrow( conn.createStatement() ) {
            _.executeUpdate( CullUndeployedCompilationsSql )
          }
        }
      }
    }

  def createUpdateAlias( blockchainId : String, alias : String, address : EthAddress ) : Failable[Unit] =
    DataSource.flatMap { ds =>
      Failable( borrow( ds.getConnection() )( Table.AddressAliases.upsert( _, blockchainId, alias, address ) ) )
    }

  def findAllAliases( blockchainId : String ) : Failable[immutable.SortedMap[String,EthAddress]] =
    DataSource.flatMap { ds =>
      Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectAllForBlockchainId( _, blockchainId ) ) )
    }

  def findAddressByAlias( blockchainId : String, alias : String ) : Failable[Option[EthAddress]] =
    DataSource.flatMap { ds =>
      Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectByAlias( _, blockchainId, alias ) ) )
    }

  def findAliasesByAddress( blockchainId : String, address : EthAddress ) : Failable[immutable.Seq[String]] =
    DataSource.flatMap { ds =>
      Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectByAddress( _, blockchainId, address ) ) )
    }

  def dropAlias( blockchainId : String, alias : String ) : Failable[Boolean] =
    DataSource.flatMap { ds =>
      Failable( borrow( ds.getConnection() )( Table.AddressAliases.delete( _, blockchainId, alias ) ) )
    }


  lazy val DataSource: Failable[DataSource] = h2.DataSource

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
        try {
          ds.setDriverClass( "org.h2.Driver" )
          ds.setJdbcUrl( jdbcUrl )
          ds.setTestConnectionOnCheckout( true )
          Schema_h2.ensureSchema( ds )
          ds
        } catch {
          case t : Throwable =>
            try ds.close() catch suppressInto(t)
            throw t
        }
      }
    }

    private def suppressInto( original : Throwable ) : PartialFunction[Throwable,Unit] = {
      case t : Throwable => original.addSuppressed( t )
    }

    def makeBackup( conn : Connection, schemaVersion : Int ) : Failable[Unit] = {
      BackupsDir.map { pmbDir =>
        val df = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ")
        val ts = df.format( new Date() )
        val targetFile = new File( pmbDir, s"$DbName-v$schemaVersion-$ts.sql" )
        borrow( conn.prepareStatement( s"SCRIPT TO '${targetFile.getAbsolutePath}' CHARSET 'UTF8'" ) )( _.executeQuery().close() ) // we don't need the result set, just want the file
      }
    }
  }
}
