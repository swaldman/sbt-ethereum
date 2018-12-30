package com.mchange.sc.v1.sbtethereum.util

import scala.collection._

// MT: protected by this' lock
class ChainIdMutable[T] {
  private val storage = mutable.Map.empty[Int,T]

  def getSet( chainId : Int, newValue : T ) : Option[T] = this.synchronized {
    val out = storage.get( chainId )
    storage += Tuple2( chainId, newValue )
    out
  }

  def get( chainId : Int ) : Option[T] = this.synchronized {
    storage.get( chainId )
  }

  def getDrop( chainId : Int ) : Option[T] = this.synchronized {
    val out = storage.get( chainId )
    storage -= chainId
    out
  }

  def reset() : Unit = this.synchronized( storage.clear() )

  // conveniences
  def set( chainId : Int, newValue : T ) : Unit = this.getSet( chainId, newValue )

  def drop( chainId : Int ) : Unit = this.getDrop( chainId )
}

