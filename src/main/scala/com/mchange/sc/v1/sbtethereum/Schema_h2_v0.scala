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

  def markDeployContract(
    conn             : Connection,
    deployerAddress  : EthAddress,
    transactionHash  : EthHash,
    name             : String,
    code             : String,
    source           : Option[String],
    language         : Option[String],
    languageVersion  : Option[String],
    compilerVersion  : Option[String],
    compilerOptions  : Option[String],
    abiDefinition    : Option[String],
    userDoc          : Option[String],
    developerDoc     : Option[String],
    policy           : IrreconcilableUpdatePolicy
  ) : Unit = {
    Table.KnownContracts.updateKnownContract( conn, name, code, source, language, languageVersion, compilerVersion, compilerOptions, abiDefinition, userDoc, developerDoc, policy )
    Table.DeployedContracts.insert( conn, code, deployerAddress, transactionHash )
  }

  sealed trait IrreconcilableUpdatePolicy;
  final case object UseOriginal        extends IrreconcilableUpdatePolicy
  final case object UseNewer           extends IrreconcilableUpdatePolicy
  final case object PrioritizeOriginal extends IrreconcilableUpdatePolicy
  final case object PrioritizeNewer    extends IrreconcilableUpdatePolicy
  final case object Throw              extends IrreconcilableUpdatePolicy

  final class ContractMergeException( message : String, cause : Throwable = null ) extends Exception( message, cause )

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
                rs.getString(2),
                Option( rs.getString( 3) ),
                Option( rs.getString( 4) ),
                Option( rs.getString( 5) ),
                Option( rs.getString( 6) ),
                Option( rs.getString( 7) ),
                Option( rs.getString( 8) ),
                Option( rs.getString( 9) ),
                Option( rs.getString(10) )
              )
              assert(!rs.next(), s"Huh? More than one row with the same codeHash in entries? [codeHash=${codeHash.hex}]" )
              Some( ok )
            } else {
              None
            }
          }
        }
      }

      def updateKnownContract( 
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
        developerDoc     : Option[String],
        policy           : IrreconcilableUpdatePolicy = Throw
      ) : Unit = {
        val ch = codeHash(code)
        val mbAlreadyKnownContract = _select( conn, ch )
        mbAlreadyKnownContract.fold( insert( conn, name, code, source, language, languageVersion, compilerVersion, compilerOptions, abiDefinition, userDoc, developerDoc ) ) { akc =>
          val newc = CachedContract( name, code, source, language, languageVersion, compilerVersion, compilerOptions, abiDefinition, userDoc, developerDoc )
          val reconciled = try {
            newc.reconcile( akc )
          } catch {
            case cre : CantReconcileException => {
              DEBUG.log( s"Attempt to reconcile ${newc} with ${akc} failed.", cre )
              policy match {
                case UseOriginal => {
                  /* nothing to do */
                }
                case UseNewer => {
                  delete( conn, ch )
                  insert( conn, newc )
                }
                case PrioritizeOriginal => {
                  delete( conn, ch )
                  insert( conn, akc reconcileOver newc )
                }
                case PrioritizeNewer => {
                  delete( conn, ch )
                  insert( conn, newc reconcileOver akc )
                } 
                case Throw => {
                  throw new ContractMergeException("Could not merge old and new versions of contract metadata for contracts with identical code", cre )
                }
              }
            }
          }
        }
      }

      def insert( conn : Connection, cc : CachedContract ) : Unit = {
        insert( conn, cc.name, cc.code, cc.source, cc.language, cc.languageVersion, cc.compilerVersion, cc.compilerOptions, cc.abiDefinition, cc.userDoc, cc.developerDoc )
      }

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
      ) : Unit = {
        borrow( conn.prepareStatement( InsertSql ) ){ ps =>

          def setClob( i : Int, str : String ) = ps.setClob( i, new StringReader( str ) )
          def setClobOption( i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.CLOB ) ){ str =>  setClob( i, str ) }
          def setVarcharOption( i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.VARCHAR ) ){ str =>  ps.setString( i, str ) }

          ps.setString(     1, codeHash(code).hex )
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
           |FROM known_contracts WHERE code_hash = ?""".stripMargin
      }

      private val DeleteSql = "DELETE FROM known_contracts WHERE code_hash = ?"

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
           |   developer_doc     CLOB
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

          n( codeHash( code ).hex ) +
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

    final object DeployedContracts {
      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS deployed_contracts (
           |   code_hash        VARCHAR(128) PRIMARY KEY,
           |   deployer_address CHAR(40) NOT NULL,
           |   txn_hash         CHAR(128) NOT NULL,
           |   when             TIMESTAMP NOT NULL,
           |   FOREIGN KEY ( code_hash ) REFERENCES known_contracts( code_hash )
           |)""".stripMargin
      }

      private val InsertSql = {
        """|INSERT INTO deployed_contracts ( code_hash, deployer_address, txn_hash, when )
           |VALUES( ?, ?, ?, ? )""".stripMargin
      }

      def insert( conn : Connection, code : String, deployerAddress : EthAddress, transactionHash : EthHash ) : Unit = {
        val timestamp = new Timestamp( System.currentTimeMillis )
        borrow( conn.prepareStatement( InsertSql ) ) { ps =>
          ps.setString( 1, codeHash( code ).hex )
          ps.setString( 2, deployerAddress.hex )
          ps.setString( 3, transactionHash.hex )
          ps.setTimestamp( 4, timestamp )
          ps.executeUpdate()
        }
      }
    }
  }



}


