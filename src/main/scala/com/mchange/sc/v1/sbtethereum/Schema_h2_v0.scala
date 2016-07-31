package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.reconcile.Reconcilable
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.ethereum.EthHash
import java.io.StringReader
import java.sql.{Connection,PreparedStatement,Types}
import javax.sql.DataSource

object Schema_h2_v0 {

  def recreateSchema( dataSource : DataSource ) = {
    borrow( dataSource.getConnection() ){ conn =>
      borrow( conn.createStatement() ){ stmt =>
        stmt.executeUpdate( Table.KnownContracts.CreateSql )
        stmt.executeUpdate( CreateDeployedContracts )
      }
    }
  }

  private def codeHash( codeHex : String ) : String = EthHash.hash(codeHex.decodeHex).hex

  final object Table {
    final object KnownContracts {
      private def _select( conn : Connection, codeHash : EthHash ) : KnownContracts.CachedContract = ???

      def insert(
        conn             : Connection,
        name             : String,
        code             : String,
        source           : Option[String],
        language         : Option[String],
        languageVersion  : Option[String],
        compilerVersion  : Option[String],
        compilerOptions  : Option[String],
        abiDefinition    : Option[String],
        userDoc          : Option[String],
        developerDoc     : Option[String]
      ) {
        borrow( conn.prepareStatement( InsertSql ) ){ ps =>

          def setClob( i : Int, str : String ) = ps.setClob( i, new StringReader( str ) )
          def setClobOption( i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.CLOB ) ){ str =>  setClob( i, str ) }
          def setVarcharOption( i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.VARCHAR ) ){ str =>  ps.setString( i, str ) }

          ps.setString(     1, codeHash(code) )
          ps.setString(     2, name )
          setClob(          3, code )
          setClobOption(    4, source )
          setVarcharOption( 5, language )
          setVarcharOption( 6, languageVersion )
          setVarcharOption( 7, compilerVersion )
          setVarcharOption( 8, compilerOptions )
          setClobOption(    9, abiDefinition )
          setClobOption(   10, userDoc )
          setClobOption(   11, developerDoc )

          ps.executeUpdate()
        }
      }

      private val InsertSql = {
        """|INSERT INTO known_contracts ( code_hash, name, code, source, language, language_version, compiler_version, compiler_options, abi_definition, user_doc, developer_doc )
           |VALUES( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )""".stripMargin
      }

      private val SelectSql = {
        """|SELECT name, code, source, language, language_version, compiler_version, compiler_options, abi_definition, user_doc, developer_doc
           |FROM known_contracts""".stripMargin
      }

      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS known_contracts (
           |   code_hash         CHAR(128) PRIMARY KEY,
           |   name              VARCHAR(128) NOT NULL,
           |   code              CLOB NOT NULL,
           |   source            CLOB,
           |   language          VARCHAR(64),
           |   language_version  VARCHAR(64),
           |   compiler_version  VARCHAR(64),
           |   compiler_options  VARCHAR(256),
           |   abi_definition    CLOB,
           |   user_doc          CLOB,
           |   developer_doc     CLOB,
           |   signature         BLOB
           |)""".stripMargin
      }

      private case class CachedContract(
        name            : String,
        code            : String,
        source          : Option[String],
        language        : Option[String],
        languageVersion : Option[String],
        compilerVersion : Option[String],
        compilerOptions : Option[String],
        abiDefinition   : Option[String],
        userDoc         : Option[String],
        developerDoc    : Option[String]
      ) extends Reconcilable[CachedContract] {

        lazy val signable = {
          def n( str : String ) = s"${str.length}.${str}"
          def xo( mbstr : Option[String] ) = mbstr.fold( "-1" )( str => n(str) )

          n( codeHash( code ) ) +
          n( name ) +
          n(code) +
          xo(source) +
          xo(language) +
          xo(languageVersion) +
          xo(compilerVersion) +
          xo(compilerOptions) +
          xo(abiDefinition) +
          xo(userDoc) +
          xo(developerDoc)
        }

        def reconcile(other : CachedContract) : CachedContract = {
          CachedContract(
            Reconcilable.reconcileLeaf( this.name, other.name ),
            Reconcilable.reconcileLeaf( this.code, other.code ),
            Reconcilable.reconcileLeaf( this.source, other.source ),
            Reconcilable.reconcileLeaf( this.language, other.language ),
            Reconcilable.reconcileLeaf( this.languageVersion, other.languageVersion ),
            Reconcilable.reconcileLeaf( this.compilerVersion, other.compilerVersion ),
            Reconcilable.reconcileLeaf( this.compilerOptions, other.compilerOptions ),
            Reconcilable.reconcileLeaf( this.abiDefinition, other.abiDefinition ),
            Reconcilable.reconcileLeaf( this.userDoc, other.userDoc ),
            Reconcilable.reconcileLeaf( this.developerDoc, other.developerDoc )
          )
        }

        def reconcileOver(other : CachedContract) : CachedContract = {
          CachedContract(
            Reconcilable.reconcileOverLeaf( this.name, other.name ),
            Reconcilable.reconcileOverLeaf( this.code, other.code ),
            Reconcilable.reconcileOverLeaf( this.source, other.source ),
            Reconcilable.reconcileOverLeaf( this.language, other.language ),
            Reconcilable.reconcileOverLeaf( this.languageVersion, other.languageVersion ),
            Reconcilable.reconcileOverLeaf( this.compilerVersion, other.compilerVersion ),
            Reconcilable.reconcileOverLeaf( this.compilerOptions, other.compilerOptions ),
            Reconcilable.reconcileOverLeaf( this.abiDefinition, other.abiDefinition ),
            Reconcilable.reconcileOverLeaf( this.userDoc, other.userDoc ),
            Reconcilable.reconcileOverLeaf( this.developerDoc, other.developerDoc )
          )
        }
      }
    }
  }


  val CreateDeployedContracts = {
    """|CREATE TABLE IF NOT EXISTS deployed_contracts (
       |   code_hash        VARCHAR(128) PRIMARY KEY,
       |   deployer_address CHAR(40),
       |   txn_hash         CHAR(128),
       |   when             TIMESTAMP,
       |   FOREIGN KEY ( code_hash ) REFERENCES known_contracts( code_hash )
       |)""".stripMargin
  }


}


