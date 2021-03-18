package com.mchange.sc.v1.sbtethereum.util

final object Rawifier {

  // for eventual resolution of github URL imports
  private val GithubUrlRegex = """^(?:https?\/\/)?(\S*github\S*.com)(\/\S*)$""".r
  private val BlobRegex      = """\/blob""".r

  def rawifyGithubUrl( mbUnraw : String ) : Option[String] = {
    mbUnraw match {
      case GithubUrlRegex( host, path ) => Some( s"""https://raw.githubusercontent.com${ BlobRegex.replaceAllIn(path, "") }""" )
      case _                            => None
    }
  }

  // for now only github is supported
  def rawifyUrl( mbUnraw : String ) : Option[String] = rawifyGithubUrl( mbUnraw )

  def rawifyUrlIfApplicable( mbUnraw : String ) : String = rawifyUrl( mbUnraw ).getOrElse( mbUnraw )
}
