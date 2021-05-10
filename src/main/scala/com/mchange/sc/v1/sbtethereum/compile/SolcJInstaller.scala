package com.mchange.sc.v1.sbtethereum.compile

import java.io._
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermission._
import java.net.{URL, URLClassLoader}
import scala.collection._
import JavaConverters._
import scala.io.Source
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v2.util.Platform
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.sbtethereum.SbtEthereumException

object SolcJInstaller {
  private lazy implicit val logger = mlogger( this )

  private lazy val PlatformDirPrefix = {
    val platformDirName = Platform.Current match {
      case Some( Platform.Mac )     => "mac"
      case Some( Platform.Unix )    => "linux" // currently the only supported UNIX
      case Some( Platform.Windows ) => "win"
      case unknown                  => throw new Exception( s"Unsupported platform: $unknown" )
    }
    "native/" + platformDirName + "/solc"
  }
  private lazy val FileListResource = PlatformDirPrefix + "/file.list"

  private val SolcJSupportedVersions = {
    immutable.TreeSet( "0.4.7", "0.4.8", "0.4.10", "0.4.25", "0.5.7" )( Ordering.by( SemanticVersion( _ ) ) ) // only metadata supporting versions
  }

  private val OtherSolcjCompatibleSupportedVersion = {
    immutable.TreeMap (
      "0.4.18" -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.4.18rev1/solcj-compat-0.4.18rev1.jar" ),
      "0.4.22" -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.4.22rev1/solcj-compat-0.4.22rev1.jar" ),
      "0.4.24" -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.4.24rev2/solcj-compat-0.4.24rev2.jar" ), // note the rev2, i screwed up rev1
      "0.5.16" -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.5.16rev1/solcj-compat-0.5.16rev1.jar" ),
      "0.6.2"  -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.6.2rev1/solcj-compat-0.6.2rev1.jar"   ),
      "0.6.12" -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.6.12rev1/solcj-compat-0.6.12rev1.jar" ),
      "0.7.6"  -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.7.6rev1/solcj-compat-0.7.6rev1.jar"   ),
      "0.8.1"  -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.8.1rev1/solcj-compat-0.8.1rev1.jar"   ),
      "0.8.4"  -> new URL( "https://repo1.maven.org/maven2/com/mchange/solcj-compat/0.8.4rev2/solcj-compat-0.8.4rev2.jar"   )
    )
  }

  val SupportedVersions = SolcJSupportedVersions ++ OtherSolcjCompatibleSupportedVersion.keySet

  val DefaultSolcJVersion = "0.8.4"

  private def mbVersionUrl( version : String ) : Option[URL] = {
    if ( SolcJSupportedVersions( version ) ) {
      Some( createSolcJJarUrl( version ) )
    }
    else {
      OtherSolcjCompatibleSupportedVersion.get( version )
    }
  }

  private def createSolcJJarUrl( version : String ) : URL = {
    new URL( s"https://dl.bintray.com/ethereum/maven/org/ethereum/solcJ-all/$version/solcJ-all-$version.jar" )
  }

  private def createClassLoader( jarUrl : URL ) : URLClassLoader = {
    new URLClassLoader( Array( jarUrl ) )
  }

  private def findSolcFileNames( cl : URLClassLoader ) : immutable.Seq[String] = {
    val fileListUrl = cl.findResource( FileListResource )
    try {
      borrow( Source.fromURL( fileListUrl ) )( _.getLines().toList )
    }
    catch {
      case e : Exception => {
        throw new SbtEthereumException( s"""Failed to load expected solc installation file list, URLClassLoader URLs ${cl.getURLs.mkString("[",", ","]")}, URL '${fileListUrl}'.""", e )
      }
    }
  }

  private def fileNameToLocalFile( rootLocalCompilerDir : Path, version : String, fileName : String, cl : URLClassLoader ) : Unit = {
    val writeDir = rootLocalCompilerDir.resolve( version )
    Files.createDirectories( writeDir )

    val filePath = writeDir.resolve( fileName )
    val resourcePath = PlatformDirPrefix + "/" + fileName

    borrow( new BufferedInputStream( cl.findResource( resourcePath ).openStream() ) ) { is =>
      borrow( new BufferedOutputStream( Files.newOutputStream( filePath ) ) ) { os =>
        var b = is.read()
        while ( b >= 0 ) {
          os.write( b )
          b = is.read()
        }
      }
    }

    val file = filePath.toFile
    if ( fileName == "solc" || fileName == "solc.exe" ) {
      file.setReadable( true )
      file.setExecutable( true )

      // fails on Windows :(
      // Files.setPosixFilePermissions( filePath, Set( OWNER_READ, OWNER_EXECUTE ).asJava )
    } else {
      file.setReadable( true )

      // fails on Windows :(
      // Files.setPosixFilePermissions( filePath, Set( OWNER_READ ).asJava )
    }
  }

  def installLocalSolcJ( rootLocalCompilerDir : Path, version : String ) : Unit = {
    val url = mbVersionUrl( version ).getOrElse( throw new Exception( s"Unsupported SolcJ Version: $version" ) )
    val cl = createClassLoader( url )
    val fileNames = findSolcFileNames( cl )
    fileNames.foreach { name =>
      DEBUG.log( s"Writing solcJ compiler file, version $version: $name" )
      fileNameToLocalFile( rootLocalCompilerDir, version, name, cl )
    }
  }
}
