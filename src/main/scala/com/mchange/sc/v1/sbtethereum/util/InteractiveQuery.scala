package com.mchange.sc.v1.sbtethereum.util

import java.io.File

import com.mchange.sc.v1.sbtethereum._

import scala.annotation.tailrec
import scala.language.higherKinds

private [sbtethereum]
object InteractiveQuery {
  private type I[T] = T

  @tailrec
  private def _queryGoodFile[F[_]]( is : sbt.InteractionService, wrap : File => F[File] )( query : String, goodFile : File => Boolean, notGoodFileRetryPrompt : String, noEntryDefault : => F[File] ) : F[File] = {
    val filepath = is.readLine( query, mask = false).getOrElse( throw new SbtEthereumException( CantReadInteraction ) ).trim
    if ( filepath.nonEmpty ) {
      val file = new File( filepath ).getAbsoluteFile()
      if (goodFile(file)) {
        println( notGoodFileRetryPrompt )
        _queryGoodFile( is, wrap )( query, goodFile, notGoodFileRetryPrompt, noEntryDefault )
      }
      else {
        wrap( file )
      }
    }
    else {
      noEntryDefault
    }
  }

  private [sbtethereum]
  def queryOptionalGoodFile( is : sbt.InteractionService, query : String, goodFile : File => Boolean, notGoodFileRetryPrompt : String ) : Option[File] = {
    _queryGoodFile[Option]( is, Some(_) )( query, goodFile, notGoodFileRetryPrompt, None )
  }


  private [sbtethereum]
  def queryMandatoryGoodFile( is : sbt.InteractionService, query : String, goodFile : File => Boolean, notGoodFileRetryPrompt : String ) : File = {
    _queryGoodFile[I]( is, identity )( query, goodFile, notGoodFileRetryPrompt, queryMandatoryGoodFile( is, query, goodFile, notGoodFileRetryPrompt ) )
  }

  private [sbtethereum]
  def queryYN( is : sbt.InteractionService, query : String ) : Boolean = {
    def prompt = is.readLine( query, mask = false ).get
    def doPrompt : Boolean = {
      def redo = {
        println( "Please enter 'y' or 'n'." )
        doPrompt
      }
      prompt.trim().toLowerCase match {
        case ""          => redo
        case "y" | "yes" => true
        case "n" | "no"  => false
        case _           => redo
      }
    }
    doPrompt
  }

  private [sbtethereum]
  def queryIntOrNone( is : sbt.InteractionService, query : String, min : Int, max : Int ) : Option[Int] = {
    require( min >= 0, "Implementation limitation, only positive numbers are supported for now." )
    require( max >= min, s"max ${max} cannot be smaller than min ${min}." )

    // -1 could not be interpreted as Int, None means empty String
    // this is why we don't support negatives, -1 is out-of-band
    def fetchNum : Option[Int] = { 
      val line = is.readLine( query, mask = false ).getOrElse( throw new SbtEthereumException( CantReadInteraction ) ).trim
      if ( line.isEmpty ) {
        None
      }
      else {
        try {
          Some( line.toInt )
        }
        catch {
          case nfe : NumberFormatException => {
            println( s"Bad entry... '${line}'. Try again." )
            Some(-1)
          }
        }
      }
    }

    def checkRange( num : Int ) = {
      if ( num < min || num > max ) {
        println( s"${num} is out of range. Try again." )
        false
      }
      else {
        true
      }
    }

    @tailrec
    def doFetchNum : Option[Int] = {
      fetchNum match {
        case Some(-1)                          => doFetchNum
        case Some( num ) if !checkRange( num ) => doFetchNum
        case ok                                => ok
      }
    }

    doFetchNum
  }
}
