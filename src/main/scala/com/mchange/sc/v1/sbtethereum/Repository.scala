package com.mchange.sc.v1.sbtethereum

import java.io.{BufferedOutputStream,File,FileOutputStream,OutputStreamWriter,PrintWriter}
import java.sql.Connection
import java.text.SimpleDateFormat
import java.util.Date

import scala.io.Codec
import scala.util.control.NonFatal

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
    val DirName = "database"
    lazy val Directory : Failable[File] = Repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )

    def insertNewDeployment( code : String, contractAddress : EthAddress, deployerAddress : EthAddress, transactionHash : EthHash ) : Failable[Unit] = {
      import Schema_h2_v0._

      h2_v0.DataSource.flatMap { ds =>
        Failable {
          borrow( ds.getConnection() ){ conn =>
            Table.DeployedContracts.insertNewDeployment( conn, code, contractAddress, deployerAddress, transactionHash )
          }
        }
      }
    }

    /*
     *  this could be a bit less convoluted.
     * 
     *  okay. it could be a lot less convoluted.
     */ 
    def updateContractDatabase( compilations : Map[String,jsonrpc20.Compilation.Contract], stubNameToAddressCodes : Map[String,Map[EthAddress,String]], policy : IrreconcilableUpdatePolicy ) : Failable[Boolean] = {
      import Schema_h2_v0._

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
            check1 <- Failable( Table.DeployedContracts.insertExistingDeployment( conn, code, address ) )
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

      h2_v0.DataSource.flatMap { ds =>
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
