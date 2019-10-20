package com.mchange.sc.v1.sbtethereum.util

import com.mchange.v2.log.{FallbackMLog,MLevel,MLog}

class MLogToggler( filter : FallbackMLog.Filter ) {
  lazy val fallback = {
    val fb = new FallbackMLog()
    fb.setOverrideCutoffLevel( MLevel.ALL )
    fb.setGlobalFilter( filter )
    fb
  }

  var replaced : MLog = null;

  def toggle() : Unit = this.synchronized {
    val current = MLog.instance()
    if ( current == fallback ) {
      assert( replaced != null, s"We should have a reference to the MLog instance we replaced!" )
      MLog.forceMLog( replaced );
      replaced = null;
    }
    else {
      assert( replaced == null, s"The default MLog is in use, we should not hold a replacement." )
      replaced = MLog.forceMLog( fallback )
    }
  }

  def reset() : Unit = this.synchronized {
    val current = MLog.instance()
    if ( current == fallback ) toggle()
  }
}
