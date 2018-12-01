package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum.{hexString, nst, shoebox, AbiUnknownException}
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, jsonrpc}
import play.api.libs.json.Json

private [sbtethereum] object Abi {
  val EmptyAbi: jsonrpc.Abi = jsonrpc.Abi.empty

  val Erc20Abi = jsonrpc.Abi( """[{"name":"approve","inputs":[{"name":"spender","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"totalSupply","inputs":[],"outputs":[{"name":"","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"transferFrom","inputs":[{"name":"from","type":"address"},{"name":"to","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"balanceOf","inputs":[{"name":"tokenOwner","type":"address"}],"outputs":[{"name":"balance","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"transfer","inputs":[{"name":"to","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"allowance","inputs":[{"name":"tokenOwner","type":"address"},{"name":"spender","type":"address"}],"outputs":[{"name":"remaining","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"Transfer","inputs":[{"name":"from","type":"address","indexed":true},{"name":"to","type":"address","indexed":true},{"name":"tokens","type":"uint256","indexed":false}],"anonymous":false,"type":"event"},{"name":"Approval","inputs":[{"name":"tokenOwner","type":"address","indexed":true},{"name":"spender","type":"address","indexed":true},{"name":"tokens","type":"uint256","indexed":false}],"anonymous":false,"type":"event"}]""")

  // use all lower-case keys
  val StandardAbis = Map (
    "erc20" -> Erc20Abi
  )

  case class StandardSource( name : String ) extends AbiSource {
    def sourceDesc = s"standard ABI definition '${name}'"
  }
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
      case StandardSource( name ) => StandardAbis.get( name.toLowerCase ).map( abi => ( abi, None ) )
      case AliasSource( chainId, alias ) => shoebox.Database.findAbiByAbiAlias( chainId, alias ).assert.map( abi => ( abi, None ) )
      case AddressSource( chainId, address, abiOverrides ) => {
        val lookup = abiLookupForAddress( chainId, address, abiOverrides )
        lookup.resolveAbi( None ).map( abi => ( abi, Some( lookup ) ) )
      }
      case HashSource( hash ) => {
        (shoebox.Database.findAbiByAbiHash( hash ).assert orElse shoebox.Database.compilationInfoForCodeHash( hash ).assert.flatMap( _.mbAbi )).map( abi => ( abi, None ) )
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
      shoebox.Database.getMemorizedContractAbi( chainId, address ).assert,        // throw an Exception if there's a database problem
      shoebox.Database.deployedContractInfoForAddress( chainId, address ).assert.flatMap( _.mbAbi ), // again, throw if database problem
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
