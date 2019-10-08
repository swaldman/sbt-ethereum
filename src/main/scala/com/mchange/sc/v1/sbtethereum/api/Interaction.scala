package com.mchange.sc.v1.sbtethereum.api

import com.mchange.sc.v1.sbtethereum.util.{InteractiveQuery => UIQ}

import java.io.File

object Interaction {
  def queryYN( is : sbt.InteractionService, query : String ) : Boolean = UIQ.queryYN( is, query )

  def queryMandatoryGoodFile( is : sbt.InteractionService, query : String, goodFile : File => Boolean, notGoodFileRetryPrompt : File => String ) : File = {
    UIQ.queryMandatoryGoodFile( is, query, goodFile, notGoodFileRetryPrompt )
  }

  def assertReadLine( is : sbt.InteractionService, prompt : String, mask : Boolean ) : String = {
    UIQ.assertReadLine( is, prompt, mask )
  }

  /**
    * Empty String aborts, yieldng an int between (inclusive) min and max, otherwise repeatedly prompts for a good value.
    */ 
  def queryPositiveIntOrNone( is : sbt.InteractionService, query : String, min : Int, max : Int ) : Option[Int] = {
    UIQ.queryPositiveIntOrNone( is, query, min, max )
  }

  def readConfirmCredential( is : sbt.InteractionService, readPrompt : String, confirmPrompt: String = "Please retype to confirm: ", maxAttempts : Int = 3 ) : String = {
    UIQ.readConfirmCredential( is, readPrompt, confirmPrompt, maxAttempts )
  }

  def aborted( msg : String ) : Nothing = UIQ.aborted( msg )
}
