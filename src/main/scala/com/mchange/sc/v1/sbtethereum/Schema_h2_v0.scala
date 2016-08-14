package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.reconcile.{Reconcilable,CantReconcileException}
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthHash}
import com.mchange.sc.v2.sql.getMaybeSingleString
import com.mchange.sc.v1.log.MLevel._

import java.io.StringReader
import java.sql.{Connection,PreparedStatement,Types,Timestamp}

import javax.sql.DataSource

object Schema_h2_v0 {

  private implicit lazy val logger = mlogger( this )

  def setClob( ps : PreparedStatement, i : Int, str : String ) = ps.setClob( i, new StringReader( str ) )
  def setClobOption( ps : PreparedStatement, i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.CLOB ) ){ str =>  setClob( ps, i, str ) }
  def setVarcharOption( ps : PreparedStatement, i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.VARCHAR ) ){ str =>  ps.setString( i, str ) }
  def setTimestampOption( ps : PreparedStatement, i : Int, mbl : Option[Long] ) = mbl.fold( ps.setNull( i, Types.TIMESTAMP ) ){ l =>  ps.setTimestamp( i, new Timestamp(l) ) }

  val SchemaVersion = 0

  def ensureSchema( dataSource : DataSource ) = {
    borrow( dataSource.getConnection() ){ conn =>
      borrow( conn.createStatement() ){ stmt =>
        stmt.executeUpdate( Table.Metadata.CreateSql )
        stmt.executeUpdate( Table.KnownContracts.CreateSql )
        stmt.executeUpdate( Table.DeployedContracts.CreateSql )
      }
      Table.Metadata.ensureSchemaVersion( conn )
    }
  }

  private def codeHash( codeHex : String ) : EthHash = EthHash.hash(codeHex.decodeHex)

  final object Table {
    final object Metadata {
      val CreateSql = "CREATE TABLE IF NOT EXISTS metadata ( key VARCHAR(64) PRIMARY KEY, value VARCHAR(64) NOT NULL )"

      def ensureSchemaVersion( conn : Connection ) : Unit = {
        val currentVersion = select( conn, Key.SchemaVersion )
        currentVersion.fold( insert( conn, Key.SchemaVersion, SchemaVersion.toString ) ){ versionStr =>
          val v = versionStr.toInt
          if ( v != 0 ) throw new DatabaseVersionException( s"Expected version 0, found version ${v}, cannot migrate." )
        }
      }

      def insert( conn : Connection, key : String, value : String ) : Unit = {
        borrow( conn.prepareStatement( "INSERT INTO metadata ( key, value ) VALUES ( ?, ? )" ) ) { ps =>
          ps.setString( 1, key )
          ps.setString( 2, value )
          ps.executeUpdate()
        }
      }

      def select( conn : Connection, key : String ) : Option[String] = {
        borrow( conn.prepareStatement( "SELECT value FROM metadata WHERE key = ?" ) ) { ps =>
          ps.setString(1, key)
          borrow( ps.executeQuery() )( getMaybeSingleString )
        }
      }

      final object Key {
        val SchemaVersion = "SchemaVersion"
      }
    }

    final object KnownContracts {

      private def delete( conn : Connection, codeHash : EthHash ) : Int = {
        borrow( conn.prepareStatement( DeleteSql ) ) { ps =>
          ps.setString(1, codeHash.hex)
          ps.executeUpdate()
        }
      }

      private def _select( conn : Connection, codeHash : EthHash ) : Option[KnownContracts.CachedContract] = {
        borrow( conn.prepareStatement( SelectSql ) ) { ps =>
          ps.setString(1, codeHash.hex)
          borrow( ps.executeQuery ) { rs =>
            if ( rs.next() ) {
              val ok = CachedContract(
                rs.getString(1),
                Option( rs.getString( 2) ),
                Option( rs.getString( 3) ),
                Option( rs.getString( 4) ),
                Option( rs.getString( 5) ),
                Option( rs.getString( 6) ),
                Option( rs.getString( 7) ),
                Option( rs.getString( 8) ),
                Option( rs.getString( 9) ),
                Option( rs.getString(10) )
              )
              assert(!rs.next(), s"Huh? More than one row with the same codeHash in known_contracts? [codeHash=${codeHash.hex}]" )
              Some( ok )
            } else {
              None
            }
          }
        }
      }

      def createUpdateKnownContract( 
        conn             : Connection,
        code             : String,
        name             : Option[String],
        source           : Option[String],
        language         : Option[String],
        languageVersion  : Option[String],
        compilerVersion  : Option[String],
        compilerOptions  : Option[String],
        abiDefinition    : Option[String],
        userDoc          : Option[String],
        developerDoc     : Option[String],
        policy           : IrreconcilableUpdatePolicy = Throw
      ) : Boolean = {
        val ch = codeHash(code)
        val mbAlreadyKnownContract = _select( conn, ch )
        mbAlreadyKnownContract.fold( merge( conn, code, name, source, language, languageVersion, compilerVersion, compilerOptions, abiDefinition, userDoc, developerDoc ) ) { akc =>
          val newc = CachedContract( code, name, source, language, languageVersion, compilerVersion, compilerOptions, abiDefinition, userDoc, developerDoc )
          try {
            val next = newc reconcile akc
            akc != next
          } catch { 
            case cre : CantReconcileException => { // we know newc and akc are different, or the reconciliztion would have succeeded
              DEBUG.log( s"Attempt to reconcile ${newc} with ${akc} failed.", cre )
              policy match {
                case UseOriginal => {
                  /* nothing to do */
                  false
                }
                case UseNewer => {
                  merge( conn, newc )
                  true
                }
                case PrioritizeOriginal => {
                  val next = akc reconcileOver newc
                  merge( conn, next )
                  akc != next
                }
                case PrioritizeNewer => {
                  merge( conn, newc reconcileOver akc )
                  true
                } 
                case Throw => {
                  throw new ContractMergeException("Could not merge old and new versions of contract metadata for contracts with identical code", cre )
                }
              }
            }
          }
        }
      }

      private def merge( conn : Connection, cc : CachedContract ) : Boolean = {
        merge( conn, cc.code, cc.name, cc.source, cc.language, cc.languageVersion, cc.compilerVersion, cc.compilerOptions, cc.abiDefinition, cc.userDoc, cc.developerDoc )
      }

      private def merge(
        conn             : Connection,
        code             : String,
        name             : Option[String],
        source           : Option[String],
        language         : Option[String],
        languageVersion  : Option[String],
        compilerVersion  : Option[String],
        compilerOptions  : Option[String],
        abiDefinition    : Option[String],
        userDoc          : Option[String],
        developerDoc     : Option[String]
      ) : Boolean = {
        borrow( conn.prepareStatement( MergeSql ) ){ ps =>

          ps.setString(          1, codeHash(code).hex )
          setClob(          ps,  2, code )
          setVarcharOption( ps,  3, name )
          setClobOption(    ps,  4, source )
          setVarcharOption( ps,  5, language )
          setVarcharOption( ps,  6, languageVersion )
          setVarcharOption( ps,  7, compilerVersion )
          setVarcharOption( ps,  8, compilerOptions )
          setClobOption(    ps,  9, abiDefinition )
          setClobOption(    ps, 10, userDoc )
          setClobOption(    ps, 11, developerDoc )

          ps.executeUpdate()

          true
        }
      }

      private val MergeSql = {
        """|MERGE INTO known_contracts ( code_hash, code, name, source, language, language_version, compiler_version, compiler_options, abi_definition, user_doc, developer_doc )
           |VALUES( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )""".stripMargin
      }

      private val SelectSql = {
        """|SELECT code, name, source, language, language_version, compiler_version, compiler_options, abi_definition, user_doc, developer_doc
           |FROM known_contracts WHERE code_hash = ?""".stripMargin
      }

      private val DeleteSql = "DELETE FROM known_contracts WHERE code_hash = ?"

      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS known_contracts (
           |   code_hash         CHAR(128) PRIMARY KEY,
           |   code              CLOB NOT NULL,
           |   name              VARCHAR(128),
           |   source            CLOB,
           |   language          VARCHAR(64),
           |   language_version  VARCHAR(64),
           |   compiler_version  VARCHAR(64),
           |   compiler_options  VARCHAR(256),
           |   abi_definition    CLOB,
           |   user_doc          CLOB,
           |   developer_doc     CLOB
           |)""".stripMargin
      }

      private case class CachedContract(
        code            : String,
        name            : Option[String],
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

          n( code) +
          xo( name ) +
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
            Reconcilable.reconcileLeaf( this.code, other.code ),
            Reconcilable.reconcileLeaf( this.name, other.name ),
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
            Reconcilable.reconcileOverLeaf( this.code, other.code ),
            Reconcilable.reconcileOverLeaf( this.name, other.name ),
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

    final object DeployedContracts {
      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS deployed_contracts (
           |   code_hash        VARCHAR(128) PRIMARY KEY,
           |   address          CHAR(40) NOT NULL,
           |   deployer_address CHAR(40),
           |   txn_hash         CHAR(128),
           |   deployed_when    TIMESTAMP,
           |   FOREIGN KEY ( code_hash ) REFERENCES known_contracts( code_hash )
           |)""".stripMargin
      }

      private val MergeSql = {
        """|MERGE INTO deployed_contracts ( code_hash, address, deployer_address, txn_hash, deployed_when )
           |VALUES( ?, ?, ?, ?, ? )""".stripMargin
      }

      def insertExistingDeployment( conn : Connection, code : String, contractAddress : EthAddress ) : Unit = {
        borrow( conn.prepareStatement( MergeSql ) ) { ps =>
          ps.setString( 1, codeHash( code ).hex )
          ps.setString( 2, contractAddress.hex )
          ps.setNull( 3, Types.CHAR )
          ps.setNull( 4, Types.CHAR )
          ps.setNull( 5, Types.TIMESTAMP )
          ps.executeUpdate()
        }
      }

      def insertNewDeployment( conn : Connection, code : String, contractAddress : EthAddress, deployerAddress : EthAddress, transactionHash : EthHash ) : Unit = {
        val timestamp = new Timestamp( System.currentTimeMillis )
        borrow( conn.prepareStatement( MergeSql ) ) { ps =>
          ps.setString( 1, codeHash( code ).hex )
          ps.setString( 2, contractAddress.hex )
          ps.setString( 3, deployerAddress.hex )
          ps.setString( 4, transactionHash.hex )
          ps.setTimestamp( 5, timestamp )
          ps.executeUpdate()
        }
      }
    }
  }
}


