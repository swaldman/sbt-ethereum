package com.mchange.sc.v1.sbtethereum

class TaskFailure( message : String ) extends SbtEthereumException( message ) {
  this.setStackTrace( Array.empty[StackTraceElement] )
}
