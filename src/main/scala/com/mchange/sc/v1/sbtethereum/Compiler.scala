package com.mchange.sc.v1.sbtethereum

import scala.collection._
import scala.concurrent.{ExecutionContext,Future}


import com.mchange.sc.v1.consuela.ethereum.jsonrpc20.Compilation

object Compiler {
  final object Solidity {
    final case class EthJsonRpc( jsonRpcUrl : String ) extends Compiler {
      def compile( log : sbt.Logger, source : String )( implicit ec : ExecutionContext ) : Future[Compilation] = {
        doWithJsonClient( log, jsonRpcUrl )( client => client.eth.compileSolidity( source ) )( ec )
      }
    }
  }
}
trait Compiler {
  def compile( log : sbt.Logger, source : String )( implicit ec : ExecutionContext ) : Future[Compilation]
}
