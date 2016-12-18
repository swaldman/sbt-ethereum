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

import com.mchange.sc.v2.util.Platform

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{clients,jsonrpc20,wallet,EthHash,EthAddress,EthTransaction}
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory

import com.mchange.v2.c3p0.ComboPooledDataSource

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

    def insertNewDeployment( contractAddress : EthAddress, code : String, deployerAddress : EthAddress, transactionHash : EthHash ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.DeployedContracts.insertNewDeployment( conn, contractAddress, code, deployerAddress, transactionHash )
          }
        }
      }
    }

    def insertExistingDeployment( contractAddress : EthAddress, code : String ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.DeployedContracts.insertExistingDeployment( conn, contractAddress, code )
          }
        }
      }
    }

    private def knownContractForCodeHash( codeHash : EthHash ) : Failable[Option[Table.KnownContracts.CachedContract]] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.KnownContracts.getByCodeHash( conn, codeHash )
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
          borrow( ds.getConnection() ){ conn =>
            Table.KnownContracts.createUpdateKnownContract( conn, code.hex, None, None, None, None, None, None, Some( abiString ), None, None, UseOriginal )
          }
        }
      }
    }

    def forgetContractAbi( code : Seq[Byte] ) : Failable[Unit] = {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() )( conn => Table.KnownContracts.forgetAbi( conn, code.hex ) )
        }
      }
    }

    /*
     *  this could be a bit less convoluted.
     * 
     *  okay. it could be a lot less convoluted.
     */ 
    def updateContractDatabase( compilations : Map[String,jsonrpc20.Compilation.Contract], stubNameToAddressCodes : Map[String,Map[EthAddress,String]], policy : IrreconcilableUpdatePolicy ) : Failable[Boolean] = {
      val ( compiledContracts, stubs ) = compilations.partition { case ( name, compilation ) => compilation.code.decodeHex.length > 0 }

      def updateKnownContracts( conn : Connection ) : Failable[Boolean] = {
        def doUpdate( conn : Connection, contractTuple : Tuple2[String,jsonrpc20.Compilation.Contract] ) : Failable[Boolean] = Failable {
          val name        = contractTuple._1
          val compilation = contractTuple._2
          val code        = compilation.code

          import compilation.info._

          Table.KnownContracts.createUpdateKnownContract( conn, code, Some(name), mbSource, mbLanguage, mbLanguageVersion, mbCompilerVersion, mbCompilerOptions, mbAbiDefinition, mbUserDoc, mbDeveloperDoc, policy )
        }
        compiledContracts.toSeq.foldLeft( succeed( false ) )( ( failable, tup ) => failable.flatMap( last => doUpdate( conn, tup ).map( next => last || next ) ) )
      }
      def updateStubs( conn : Connection ) : Failable[Boolean] = {
        def doUpdate( address : EthAddress, code : String, name : String, abiDefinition : String ) : Failable[Boolean] = {
          for {
            check0 <- Failable( Table.KnownContracts.createUpdateKnownContract( conn, code, Some(name), None, None, None, None, None, Some( abiDefinition ), None, None, policy ) )
            check1 <- Failable( Table.DeployedContracts.insertExistingDeployment( conn, address, code ) )
          } yield {
            check0
          }
        }
        stubNameToAddressCodes.toSeq.foldLeft( succeed( false ) ) { case ( last, ( name, addressToCode ) ) =>
          last.flatMap { l =>
            val fabi = {
              (for {
                compilation <- stubs.get( name )
                out         <- compilation.info.mbAbiDefinition
              } yield {
                out
              }).toFailable( s"Could not find abi definition for '${name}' in stub compilations. [ present in compilations? ${compilations.contains(name)}; zero-code stub? ${stubs.contains(name)} ]" )
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

      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            conn.setAutoCommit( true )
            try {
              for {
                knownContractsCheck <- updateKnownContracts( conn )
                stubsCheck          <- updateStubs( conn )
              } yield {
                conn.commit()
                knownContractsCheck || stubsCheck
              }
            } catch {
              case NonFatal( t ) => {
                conn.rollback()
                throw t
              }
            }
          }
        }.flatten
      }
    }

    case class DeployedContractInfo (
      address         : EthAddress,
      code            : String,
      deployerAddress : Option[EthAddress],
      transactionHash : Option[EthHash],
      deployedWhen    : Option[Long],
      name            : Option[String],
      source          : Option[String],
      language        : Option[String],
      languageVersion : Option[String],
      compilerVersion : Option[String],
      compilerOptions : Option[String],
      abiDefinition   : Option[String],
      userDoc         : Option[String],
      developerDoc    : Option[String]
    )

    case class KnownContractInfo (
      code            : String,
      name            : Option[String],
      source          : Option[String],
      language        : Option[String],
      languageVersion : Option[String],
      compilerVersion : Option[String],
      compilerOptions : Option[String],
      abiDefinition   : Option[String],
      userDoc         : Option[String],
      developerDoc    : Option[String]
    )

    def deployedContractInfoForAddress( address : EthAddress ) : Failable[Option[DeployedContractInfo]] =  {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection ) { conn =>
            for {
              deployedContract <- Table.DeployedContracts.getByAddress( conn, address )
              codeHash = deployedContract.codeHash
              knownContract <- Table.KnownContracts.getByCodeHash( conn, deployedContract.codeHash )
            } yield {
              DeployedContractInfo (
                address         = deployedContract.address,
                code            = knownContract.code,
                deployerAddress = deployedContract.deployerAddress,
                transactionHash = deployedContract.transactionHash,
                deployedWhen    = deployedContract.deployedWhen,
                name            = knownContract.name,
                source          = knownContract.source,
                language        = knownContract.language,
                languageVersion = knownContract.languageVersion,
                compilerVersion = knownContract.compilerVersion,
                compilerOptions = knownContract.compilerOptions,
                abiDefinition   = knownContract.abiDefinition,
                userDoc         = knownContract.userDoc,
                developerDoc    = knownContract.developerDoc
              )
            }
          }
        }
      }
    }

    def deployedContractInfoForCodeHash( codeHash : EthHash ) : Failable[Option[KnownContractInfo]] =  {
      DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection ) { conn =>
            for {
              knownContract <- Table.KnownContracts.getByCodeHash( conn, codeHash )
            } yield {
              KnownContractInfo (
                code            = knownContract.code,
                name            = knownContract.name,
                source          = knownContract.source,
                language        = knownContract.language,
                languageVersion = knownContract.languageVersion,
                compilerVersion = knownContract.compilerVersion,
                compilerOptions = knownContract.compilerOptions,
                abiDefinition   = knownContract.abiDefinition,
                userDoc         = knownContract.userDoc,
                developerDoc    = knownContract.developerDoc
              )
            }
          }
        }
      }
    }

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

    case class ContractsSummaryRow( contract_address : String, name : String, deployer_address : String, code_hash : String, txn_hash : String, timestamp : String )

    lazy val DataSource = h2_v0.DataSource

    final object h2_v0 {
      val DirName = "h2_v0"
      lazy val Directory : Failable[File] = Database.Directory.flatMap( dbDir => ensureUserOnlyDirectory( new File( dbDir, DirName ) ) )

      lazy val JdbcUrl : Failable[String] = h2_v0.Directory.map( d => s"jdbc:h2:${d.getAbsolutePath}" )

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
