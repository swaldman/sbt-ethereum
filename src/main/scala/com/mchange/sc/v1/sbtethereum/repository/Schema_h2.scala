package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v1.sbtethereum._
import util.BaseCodeAndSuffix
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash}
import com.mchange.sc.v1.consuela.ethereum.jsonrpc._
import com.mchange.sc.v2.sql.{borrowTransact, getMaybeSingleString, getMaybeSingleValue, setMaybeString}
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.reconcile.{CantReconcileException, Reconcilable}
import java.io.StringReader
import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp, Types}
import javax.sql.DataSource
import com.mchange.sc.v1.log.MLogger
import scala.annotation.tailrec
import scala.collection._
import play.api.libs.json._

object Schema_h2 {
  private implicit lazy val logger: MLogger = mlogger( this )

  def setClob( ps : PreparedStatement, i : Int, str : String ): Unit =
    ps.setClob( i, new StringReader( str ) )

  def setClobOption( ps : PreparedStatement, i : Int, mbs : Option[String] ): Unit =
    mbs.fold( ps.setNull( i, Types.CLOB ) ){ str =>  setClob( ps, i, str ) }

  def setVarcharOption( ps : PreparedStatement, i : Int, mbs : Option[String] ): Unit =
    mbs.fold( ps.setNull( i, Types.VARCHAR ) ){ str =>  ps.setString( i, str ) }

  def setTimestampOption( ps : PreparedStatement, i : Int, mbl : Option[Long] ): Unit =
    mbl.fold( ps.setNull( i, Types.TIMESTAMP ) ){ l =>  ps.setTimestamp( i, new Timestamp(l) ) }

  val SchemaVersion = 3

  // should be executed in a transaction, although it looks like in h2 DDL commands autocommit anyway :(
  def ensureSchema( dataSource : DataSource ): Boolean =
    borrowTransact( dataSource.getConnection() ) { conn =>
      borrow( conn.createStatement() ){ stmt =>
        stmt.executeUpdate( Table.Metadata.CreateSql )
        stmt.executeUpdate( Table.KnownCode.CreateSql )
        stmt.executeUpdate( Table.KnownCompilations.CreateSql )
        stmt.executeUpdate( Table.DeployedCompilations.CreateSql )
        stmt.executeUpdate( Table.MemorizedAbis.CreateSql )
        stmt.executeUpdate( Table.AddressAliases.CreateSql )
        stmt.executeUpdate( Table.AddressAliases.CreateIndex )
      }
      Table.Metadata.ensureSchemaVersion( conn )
      Table.AddressAliases.attemptInsertTestrpcFaucetDefaultSender( conn )
    }

  private def migrateUpOne( conn : Connection, versionFrom : Int ) : Int = {
    versionFrom match {
      case 0 =>
        /*
         *  Schema version 0 was identical to schema version 1, except deployed_compilations lacked a blockchain_id
         *  We have to completely reconstruct deployed_compilations because blockchain_id becomes part of a compound public key
         */
        borrow( conn.createStatement() ) { stmt =>
          stmt.executeUpdate("ALTER TABLE deployed_compilations RENAME TO deployed_compilations_v0")
          stmt.executeUpdate( Table.DeployedCompilations.V1.CreateSql )
          stmt.executeUpdate(
            s"""|INSERT INTO deployed_compilations ( blockchain_id, contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when )
                |SELECT '$MainnetIdentifier', contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when
                |FROM deployed_compilations_v0""".stripMargin
          )
          stmt.executeUpdate("DROP TABLE deployed_compilations_v0")
        }

      case 1 =>
        /* Schema version 1 was identical to schema version 2, except version 2 adds a constructor_inputs_hex column
         * to the deployed_compilations table */
        borrow( conn.createStatement() ) { stmt =>
          stmt.executeUpdate("ALTER TABLE deployed_compilations ADD COLUMN constructor_inputs_hex CLOB AFTER deployed_when")
        }

      case 2 =>
        /* Schema version 3 was identical to schema version 2, except address_aliases lacked a blockchain_id
         *  We have to completely reconstruct address_aliases because blockchain_id becomes part of a compound public key */
        borrow( conn.createStatement() ) { stmt =>
          stmt.executeUpdate("ALTER TABLE address_aliases RENAME TO address_aliases_v2")
          stmt.executeUpdate( Table.AddressAliases.V3.CreateSql )
          stmt.executeUpdate(
            s"""|INSERT INTO address_aliases ( blockchain_id, alias, address )
                |SELECT '$MainnetIdentifier', alias, address
                |FROM address_aliases_v2""".stripMargin
          )
          stmt.executeUpdate("DROP TABLE address_aliases_v2")
        }

      case unknown => throw new DatabaseVersionException( s"Cannot migrate from unknown database version $unknown." )
    }
    versionFrom + 1
  }

