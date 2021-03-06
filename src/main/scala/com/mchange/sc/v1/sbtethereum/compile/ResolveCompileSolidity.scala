package com.mchange.sc.v1.sbtethereum.compile

import java.io.{BufferedOutputStream, File, FileOutputStream, OutputStreamWriter}
import java.nio.file.Files
import scala.annotation.tailrec
import scala.collection._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.{Codec, Source}
import scala.math.max
import scala.util.matching.Regex.Match
import com.mchange.sc.v3.failable._
import com.mchange.sc.v3.failable.logging._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.literal._
import com.mchange.sc.v2.concurrent._
import com.mchange.sc.v1.consuela.ethereum.jsonrpc
import jsonrpc.MapStringCompilationContractFormat
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.log.MLogger
import play.api.libs.json.Json
import sbt._ // for ** operator on File

object ResolveCompileSolidity {

  private implicit lazy val logger: MLogger = mlogger( this )

  private val SolFileRegex = """(.+)\.sol""".r

  private val SolidityFileBadFirstChars = ".#~"

  private val ImportRegex = """import\s+(.*)\;""".r
  private val GoodImportBodyRegex = """\s*(?:\042(.*?)\042|\047(.*?)\047)\s*""".r

  // XXX: hardcoded
  private val SolidityWriteBufferSize = 1024 * 1024; // 1 MiB

  private def loadResolveSourceFile( allSourceLocations : Seq[SourceFile.Location], key : String ) : Failable[SourceFile] = {

    // yuk, but doing this more functionally, via immutable arguments, prevents duplicates only down a single
    // subtree of the recursive replacemnt, and i can't figure out an elegant way to prevent duplicates "globally",
    // so this.

    // MT: local var referring to an unshared (although mutable) object. naturally single-threaded
    val visitedRawText = mutable.HashSet.empty[String]

    def safeComment( text : String ) = {
      val safeText = text.replaceAll( """\*\/""", """*.../""" )
      s"""${SEP}/* ${safeText} */${SEP}"""
    }

    @tailrec
    def loadResolve( localKey : String, remainingSourceLocations : Seq[SourceFile.Location] ) : Failable[SourceFile] = {
      if ( remainingSourceLocations.isEmpty ){
        Failable.fail( s"""Could not resolve file for '$localKey', checked source locations: '${allSourceLocations.mkString(", ")}'""" )
      } else {
        val nextSrcLoc = remainingSourceLocations.head
        def premessage( from : String = nextSrcLoc.toString ) = s"Failed to load '$localKey' from '$from': "
        val fsource = Failable( SourceFile( nextSrcLoc, localKey ) ).xdebug( premessage() )
        if ( fsource.isFailed ) {
          loadResolve( localKey, remainingSourceLocations.tail )
        } else {
          val sourceFile = fsource.get
          val rawText = sourceFile.rawText
          if ( !visitedRawText( rawText ) ) {
            visitedRawText += rawText
            substituteImports( sourceFile )
          }
          else {
            Failable.succeed( SourceFile( SourceFile.Location.Empty, safeComment(s"Skipping duplicate text via import from '${nextSrcLoc}' with key '${localKey}'."), Long.MinValue ) )
          }
        }
      }
    }

    def substituteImports( input : SourceFile ) : Failable[SourceFile] = {

      def doubleQuote( contents : String ) = "\"" + contents + "\""

      // local reference to immutable object, naturally single-threaded
      var lastModified : Long = input.lastModified

      val ( normalized, tcq ) = TextCommentQuote.parse( input.rawText )

      // we will let this function have side effects, updates lastModified...
      def replaceMatch( m : Match ) : String = {
        if ( tcq.quote.containsPoint( m.start ) ) {          // if the word import is in a quote
          m.group(0)                                         //   replace the match with itself
        } else if ( tcq.comment.containsPoint( m.start ) ) { // if the word import is in a comment
          m.group(0)                                         //   replace the match with itself
        } else {                                             // otherwise, do the replacement
          m.group(1) match {
            case GoodImportBodyRegex( ifDoubleQuoted, ifSingleQuoted ) => {
              val imported = if ( ifDoubleQuoted != null ) doubleQuote( ifDoubleQuoted ) else doubleQuote( ifSingleQuoted ) // normalize to double-quoted string
              val key = StringLiteral.parsePermissiveStringLiteral( imported ).parsed
              val newAllSourceLocations = input.immediateParent +: allSourceLocations // look first local to this file to resolve recursive imports
              val fimport = loadResolve( key, newAllSourceLocations )
              val sourceFile = fimport.get // throw the Exception if resolution failed
              lastModified = max( lastModified, sourceFile.lastModified )

              val srcLoc = sourceFile.immediateParent
              val srcBegin = safeComment( s"Importing from '${srcLoc}' with key '${key}'." )
              val srcEnd = safeComment( s"End importing from '${srcLoc}' with key '${key}'." )
              srcBegin + sourceFile.rawText.trim + srcEnd
            }
            case unkey => throw new Exception( s"""Unsupported import format: '$unkey' [sbt-ethereum supports only simple 'import "<filespec>"', without 'from' or 'as' clauses.]""" )
          }
        }
      }

      val resolved = ImportRegex.replaceAllIn( normalized, replaceMatch _ )

      Failable.succeed( SourceFile( input.immediateParent, resolved, lastModified ) )
    }

    loadResolve( key, allSourceLocations )
  }

