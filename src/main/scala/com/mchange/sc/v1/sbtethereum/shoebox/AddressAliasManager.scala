package com.mchange.sc.v1.sbtethereum.shoebox

import scala.collection._

import com.mchange.sc.v1.sbtethereum._
import com.mchange.sc.v1.sbtethereum.shoebox

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.EthAddress
import com.mchange.sc.v3.failable._

class AddressAliasManager( parent : Shoebox ) {
  private val SyntheticAliases = immutable.Set( DefaultSenderAlias ) // XXX: in theory, we should just use the keyset of the map below. but that seems like a pain for now

  private def synthetics( chainId : Int ) : Failable[immutable.SortedMap[String,EthAddress]] = {
    parent.database.findDefaultSenderAddress( chainId ) map { mbSenderAddress =>
      mbSenderAddress match {
        case Some( address ) => immutable.SortedMap( DefaultSenderAlias -> address )
        case None            => immutable.SortedMap.empty
      }
    }
  }

  private def syntheticsByAddress( chainId : Int, address : EthAddress ) : Failable[immutable.SortedMap[String,EthAddress]] = {
    synthetics( chainId ).map( _.filter { case (k, v) => v == address } )
  }

  private [sbtethereum] 
  def insertAddressAlias( chainId : Int, alias : String, address : EthAddress ) : Failable[Unit] = {
    require( !SyntheticAliases( alias ), s"'${alias}' is reserved as a synthetic alias. You can't directly define it." )
    parent.database.insertAddressAlias( chainId, alias, address )
  }


  private [sbtethereum] 
  def findAllAddressAliases( chainId : Int ) : Failable[immutable.SortedMap[String,EthAddress]] = {
    for {
      dbAliases <- parent.database.findAllAddressAliases( chainId )
      syntheticAliases <- synthetics( chainId )
    }
    yield {
      dbAliases ++ syntheticAliases
    }
  }

  private [sbtethereum] 
  def findAddressByAddressAlias( chainId : Int, alias : String ) : Failable[Option[EthAddress]] = {
    for {
      mbSynthAlias <- synthetics( chainId ).map( _.get( alias ) )
      mbDbAlias <- parent.database.findAddressByAddressAlias( chainId, alias )
    }
    yield {
      mbSynthAlias orElse mbDbAlias
    }
  }

  private [sbtethereum] 
  def findAddressAliasesByAddress( chainId : Int, address : EthAddress ) : Failable[immutable.Seq[String]] = {
    for {
      dbAliases <- parent.database.findAddressAliasesByAddress( chainId, address )
      synths <- syntheticsByAddress( chainId, address ).map( theMap => theMap.map( _._1 ).toSeq )
    }
    yield {
      (dbAliases ++ synths).sorted
    }
  }

  private [sbtethereum] 
  def hasAddressAliases( chainId : Int, address : EthAddress ) : Failable[Boolean] = {
    findAddressAliasesByAddress( chainId, address ).map( _.nonEmpty )
  }

  private [sbtethereum] 
  def hasNonSyntheticAddressAliases( chainId : Int, address : EthAddress ) : Failable[Boolean] = {
    parent.database.findAddressAliasesByAddress( chainId, address ).map( _.nonEmpty )
  }

  private [sbtethereum] 
  def dropAddressAlias( chainId : Int, alias : String ) : Failable[Boolean] = {
    require( !SyntheticAliases( alias ), s"'${alias}' is reserved as a synthetic alias. You can't directly remove it." )
    parent.database.dropAddressAlias( chainId, alias )
  }
}
