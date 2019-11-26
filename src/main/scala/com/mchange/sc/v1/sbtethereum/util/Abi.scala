package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum.{nst, AbiUnknownException, SbtEthereumPlugin}
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, jsonrpc}
import play.api.libs.json.Json

import Formatting._

private [sbtethereum] object Abi {
  val EmptyAbi: jsonrpc.Abi = jsonrpc.Abi.empty

  case class AliasSource( chainId : Int, alias : String ) extends AbiSource {
    def sourceDesc = s"ABI alias 'abi:${alias}' (on chain with ID ${chainId})"
  }
  case class AddressSource( chainId : Int, address : EthAddress, abiOverrides : Map[EthAddress,jsonrpc.Abi] ) extends AbiSource {
    def sourceDesc = s"ABI associated with contract address '${hexString(address)}' on chain with ID ${chainId}"
  }
  case class HashSource( hash : EthHash ) extends AbiSource {
    def sourceDesc = s"hash of compilation or ABI '${hexString(hash)}'"
  }
  trait AbiSource {
    def sourceDesc : String
  }

  def abiFromAbiSource( source : AbiSource ) : Option[ ( jsonrpc.Abi, Option[AbiLookup] ) ] = {
    source match {
      case AliasSource( chainId, alias ) => SbtEthereumPlugin.activeShoebox.abiAliasHashManager.findAbiByAbiAlias( chainId, alias ).assert.map( abi => ( abi, None ) )
      case AddressSource( chainId, address, abiOverrides ) => {
        val lookup = abiLookupForAddress( chainId, address, abiOverrides )
        lookup.resolveAbi( None ).map( abi => ( abi, Some( lookup ) ) )
      }
      case HashSource( hash ) => {
        val mbAbi = SbtEthereumPlugin.activeShoebox.abiAliasHashManager.findAbiByAbiHash( hash ).assert orElse SbtEthereumPlugin.activeShoebox.database.compilationInfoForCodeHash( hash ).assert.flatMap( _.mbAbi )
        mbAbi.map( abi => Tuple2( abi, None ) )
      }
    }
  }

  def loggedAbiFromAbiSource( log : sbt.Logger, source : AbiSource ) : Option[jsonrpc.Abi] = {
    abiFromAbiSource( source ) map { case ( abi, mbAbiLookup ) =>
      mbAbiLookup.foreach( _.logGenericShadowWarning( log ) )
      abi
    }
  }

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

    def genericShadowWarningMessage : Option[String] = this.shadowMessage.map( usingStr => s"Found multiple candidates when looking up the ABI for 0x${lookupAddress.hex}. ${usingStr}" )

    def logGenericShadowWarning( log : sbt.Logger ) : Unit  = this.genericShadowWarningMessage.foreach( msg => log.warn( msg ) )
  }

  def abiLookupForAddress( chainId : Int, address : EthAddress, abiOverrides : Map[EthAddress,jsonrpc.Abi], defaultBuilder : () => Option[jsonrpc.Abi] = () => None ) : AbiLookup = {
    AbiLookup(
      address,
      abiOverrides.get(address),
      SbtEthereumPlugin.activeShoebox.database.getImportedContractAbi( chainId, address ).assert,        // throw an Exception if there's a database problem
      SbtEthereumPlugin.activeShoebox.database.deployedContractInfoForAddress( chainId, address ).assert.flatMap( _.mbAbi ), // again, throw if database problem
      defaultBuilder
    )
  }

  def ensureAbiLookupForAddress( chainId : Int, address : EthAddress, abiOverrides : Map[EthAddress,jsonrpc.Abi], suppressStackTrace : Boolean = false ) : AbiLookup = {
    val defaultNotAvailable : () => Option[jsonrpc.Abi] = () => {
      val e = new AbiUnknownException( s"An ABI for a contract at address '${ hexString(address) }' is not known in the sbt-ethereum shoebox or set as an override." )
      throw ( if ( suppressStackTrace ) nst(e) else e )
    }
    abiLookupForAddress( chainId, address, abiOverrides, defaultNotAvailable )
  }

  def abiLookupForAddressDefaultEmpty( chainId : Int, address : EthAddress, abiOverrides : Map[EthAddress,jsonrpc.Abi] ) : AbiLookup = {
    abiLookupForAddress( chainId, address, abiOverrides, () => Some(EmptyAbi) )
  }
}
