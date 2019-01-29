package com.mchange.sc.v1.sbtethereum.shoebox

import scala.collection._

import com.mchange.sc.v1.sbtethereum._

import com.mchange.sc.v1.consuela.ethereum.{EthHash,jsonrpc}

import com.mchange.sc.v1.sbtethereum.util.Abi.abiHash

object StandardAbi {
  val Erc20Abi = jsonrpc.Abi( """[{"name":"approve","inputs":[{"name":"spender","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"totalSupply","inputs":[],"outputs":[{"name":"","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"transferFrom","inputs":[{"name":"from","type":"address"},{"name":"to","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"balanceOf","inputs":[{"name":"tokenOwner","type":"address"}],"outputs":[{"name":"balance","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"transfer","inputs":[{"name":"to","type":"address"},{"name":"tokens","type":"uint256"}],"outputs":[{"name":"success","type":"bool"}],"constant":false,"payable":false,"stateMutability":"nonpayable","type":"function"},{"name":"allowance","inputs":[{"name":"tokenOwner","type":"address"},{"name":"spender","type":"address"}],"outputs":[{"name":"remaining","type":"uint256"}],"constant":true,"payable":false,"stateMutability":"view","type":"function"},{"name":"Transfer","inputs":[{"name":"from","type":"address","indexed":true},{"name":"to","type":"address","indexed":true},{"name":"tokens","type":"uint256","indexed":false}],"anonymous":false,"type":"event"},{"name":"Approval","inputs":[{"name":"tokenOwner","type":"address","indexed":true},{"name":"spender","type":"address","indexed":true},{"name":"tokens","type":"uint256","indexed":false}],"anonymous":false,"type":"event"}]""").withStandardSort

  // use all lower-case keys
  val StandardAbis : Map[String,jsonrpc.Abi] = Map (
    "erc20" -> Erc20Abi
  )
  lazy val StandardAbiHashes : Map[String,EthHash] = StandardAbis.map { case ( alias, abi ) => (alias, abiHash(abi)) }

  val StandardPrefix = "standard:"

  val StandardPrefixLen = StandardPrefix.length

  def hasStandardPrefix( alias : String ) : Boolean = alias.startsWith( StandardPrefix )

  def noStandardPrefix( alias : String ) : Boolean = !hasStandardPrefix( alias )

  def addStandardPrefix( rawAlias : String ) : String = StandardPrefix + rawAlias

  def dropStandardPrefix( standardAlias : String ) : String = {
    require( hasStandardPrefix( standardAlias ), s"Can't drop 'standard:' prefix from '${standardAlias}', which does not have it." )
    standardAlias.drop( StandardPrefixLen )
  }

  lazy val StandardPrefixedAliasesToStandardAbiHashes = StandardAbiHashes.map { case ( unprefixed, hash ) => ( addStandardPrefix( unprefixed ), hash ) }

  lazy val StandardAbiHashesToStandardPrefixedAliases = StandardPrefixedAliasesToStandardAbiHashes.map { case (k,v) => (v,k) }

  lazy val StandardAbisByHash : Map[EthHash,jsonrpc.Abi] = StandardAbis.map { case ( _, abi ) => ( abiHash(abi), abi ) }

  def isStandardAbiAlias( noAbiPrefixAlias : String ) = noAbiPrefixAlias.startsWith( "standard:" )
}
