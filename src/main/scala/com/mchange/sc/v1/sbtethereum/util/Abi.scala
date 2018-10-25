package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum.{hexString, nst, repository, AbiUnknownException}
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, jsonrpc}
import play.api.libs.json.Json

object Abi {
  val EmptyAbi: jsonrpc.Abi = jsonrpc.Abi.empty

  def abiTextHash( abi : jsonrpc.Abi ) : ( String, EthHash ) = {
    val abiText = Json.stringify( Json.toJson( abi.withStandardSort ) ) // Note the use of withStandardSort!!!
    val abiHash = EthHash.hash( abiText.getBytes( scala.io.Codec.UTF8.charSet ) )
    ( abiText, abiHash )
  }

  def abiHash( abi : jsonrpc.Abi ) : EthHash = abiTextHash( abi )._2

  final case class AbiLookup( lookupAddress : EthAddress, abiOverride : Option[jsonrpc.Abi], memorizedAbi : Option[jsonrpc.Abi], compilationAbi : Option[jsonrpc.Abi], defaultBuilder : () => Option[jsonrpc.Abi] ) {
    def resolveAbi( mbLogger : Option[sbt.Logger] = None ) : Option[jsonrpc.Abi] = {
      mbLogger.foreach( logGenericShadowWarning )
      abiOverride orElse memorizedAbi orElse compilationAbi orElse defaultBuilder()
    }

    def shadowMessage : Option[String] = {
      ( abiOverride, memorizedAbi, compilationAbi ) match {
        case ( Some( ao ), Some( ma ), Some( ca ) ) => Some( "Using a user-set ABI override, which shadows both a memorized ABI and a compilation ABI." )
        case ( None,       Some( ma ), Some( ca ) ) => Some( "Using a memorized ABI which shadows a compilation ABI." )
        case ( Some( ao ),       None, Some( ca ) ) => Some( "Using a user-set ABI override, which shadows both a compilation ABI." )
        case _                                      => None
      }
    }

    def logGenericShadowWarning( log : sbt.Logger ) : Unit  = this.shadowMessage.foreach( usingStr => log.warn( s"Found multiple candidates when looking up the ABI for 0x${lookupAddress.hex}. ${usingStr}" ) )
  }

  def abiLookupForAddress( chainId : Int, address : EthAddress, abiOverrides : Map[EthAddress,jsonrpc.Abi], defaultBuilder : () => Option[jsonrpc.Abi] = () => None ) : AbiLookup = {
    AbiLookup(
      address,
      abiOverrides.get(address),
      repository.Database.getMemorizedContractAbi( chainId, address ).assert,        // throw an Exception if there's a database problem
      repository.Database.deployedContractInfoForAddress( chainId, address ).assert.flatMap( _.mbAbi ), // again, throw if database problem
      defaultBuilder
    )
  }

  def ensureAbiLookupForAddress( chainId : Int, address : EthAddress, abiOverrides : Map[EthAddress,jsonrpc.Abi], suppressStackTrace : Boolean = false ) : AbiLookup = {
    val defaultNotAvailable : () => Option[jsonrpc.Abi] = () => {
      val e = new AbiUnknownException( s"An ABI for a contract at address '${ hexString(address) }' is not known in the sbt-ethereum repository or set as an override." )
      throw ( if ( suppressStackTrace ) nst(e) else e )
    }
    abiLookupForAddress( chainId, address, abiOverrides, defaultNotAvailable )
  }

  def abiLookupForAddressDefaultEmpty( chainId : Int, address : EthAddress, abiOverrides : Map[EthAddress,jsonrpc.Abi] ) : AbiLookup = {
    abiLookupForAddress( chainId, address, abiOverrides, () => Some(EmptyAbi) )
  }
}
