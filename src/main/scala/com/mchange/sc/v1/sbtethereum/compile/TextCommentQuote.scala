package com.mchange.sc.v1.sbtethereum.compile

import continuum._

object TextCommentQuote {
  sealed trait State
  case object InText               extends State
  case object InQuote              extends State
  case object InQuoteBackslash     extends State
  case object AfterSlash           extends State
  case object InCStyleComment      extends State
  case object InCStyleCommentSplat extends State
  case object InDoubleSlashComment extends State

  private val AnyNewLine = """(?:\r(?:\n)?)|\n""".r

  val empty = TextCommentQuote( IntervalSet.empty[Int], IntervalSet.empty[Int], IntervalSet.empty[Int] )

  /** @return as `_1` the original source string with its line breaks normalized to '\n',
   *  which is the version of the `String` that corresponds to `_2` */
  def parse( source : String )  : ( String, TextCommentQuote ) = {
    def normalizeLineSeparators( input : String ) : String = AnyNewLine.replaceAllIn(input, "\n")

    val input = normalizeLineSeparators( source )
    val Len = input.length

    def i( start : Int, end : Int ) = Interval.closedOpen( start, end )

    def addText( tcq : TextCommentQuote, start : Int, end : Int )    = if ( end > start ) tcq.copy( text    = tcq.text    + i(start, end) ) else tcq
    def addComment( tcq : TextCommentQuote, start : Int, end : Int ) = if ( end > start ) tcq.copy( comment = tcq.comment + i(start, end) ) else tcq
    def addQuote( tcq : TextCommentQuote, start : Int, end : Int )   = if ( end > start ) tcq.copy( quote   = tcq.quote   + i(start, end) ) else tcq

    def _parse( index : Int, state : State, line : Int, col : Int, sectionBegin : Int, accum : TextCommentQuote ) : TextCommentQuote = {
      ( index, state ) match {
        case ( Len, InText | AfterSlash )                    => accum.copy( text = accum.text + i( sectionBegin, Len ) )
        case ( Len, InDoubleSlashComment )                   => accum.copy( comment = accum.comment + i( sectionBegin, Len ) )
        case ( Len, InQuote | InQuoteBackslash )             => throw new UnparsableFileException( "Unterminated quote at EOF", line, col )
        case ( Len, InCStyleComment | InCStyleCommentSplat ) => throw new UnparsableFileException( "Unterminated comment at EOF", line, col )
        case _ =>
          val next = input( index )
          ( state, next ) match {
            case ( InText,               '\042' ) => _parse( index + 1,              InQuote, line    , col + 1,        index, addText(accum, sectionBegin, index) )
            case ( InText,                  '/' ) => _parse( index + 1,           AfterSlash, line    , col + 1, sectionBegin, accum )
            case ( InText,                 '\n' ) => _parse( index + 1,               InText, line + 1,       1, sectionBegin, accum )
            case ( InText,                    _ ) => _parse( index + 1,               InText, line    , col + 1, sectionBegin, accum )
            case ( AfterSlash,              '/' ) => _parse( index + 1, InDoubleSlashComment, line    , col + 1,    index - 1, addText( accum, sectionBegin, index - 1) )
            case ( AfterSlash,              '*' ) => _parse( index + 1,      InCStyleComment, line    , col + 1,    index - 1, addText( accum, sectionBegin, index - 1) )
            case ( AfterSlash,             '\n' ) => _parse( index + 1,               InText, line + 1,       1, sectionBegin, accum )
            case ( AfterSlash,                _ ) => _parse( index + 1,               InText, line    , col + 1, sectionBegin, accum )
            case ( InQuote,              '\042' ) => _parse( index + 1,               InText, line    , col + 1,    index + 1, addQuote( accum, sectionBegin, index + 1) )
            case ( InQuote,                '\n' ) => throw new UnparsableFileException("Unterminated quote", line, col)
            case ( InQuote,                '\\' ) => _parse( index + 1,     InQuoteBackslash, line    , col + 1, sectionBegin, accum )
            case ( InQuote,                   _ ) => _parse( index + 1,              InQuote, line    , col + 1, sectionBegin, accum )
            case ( InQuoteBackslash,       '\n' ) => throw new UnparsableFileException("Unterminated quote (after backslash)", line, col)
            case ( InQuoteBackslash,          _ ) => _parse( index + 1,              InQuote, line    , col + 1, sectionBegin, accum )
            case ( InCStyleComment,         '*' ) => _parse( index + 1, InCStyleCommentSplat, line    , col + 1, sectionBegin, accum )
            case ( InCStyleComment,        '\n' ) => _parse( index + 1,      InCStyleComment, line + 1,       1, sectionBegin, accum )
            case ( InCStyleComment,           _ ) => _parse( index + 1,      InCStyleComment, line    , col + 1, sectionBegin, accum )
            case ( InCStyleCommentSplat,    '/' ) => _parse( index + 1,               InText, line    , col + 1,    index + 1, addComment( accum, sectionBegin, index + 1) )
            case ( InCStyleCommentSplat,   '\n' ) => _parse( index + 1,      InCStyleComment, line + 1, col    , sectionBegin, accum )
            case ( InCStyleCommentSplat,      _ ) => _parse( index + 1,      InCStyleComment, line    , col + 1, sectionBegin, accum )
            case ( InDoubleSlashComment,   '\n' ) => _parse( index + 1,               InText, line + 1,       1,    index + 1, addComment( accum, sectionBegin, index + 1) )
            case ( InDoubleSlashComment,      _ ) => _parse( index + 1, InDoubleSlashComment, line    , col + 1, sectionBegin, accum )
          }
      }
    }

    val tcq = _parse( 0, InText, 1, 1, 0, TextCommentQuote.empty )
    ( input, tcq )
  }
}

case class TextCommentQuote( text : IntervalSet[Int], comment : IntervalSet[Int], quote : IntervalSet[Int] )
