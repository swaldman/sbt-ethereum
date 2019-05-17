package com.mchange.sc.v1.sbtethereum.util

import scala.collection._

import com.mchange.sc.v1.sbtethereum.shoebox

import com.mchange.sc.v1.consuela.ethereum.jsonrpc

private [sbtethereum]
object Spawn {
  final object SpawnInstruction {
    final case object Auto                                                                                                            extends SpawnInstruction
    final case class  UncompiledName( name : String )                                                                                 extends SpawnInstruction
    final case class  Full( deploymentAlias : String, args : immutable.Seq[String], valueInWei : BigInt, seed : MaybeSpawnable.Seed ) extends SpawnInstruction
  }
  sealed trait SpawnInstruction

  final object MaybeSpawnable {
    final case class Seed( contractName : String, codeHex : String, abi : jsonrpc.Abi, currentCompilation : Boolean )

    implicit final object CompilationBindingIsMaybeSpawnable extends MaybeSpawnable[Tuple2[String,jsonrpc.Compilation.Contract]] {
      def mbSeed( binding : ( String, jsonrpc.Compilation.Contract ) ) : Option[Seed] = {
        val ( name, contract ) = binding
        contract.info.mbAbi.map( abi => Seed( name, contract.code, abi, true ) )
      }
    }
    implicit final object DatabaseCompilationInfoIsMaybeSpawnable extends MaybeSpawnable[shoebox.Database.CompilationInfo] {
      def mbSeed( dci : shoebox.Database.CompilationInfo ) : Option[Seed] = {
        for {
          abi <- dci.mbAbi
          name <- dci.mbName
        } yield {
          Seed( name, dci.code, abi, false )
        }
      }
    }
  }
  trait MaybeSpawnable[T] {
    def mbSeed( t : T ) : Option[MaybeSpawnable.Seed]
  }
}
