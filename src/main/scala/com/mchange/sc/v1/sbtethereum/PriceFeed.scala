package com.mchange.sc.v1.sbtethereum

import scala.collection._
import scala.concurrent.Await
import scala.concurrent.duration._

import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.ethereum.EthAddress

import com.mchange.sc.v2.concurrent.Scheduler

import play.api.libs.json._

import java.net.URL

object PriceFeed {
  final case class Datum( price : Double, timestamp : Long = System.currentTimeMillis() )
  final object Coinbase {
    val SupportedCurrencies = immutable.Vector( "USD", "EUR" )
  }
  class Coinbase( updatePeriod : Duration)( implicit scheduler : Scheduler ) extends PriceFeed {
    // MT: protected by this' lock
    private val cachedEthPrices = mutable.HashMap.empty[String,Datum]

    private def updateEthPrice( currencyCode : String ) : Unit = {
      val url = new URL( s"https://api.coinbase.com/v2/prices/ETH-${currencyCode}/spot" )

      val jsObj = borrow( url.openStream() )( Json.parse )

      // XXX: Add logging of what-went-wrong
      val mbDatum = {
        jsObj \ "data" \ "amount" match {
          case JsDefined( JsString( amountStr ) ) => Some( Datum( amountStr.toDouble ) )
          case _                                  => None
        }
      }

      this.synchronized {
        mbDatum match {
          case Some( datum ) => {
            cachedEthPrices += Tuple2( currencyCode, datum )
          }
          case None => {
            cachedEthPrices -= currencyCode
          }
        }
      }
    }
    private def updateEthPrices() : Unit = Coinbase.SupportedCurrencies.foreach( updateEthPrice )

    private val scheduled = scheduler.scheduleWithFixedDelay( () => updateEthPrices(), 0.seconds, updatePeriod )

    override def ethPriceInCurrency( chainId : Int, currencyCode : String, forceRefresh : Boolean = false ) : Option[Datum] = {
      if ( chainId == 1 ) { // Coinbase ETH prices are mainnet only
        this.synchronized {
          if (forceRefresh) updateEthPrice( currencyCode )
          cachedEthPrices.get( currencyCode )
        }
      }
      else {
        None
      }
    }

    val source = "Coinbase"

    def close() : Unit = {
      this.synchronized {
        scheduled.attemptCancel()
        Await.ready( scheduled.future, Duration.Inf )
        cachedEthPrices.clear()
      }
    }
  }
}
trait PriceFeed {
  def ethPriceInCurrency( chainId : Int, currencyCode : String, forceRefresh : Boolean = false ) : Option[PriceFeed.Datum] = None
  def tokenPriceInCurrency( chainId : Int, tokenSymbol : String, tokenAddress : EthAddress, currencyCode : String, forceRefresh : Boolean = false ) : Option[PriceFeed.Datum] = None
  def source : String
  def close() : Unit
}
