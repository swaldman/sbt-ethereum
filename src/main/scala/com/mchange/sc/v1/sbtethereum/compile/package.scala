package com.mchange.sc.v1.sbtethereum

package object compile {
  final class UnparsableFileException( msg : String, line : Int, col : Int )
    extends SbtEthereumException( msg + s""" [$line:$col]""" )

  final class BadSolidityVersionException( badVersion : String )
    extends SbtEthereumException( s"Bad version string: '$badVersion'" )

  final class IncompatibleSolidityVersionsException( versions : Iterable[SemanticVersion] )
    extends SbtEthereumException( s"""Can't reconcile: ${versions.map("^"+_).mkString(", ")}""" )
}
