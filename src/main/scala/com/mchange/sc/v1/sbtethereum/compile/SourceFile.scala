package com.mchange.sc.v1.sbtethereum.compile

import com.mchange.sc.v1.sbtethereum._
import java.io.{BufferedInputStream, File}
import java.net.URL
import scala.collection._
import scala.io.{Codec, Source}
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.failable._
import scala.util.matching.Regex

object SourceFile {
  // for eventual resolution of github URL imports
  private val GithubUrlRegex = """^(?:https?\/\/)?(\S*github\S*.com)(\/\S*)$""".r
  private val BlobRegex      = """\/blob""".r

  val AbsoluteFileRegex: Regex = """^(?:/|\\).*|[A-Z]\:\\.*""".r
  val PragmaSolidityRegex: Regex = """(?i)pragma\s+solidity\s+\^([^\s\;]+)\s*\;""".r

  def transformSpecialUrl( mbSpecialUrl : String ) : Option[String] = {
    mbSpecialUrl match {
      case GithubUrlRegex( host, path ) => Some( s"""https://raw.githubusercontent.com${ BlobRegex.replaceAllIn(path, "") }""" )
      case _                            => None
    }
  }

  final object Location {
    def apply( parent : Location, spec : String = "" ) : Location = {
      spec match {
        case AbsoluteFileRegex()           => Location.File( new java.io.File( spec ) )
        case _ if spec.indexOf(':') >= 0   => Location.URL( new java.net.URL( spec ) )
        case _                             => if ( spec.length > 0 ) parent.child( spec ) else parent
      }
    }

    def apply( file : java.io.File, spec : String ) : Location = apply( Location( file ), spec )

    def apply( spec : String ) : Location = this.apply( Empty, spec )

    def apply( file : java.io.File ) : Location = Location.File( file.getCanonicalFile )

    def apply( url : java.net.URL ) : Location = {
      val spec = url.toExternalForm
      Location.URL( new java.net.URL( transformSpecialUrl( spec ).getOrElse( spec ) ) )
    }

    private val FileSeparator = System.getProperty("file.separator")

    private val forwardSlashes : String => String =
      _.replace( '\\', '/' )

    private val backwardSlashes : String => String =
      _.replace( '/', '\\' )

    private val fileSlashes : String => String = {
       FileSeparator match {
        case "/"  => forwardSlashes
        case "\\" => backwardSlashes
        case oops => throw new Exception( s"Unexpected 'file.separator', expect '/' or '\', found '$oops'." )
      }
    }

    // Empty can be used to resolve ABSOLUTE filenames and URLs only
    final case object Empty extends Location {
      def resolveLocalKey( key : String ) : Failable[SourceFile] = fail( s"Key '$key' could not be found, because nothing can be found, locally from Location.Empty" )
      def child( spec : String ) : Location = this
    }

    final case class URL private[SourceFile] ( parent : java.net.URL ) extends Location {
      val base: String = {
        val raw = parent.toExternalForm
        if ( raw.last == '/' ) raw else raw + '/'
      }

      def resolveLocalKey( key : String ) : Failable[SourceFile] = Failable {
        require ( key.length > 0 && key(0) != '/', s"key must be at least one char long and not begin with slash, found: '$key'" )
        val fullUrl = new java.net.URL( base + forwardSlashes( key ) )
        val urlConn = fullUrl.openConnection()
        val lastMod = urlConn.getLastModified
        val contents = borrow( Source.fromInputStream( new BufferedInputStream( urlConn.getInputStream ) )(Codec.UTF8) )( _.close )
                             { _.foldLeft("")( _ + _ ) }
        val immediateParent = {
          val spec = fullUrl.toExternalForm
          Location.URL( new java.net.URL( spec.substring(0, spec.lastIndexOf('/') + 1) ) ) // include the final slash
        }
        SourceFile( immediateParent, contents, lastMod )
      }

      def child( spec : String ) : Location = URL( new java.net.URL( parent, forwardSlashes( spec ) ) )
    }

    final case class File private[SourceFile] ( parent : java.io.File ) extends Location {
      def resolveLocalKey( key : String ) : Failable[SourceFile] = Failable {
        val f = new java.io.File( parent, fileSlashes( key ) )
        val contents = fileToString(f)
        val lastMod = f.lastModified
        val immediateParent = Location.File( f.getParentFile )
        SourceFile( immediateParent, contents, lastMod )
      }

      def child( spec : String ) : Location = File( new java.io.File( parent, fileSlashes( spec ) ) )
    }
  }

  trait Location {
    final def resolveKey( key : String ) : Failable[SourceFile] = key match {
      case AbsoluteFileRegex() =>
        val f = new File( key )
        Location.File( f.getParentFile ).resolveLocalKey( f.getName )

      case _ if key.indexOf(':') >= 0 =>
        val spec = transformSpecialUrl( key ).getOrElse( key )
        val ( parent, name ) = {
          val nameIndex = spec.lastIndexOf('/') + 1
          ( spec.substring(0, nameIndex), spec.substring( nameIndex ) )
        }
        Location.URL( new java.net.URL( parent ) ).resolveLocalKey( name )

      case _ => this.resolveLocalKey( key )
    }

    def resolveLocalKey( key : String ) : Failable[SourceFile]

    def child( spec : String ) : Location
  }

  def apply( parentDir : File, filePath : String ) : SourceFile = Location.File( parentDir ).resolveKey( filePath ).get

  def apply( parent : Location, key : String ) : SourceFile = parent.resolveKey( key ).get

  def apply( url : URL ) : SourceFile = {
    val spec = url.toExternalForm
    val lastSlash = spec.lastIndexOf('/')
    val parentSpec = spec.substring(0, lastSlash + 1) // include the slash
    val key = spec.substring( lastSlash + 1 ) // exclude the slash, vomit if slash was last character
    Location.URL( new java.net.URL( parentSpec ) ).resolveKey( key ).get
  }

  val oldAndEmpty = SourceFile(Location.Empty, "", Long.MinValue)

  def fileToString( srcFile : File ) : String =
    borrow( Source.fromFile( srcFile )(Codec.UTF8) )( _.close() ){ _.foldLeft("")( _ + _ ) }
}

case class SourceFile( immediateParent : SourceFile.Location, rawText : String, lastModified : Long ) {
  import SourceFile.PragmaSolidityRegex

  private def exciseAndPrepend( semanticVersion : SemanticVersion ) : String = {
    val pragma = s"pragma solidity ^${ semanticVersion.versionString };\n\n"
    pragma + PragmaSolidityRegex.replaceAllIn( rawText, _ => "\n\n" )
  }

  lazy val pragmaResolvedText : String = {
    val matches = PragmaSolidityRegex.findAllMatchIn( rawText ).toVector
    matches.length match {
      case 0 => rawText

      case 1 => exciseAndPrepend( SemanticVersion( matches.head.group(1) ) )

      case n =>
        val versions = matches.map( m => SemanticVersion( m.group(1) ) )
        val optVersions = versions.map( Some.apply )
        val mbCompatibleVersion = optVersions.reduceLeft( SemanticVersion.restrictiveCaretCompatible )
        val compatibleVersion = mbCompatibleVersion match {
          case Some( sv ) => sv

          case None => throw new IncompatibleSolidityVersionsException( versions )
        }
        exciseAndPrepend( compatibleVersion )
    }
  }
}
