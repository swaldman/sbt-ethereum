package com.mchange.sc.v1.sbtethereum.util

import java.io.File

import scala.collection._

import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._

import com.mchange.sc.v1.consuela.ethereum._

private [sbtethereum]
object WalletsV3 {

  private [sbtethereum]
  def combinedKeystoresMultiMap( keystoresV3 : Seq[File] ) : immutable.Map[EthAddress, immutable.Set[wallet.V3]] = {
    def combineMultiMaps( base : immutable.Map[EthAddress, immutable.Set[wallet.V3]], next : immutable.Map[EthAddress, immutable.Set[wallet.V3]] ) : immutable.Map[EthAddress, immutable.Set[wallet.V3]] = {
      val newTuples = next.map { case ( key, valueSet ) =>
        Tuple2( key, valueSet ++ base.get(key).getOrElse( immutable.Set.empty ) )
      }

      (base ++ newTuples)
    }

    keystoresV3
      .map( dir => Failable( wallet.V3.keyStoreMultiMap(dir) ).xdebug( "Failed to read keystore directory: ${dir}" ).recover( _ => immutable.Map.empty[EthAddress,immutable.Set[wallet.V3]] ).assert )
      .foldLeft( immutable.Map.empty[EthAddress,immutable.Set[wallet.V3]] )( combineMultiMaps )
  }

  private [sbtethereum]
  def walletsForAddress( address : EthAddress, keystoresV3 : Seq[File] ) : immutable.Set[wallet.V3] = {
    val combined = combinedKeystoresMultiMap( keystoresV3 )
    combined.get( address ).getOrElse( immutable.Set.empty )
  }

}
