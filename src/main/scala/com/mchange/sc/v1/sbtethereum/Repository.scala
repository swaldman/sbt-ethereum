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

import com.mchange.sc.v2.util.Platform

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{clients,jsonrpc20,wallet,EthHash,EthAddress,EthTransaction}
import com.mchange.sc.v1.consuela.ethereum.jsonrpc20._
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory

import com.mchange.v2.c3p0.ComboPooledDataSource

import play.api.libs.json._

object Repository {
  private val TimestampPattern = "yyyy-MM-dd'T'HH-mm-ssZ"

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
    import Schema_h2_v0._

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

    def insertNewDeployment( contractAddress : EthAddress, code : String, deployerAddress : EthAddress, transactionHash : EthHash ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.DeployedCompilations.insertNewDeployment( conn, contractAddress, code, deployerAddress, transactionHash )
          }
        }
      }
    }

    def insertExistingDeployment( contractAddress : EthAddress, code : String ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.DeployedCompilations.insertExistingDeployment( conn, contractAddress, code )
          }
        }
      }
    }

    /**
      * Return value is whether any change was made, won't update already set ABIs.
      * Explicitly forget the ABI if you really need to change the ABI.
      */ 
    def setContractAbi( code : Seq[Byte], abiString : String ) : Failable[Boolean] = {
      DataSource.flatMap { ds =>
        Failable {
          val bcas = BaseCodeAndSuffix( code.hex )
          borrow( ds.getConnection() ){ conn =>
            Table.KnownCompilations.select( conn, bcas.fullCodeHash ) match {
              case Some( knownCompilation ) => {
                knownCompilation.mbAbiDefinition match {
                  case Some( _ ) => false // ABI definition already set
                  case None      => Table.KnownCompilations.updateAbiDefinition( conn, bcas.baseCodeHash, bcas.fullCodeHash, Some( abiString ) )
                }
              }
              case None => {
                insertCompilation( code = code.hex, mbAbiDefinition = Some( abiString ) )
                true
              }
            }
          }
        }
      }
    }

    def forgetContractAbi( code : Seq[Byte] ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          val bcas = BaseCodeAndSuffix( code.hex )
          borrow( ds.getConnection() ){ conn =>
            Table.KnownCompilations.updateAbiDefinition( conn, bcas.baseCodeHash, bcas.fullCodeHash, None )
          }
        }
      }
    }

    /*
     *  this could be a bit less convoluted.
     * 
     *  okay. it could be a lot less convoluted.
     */ 
    def updateContractDatabase( compilations : Iterable[(String,jsonrpc20.Compilation.Contract)], stubNameToAddressCodes : Map[String,Map[EthAddress,String]] ) : Failable[Boolean] = {
      val ( compiledContracts, stubsWithDups ) = compilations.partition { case ( name, compilation ) => compilation.code.decodeHex.length > 0 }

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
      def updateStubs( conn : Connection ) : Failable[Boolean] = {
        def doUpdate( address : EthAddress, code : String, name : String, abiDefinition : Abi.Definition ) : Failable[Boolean] = Failable {
          val bcas = BaseCodeAndSuffix( code )

          Table.KnownCode.upsert( conn, bcas.baseCodeHex )

          val stubCompilation = Table.KnownCompilations.KnownCompilation(
            bcas.baseCodeHash,
            bcas.fullCodeHash,
            bcas.codeSuffixHex,
            Some( name ),
            None,
            None,
            None,
            None,
            None,
            Some( abiDefinition ),
            None,
            None,
            None
          )

          val mbKnownCompilation = Table.KnownCompilations.select( conn, bcas.fullCodeHash )

          mbKnownCompilation match {
            case Some( kc ) => {
              if ( kc != stubCompilation ) {
                // note: we don't reconcile stubs over known compilations, we fail on any inconsistency
                Table.KnownCompilations.upsert( conn, stubCompilation reconcile kc ) 
                true
              } else {
                false
              }
            }
            case None => {
              Table.KnownCompilations.upsert( conn, stubCompilation )
              true
            }
          }
        }

        // we fail if there are duplicate stubs, which we cannot support, succeed otherwise
        val fstubs : Failable[Map[String,jsonrpc20.Compilation.Contract]] = Failable.sequence {
          stubsWithDups
            .groupBy( _._1 )                                     // key -> Iterable( key -> v0, key -> v1, ... )
            .map( tup => ( tup._1, tup._2.map( _._2 ).toSet ) )  // key -> Set( v0, v1, ... )
            .map( tup => if ( tup._2.size > 1 ) fail( s"Unsupported: '${tup._1}' is associated with multiple stubs with different ABIs." ) else succeed( ( tup._1, tup._2.head ) ) )
            .toSeq
        }.map( _.toMap )

        fstubs.flatMap { stubs =>
          stubNameToAddressCodes.toSeq.foldLeft( succeed( false ) ) { case ( last, ( name, addressToCode ) ) =>
            last.flatMap { l =>
              val fabi = {
                (for {
                  compilation <- stubs.get( name )
                  abiStr      <- compilation.info.mbAbiDefinition
                } yield {
                  Json.parse( abiStr ).as[Abi.Definition]
                }).toFailable( s"Could not find abi definition for '${name}' in stub compilations. [ present in compilations? ${compilations.toMap.contains(name)}; zero-code stub? ${stubs.contains(name)} ]" )
              }
              val fb = fabi.flatMap { abi =>
                addressToCode.toSeq.foldLeft( succeed( false ) ){ ( failable, tup ) =>
                  failable.flatMap( last => doUpdate( tup._1, tup._2, name, abi ).map( next => last || next ) )
                }
              }
              fb.map( b => l || b )
            }
          }
        }
      }

      DataSource.flatMap { ds =>
        borrowTransact( ds.getConnection() ){ conn =>
          for {
            knownContractsCheck <- updateKnownContracts( conn )
            stubsCheck          <- updateStubs( conn )
          } yield {
            knownContractsCheck || stubsCheck
          }
        }
      }
    }

    case class DeployedContractInfo (
      contractAddress   : EthAddress,
      codeHash          : EthHash,
      code              : String,
      mbDeployerAddress : Option[EthAddress],
      mbTransactionHash : Option[EthHash],
      mbDeployedWhen    : Option[Long],
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

    def deployedContractInfoForAddress( address : EthAddress ) : Failable[Option[DeployedContractInfo]] =  {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection ) { conn =>
            for {
              deployedCompilation <- Table.DeployedCompilations.select( conn, address )
              knownCode           <- Table.KnownCode.select( conn, deployedCompilation.baseCodeHash )
              knownCompilation    <- Table.KnownCompilations.select( conn, deployedCompilation.fullCodeHash )
            } yield {
              DeployedContractInfo (
                contractAddress    = deployedCompilation.contractAddress,
                codeHash           = deployedCompilation.fullCodeHash,
                code               = knownCode ++ knownCompilation.codeSuffix,
                mbDeployerAddress  = deployedCompilation.mbDeployerAddress,
                mbTransactionHash  = deployedCompilation.mbTransactionHash,
                mbDeployedWhen     = deployedCompilation.mbDeployedWhen,
                mbName             = knownCompilation.mbName,
                mbSource           = knownCompilation.mbSource,
                mbLanguage         = knownCompilation.mbLanguage,
                mbLanguageVersion  = knownCompilation.mbLanguageVersion,
                mbCompilerVersion  = knownCompilation.mbCompilerVersion,
                mbCompilerOptions  = knownCompilation.mbCompilerOptions,
                mbAbiDefinition    = knownCompilation.mbAbiDefinition,
                mbUserDoc          = knownCompilation.mbUserDoc,
                mbDeveloperDoc     = knownCompilation.mbDeveloperDoc,
                mbMetadata         = knownCompilation.mbMetadata
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

    def contractAddressesForCodeHash( codeHash : EthHash ) : Failable[immutable.Set[EthAddress]] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ) { conn =>
            Table.DeployedCompilations.allForFullCodeHash( conn, codeHash ).map( _.contractAddress )
          }
        }
      }
    }

    case class ContractsSummaryRow( contract_address : String, name : String, deployer_address : String, code_hash : String, txn_hash : String, timestamp : String )

    def contractsSummary : Failable[immutable.Seq[ContractsSummaryRow]] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ) { conn =>
            borrow( conn.createStatement() ) { stmt =>
              borrow( stmt.executeQuery( ContractsSummarySql ) ) { rs =>
                val buffer = new mutable.ArrayBuffer[ContractsSummaryRow]
                val df = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss.SSSZ" )
                def mbformat( ts : Timestamp ) : String = if ( ts == null ) null else df.format( ts )
                while( rs.next() ) {
                  buffer += ContractsSummaryRow( rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), mbformat( rs.getTimestamp(6) ) )
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

    lazy val DataSource = h2_v0.DataSource

    final object h2_v0 {
      val DirName = "h2_v0"
      lazy val Directory : Failable[File] = Database.Directory.flatMap( dbDir => ensureUserOnlyDirectory( new File( dbDir, DirName ) ) )

      lazy val JdbcUrl : Failable[String] = h2_v0.Directory.map( d => s"jdbc:h2:${d.getAbsolutePath};AUTO_SERVER=TRUE" )

      lazy val DataSource : Failable[javax.sql.DataSource] = {
        for {
          _       <- Directory
          jdbcUrl <- JdbcUrl
        } yield {
          val ds = new ComboPooledDataSource
          ds.setDriverClass( "org.h2.Driver" )
          ds.setJdbcUrl( jdbcUrl )
          ds.setTestConnectionOnCheckout( true )
          Schema_h2_v0.ensureSchema( ds )
          ds
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
