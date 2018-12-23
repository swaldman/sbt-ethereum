package com.mchange.sc.v1.sbtethereum

package object lib {
  implicit val EthAddressFormat     = util.SJsonNewFormats.EthAddressIso
  implicit val EthHashFormat        = util.SJsonNewFormats.EthAddressIso
  implicit val RichParserInfoFormat = util.SJsonNewFormats.RichParserInfoFormat

  type RichParserInfo = com.mchange.sc.v1.sbtethereum.RichParserInfo
}