  @tailrec
  private def migrateUpTo( conn : Connection, versionFrom : Int, versionTo : Int ) : Unit = {
    val next = migrateUpOne( conn, versionFrom )
    if ( next != versionTo ) migrateUpTo( conn, next, versionFrom )
  }

  private def migrateSchema( conn : Connection, versionFrom : Int, versionTo : Int ) : Unit = {
    // we don't check whether versionFrom is the current version in the database, because
    // we should have just gotten the current version from the database

    require( versionFrom >= 0, s"Please restore database from backup! Valid schema versions begin are non-negative, version $versionFrom is invalid, may indicate database corruption." )
    require( versionFrom < versionTo, s"We can only upmigrate schemas, can't transition from $versionFrom to $versionTo" )

    repository.Database.h2.makeBackup( conn, versionFrom ).get // throw if something goes wrong

    DEBUG.log( s"Migrating sbt-ethereum database schema from version $versionFrom to version $versionTo." )
    Table.Metadata.upsert( conn, Table.Metadata.Key.SchemaVersion, "-1" )
    migrateUpTo( conn, versionFrom, versionTo )
    Table.Metadata.upsert( conn, Table.Metadata.Key.SchemaVersion, versionTo.toString )
    DEBUG.log( s"Migration complete." )
  }

  final object ContractsSummary {
    final object Column {
      val blockchain_id      = "blockchain_id"
      val contract_address   = "contract_address"
      val name               = "name"
      val deployer_address   = "deployer_address"
      val full_code_hash     = "full_code_hash"
      val txn_hash           = "txn_hash"
      val deployed_when      = "deployed_when"
    }

    val Sql: String = {
      import Column._
      s"""|SELECT DISTINCT $blockchain_id, $contract_address, $name, $deployer_address, known_compilations.$full_code_hash, $txn_hash, $deployed_when
          |FROM deployed_compilations RIGHT JOIN known_compilations ON deployed_compilations.full_code_hash = known_compilations.full_code_hash
          |ORDER BY deployed_when DESC""".stripMargin
    }
  }

  // no reference to blockchain_id here, because a deployment on any blockchain is sufficient to prevent a cull
  val CullUndeployedCompilationsSql: String = {
    """|DELETE FROM known_compilations
       |WHERE full_code_hash NOT IN (
       |  SELECT full_code_hash
       |  FROM deployed_compilations
       |)""".stripMargin
  }

  final object Table {
    final object Metadata {
      val CreateSql = "CREATE TABLE IF NOT EXISTS metadata ( key VARCHAR(64) PRIMARY KEY, value VARCHAR(64) NOT NULL )"

      def ensureSchemaVersion( conn : Connection ) : Unit = {
        val currentVersion = select( conn, Key.SchemaVersion )
        currentVersion.fold( upsert( conn, Key.SchemaVersion, SchemaVersion.toString ) ){ versionStr =>
          val v = versionStr.toInt
          if ( v != SchemaVersion ) migrateSchema( conn, v, SchemaVersion )
        }
      }

