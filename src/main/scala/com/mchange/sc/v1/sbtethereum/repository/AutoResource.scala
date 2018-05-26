package com.mchange.sc.v1.sbtethereum.repository

import java.io.File
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import com.mchange.sc.v3.failable._


object AutoResource {
  object UserOnlyDirectory {
    case class Spec( rawParent : Failable[File], enforcedParent : ()=>Failable[File], dirName : String )
  }
  class UserOnlyDirectory( rawParent : Failable[File], enforcedParent : ()=>Failable[File], dirName : String ) extends AutoResource[UserOnlyDirectory.Spec,Failable[File]](
    UserOnlyDirectory.Spec( rawParent, enforcedParent, dirName ),
    spec => spec.enforcedParent().map( new File( _, dirName ) ).flatMap( ensureUserOnlyDirectory ),
    _ => Failable.succeed( () ) // directories don't need cleanup
  ) {
    def existenceAndPermissionsEnforced        = this.active
    lazy val existenceAndPermissionsUnenforced = rawParent.map( new File( _, dirName ) )
  }
}
class AutoResource[S,T>:Null]( val specifier : S, recreate : S => T, close : (T) => Failable[Any] ) {

  // MT: protected by this' lock
  private var _active : T = null

  def active : T = this.synchronized {
    if ( _active != null ) {
      _active
    }
    else {
      _active = recreate( specifier )
      _active
    }
  }

  def reset() : Failable[Any] = this.synchronized {
    _active = null
    Failable( close( _active ) )
  }
}
