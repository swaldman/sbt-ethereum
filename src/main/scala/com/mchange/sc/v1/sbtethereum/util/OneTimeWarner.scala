package com.mchange.sc.v1.sbtethereum.util

import sbt._

import scala.collection._

class OneTimeWarner[KEY] {
  //MT: protected by this' lock
  val warned = mutable.Set.empty[Tuple3[KEY,Configuration,Int]]

  def warn( key : KEY, config : Configuration, chainId : Int, log : sbt.Logger, messageLinesBuilder : () => Option[Seq[String]] ) : Unit = this.synchronized {
    val check = Tuple3( key, config, chainId )
    if( !warned( check ) ) {
      messageLinesBuilder().foreach { messageLines =>
        messageLines.foreach { message =>
          log.warn( message )
        }
        warned += check  // only update as set if we built the message.if None, if no message was warned
      }
    }
  }

  def reset( key : KEY, config : Configuration, chainId : Int ) : Unit = this.synchronized {
    warned -= Tuple3( key, config, chainId )
  }

  def resetAll() : Unit = this.synchronized {
    warned.clear()
  }
}
