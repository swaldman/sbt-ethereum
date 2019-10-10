package com.mchange.sc.v1.sbtethereum.util

import sbt.util._

import com.mchange.v2.log.{MLevel,MLog,MLogger}

import scala.collection._

object MLogSbtLogger {

  //MT: access synchronized on this' lock
  val keysToLoggers = mutable.Map.empty[sbt.Def.ScopedKey[_],MLogSbtLogger]

  def apply(key : sbt.Def.ScopedKey[_]) : MLogSbtLogger = this.synchronized {
    keysToLoggers.getOrElseUpdate( key, new MLogSbtLogger( MLog.getLogger( loggerNameForKey(key) ) ) )
  }

  private def loggerNameForKey( key : sbt.Def.ScopedKey[_] ) = s"sbtkey.${key}"
  
  private def mlevelToLevel( ml : MLevel ) : Level.Value = {
    ml match {
      case MLevel.ALL     => Level.Debug
      case MLevel.CONFIG  => Level.Info
      case MLevel.FINE    => Level.Debug
      case MLevel.FINER   => Level.Debug
      case MLevel.FINEST  => Level.Debug
      case MLevel.INFO    => Level.Info
      case MLevel.OFF     => Level.Error
      case MLevel.SEVERE  => Level.Error
      case MLevel.WARNING => Level.Warn
    }
  }
  private def levelToMLevel( l : Level.Value ) : MLevel = {
    l match {
      case Level.Debug => MLevel.FINE
      case Level.Info  => MLevel.INFO
      case Level.Warn  => MLevel.WARNING
      case Level.Error => MLevel.SEVERE
    }
  }
  private def labelForLevel( l : Level.Value ) : String = {
    l match {
      case Level.Debug => "[debug]"
      case Level.Info  => "[info]"
      case Level.Warn  => "[warn]"
      case Level.Error => "[error]"
    }
  }
}
final class MLogSbtLogger( mlogger : MLogger ) extends AbstractLogger {
  import MLogSbtLogger._

  // MT: protected by this' lock
  var trace : Int = 0;
  var se    : Boolean = true;

  def getLevel: Level.Value                  = mlevelToLevel( mlogger.getLevel() )
  def setLevel(newLevel: Level.Value): Unit  = mlogger.setLevel( levelToMLevel( newLevel ) )

  def setTrace(flag: Int): Unit              = this.synchronized { this.trace = flag }
  def getTrace: Int                          = this.synchronized { this.trace }
  def successEnabled: Boolean                = this.synchronized { this.se }
  def setSuccessEnabled(flag: Boolean): Unit = this.synchronized { this.se = flag }

  def log(level : sbt.util.Level.Value, message : =>String): Unit = mlogger.log( levelToMLevel( level ), labelForLevel(level) + " " + message )

  def success(message : => String) : Unit = if ( successEnabled ) mlogger.log( MLevel.INFO, s"[success] ${message}" )

  def trace(t : => Throwable) : Unit = {
    if ( trace != 0 ) {
      mlogger.log( MLevel.FINE, s"[trace] Throwable encountered: ${t}", t )
    }
  }

  def control(event: ControlEvent.Value, message: => String): Unit = mlogger.log( MLevel.FINE, s"Control Event -> ${event}: ${message}" )

  def logAll(events: Seq[LogEvent]): Unit = events.foreach( log )
}