  private def loadResolveSourceFile( file : File, includeLocations : Seq[SourceFile.Location] = Nil ) : Failable[SourceFile] = {
    loadResolveSourceFile( SourceFile.Location( file.getParentFile ) +: includeLocations, file.getName )
  }


  def goodSolidityFileName( simpleName : String ) : Boolean =  simpleName.endsWith(".sol") && SolidityFileBadFirstChars.indexOf( simpleName.head ) < 0

  def doResolveCompile(
    log : sbt.Logger,
    compiler : Compiler.Solidity,
    optimizerRuns : Option[Int],
    includeSourceLocations : Seq[SourceFile.Location],
    solSourceDir : File,
    solDestDir : File
  )( implicit ec : ExecutionContext ) : Unit = {

    def solToJson( filename : String ) : String = filename match {
      case SolFileRegex( base ) => base + ".json"
    }

    // TODO XXX: check imported files as well!
    def changed( destinationFile : File, sourceFile : SourceFile ) : Boolean = (! destinationFile.exists) || (sourceFile.lastModified > destinationFile.lastModified() )

    def waitForFiles[T]( files : Iterable[(File,Future[T])], errorMessage : Int => String ) : Unit = {
      val labeledFailures = awaitAndGatherLabeledFailures( files )
      val failureCount = labeledFailures.size
      if ( failureCount > 0 ) {
        log.error( errorMessage( failureCount ) )
        labeledFailures.foreach {
          case ( file, info ) => log.error( s"File: ${file.getAbsolutePath}${SEP}${info}" )
        }
        throw labeledFailures.head._2
      }
    }

    solDestDir.mkdirs()
    val files = (solSourceDir ** "*.sol").get.filter( file => goodSolidityFileName( file.getName ) )

    val filePairs = files.map { file =>
       // ( rawSourceFile, sourceFile, destinationFile, debugDestinationFile ), exception if file can't load
      ( file, loadResolveSourceFile( file, includeSourceLocations ).get, new File( solDestDir, solToJson( file.getName ) ), new File( solDestDir, file.getName ) )
    }

    val compileFiles = filePairs.filter { case ( _, sourceFile, destFile, _ ) => changed( destFile, sourceFile ) }

    val cfl = compileFiles.length
    if ( cfl > 0 ) {
      val mbS = if ( cfl > 1 ) "s" else ""
      log.info( s"Compiling ${compileFiles.length} Solidity source$mbS to $solDestDir..." )

      val compileLabeledFuts = compileFiles.map { case ( file, sourceFile, destFile, debugDestFile ) =>
        val combinedSource = {
          s"""|/*
              | * DO NOT EDIT! DO NOT EDIT! DO NOT EDIT!
              | *
              | * This is an automatically generated file. It will be overwritten.
              | *
              | * For the original source see
              | *    '${file.getPath}'
              | */
              |
              |""".stripMargin + sourceFile.resolvedText
        }
        Files.write( debugDestFile.toPath, combinedSource.getBytes( Codec.UTF8.charSet ) )
        log.info( s"Compiling '${ file.getName }'. (Debug source: '${ debugDestFile.getPath }')" )
        file -> compiler.compile( log, optimizerRuns, combinedSource, Some( debugDestFile.lastModified ) ).map( result => ( destFile, result ) )
      }
      waitForFiles( compileLabeledFuts, count => s"compileSolidity failed. [${count} failures]" )

      // if we're here, all compilations succeeded
      val destFileResultPairs = compileLabeledFuts.map {
        case ( _, fut ) => fut.value.get.get
      }

      val writerLabeledFuts = destFileResultPairs.map {
        case ( destFile, result ) => {
          destFile -> Future {
            borrow( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( destFile ), SolidityWriteBufferSize ), Codec.UTF8.charSet ) ) {
              _.write( Json.stringify( Json.toJson ( result ) ) )
            }
          }
        }
      }
      waitForFiles( writerLabeledFuts, count => s"Failed to write the output of some compilations. [${count} failures]" )
    }
  }
}
