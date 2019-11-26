package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._
import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthChainId}
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v2.ens
import com.mchange.sc.v3.failable._

import scala.collection._
import scala.util.control.NonFatal

private [sbtethereum]
class EnsAddressCache {
  private implicit lazy val logger = mlogger( this )

  private val TTL     = 300000 // 300 secs, 5 mins, maybe someday make this sensitive to ENS TTLs
  private val MaxSize = 100

  private case class Key( jsonRpcUrl : String, chainId : Int, nameServiceAddress : EthAddress, path : String )

  // MT: synchronized on this' lock
  private val cache = mutable.HashMap.empty[Key,Tuple2[Failable[EthAddress],Long]]

  private def doLookup( ensClient : ens.Client, key : Key ) : ( Failable[EthAddress], Long ) = {
    TRACE.log( s"doLookup( $key )" )
    val ts = System.currentTimeMillis()
    try {
      Tuple2( ensClient.address( key.path ).toFailable( s"No address has been associated with ENS name '${key.path}'." ), ts )
    }
    catch {
      case NonFatal( nfe ) => ( Failable.fail( s"Exception while looking up ENS name '${key.path}': ${nfe}", includeStackTrace = false ), ts )
    }
  }

  // called only from synchronized lookup(...)
  private def update( key : Key ) : Tuple2[Failable[EthAddress],Long] = {
    val chainId = if ( key.chainId >= 0 ) Some( EthChainId( key.chainId )  ) else None
    val ensClient = ens.Client( jsonRpcUrl = key.jsonRpcUrl, chainId = chainId, nameServiceAddress = key.nameServiceAddress )
    val updated = doLookup( ensClient, key )
    cache += Tuple2( key, updated )
    //println( s"update: ${updated} (path=${key.path})" )
    updated
  }

  def lookup( rpi : RichParserInfo, path : String ) : Failable[EthAddress] = {
    this.synchronized {
      Failable.flatCreate {
        def assertJsonRpcUrl = {
          rpi.mbJsonRpcUrl.getOrElse( throw new Exception("No jsonRpcUrl available in RichParserInfo: ${rpi}") )
        }
        val key = Key( assertJsonRpcUrl, rpi.chainId, rpi.nameServiceAddress, path )
        val ( result, timestamp ) = {
          val out = {
            cache.get( key ) match {
              case Some( tup ) => if ( System.currentTimeMillis() > tup._2 + TTL ) update( key ) else tup
              case None        => update( key )
            }
          }
          if ( cache.size > MaxSize ) { // an ugly, but easy, way to bound the size of the cache
            cache.clear()
            cache += Tuple2( key, out )
          }
          out
        }
        // println( s"${path} => ${result}" )
        result
      }
    }
  }

  def reset() : Unit = this.synchronized( cache.clear() )
}
