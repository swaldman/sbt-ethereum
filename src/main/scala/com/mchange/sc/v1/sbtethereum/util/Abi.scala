package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum.{nst, syncOut, AbiUnknownException, SbtEthereumPlugin, SbtEthereumException}
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, jsonrpc}

import Rawifier.rawify
import InteractiveQuery.aborted

import play.api.libs.json._

import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._

import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v2.io._

import java.io.File
import java.net.URL

import Formatting._

private [sbtethereum] object Abi {

  private lazy implicit val logger = mlogger( this )
  
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

  def fromJsArray( jsa : JsArray ) : Failable[jsonrpc.Abi] = Failable(jsa.as[jsonrpc.Abi]).xdebug("Interpreting JsArray as ABI:")

  def fromEnclodingJsObjectAtAbi( jso : JsObject ) : Failable[jsonrpc.Abi] = Failable( (jso \ "abi").get.as[jsonrpc.Abi] ).xdebug("""Looking for ABI in JsObjects as "abi":""")

  def fromEnclodingJsObjectAtMetadataAbi( jso : JsObject ) : Failable[jsonrpc.Abi] = Failable( (jso \ "metadata" \ "abi").get.as[jsonrpc.Abi] ).xdebug("""Looking for ABI in JsObjects as "metadata/abi":""")

  def fromEnclosingJsObject( jso : JsObject ) : Failable[jsonrpc.Abi] = fromEnclodingJsObjectAtAbi( jso ) orElse fromEnclodingJsObjectAtMetadataAbi( jso )

  def fromJsValue( jsv : JsValue ) : Failable[jsonrpc.Abi] = {
    jsv match {
      case jsa : JsArray  => fromJsArray( jsa )
      case jso : JsObject => fromEnclosingJsObject( jso )
      case other          => Failable.fail( s"Cannot extract an ABI from JSON: ${other}", false )
    }
  }

  def fromJsonText( text : String ) : Failable[jsonrpc.Abi] = fromJsValue( Json.parse( text ) )

  def fromFile( file : File ) : Failable[jsonrpc.Abi] = {
    for {
      bytes <- Failable(file.contentsAsByteArray).xdebug("Loading JSON file:")
      jsv   <- Failable( Json.parse( bytes ) ).xdebug("Parsing JSON bytes from file:")
      abi   <- fromJsValue( jsv )
    }
    yield {
      abi
    }
  }

  // unused for now
  private [this] def fromFile( s : String ) : Failable[jsonrpc.Abi] = {
    for {
      file <- Failable( new File(s) ).xdebug( s"Creating file '${s}' for JSON parse:" )
      abi  <- fromFile( file )
    }
    yield {
      abi
    }
  }

  def fromURL( url : URL ) : Failable[jsonrpc.Abi] = {
    for {
      is    <- Failable( url.openStream ).xdebug("Opening URL stream for JSON parse:" )
      jsv   <- Failable( Json.parse(is) ).xdebug("Parsing JSON from URL InputStream:" )
      abi   <- fromJsValue( jsv )
    }
    yield {
      abi
    }
  }

  // unused for now
  private [this] def fromURL( s : String ) : Failable[jsonrpc.Abi] = {
    for {
      url <- Failable( new URL(s) ).xdebug( s"Attempting java.net.URL object from '${s}':" )
      abi <- fromURL( url )
    }
    yield {
      abi
    }
  }

  // unused for now
  private [this] def fromUnknownString( s : String ) = fromJsonText( s ) orElse fromURL(s) orElse fromFile(s) orElse Failable.fail( s"Could not interpret as JSON text or as a URL or File pointing to JSON text." )

  def interactImportContractAbi( log : sbt.Logger, is : sbt.InteractionService ) : Failable[jsonrpc.Abi] = syncOut {
    println("To import an ABI, you may provide the JSON ABI directly, or a file path or URL from which the ABI can be downloaded.")
    val source = is.readLine("Contract ABI or Source: ", mask = false).getOrElse( aborted( "ABI import aborted by user. No ABI or source file / url provided." ) )

    log.info("Attempting to interactively import a contract ABI.")

    def verboseFromJsValue( jsv : JsValue ) : Failable[jsonrpc.Abi] = Failable.flatCreate {
      jsv match {
        case jsa : JsArray => {
          log.info("Found JSON array. Will attempt to interpret as ABI directly.")
          fromJsArray( jsa )
        }
        case jso : JsObject => {
          log.info("Found JSON object. Will look for an ABI under the key 'abi'.")
          fromEnclodingJsObjectAtAbi( jso ) orElse {
            log.info("Failed. Will look under the path 'metadata/abi'.")
            fromEnclodingJsObjectAtMetadataAbi( jso )
          }
        }
        case other => {
          log.info( s"Failed. Found JSON value not interpretable as an ABI: ${other}." )
          Failable.fail( "Found JSON value not interpretable as an ABI.", false )
        }
      }
    }

    val f_jsObjectOrArray : Failable[JsValue] = Failable {
      Json.parse( source ) match {
        case jsa : JsArray  => jsa
        case jso : JsObject => jso
        case _              => throw new SbtEthereumException( "Putative JSON ABI source not a JsArray or JsObject." )
      }
    }

    f_jsObjectOrArray match {
      case Succeeded( jsv : JsValue ) => {
        verboseFromJsValue( jsv )
      }
      case Failed( _ ) => {
        val f_bytes : Failable[Array[Byte]] = Failable.flatCreate {
          log.info("Checking if the provided source exists as a File.")
          val srcFile = new File( source )
          if ( srcFile.exists() ) {
            log.info( s"Found file '${srcFile.getAbsolutePath}'. Will interpret file contents." )
            Failable( srcFile.contentsAsByteArray )
          }
          else {
            log.info("No file found. Checking if the provided source is interpretable as a URL.")

            def tryAsUrl( s : String ) = {
              val f_u = Failable(new URL(s))
              f_u match {
                case Succeeded( url ) => {
                  log.info("Interpreted user-provided source as a URL. Attempting to fetch contents.")
                  Failable( url.openStream().remainingToByteArray )
                }
                case Failed( _ ) => {
                  val msg = "Could not interpret user-provided ABI source as valid JSON text, an existing file, or a URL. Giving up."
                  log.info(msg)
                  Failable.fail( msg, false )
                }
              }
            }

            val mbRawBytes = {
              rawify(source).map { rawified =>
                log.info( s"'${source}' was reinterpreted into raw URL '${rawified}. Trying that, will retry provided URL if necessary." )
                tryAsUrl(rawified)
              }
            }
            mbRawBytes match { // first try a rawified source, of applicable
              case Some( ok @ Succeeded(_) ) => ok
              case _                         => tryAsUrl(source) // if that didn't work try the source directly
            }
          }
        }
        f_bytes match {
          case Succeeded( bytes ) => {
            val f_unscraped = Failable( Json.parse( bytes ) ).flatMap( verboseFromJsValue )
            f_unscraped
          }
          case failed @ Failed(_) => Failable.refail(failed)
        }
      }
    }
  }
}
