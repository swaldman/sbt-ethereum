package com.mchange.sc.v1.sbtethereum.util

import scala.collection._

object ChainIdMutable {
  final case class Modified[T]( pre : Option[T], post : Option[T] )  
}

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

  def modify( chainId : Int )( op : Option[T] => Option[T] ) : ChainIdMutable.Modified[T] = this.synchronized {
    val pre = storage.get( chainId )
    val post = op( pre )
    post match {
      case Some( elem ) => storage += Tuple2( chainId, elem )
      case None         => storage -= chainId
    }
    ChainIdMutable.Modified( pre, post )
  }

  def reset() : Unit = this.synchronized( storage.clear() )

  // conveniences
  def set( chainId : Int, newValue : T ) : Unit = this.getSet( chainId, newValue )

  def drop( chainId : Int ) : Unit = this.getDrop( chainId )
}

