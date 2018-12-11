package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v3.failable._

object OverrideBuildDefaultBackstop {
  def find[KEY,T] (
    warn                  : Boolean,
    buildShadowWarningKey : KEY,
    buildShadowWarn       : ( OneTimeWarner[KEY], KEY, sbt.Configuration, sbt.Logger ) => Unit,
    mbOverride            : Option[T],
    mbBuild               : Option[T],
    mbDefault             : Option[T],
    mbBackstop            : Option[T],
    createFailure         : () => Throwable
  ) ( log : sbt.Logger, config : sbt.Configuration, warner : OneTimeWarner[KEY] ) : Failable[T] = {
    val resolutionTuple = Tuple4( mbOverride, mbBuild, mbDefault, mbBackstop )
    if ( warn ) {
      resolutionTuple match {
        case ( None, Some( build ), Some( default ), _ ) if build != default => buildShadowWarn( warner, buildShadowWarningKey, config, log )
        case _ => /* skip */
      }
    }
    val mbEffective = mbOverride orElse mbBuild orElse mbDefault orElse mbBackstop
    mbEffective match {
      case Some( out ) => Failable.succeed( out )
      case None => Failable.fail( createFailure() )
    }
  }
  def print[T] (
    mbOverride            : Option[T],
    mbBuild               : Option[T],
    mbDefault             : Option[T],
    mbBackstop            : Option[T],
    logEffective          : ( sbt.Logger, sbt.Configuration, T ) => Unit,
    logOverrideWins       : ( sbt.Logger, sbt.Configuration, T, Option[T], Option[T], Option[T] ) => Unit,
    logBuildWins          : ( sbt.Logger, sbt.Configuration, T, Option[T], Option[T] ) => Unit,
    logDefaultWins        : ( sbt.Logger, sbt.Configuration, T, Option[T] ) => Unit,
    logBackstopWins       : ( sbt.Logger, sbt.Configuration, T ) => Unit,
    logFailure            : ( sbt.Logger, sbt.Configuration ) => Unit,
  ) ( log : sbt.Logger, config : sbt.Configuration ) : Unit = {
    val mbEffective = mbOverride orElse mbBuild orElse mbDefault orElse mbBackstop
    mbEffective match {
      case Some( out ) => {
        logEffective( log, config, out )
        Tuple4( mbOverride, mbBuild, mbDefault, mbBackstop ) match {
          case ( Some( ov ), _, _, _ )                => logOverrideWins( log, config, ov, mbBuild, mbDefault, mbBackstop )
          case ( None, Some( build ), _, _ )          => logBuildWins( log, config, build, mbDefault, mbBackstop )
          case ( None, None, Some( default ), _ )     => logDefaultWins( log, config, default, mbBackstop )
          case ( None, None, None, Some( backstop ) ) => logBackstopWins( log, config, backstop )
          case ( None, None, None, None )             => assert( false, "If all four possible values are None, we never should have reached here, mbEffective would have been None." )
        }
      }
      case None => logFailure( log, config )
    }
  }
}

