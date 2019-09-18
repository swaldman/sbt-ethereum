package com.mchange.sc.v1.sbtethereum.util

import java.io.File
import java.time.{Duration => JDuration}
import java.time.temporal.ChronoUnit

import com.mchange.sc.v1.sbtethereum._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthTransaction}
import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256

import com.mchange.sc.v3.failable._

import Parsers._

import scala.annotation.tailrec
import scala.language.higherKinds

private [sbtethereum]
object InteractiveQuery {
  private type I[T] = T

  @tailrec
  private def _queryGoodFile[F[_]]( is : sbt.InteractionService, wrap : File => F[File] )( query : String, goodFile : File => Boolean, notGoodFileRetryPrompt : File => String, noEntryDefault : => F[File] ) : F[File] = {
    val filepath = is.readLine( query, mask = false).getOrElse( throwCantReadInteraction ).trim
    if ( filepath.nonEmpty ) {
      val file = new File( filepath ).getAbsoluteFile()
      if (!goodFile(file)) {
        println( notGoodFileRetryPrompt( file ) )
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
  def queryOptionalGoodFile( is : sbt.InteractionService, query : String, goodFile : File => Boolean, notGoodFileRetryPrompt : File => String ) : Option[File] = {
    _queryGoodFile[Option]( is, Some(_) )( query, goodFile, notGoodFileRetryPrompt, None )
  }


  private [sbtethereum]
  def queryMandatoryGoodFile( is : sbt.InteractionService, query : String, goodFile : File => Boolean, notGoodFileRetryPrompt : File => String ) : File = {
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

  /**
    * Empty String aborts, yieldng an int between (inclusive) min and max, otherwise repeatedly prompts for a good value.
    */ 
  private [sbtethereum]
  def queryPositiveIntOrNone( is : sbt.InteractionService, query : String, min : Int, max : Int ) : Option[Int] = {
    require( min >= 0, "Only positive numbers are supported." )
    require( max >= min, s"max ${max} cannot be smaller than min ${min}." )

    // -1 could not be interpreted as Int, None means empty String
    // this is why we don't support negatives, -1 is out-of-band
    def fetchNum : Option[Int] = { 
      val line = is.readLine( query, mask = false ).getOrElse( throwCantReadInteraction ).trim
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
            Some(Int.MinValue) // omits the range check warning
          }
        }
      }
    }

    def checkRange( num : Int ) = {
      if ( num < min || num > max ) {
        println( s"${num} is out of range [${min},${max}]. Try again." )
        false
      }
      else {
        true
      }
    }

    @tailrec
    def doFetchNum : Option[Int] = {
      fetchNum match {
        case Some(Int.MinValue)                => doFetchNum // omits the range check warning
        case Some( num ) if !checkRange( num ) => doFetchNum
        case None                              => None
        case ok                                => ok
      }
    }

    doFetchNum
  }

  private [sbtethereum]
  def throwCantReadInteraction : Nothing = throw new CantReadInteractionException

  private [sbtethereum]
  def notConfirmedByUser = new OperationAbortedByUserException( "Not confirmed by user." )

  private [sbtethereum]
  def assertReadLine( is : sbt.InteractionService, prompt : String, mask : Boolean ) : String = {
    is.readLine( prompt, mask ).getOrElse( throwCantReadInteraction )
  }

  private val AmountInWeiParser = valueInWeiParser("<amount>")

  private [sbtethereum]
  def assertReadOptionalAmountInWei( log : sbt.Logger, is : sbt.InteractionService, prompt : String, mask : Boolean ) : Option[BigInt] = {

    @tailrec
    def doRead : Option[BigInt] = {
      val check = assertReadLine( is, prompt, mask ).trim
      if ( check.nonEmpty ) {
        sbt.complete.Parser.parse( check, AmountInWeiParser ) match {
          case Left( oops ) => {
            log.error( s"Parse failure: ${oops}" )
            println("""Please enter an integral amount, then a space, then a unit. For example, "5 gwei" or "10 ether".""")
            println("""Supported units: wei, gwei, szabo, finney, ether""");
            doRead
          }
          case Right( amountInWei ) => Some( amountInWei )
        }
      }
      else {
        None
      }
    }

    doRead
  }

  private [sbtethereum]
  def assertReadOptionalBigInt( log : sbt.Logger, is : sbt.InteractionService, prompt : String, mask : Boolean ) : Option[BigInt] = {

    @tailrec
    def doRead : Option[BigInt] = {
      val check = assertReadLine( is, prompt, mask ).trim
      if ( check.nonEmpty ) {
        val fbi = Failable( BigInt( check ) )
        if( fbi.isSucceeded ) {
          Some( fbi.assert )
        }
        else {
          log.warn( s"Invalid integral value '${check}'. Try again." )
          doRead
        }
      }
      else {
        None
      }
    }

    doRead
  }

  private [sbtethereum]
  def aborted( msg : String ) : Nothing = throw new OperationAbortedByUserException( msg )

  private [sbtethereum]
  def readCredential( is : sbt.InteractionService, address : EthAddress, acceptHexPrivateKey : Boolean = true ) : String = {
    val hpkPart = if (acceptHexPrivateKey) " or hex private key" else ""
    is.readLine(s"Enter passphrase${hpkPart} for address '0x${address.hex}': ", mask = true).getOrElse(throw new Exception("Failed to read a credential")) // fail if we can't get a credential
  }

  private [sbtethereum]
  def readConfirmCredential( log : sbt.Logger, is : sbt.InteractionService, readPrompt : String, confirmPrompt: String = "Please retype to confirm: ", maxAttempts : Int = 3, attempt : Int = 0 ) : String = {
    if ( attempt < maxAttempts ) {
      val credential = is.readLine( readPrompt, mask = true ).getOrElse( throwCantReadInteraction )
      val confirmation = is.readLine( confirmPrompt, mask = true ).getOrElse( throwCantReadInteraction )
      if ( credential == confirmation ) {
        credential
      } else {
        log.warn("Entries did not match! Retrying.")
        readConfirmCredential( log, is, readPrompt, confirmPrompt, maxAttempts, attempt + 1 )
      }
    } else {
      throw new Exception( s"After ${attempt} attempts, provided credential could not be confirmed. Bailing." )
    }
  }

  private [sbtethereum]
  def queryDurationInSeconds( log : sbt.Logger, is : sbt.InteractionService, readPrompt : String ) : Option[DurationParsers.SecondsViaUnit] = {

    @tailrec
    def doRead : Option[DurationParsers.SecondsViaUnit] = {
      val entry = assertReadLine( is, readPrompt, mask = false ).trim
      if ( entry.nonEmpty ) {
        sbt.complete.Parser.parse( entry, DurationParsers.DurationInSecondsParser ) match {
          case Left( oops ) => {
            log.error( s"Parse failure: ${oops}" )
            println("""Please enter an integral amount, then a space, then a unit. For example, "10 seconds" or "5 years".""")
            println("""Supported units: """ + DurationParsers.AllUnits.mkString(", ") )
            doRead
          }
          case Right( secondsViaUnit ) => Some( secondsViaUnit )
        }
      }
      else {
        None
      }
    }

    doRead
  }

  // not currently used
  private [sbtethereum]
  def interactiveQueryUnsignedTransaction( is : sbt.InteractionService, log : sbt.Logger ) : EthTransaction.Unsigned = {
    def queryEthAmount( query : String, nonfunctionalTabHelp : String ) : BigInt = {
      val raw = is.readLine( query, mask = false).getOrElse( throwCantReadInteraction ).trim
      try {
        BigInt(raw)
      }
      catch {
        case nfe : NumberFormatException => {
          sbt.complete.Parser.parse( raw, valueInWeiParser( nonfunctionalTabHelp ) ) match {
            case Left( errorMessage )          => throw new SbtEthereumException( s"Failed to parse amount of ETH to send. Error Message: '${errorMessage}'" )
            case Right( amount ) => amount
          }
        }
      }
    }
    val nonce = {
      val raw = is.readLine( "Nonce: ", mask = false).getOrElse( throwCantReadInteraction ).trim
      BigInt(raw)
    }
    val gasPrice = queryEthAmount( "Gas price (as wei, or else number and unit): ", "<gas-price>" )
    val gasLimit = {
      val raw = is.readLine( "Gas Limit: ", mask = false).getOrElse( throwCantReadInteraction ).trim
      BigInt(raw)
    }
    val to = {
      val raw = is.readLine( "To (as hex address): ", mask = false).getOrElse( throwCantReadInteraction ).trim
      if ( raw.nonEmpty ) Some( EthAddress( raw ) ) else None
    }
    if ( to == None ) log.warn( "No 'To:' address specified. This is a contract creation transaction!" )
    val amount = queryEthAmount( "ETH to send (as wei, or else number and unit): ", "<eth-to-send>" )
    val data = {
      val raw = is.readLine( "Data / Init (as hex string): ", mask = false).getOrElse( throwCantReadInteraction ).trim
      raw.decodeHexAsSeq
    }
    to match {
      case Some( recipient ) => EthTransaction.Unsigned.Message( nonce=Unsigned256( nonce ), gasPrice=Unsigned256(gasPrice), gasLimit=Unsigned256(gasLimit), to=recipient, value=Unsigned256(amount), data=data )
      case None              => EthTransaction.Unsigned.ContractCreation( nonce=Unsigned256( nonce ), gasPrice=Unsigned256(gasPrice), gasLimit=Unsigned256(gasLimit), value=Unsigned256(amount), init=data )
    }
  }
}
