package com.mchange.sc.v1.sbtethereum.shoebox

import scala.collection._
import java.io.File

trait PermissionsOverrideSource {
  def userReadOnlyFiles   : immutable.Set[File]
  def userExecutableFiles : immutable.Set[File]
}
