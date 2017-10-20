package com.mchange.sc.v1.sbtethereum.compile

import java.io._
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermission._
import java.net.{URL, URLClassLoader}
import scala.collection._
import JavaConverters._
import scala.io.Source
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.log.MLogger
import com.mchange.sc.v2.util.Platform
import com.mchange.sc.v2.lang.borrow
import scala.collection.immutable.TreeSet

object SolcJInstaller {
  private lazy implicit val logger: MLogger = mlogger( this )

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

  val SupportedVersions: TreeSet[String] = TreeSet( /* "0.4.3", "0.4.4", "0.4.6", */ "0.4.7", "0.4.8", "0.4.10" )( Ordering.by( SemanticVersion( _ ) ) ) // only metadata supporting versions

  private def createJarUrl( version : String ) : URL =
    new URL( s"https://dl.bintray.com/ethereum/maven/org/ethereum/solcJ-all/$version/solcJ-all-$version.jar" )

  private def createClassLoader( version : String ) : URLClassLoader =
    new URLClassLoader( Array( createJarUrl( version ) ) )

  private def findSolcFileNames( cl : URLClassLoader ) : immutable.Seq[String] = {
    val fileListUrl = cl.findResource( FileListResource )
    borrow( Source.fromURL( fileListUrl ) )( _.close )( _.getLines().toList )
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

    if ( fileName == "solc" ) {
      Files.setPosixFilePermissions( filePath, Set( OWNER_READ, OWNER_EXECUTE ).asJava )
    } else {
      Files.setPosixFilePermissions( filePath, Set( OWNER_READ ).asJava )
    }
  }

  def installLocalSolcJ( rootLocalCompilerDir : Path, version : String ) : Unit = {
    if ( ! SupportedVersions( version ) )
      throw new Exception( s"Unsupported SolcJ Version: $version" )

    val cl = createClassLoader( version )
    val fileNames = findSolcFileNames( cl )
    fileNames.foreach { name =>
      DEBUG.log( s"Writing solcJ compiler file, version $version: $name" )
      fileNameToLocalFile( rootLocalCompilerDir, version, name, cl )
    }
  }
}
