package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc._
import v1.consuela._
import v1.consuela.ethereum.{EthAddress,EthHash}
import v1.consuela.ethereum.encoding.{RLP,RLPSerializing}
import v1.consuela.ethereum.jsonrpc
import v1.sbtethereum.RichParserInfo
import v1.sbtethereum.util.Spawn.MaybeSpawnable

import sjsonnew._
import BasicJsonProtocol._

import play.api.libs.json.{Json, Format => JsFormat}

import scala.collection._


object SJsonNewFormats {

  private def rlpSerializingIso[T : RLPSerializing] = IsoString.iso(
    { ( t : T ) => RLP.encode(t).hex },
    { ( rlp : String ) => RLP.decodeComplete[T]( rlp.decodeHexAsSeq ).get }
  )

  private def playJsonSerializingIso[T : JsFormat] = IsoString.iso (
    { (t : T) => Json.stringify( Json.toJson( t ) ) },
    { ( json : String ) => Json.parse( json ).as[T] }
  )

  private def stringKeyedSortedMapFormat[T : JsonFormat]= new JsonFormat[immutable.SortedMap[String,T]]{
    val inner = mapFormat[String,T]

    def write[J](m : immutable.SortedMap[String, T], builder : Builder[J]): Unit = {
      inner.write(m, builder)
    }
    def read[J](jsOpt : Option[J], unbuilder : Unbuilder[J]) : immutable.SortedMap[String, T] = {
      immutable.TreeMap.empty[String, T] ++ inner.read( jsOpt, unbuilder )
    }
  }

  implicit val StringKeyedSortedSetFormat = new JsonFormat[immutable.SortedSet[String]]{
    val inner = setFormat[String]

    def write[J](m : immutable.SortedSet[String], builder : Builder[J]): Unit = {
      inner.write(m, builder)
    }
    def read[J](jsOpt : Option[J], unbuilder : Unbuilder[J]) : immutable.SortedSet[String] = {
      immutable.TreeSet.empty[String] ++ inner.read( jsOpt, unbuilder )
    }
  }

  implicit val EthAddressIso = rlpSerializingIso[EthAddress]

  implicit val EthHashIso = rlpSerializingIso[EthHash]

  implicit val CompilationIso = playJsonSerializingIso[jsonrpc.Compilation.Contract]

  implicit val MaybeSpawnableJsFormat = Json.format[MaybeSpawnable.Seed]

  implicit val SeedIso = playJsonSerializingIso[MaybeSpawnable.Seed]

  implicit val AbiFormat = playJsonSerializingIso[jsonrpc.Abi]

  implicit val StringEthAddressSortedMapFormat = stringKeyedSortedMapFormat[EthAddress]

  implicit val StringEthHashSortedMapFormat = stringKeyedSortedMapFormat[EthHash]

  implicit object RichParserInfoFormat extends JsonFormat[RichParserInfo] {
    def write[J](api : RichParserInfo, builder: Builder[J]) : Unit = {
      builder.beginObject()
      builder.addField("chainId", api.chainId)
      builder.addField("mbJsonRpcUrl", api.mbJsonRpcUrl)
      builder.addField("addressAliases", api.addressAliases)
      builder.addField("abiAliases", api.abiAliases)
      builder.addField("abiOverrides", api.abiOverrides)
      builder.addField("nameServiceAddressHex", api.nameServiceAddress.hex)
      builder.addField("exampleNameServiceTld", api.exampleNameServiceTld)
      builder.addField("exampleNameServiceReverseTld", api.exampleNameServiceReverseTld)
      builder.endObject()
    }
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]) : RichParserInfo = {
      jsOpt match {
        case Some(js) =>
          unbuilder.beginObject(js)
          val chainId = unbuilder.readField[Int]("chainId")
          val jsonRpcUrl = unbuilder.readField[Option[String]]("mbJsonRpcUrl")
          val addressAliases = unbuilder.readField[immutable.SortedMap[String,EthAddress]]("addressAliases")
          val abiAliases = unbuilder.readField[immutable.SortedMap[String,EthHash]]("abiAliases")
          val abiOverrides = unbuilder.readField[immutable.Map[EthAddress,jsonrpc.Abi]]("abiOverrides")
          val nameServiceAddressHex = unbuilder.readField[String]("nameServiceAddressHex")
          val exampleNameServiceTld = unbuilder.readField[String]("exampleNameServiceTld")
          val exampleNameServiceReverseTld = unbuilder.readField[String]("exampleNameServiceReverseTld")
          unbuilder.endObject()
          RichParserInfo(chainId, jsonRpcUrl, addressAliases, abiAliases, abiOverrides, EthAddress(nameServiceAddressHex), exampleNameServiceTld, exampleNameServiceReverseTld)
        case None =>
          deserializationError("Expected JsObject but found None")
      }
    }
  }
}
