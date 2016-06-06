package com.mchange.sc.v1

import sbt._
import sbt.Keys._

import scala.io.{Codec,Source}
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration.Duration
import scala.util.Failure

import java.io._

import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.concurrent._


import com.mchange.sc.v1.consuela.ethereum.jsonrpc20

import play.api.libs.json._

package object sbtethereum {
  final class NoSolidityCompilerException( msg : String ) extends Exception( msg )

  private val SolFileRegex = """(.+)\.sol""".r

  // XXX: hardcoded
  private val SolidityWriteBufferSize = 1024 * 1024; //1 MiB

  private [sbtethereum] def doCompileSolidity( log : sbt.Logger, jsonRpcUrl : String, solSource : File, solDestination : File )( implicit ec : ExecutionContext ) : Unit = {
    def solToJson( filename : String ) : String = filename match {
      case SolFileRegex( base ) => base + ".json"
    }

    // TODO XXX: check imported files as well!
    def changed( destinationFile : File, sourceFile : File ) : Boolean = (! destinationFile.exists) || (sourceFile.lastModified() > destinationFile.lastModified() )

    try {
      borrow( new jsonrpc20.Client.Simple( new URL( jsonRpcUrl ) ) ) { client =>
        solDestination.mkdirs()
        val files = (solSource ** "*.sol").get

        val filePairs = files.map( file => ( file, new File( solDestination, solToJson( file.getName() ) ) ) ) // (sourceFile, destinationFile)
        val compileFiles = filePairs.filter( tup => changed( tup._2, tup._1 ) )

        val cfl = compileFiles.length
        if ( cfl > 0 ) {
          val mbS = if ( cfl > 1 ) "s" else ""
          log.info( s"Compiling ${compileFiles.length} Solidity source${mbS} to ${solDestination}..." )

          val compileFuts = compileFiles.map { tup =>
            val srcFile  = tup._1
            val destFile = tup._2
            borrow( Source.fromFile( srcFile )(Codec.UTF8) )( _.close() ){ source =>
              val code = source.foldLeft("")( _ + _ )
              client.eth.compileSolidity( code ).map( result => ( destFile, result ) )
            }
          }
          val failures = awaitAndGatherFailures( compileFuts )
          val failureCount = failures.size
          if ( failureCount > 0 ) {
            log.error( s"compileSolidity failed. [${failureCount} failures]" )
            failures.foreach {
              case jf : jsonrpc20.Failure => log.error( jf.message )
              case other                  => log.error( other.toString )
            }
            throw failures.head
          } else {
            import Json._
            compileFuts.map { fut =>
              fut.map {
                case ( destFile, result ) => {
                  borrow( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( destFile ), SolidityWriteBufferSize ), Codec.UTF8.charSet ) )( _.write( stringify( toJson ( result ) ) ) )
                }
              }
            }
          }
        }
      }
    } catch {
      case e : java.net.ConnectException => {
        log.error( s"Failed to connect to JSON-RPC client at '${jsonRpcUrl}': ${e}" )
        throw e
      }
    }
  }
}


