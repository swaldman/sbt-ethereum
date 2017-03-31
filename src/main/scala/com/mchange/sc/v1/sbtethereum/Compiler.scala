package com.mchange.sc.v1.sbtethereum

import java.net.URL

import scala.collection._
import scala.concurrent.{ExecutionContext,Future}

import play.api.libs.json._

import com.mchange.sc.v2.lang.borrow

import com.mchange.sc.v1.consuela.ethereum.jsonrpc20._


object Compiler {
  final object Solidity {
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
          top( "contracts" ).as[JsObject].fields.map { case ( contractName : String, jsv : JsValue ) =>
            val jsoMap = jsv.as[JsObject].value
            val contract = contractFromMetadata( log, source, contractName, jsoMap( "code" ).as[String], jsoMap( "metadata" ).as[String] )
            contractName -> contract
          }
        }
        immutable.Map( tuples : _* )
      }
    }
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
