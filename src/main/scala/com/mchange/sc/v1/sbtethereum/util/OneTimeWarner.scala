package com.mchange.sc.v1.sbtethereum.util

import sbt._

import scala.collection._

class OneTimeWarner[KEY] {
  //MT: protected by this' lock
  val warned = mutable.Set.empty[Tuple2[KEY,Configuration]]

  def warn( key : KEY, config : Configuration, log : sbt.Logger, messageLinesBuilder : () => Option[Seq[String]] ) : Unit = this.synchronized {
    val check = Tuple2( key, config )
    if( !warned( check ) ) {
      messageLinesBuilder().foreach { messageLines =>
        messageLines.foreach { message =>
          log.warn( message )
        }
        warned += check  // only update as set if we built the message.if None, if no message was warned
      }
    }
  }

  def reset( key : KEY, config : Configuration ) : Unit = this.synchronized {
    warned -= Tuple2( key, config )
  }

  def resetAll() : Unit = this.synchronized {
    warned.clear()
  }
}
