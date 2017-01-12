package com.mchange.sc.v1.sbtethereum

import sbinary._
import DefaultProtocol._
import Operations._

import com.mchange.sc.v1.consuela.ethereum.EthAddress
import com.mchange.sc.v1.consuela.ethereum.encoding.{RLP,RLPSerializing}
import com.mchange.sc.v2.collection.immutable.ImmutableArraySeq

object SBinaryFormats {

  // don't make this implicit, stuff like int is RLP serializing, and we don't want to
  // interfere with sbinary's usual behavior with that stuff

  private def rlpSerializingAsFormat[T : RLPSerializing] : Format[T] = new Format[T]{
    def reads(in : Input) : T = {
      val len = read[Int]( in )
      val seq = {
        val arr = Array.ofDim[Byte]( len )
        in.readFully( arr )
        ImmutableArraySeq.Byte.createNoCopy( arr )
      }
      RLP.decodeComplete[T]( seq ).get // vomit an Exception on failure
    }

    def writes(out : Output, t : T) : Unit = {
      val seq = RLP.encode(t)
      write[Int]( out, seq.length )
      out.writeAll( seq.toArray )
    }
  }

  implicit val EthAddressAsFormat = rlpSerializingAsFormat[EthAddress]

  /*
   implicit object CompilationMapSBinaryFormat extends sbinary.Format[immutable.Map[String,jsonrpc20.Compilation.Contract]]{
     def reads(in : Input) = Json.parse( StringFormat.reads( in ) ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]]
     def writes(out : Output, value : immutable.Map[String,jsonrpc20.Compilation.Contract]) = StringFormat.writes( out, Json.stringify( Json.toJson( value ) ) )
   }
   */

}
