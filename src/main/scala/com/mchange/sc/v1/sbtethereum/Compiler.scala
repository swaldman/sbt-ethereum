package com.mchange.sc.v1.sbtethereum

import java.io.File
import java.net.URL

import scala.collection._
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration.Duration

import play.api.libs.json._

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela.ethereum.jsonrpc20._

import com.mchange.sc.v1.log.MLevel._

object Compiler {
  private implicit val logger = mlogger( this )

  // for testing whether compilation succeeds
  private final class TestLogger( compilerName : String ) extends sbt.Logger {
    import sbt._

    lazy val pfx = s"While testing $compilerName: "

    def log( level : Level.Value, message : =>String ) : Unit = DEBUG.log( pfx + message )
    def success( message: =>String ) : Unit                   = DEBUG.log( pfx + "success! " + message )
    def trace( t : =>Throwable ) : Unit                       = DEBUG.log( pfx + "oops! see exception trace.", t )
  }
  final object Solidity {
    def test( compiler : Compiler.Solidity )( implicit ec : ExecutionContext ) : Boolean = {
      try {
        val fcompilation = compiler.compile( new TestLogger( compiler.toString ), "pragma solidity ^0.4.7;\ncontract Test {}" )( ec )
        Await.ready( fcompilation, Duration.Inf )
        fcompilation.value.get.isSuccess
      } catch {
        case e : Exception => false
      }
    }
    final case class EthJsonRpc( jsonRpcUrl : String ) extends Compiler.Solidity {
      def compile( log : sbt.Logger, source : String )( implicit ec : ExecutionContext ) : Future[Compilation] = {
        doWithJsonClient( log, jsonRpcUrl )( client => client.eth.compileSolidity( source ) )( ec )
      }
    }
    final case class EthNetcompile( ethNetcompileUrl : String ) extends Compiler.Solidity {
      val url = new URL( ethNetcompileUrl ) 
      def compile( log : sbt.Logger, source : String )( implicit ec : ExecutionContext ) : Future[Compilation] = {
        borrow( new Exchanger.Simple( url ) ) { exchanger =>
          val fsuccess = exchanger.exchange( "ethdev_compile", new JsArray( immutable.Seq( JsObject( immutable.Seq("language" -> JsString("solidity"), "sourceFile" -> JsString(source) ) ) ) ) )( ec )
          fsuccess.map( success => compilationFromEthNetcompileResult( log, source, success.result.as[JsObject] ) ) 
        }
      }
      private def compilationFromEthNetcompileResult( log : sbt.Logger, source : String, ethNetcompileResult : JsObject ) : Compilation = {
        val top = ethNetcompileResult.value
        top("warnings").as[JsArray].value.foreach( jsv => log.warn( jsv.as[String] ) )
        val tuples = {
          top( "contracts" ).as[JsObject].fields.foldLeft( immutable.Seq.empty[(String,Compilation.Contract)] ) { case ( last, ( contractName : String, jsv : JsValue ) ) =>
            val jsoMap = jsv.as[JsObject].value
            val metadata = jsoMap( "metadata" ).as[String]
            if ( metadata != null && metadata.length > 0 ) {
              val contract = contractFromMetadata( log, source, contractName, jsoMap( "code" ).as[String], metadata )
              last :+ ( contractName -> contract )
            } else {
              last
            }
          }
        }
        immutable.Map( tuples : _* )
      }
    }
    final object LocalSolc {
      val SimpleContractNameRegex = """^(?:[^\:]*\:)?(.+)$""".r
    }
    final case class LocalSolc( mbSolcParentDir : Option[File] ) extends Compiler.Solidity {
      import LocalSolc.SimpleContractNameRegex

      def solcExecutable = {
        mbSolcParentDir match {
          case Some( dir ) => new File( dir, "solc" ).getAbsolutePath
          case None        => "solc" // resolve via PATH environment variable
        }
      }
      def solcCommand = immutable.Seq(solcExecutable,"--combined-json","bin,metadata","-")

      def compile( log : sbt.Logger, source : String )( implicit ec : ExecutionContext ) : Future[Compilation] = {
        import java.io._
        import scala.sys.process._

        import java.util.concurrent.atomic.AtomicReference
        import scala.io.Codec
        import com.mchange.v1.io.{InputStreamUtils,OutputStreamUtils}

        // potential Exceptions from async process management methods,
        // see method names below to understand variable names
        val sse    = new AtomicReference[Option[Throwable]]( None )
        val lsee   = new AtomicReference[Option[Throwable]]( None )
        val gcfsoe = new AtomicReference[Option[Throwable]]( None )

        val completedCompilation = new AtomicReference[Option[Compilation]]( None )

        def doubleWarn( msg : String, e : Throwable ) : Unit = {
          WARNING.log( msg, e ) // sbt-ethereum.log
          log.warn( msg + " Please see 'sbt-ethereum.log' for details." )
        }

        def spewSource( os : OutputStream ) : Unit = {
          try {
            os.write( source.getBytes( Codec.UTF8.charSet ) )
          } catch {
            case e : Exception => {
              doubleWarn( "Exception while sending source to local solc process.", e )
              sse.set( Some( e ) )
            }
          } finally {
            OutputStreamUtils.attemptCloseIgnore( os )
          }
        }
        def logStandardError( is : InputStream ) : Unit = {
          try {
            val errout = InputStreamUtils.getContentsAsString( is, "UTF8" )
            if ( errout.length > 0 ) log.warn( errout )
          } catch {
            case e : Exception => {
              doubleWarn( "Exception while trying to log standard error from local solc process.", e )
              lsee.set( Some( e ) )
            }
          } finally {
            InputStreamUtils.attemptCloseIgnore( is )
          }
        }
        def generateCompilationFromStandardOut( is : InputStream ) : Unit = {
          try {
            val stdout = InputStreamUtils.getContentsAsString( is, "UTF8" )
            if ( stdout.length > 0 ) {
              val top = Json.parse( stdout ).as[JsObject].value
              val tuples = {
                top( "contracts" ).as[JsObject].fields.foldLeft( immutable.Seq.empty[(String,Compilation.Contract)] ) { case ( last, ( SimpleContractNameRegex( contractName ), jsv : JsValue ) ) =>
                  val jsoMap = jsv.as[JsObject].value
                  val metadata = jsoMap( "metadata" ).as[String]
                  if ( metadata != null && metadata.length > 0 ) {
                    val contract = contractFromMetadata( log, source, contractName, jsoMap( "bin" ).as[String], metadata )
                    last :+ ( contractName -> contract )
                  } else {
                    last
                  }
                }
              }
              completedCompilation.set( Some( immutable.Map( tuples : _* ) ) )
            }
          } catch {
            case e : Exception => {
              doubleWarn( "Exception while trying to convert output from local solc process into a compilation.", e )
              gcfsoe.set( Some( e ) )
            }
          } finally {
            InputStreamUtils.attemptCloseIgnore( is )
          }
        }

        Future {
          val processio = new ProcessIO( spewSource, generateCompilationFromStandardOut, logStandardError )
          val exitValue = solcCommand.run( processio ).exitValue() // awaits completion
          val mbOut = completedCompilation.get()
          if ( exitValue != 0 || mbOut == None || sse.get() != None || lsee.get() != None || gcfsoe.get() != None ) {
            throw new CompilationFailedException( s"solc exit value: $exitValue. See messages logged previously." )
          }
          mbOut.get
        }
      }
    }
    val LocalPathSolc = LocalSolc( None )

