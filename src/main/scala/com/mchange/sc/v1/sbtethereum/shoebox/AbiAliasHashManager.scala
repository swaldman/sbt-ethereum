package com.mchange.sc.v1.sbtethereum.shoebox

import scala.collection._

import com.mchange.sc.v1.sbtethereum._
import com.mchange.sc.v1.sbtethereum.shoebox
import com.mchange.sc.v1.sbtethereum.shoebox.StandardAbi._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthHash,jsonrpc}
import com.mchange.sc.v3.failable._

import com.mchange.sc.v1.sbtethereum.util.Abi.abiHash

/*
 *  All methods here accept or return aliases with the "abi:" prefix dropped.
 * 
 *  Standard aliases still include the "standard:" prefix, however. 
 */ 
object AbiAliasHashManager {

  private [sbtethereum]
  def createUpdateAbiAlias( chainId : Int, alias : String, abiHash : EthHash ) : Failable[Unit] = {
    require( noStandardPrefix( alias ), "Can't create or update new standard ABI aliases. (These should be hardcoded into 'sbt-ethereum'.)" )
    shoebox.Database.createUpdateAbiAlias( chainId, alias, abiHash )
  }

  private [sbtethereum]
  def createUpdateAbiAlias( chainId : Int, alias : String, abi : jsonrpc.Abi ) : Failable[Unit] = {
    require( noStandardPrefix( alias ), "Can't create or update new standard ABI aliases. (These should be hardcoded into 'sbt-ethereum'.)" )
    shoebox.Database.createUpdateAbiAlias( chainId, alias, abi )
  }

  private [sbtethereum]
  def findAllAbiAliases( chainId : Int ) : Failable[immutable.SortedMap[String,EthHash]] = {
    shoebox.Database.findAllAbiAliases( chainId ).map( _ ++ StandardPrefixedAliasesToStandardAbiHashes )
  }

  private [sbtethereum]
  def findAbiHashByAbiAlias( chainId : Int, alias : String ) : Failable[Option[EthHash]] = {
    shoebox.Database.findAbiHashByAbiAlias( chainId, alias ).map( mbDbHash => mbDbHash orElse StandardPrefixedAliasesToStandardAbiHashes.get( alias ) )
  }

  private [sbtethereum]
  def findAbiByAbiAlias( chainId : Int, alias : String ) : Failable[Option[jsonrpc.Abi]] = {
    shoebox.Database.findAbiByAbiAlias( chainId, alias ).map( mbDbAbi => mbDbAbi orElse StandardPrefixedAliasesToStandardAbiHashes.get( alias ).flatMap( stdhash => StandardAbisByHash.get( stdhash ) ) )
  }

  private [sbtethereum]
  def findAbiAliasesByAbiHash( chainId : Int, abiHash : EthHash ) : Failable[immutable.Seq[String]] = {
    shoebox.Database.findAbiAliasesByAbiHash( chainId, abiHash ).map( aliases => aliases ++ StandardAbiHashesToStandardPrefixedAliases.get( abiHash ).toSeq )
  }

  private [sbtethereum]
  def findAbiAliasesByAbi( chainId : Int, abi : jsonrpc.Abi ) : Failable[immutable.Seq[String]] = this.findAbiAliasesByAbiHash( chainId, abiHash( abi ) )

  private [sbtethereum]
  def hasAbiAliases( chainId : Int, abiHash : EthHash ) : Failable[Boolean] = {
    shoebox.Database.hasAbiAliases( chainId, abiHash ).map( dbHas => dbHas || StandardAbiHashesToStandardPrefixedAliases.contains( abiHash ) )
  }

  private [sbtethereum]
  def hasAbiAliases( chainId : Int, abi : jsonrpc.Abi ) : Failable[Boolean] = this.hasAbiAliases( chainId, abiHash( abi ) )

  private [sbtethereum]
  def dropAbiAlias( chainId : Int, alias : String ) : Failable[Boolean] = {
    require( noStandardPrefix( alias ), "Can't drop standard ABI aliases. (These are hardcoded into 'sbt-ethereum'.)" )
    shoebox.Database.dropAbiAlias( chainId, alias )
  }

  private [sbtethereum]
  def findAbiByAbiHash( abiHash : EthHash ) : Failable[Option[jsonrpc.Abi]] = {
    shoebox.Database.findAbiByAbiHash( abiHash ).map( mbAbi => mbAbi orElse StandardAbisByHash.get( abiHash ) )
  }
}
