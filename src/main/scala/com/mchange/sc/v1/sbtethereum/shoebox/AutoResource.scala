package com.mchange.sc.v1.sbtethereum.shoebox

import java.io.File
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import com.mchange.sc.v3.failable._


private [shoebox]
object AutoResource {

  object UserOnlyDirectory {

    trait Owner {

      private [shoebox]
      val DirectoryManager : UserOnlyDirectory;

      private [shoebox]
      def Directory_ExistenceAndPermissionsUnenforced : Failable[File] = DirectoryManager.existenceAndPermissionsUnenforced

      def Directory : Failable[File] = DirectoryManager.existenceAndPermissionsEnforced

    }

    def apply( rawParent : Failable[File], enforcedParent : ()=>Failable[File], dirName : String ) : UserOnlyDirectory = new UserOnlyDirectory( rawParent, enforcedParent, dirName )

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

  def apply[S,T>:Null]( specifier : S, recreate : S => T, close : (T) => Failable[Any] ) : AutoResource[S,T] = new AutoResource( specifier, recreate, close )
}
private [shoebox]
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
    val tmp = _active
    _active = null
    Failable( close( tmp ) )
  }
}
