package com.mchange.sc.v1

import sbt._
import sbt.Keys._

import scala.io.{Codec,Source}
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration.Duration
import scala.util.Failure

import java.io._

import com.mchange.sc.v2.lang.borrow

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
        /*
        val compilers = client.eth.getCompilers().compilers 
        if (! compilers( solidity )) {
          val msg = s"""No solidity compiler available. [compilers: ${compilers.mkString(", ")}]"""
          log.error( msg )
          throw new NoSolidityCompilerException( msg )
        }
        */

        solDestination.mkdirs()
        val files = (solSource ** "*.sol").get

        val filePairs = files.map( file => ( file, new File( solDestination, solToJson( file.getName() ) ) ) ) // (sourceFile, destinationFile)
        val compileFiles = filePairs.filter( tup => changed( tup._2, tup._1 ) )

        log.info( s"compileSolidity: ${compileFiles.length} files to compile." )

        val compileFuts = compileFiles.map { tup =>
          val srcFile  = tup._1
          val destFile = tup._2
          borrow( Source.fromFile( srcFile )(Codec.UTF8) )( _.close() ){ source =>
            val code = source.foldLeft("")( _ + _ )
            client.eth.compileSolidity( code ).map( failable => ( destFile, failable ) )
          }
        }
        val compileFut : Future[Unit] = {
          Future.sequence( compileFuts )
            .map { seq =>
            if ( seq.forall( _._2.isSucceeded ) ) {
              seq.map { case ( destFile, mbjson ) =>
                borrow( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( destFile ), SolidityWriteBufferSize ), Codec.UTF8.charSet ) ) { writer =>
                  import Json._
                  writer.write( stringify( toJson ( mbjson.get ) ) )
                }
              }
            } else {
              val errors = seq.filter( _._2.isFailed )
              errors.foreach( tup => log.error( tup._2.fail.message ) )
              errors.head._2.fail.vomit
            }
          }
        }
        Await.ready( compileFut, Duration.Inf )
        compileFut.value.get match { // we've awaited, so the call to get should succeed
          case Failure( e ) => throw e
          case _            => /* ignore */
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


