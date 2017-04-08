package com.mchange.sc.v1.sbtethereum

import scala.math.max

object SemanticVersion {

  // see https://docs.npmjs.com/misc/semver, sort of
  //
  // omitting the possibility of missing or non-numeric version-parts

  private val Regex = """^(\d+)\.(\d+)\.(\d+)(?:\W*)?$""".r

  def apply( versionString : String ) : SemanticVersion= {
    versionString match {
      case Regex( major, minor, patch ) => SemanticVersion( major.toInt, minor.toInt, patch.toInt )
      case _                            => throw new BadSolidityVersionException( versionString )
    }
  }

  def canBeCaretCompatible( a : SemanticVersion, b : SemanticVersion ) : Boolean = {
    if ( a.major == 0 ) {
      if ( a.minor == 0 ) {
        a.patch == b.patch
      } else {
        a.minor == b.minor
      }
    } else {
      a.major == b.major
    }
  }

  def restrictiveCaretCompatible( a : SemanticVersion, b : SemanticVersion ) : Option[SemanticVersion] = {
    if ( ! canBeCaretCompatible( a, b ) ) {
      None
    } else {
      Some( SemanticVersion( max( a.major, b.major ), max( a.minor, b.minor ), max( a.patch, b.patch ) ) )
    }
  }

  def restrictiveCaretCompatible( a : Option[SemanticVersion], b : Option[SemanticVersion] ) : Option[SemanticVersion] = {
    if ( a == None ) None else if ( b == None ) None else restrictiveCaretCompatible( a.get, b.get )
  }
}
case class SemanticVersion( major : Int, minor : Int, patch : Int ) {
  def versionString = s"${major}.${minor}.${patch}"
}