    private val LanguageVersionRegex = """^(\d+\.\d+\.\d+)\D?.*""".r

    private def contractFromMetadata( log : sbt.Logger, source : String, contractName : String,  code : String, metadata : String ) : Compilation.Contract = {
      val map = Json.parse( metadata ).as[JsObject].value
      val language = map.get("language").map( _.as[String] )
      val compilerVersion = map.get("compiler").flatMap( _.as[JsObject].value.get("version").map( _.as[String] ) )
      val languageVersion = {
        compilerVersion.flatMap { cv =>
          cv match {
            case LanguageVersionRegex( lv ) => Some( lv )
            case _ => {
              log.warn( s"Could not parse Solidity language version from compiler version '$compilerVersion'." )
              None
            }
          }
        }
      }
      val compilerOptions = map.get("settings").map( Json.stringify( _ ) )

      val omap = map.get("output").map( _.as[JsObject].value )
      val abiDefinition = omap.flatMap( _.get("abi").map( _.as[Abi.Definition] ) )
      val userDoc       = omap.flatMap( _.get("userdoc").map( _.as[Doc.User] ) )
      val developerDoc  = omap.flatMap( _.get("devdoc").map( _.as[Doc.Developer] ) )

      val info = Compilation.Contract.Info( Some( source ), language, languageVersion, compilerVersion, compilerOptions, abiDefinition, userDoc, developerDoc, Some( metadata ) )
      Compilation.Contract( code, info )
    }
  }
  trait Solidity extends Compiler

}
trait Compiler {
  def compile( log : sbt.Logger, source : String )( implicit ec : ExecutionContext ) : Future[Compilation]
}