      def upsert( conn : Connection, key : String, value : String ) : Unit = {
        borrow( conn.prepareStatement( "MERGE INTO metadata ( key, value ) VALUES ( ?, ? )" ) ) { ps =>
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

    final object KnownCode {
      val CreateSql: String =
        """|CREATE TABLE IF NOT EXISTS known_code (
           |   base_code_hash  CHAR(128) PRIMARY KEY,
           |   base_code       CLOB NOT NULL
           |)""".stripMargin

      val SelectSql = "SELECT base_code FROM known_code WHERE base_code_hash = ?"

      val UpsertSql = "MERGE INTO known_code ( base_code_hash, base_code ) VALUES ( ?, ? )"

      def select( conn : Connection, baseCodeHash : EthHash ) : Option[String] =
        borrow( conn.prepareStatement( SelectSql ) ) { ps =>
          ps.setString(1, baseCodeHash.hex)
          borrow( ps.executeQuery() )( getMaybeSingleString )
        }

      def upsert( conn : Connection, baseCode : String ) : Unit =
        borrow( conn.prepareStatement( UpsertSql ) ) { ps =>
          ps.setString( 1, EthHash.hash( baseCode.decodeHex ).hex )
          ps.setString( 2, baseCode )
          ps.executeUpdate()
        }
    }

    final object KnownCompilations {
      val CreateSql: String =
        """|CREATE TABLE IF NOT EXISTS known_compilations (
           |   full_code_hash    CHAR(128),
           |   base_code_hash    CHAR(128),
           |   code_suffix       CLOB NOT NULL,
           |   name              VARCHAR(128),
           |   source            CLOB,
           |   language          VARCHAR(64),
           |   language_version  VARCHAR(64),
           |   compiler_version  VARCHAR(64),
           |   compiler_options  VARCHAR(256),
           |   abi_definition    CLOB,
           |   user_doc          CLOB,
           |   developer_doc     CLOB,
           |   metadata          CLOB,
           |   PRIMARY KEY ( full_code_hash ),
           |   FOREIGN KEY ( base_code_hash ) REFERENCES known_code ( base_code_hash ) ON DELETE CASCADE
           |)""".stripMargin // we delete known_compilations when culling undeployed compilations

      val SelectSql: String =
        """|SELECT
           |   base_code_hash,
           |   code_suffix,
           |   name,
           |   source,
           |   language,
           |   language_version,
           |   compiler_version,
           |   compiler_options,
           |   abi_definition,
           |   user_doc,
           |   developer_doc,
           |   metadata
           |FROM known_compilations
           |WHERE full_code_hash = ?""".stripMargin

      val UpsertSql: String =
        """|MERGE INTO known_compilations (
           |   full_code_hash,
           |   base_code_hash,
           |   code_suffix,
           |   name,
           |   source,
           |   language,
           |   language_version,
           |   compiler_version,
           |   compiler_options,
           |   abi_definition,
           |   user_doc,
           |   developer_doc,
           |   metadata
           |) VALUES( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )""".stripMargin

      val UpdateAbiSql: String =
        """|UPDATE known_compilations
           |SET abi_definition = ?
           |WHERE full_code_hash = ?""".stripMargin

      case class KnownCompilation (
        fullCodeHash      : EthHash,
        baseCodeHash      : EthHash,
        codeSuffix        : String,
        mbName            : Option[String],
        mbSource          : Option[String],
        mbLanguage        : Option[String],
        mbLanguageVersion : Option[String],
        mbCompilerVersion : Option[String],
        mbCompilerOptions : Option[String],
        mbAbi   : Option[Abi],
        mbUserDoc         : Option[Doc.User],
        mbDeveloperDoc    : Option[Doc.Developer],
        mbMetadata        : Option[String]
      ) extends Reconcilable[KnownCompilation] {
        import Reconcilable._

        def reconcile(other : KnownCompilation) : KnownCompilation = {
          KnownCompilation(
            fullCodeHash      = reconcileLeaf( this.fullCodeHash, other.fullCodeHash ),
            baseCodeHash      = reconcileLeaf( this.baseCodeHash, other.baseCodeHash ),
            codeSuffix        = reconcileLeaf( this.codeSuffix, other.codeSuffix ),
            mbName            = reconcileLeaf( this.mbName, other.mbName ),
            mbSource          = reconcileLeaf( this.mbSource, other.mbSource ),
            mbLanguage        = reconcileLeaf( this.mbLanguage, other.mbLanguage ),
            mbLanguageVersion = reconcileLeaf( this.mbLanguageVersion, other.mbLanguageVersion ),
            mbCompilerVersion = reconcileLeaf( this.mbCompilerVersion, other.mbCompilerVersion ),
            mbCompilerOptions = reconcileLeaf( this.mbCompilerOptions, other.mbCompilerOptions ),
            mbAbi   = reconcileLeaf( this.mbAbi, other.mbAbi ),
            mbUserDoc         = reconcileLeaf( this.mbUserDoc, other.mbUserDoc ),
            mbDeveloperDoc    = reconcileLeaf( this.mbDeveloperDoc, other.mbDeveloperDoc ),
            mbMetadata        = reconcileLeaf( this.mbMetadata, other.mbMetadata )
          )
        }

        def reconcileOver(other : KnownCompilation) : KnownCompilation = {
          KnownCompilation(
            fullCodeHash      = reconcileOverLeaf( this.fullCodeHash, other.fullCodeHash ),
            baseCodeHash      = reconcileOverLeaf( this.baseCodeHash, other.baseCodeHash ),
            codeSuffix        = reconcileOverLeaf( this.codeSuffix, other.codeSuffix ),
            mbName            = reconcileOverLeaf( this.mbName, other.mbName ),
            mbSource          = reconcileOverLeaf( this.mbSource, other.mbSource ),
            mbLanguage        = reconcileOverLeaf( this.mbLanguage, other.mbLanguage ),
            mbLanguageVersion = reconcileOverLeaf( this.mbLanguageVersion, other.mbLanguageVersion ),
            mbCompilerVersion = reconcileOverLeaf( this.mbCompilerVersion, other.mbCompilerVersion ),
            mbCompilerOptions = reconcileOverLeaf( this.mbCompilerOptions, other.mbCompilerOptions ),
            mbAbi   = reconcileOverLeaf( this.mbAbi, other.mbAbi ),
            mbUserDoc         = reconcileOverLeaf( this.mbUserDoc, other.mbUserDoc ),
            mbDeveloperDoc    = reconcileOverLeaf( this.mbDeveloperDoc, other.mbDeveloperDoc ),
            mbMetadata        = reconcileOverLeaf( this.mbMetadata, other.mbMetadata )
          )
        }
      }

      def select( conn : Connection, fullCodeHash : EthHash ) : Option[KnownCompilation] = {
        import Json.parse

        val extract : ResultSet => KnownCompilation = { rs =>
          KnownCompilation (
            fullCodeHash      = fullCodeHash,
            baseCodeHash      = EthHash.withBytes( rs.getString( "base_code_hash" ).decodeHex ),
            codeSuffix        = rs.getString( "code_suffix" ),
            mbName            = Option( rs.getString("name") ),
            mbSource          = Option( rs.getString("source") ),
            mbLanguage        = Option( rs.getString("language") ),
            mbLanguageVersion = Option( rs.getString("language_version") ),
            mbCompilerVersion = Option( rs.getString("compiler_version") ),
            mbCompilerOptions = Option( rs.getString("compiler_options") ),
            mbAbi   = Option( rs.getString("abi_definition") ).map( parse ).map( _.as[Abi] ),
            mbUserDoc         = Option( rs.getString("user_doc") ).map( parse ).map( _.as[Doc.User] ),
            mbDeveloperDoc    = Option( rs.getString("developer_doc") ).map( parse ).map( _.as[Doc.Developer] ),
            mbMetadata        = Option( rs.getString("metadata") )
          )
        }

        borrow( conn.prepareStatement( SelectSql ) ) { ps =>
          ps.setString(1, fullCodeHash.hex)
          borrow( ps.executeQuery() )( getMaybeSingleValue( extract ) )
        }
      }

      def upsert(
        conn              : Connection,
        fullCodeHash      : EthHash,
        baseCodeHash      : EthHash,
        codeSuffix        : String,
        mbName            : Option[String],
        mbSource          : Option[String],
        mbLanguage        : Option[String],
        mbLanguageVersion : Option[String],
        mbCompilerVersion : Option[String],
        mbCompilerOptions : Option[String],
        mbAbi   : Option[Abi],
        mbUserDoc         : Option[Doc.User],
        mbDeveloperDoc    : Option[Doc.Developer],
        mbMetadata        : Option[String]
      ) : Unit = {
        import Json.{toJson,stringify}
        borrow( conn.prepareStatement( UpsertSql ) ) { ps =>
          ps.setString( 1, fullCodeHash.hex )
          ps.setString( 2, baseCodeHash.hex )
          ps.setString( 3, codeSuffix )
          setMaybeString( Types.VARCHAR )( ps,  4, mbName )
          setMaybeString( Types.CLOB )   ( ps,  5, mbSource )
          setMaybeString( Types.VARCHAR )( ps,  6, mbLanguage )
          setMaybeString( Types.VARCHAR )( ps,  7, mbLanguageVersion )
          setMaybeString( Types.VARCHAR )( ps,  8, mbCompilerVersion )
          setMaybeString( Types.VARCHAR )( ps,  9, mbCompilerOptions )
          setMaybeString( Types.CLOB )   ( ps, 10, mbAbi.map( ad => stringify( toJson( ad ) ) ) )
          setMaybeString( Types.CLOB )   ( ps, 11, mbUserDoc.map( ud => stringify( toJson( ud ) ) ) )
          setMaybeString( Types.CLOB )   ( ps, 12, mbDeveloperDoc.map( dd => stringify( toJson( dd ) ) ) )
          setMaybeString( Types.CLOB )   ( ps, 13, mbMetadata )
          ps.executeUpdate()
        }
      }

      def upsert(
        conn : Connection,
        kc   : KnownCompilation
      ) : Unit = {
        upsert(
          conn,
          kc.fullCodeHash,
          kc.baseCodeHash,
          kc.codeSuffix,
          kc.mbName,
          kc.mbSource,
          kc.mbLanguage,
          kc.mbLanguageVersion,
          kc.mbCompilerVersion,
          kc.mbCompilerOptions,
          kc.mbAbi,
          kc.mbUserDoc,
          kc.mbDeveloperDoc,
          kc.mbMetadata
        )
      }

      def updateAbiDefinition( conn : Connection, baseCodeHash : EthHash, fullCodeHash : EthHash, mbAbiDefinition : Option[String] ) : Boolean = {
        borrow( conn.prepareStatement( UpdateAbiSql ) ){ ps =>
          mbAbiDefinition match {
            case Some( abi ) => ps.setString( 1, abi )
            case None                  => ps.setNull( 1, Types.CLOB )
          }
          ps.setString( 2, fullCodeHash.hex )
          ps.executeUpdate() match {
            case 0 => false
            case 1 => true
            case n => throw new RepositoryException( s"ABI update should affect no more than one row, affected ${n} rows." )
          }
        }
      }
    }

    final object DeployedCompilations {
      final object V0 {
        val CreateSql: String =
          """|CREATE TABLE IF NOT EXISTS deployed_compilations (
             |   contract_address CHAR(40) PRIMARY KEY,
             |   base_code_hash   CHAR(128) NOT NULL,
             |   full_code_hash   CHAR(128) NOT NULL,
             |   deployer_address CHAR(40),
             |   txn_hash         CHAR(128),
             |   deployed_when    TIMESTAMP,
             |   FOREIGN KEY ( base_code_hash, full_code_hash ) REFERENCES known_compilations( base_code_hash, full_code_hash )
             |)""".stripMargin
      }

      final object V1 {
        val CreateSql: String =
          """|CREATE TABLE IF NOT EXISTS deployed_compilations (
             |   blockchain_id    VARCHAR(64),
             |   contract_address CHAR(40),
             |   base_code_hash   CHAR(128) NOT NULL,
             |   full_code_hash   CHAR(128) NOT NULL,
             |   deployer_address CHAR(40),
             |   txn_hash         CHAR(128),
             |   deployed_when    TIMESTAMP,
             |   PRIMARY KEY ( blockchain_id, contract_address ),
             |   FOREIGN KEY ( base_code_hash, full_code_hash ) REFERENCES known_compilations( base_code_hash, full_code_hash )
             |)""".stripMargin
      }

      final object V2 {
        val CreateSql: String =
          """|CREATE TABLE IF NOT EXISTS deployed_compilations (
             |   blockchain_id          VARCHAR(64),
             |   contract_address       CHAR(40),
             |   base_code_hash         CHAR(128) NOT NULL,
             |   full_code_hash         CHAR(128) NOT NULL,
             |   deployer_address       CHAR(40),
             |   txn_hash               CHAR(128),
             |   deployed_when          TIMESTAMP,
             |   constructor_inputs_hex CLOB,
             |   PRIMARY KEY ( blockchain_id, contract_address ),
             |   FOREIGN KEY ( base_code_hash, full_code_hash ) REFERENCES known_compilations( base_code_hash, full_code_hash )
             |)""".stripMargin
      }

      val CreateSql: String = DeployedCompilations.V2.CreateSql

      val SelectSql: String =
        """|SELECT blockchain_id, contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when, constructor_inputs_hex
           |FROM deployed_compilations
           |WHERE blockchain_id = ? AND contract_address = ?""".stripMargin

      val InsertSql: String =
        "INSERT INTO deployed_compilations ( blockchain_id, contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when, constructor_inputs_hex ) VALUES ( ?, ?, ?, ?, ?, ?, ?, ? )"

      val AllForFullCodeHashSql: String =
        """|SELECT blockchain_id, contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when, constructor_inputs_hex
           |FROM deployed_compilations
           |WHERE blockchain_id = ? AND full_code_hash = ?""".stripMargin

      val AllForFullCodeHashAnyBlockchainIdSql: String =
        """|SELECT blockchain_id, contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when, constructor_inputs_hex
           |FROM deployed_compilations
           |WHERE full_code_hash = ?""".stripMargin

      final case class DeployedCompilation (
        blockchainId        : String,
        contractAddress     : EthAddress,
        baseCodeHash        : EthHash,
        fullCodeHash        : EthHash,
        mbDeployerAddress   : Option[EthAddress],
        mbTransactionHash   : Option[EthHash],
        mbDeployedWhen      : Option[Long],
        mbConstructorInputs : Option[immutable.Seq[Byte]]
      )

      val extract : ResultSet => DeployedCompilation = { rs =>
        DeployedCompilation (
          blockchainId        = rs.getString( "blockchain_id" ),
          contractAddress     = EthAddress( rs.getString( "contract_address" ) ),
          baseCodeHash        = EthHash.withBytes( rs.getString( "base_code_hash" ).decodeHex ),
          fullCodeHash        = EthHash.withBytes( rs.getString( "full_code_hash" ).decodeHex ),
          mbDeployerAddress   = Option( rs.getString( "deployer_address" ) ).map( EthAddress.apply ),
          mbTransactionHash   = Option( rs.getString( "txn_hash" ) ).map( _.decodeHex ).map( EthHash.withBytes ),
          mbDeployedWhen      = Option( rs.getTimestamp( "deployed_when" ) ).map( _.getTime ),
          mbConstructorInputs = Option( rs.getString( "constructor_inputs_hex" ) ).map( _.decodeHexAsSeq )
        )
      }

      def select( conn : Connection, blockchainId : String, contractAddress : EthAddress ) : Option[DeployedCompilation] =
        borrow( conn.prepareStatement( SelectSql ) ) { ps =>
          ps.setString(1, blockchainId)
          ps.setString(2, contractAddress.hex)
          borrow( ps.executeQuery() )( getMaybeSingleValue( extract ) )
        }

      def allForFullCodeHash( conn : Connection, blockchainId : String, fullCodeHash : EthHash ) : immutable.Set[DeployedCompilation] =
        borrow( conn.prepareStatement( AllForFullCodeHashSql ) ) { ps =>
          ps.setString(1, blockchainId)
          ps.setString(2, fullCodeHash.hex)
          borrow( ps.executeQuery() ) { rs =>
            var out = immutable.Set.empty[DeployedCompilation]
            while ( rs.next() ) out = out + extract( rs )
            out
          }
        }

      def allForFullCodeHashAnyBlockchainId( conn : Connection, fullCodeHash : EthHash ) : immutable.Set[DeployedCompilation] =
        borrow( conn.prepareStatement( AllForFullCodeHashAnyBlockchainIdSql ) ) { ps =>
          ps.setString(1, fullCodeHash.hex)
          borrow( ps.executeQuery() ) { rs =>
            var out = immutable.Set.empty[DeployedCompilation]
            while ( rs.next() ) out = out + extract( rs )
            out
          }
        }

      def insertNewDeployment( conn : Connection, blockchainId : String, contractAddress : EthAddress, code : String, deployerAddress : EthAddress, transactionHash : EthHash, constructorInputs : immutable.Seq[Byte] ) : Unit = {
        val bcas = BaseCodeAndSuffix( code )
        val timestamp = new Timestamp( System.currentTimeMillis )
        borrow( conn.prepareStatement( InsertSql ) ) { ps =>
          ps.setString   ( 1, blockchainId )
          ps.setString   ( 2, contractAddress.hex )
          ps.setString   ( 3, bcas.baseCodeHash.hex )
          ps.setString   ( 4, bcas.fullCodeHash.hex )
          ps.setString   ( 5, deployerAddress.hex )
          ps.setString   ( 6, transactionHash.hex )
          ps.setTimestamp( 7, timestamp )
          ps.setString   ( 8, constructorInputs.hex )
          ps.executeUpdate()
        }
      }
    }

    final object MemorizedAbis {
      final val CreateSql =
        """|CREATE TABLE IF NOT EXISTS memorized_abis (
           |   blockchain_id    VARCHAR(64),
           |   contract_address CHAR(40),
           |   abi_definition   CLOB,
           |   PRIMARY KEY ( blockchain_id, contract_address )
           |)""".stripMargin

      private val InsertSql =
        """|INSERT INTO memorized_abis ( blockchain_id, contract_address, abi_definition )
           |VALUES( ?, ?, ? )""".stripMargin

      private val DeleteSql =
        """|DELETE FROM memorized_abis
           |WHERE blockchain_id = ? AND contract_address = ?""".stripMargin

      private val SelectSql =
        """|SELECT abi_definition
           |FROM memorized_abis
           |WHERE blockchain_id = ? AND contract_address = ?""".stripMargin

      private val SelectAddressesForBlackchainIdSql =
        """|SELECT contract_address
           |FROM memorized_abis
           |WHERE blockchain_id = ?
           |ORDER BY contract_address DESC""".stripMargin

      def selectAddressesForBlockchainId( conn : Connection, blockchainId : String ) : immutable.Seq[EthAddress] =
        borrow( conn.prepareStatement( SelectAddressesForBlackchainIdSql ) ){ ps =>
          ps.setString(1, blockchainId )
          borrow( ps.executeQuery() ){ rs =>
            @tailrec
            def prepend( accum : List[EthAddress] ) : List[EthAddress] = if ( rs.next() ) prepend( EthAddress( rs.getString(1) ) :: accum ) else accum
            prepend( Nil )
          }
        }

      def select( conn : Connection, blockchainId : String, contractAddress : EthAddress ) : Option[Abi] =
        borrow( conn.prepareStatement( SelectSql ) ){ ps =>
          ps.setString(1, blockchainId )
          ps.setString(2, contractAddress.hex )
          borrow( ps.executeQuery() ){ rs =>
            val mbJson = getMaybeSingleString( rs )
            mbJson.map( Json.parse ).map( _.as[Abi] )
          }
        }

      def insert( conn : Connection, blockchainId : String, contractAddress : EthAddress, abi : Abi ) : Unit =
        borrow( conn.prepareStatement( InsertSql ) ) { ps =>
          ps.setString( 1, blockchainId )
          ps.setString( 2, contractAddress.hex )
          ps.setString( 3, Json.stringify( Json.toJson( abi ) ) )
          ps.executeUpdate()
        }

      def delete( conn : Connection, blockchainId : String, contractAddress : EthAddress ) : Boolean =
        borrow( conn.prepareStatement( DeleteSql ) ) { ps =>
          ps.setString( 1, blockchainId )
          ps.setString( 2, contractAddress.hex )
          ps.executeUpdate() == 1
        }
    }

    final object AddressAliases {
      final object V2 {
        val CreateSql: String =
          """|CREATE TABLE IF NOT EXISTS address_aliases (
             |   alias   VARCHAR(128) PRIMARY KEY,
             |   address CHAR(40) NOT NULL
             |)""".stripMargin
      }

      final object V3 {
        val CreateSql: String =
          """|CREATE TABLE IF NOT EXISTS address_aliases (
             |   blockchain_id VARCHAR(64),
             |   alias         VARCHAR(128),
             |   address       CHAR(40) NOT NULL,
             |   PRIMARY KEY ( blockchain_id, alias )
             |)""".stripMargin
      }

      val CreateSql: String = V3.CreateSql

      val CreateIndex: String = "CREATE INDEX IF NOT EXISTS address_aliases_address_idx ON address_aliases( address )"

      def selectByAlias( conn : Connection, blockchainId : String, alias : String ) : Option[EthAddress] =
        borrow( conn.prepareStatement( "SELECT address FROM address_aliases WHERE blockchain_id = ? AND alias = ?" ) ) { ps =>
          ps.setString(1, blockchainId)
          ps.setString(2, alias)
          val mbAddressStr = borrow( ps.executeQuery() )( getMaybeSingleString )
          mbAddressStr.map( EthAddress.apply )
        }

      def selectByAddress( conn : Connection, blockchainId : String, address : EthAddress ) : immutable.Seq[String] =
        borrow( conn.prepareStatement( "SELECT alias FROM address_aliases WHERE blockchain_id = ? AND address = ? ORDER BY alias DESC" ) ) { ps =>
          ps.setString(1, blockchainId)
          ps.setString(2, address.hex)
          borrow( ps.executeQuery() ){ rs =>
            @tailrec
            def prepend( accum : List[String] ) : List[String] = if ( rs.next() ) prepend( rs.getString(1) :: accum ) else accum
            prepend( Nil )
          }
        }

      def selectAllForBlockchainId( conn : Connection, blockchainId : String ) : immutable.SortedMap[String,EthAddress] = {
        val buffer = mutable.ArrayBuffer.empty[Tuple2[String, EthAddress]]
        borrow( conn.prepareStatement( "SELECT alias, address FROM address_aliases WHERE blockchain_id = ? ORDER BY alias ASC" ) ) { ps =>
          ps.setString( 1, blockchainId )
          borrow( ps.executeQuery() ) { rs =>
            while ( rs.next() ) {
              buffer += Tuple2( rs.getString(1), EthAddress( rs.getString(2) ) )
            }
          }
        }
        immutable.SortedMap( buffer : _* )
      }

      private def sert( verb : String )( conn : Connection, blockchainId : String, alias : String, address : EthAddress ) : Unit =
        borrow( conn.prepareStatement( s"$verb INTO address_aliases ( blockchain_id, alias, address ) VALUES ( ?, ?, ? )" ) ) { ps =>
          ps.setString( 1, blockchainId )
          ps.setString( 2, alias )
          ps.setString( 3, address.hex )
          ps.executeUpdate()
        }

      def upsert( conn : Connection, blockchainId : String, alias : String, address : EthAddress ) : Unit =
        sert( "MERGE" )( conn, blockchainId, alias, address )

      def insert( conn : Connection, blockchainId : String, alias : String, address : EthAddress ) : Unit =
        sert( "INSERT" )( conn, blockchainId, alias, address )

      def delete( conn : Connection, blockchainId : String, alias : String ) : Boolean =
        borrow( conn.prepareStatement( "DELETE FROM address_aliases WHERE blockchain_id = ? AND alias = ?" ) ) { ps =>
          ps.setString( 1, blockchainId )
          ps.setString( 2, alias )
          ps.executeUpdate() == 1
        }

      def attemptInsertTestrpcFaucetDefaultSender( conn : Connection ) : Boolean =
        selectByAlias( conn, TestrpcIdentifier, DefaultSenderAlias ) match {
          case None =>
            insert( conn, TestrpcIdentifier, DefaultSenderAlias, testing.Default.Faucet.address )
            true

          case _ => false
        }
    }
  }
}


