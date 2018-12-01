package com.mchange.sc.v1.sbtethereum.shoebox

import com.mchange.sc.v1.sbtethereum.{nst, recursiveListBeneath, shoebox, SbtEthereumException}

import java.io.{ BufferedInputStream, BufferedOutputStream, File, FileInputStream, FileOutputStream }
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.{ ZipEntry, ZipFile, ZipOutputStream }

import com.mchange.sc.v3.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.log.MLevel._

import java.time.Instant
import scala.collection._

object Backup {

  private implicit lazy val logger = mlogger( this )

  private val fsep = File.separator

  private def timestamp = InFilenameTimestamp.generate()

  private def parseTimestamp( ts : String ) : Long = InFilenameTimestamp.parse( ts ).toEpochMilli

  val BackupFileRegex = """sbt\-ethereum\-shoebox\-backup\-(\p{Alnum}+)(-DB-DUMP-FAILED)?\.zip$""".r

  final case class BackupFile( file : File, timestamp : Long, dbDumpSucceeded : Boolean )

  private def _attemptAsBackupFile( f : File ) : Option[BackupFile] = {
    BackupFileRegex.findFirstMatchIn(f.getName()) match {
      case Some( m ) if f.isFile => Some( BackupFile( f, parseTimestamp( m.group(1) ), m.group(2) == null ) )
      case None                  => None
    }
  }

  def attemptAsBackupFile( f : File ) : Option[BackupFile] = _attemptAsBackupFile( f )

  def backupFilesOrderedByMostRecent( candidateList : Iterable[File] ) : immutable.SortedSet[BackupFile] = {
    val rawBackupFiles = candidateList.map( attemptAsBackupFile ).filter( _.nonEmpty ).map( _.get )
    immutable.TreeSet.empty[BackupFile]( Ordering.by( (bf : BackupFile) => bf.timestamp ).reverse ) ++ rawBackupFiles
  }

  def backupFileName( dbDumpSucceeded : Boolean ) = {
    val dbDumpExtra = if (dbDumpSucceeded) "" else "-DB-DUMP-FAILED"
    s"sbt-ethereum-shoebox-backup-${timestamp}${dbDumpExtra}.zip"
  }

  def perform( mbLog : Option[sbt.Logger], priorDatabaseFailureDetected : Boolean, backupsDir : File ) : Unit = this.synchronized {
    def info( msg : String ) : Unit = {
      mbLog.foreach( _.info( msg ) )
      INFO.log( msg )
    }
    def warn( msg : String ) : Unit = {
      mbLog.foreach( _.warn( msg ) )
      WARNING.log( msg )
    }

    Thread.sleep( 10 ) // with synchronization, kind of a hacksh way to make sure we don't generate backups with identical names

    val dbDumpSucceeded = {
      info( "Creating SQL dump of sbt-ethereum shoebox database..." )
      val fsuccess = Database.dump() map { dbDump =>
        info( s"Successfully created SQL dump of the sbt-ethereum shoebox database: '${dbDump.file}'" )
        true
      } recover { failed : Failed[_] =>
        warn( s"Failed to create SQL dump of the sbt-ethereum shoebox database. Failure: ${failed}" )
        false
      }
      fsuccess.assert
    }

    val outFile = new File( backupsDir, backupFileName( dbDumpSucceeded ) )

    val solcJCanonicalPrefix = SolcJ.Directory_ExistenceAndPermissionsUnenforced.assert.getCanonicalFile().getPath

    def canonicalFileFilter( cf : File ) : Boolean = {
      !cf.getPath.startsWith( solcJCanonicalPrefix )
    }

    info( s"Backing up sbt-ethereum shoebox. Reinstallable compilers will be excluded." )
    zip( outFile, shoebox.Directory_ExistenceAndPermissionsUnenforced.assert, canonicalFileFilter _ )
    info( s"sbt-ethereum shoebox successfully backed up to '${outFile}'." )

    if ( priorDatabaseFailureDetected || !dbDumpSucceeded ) {
      warn( "Some database errors were noted while performing this backup. (This does not affect the integrity of wallet files.)" )
    }
  }

  def restore( mbLog : Option[sbt.Logger], backupZipFile : File ) : Unit = {
    def info( msg : String ) : Unit = {
      mbLog.foreach( _.info( msg ) )
      INFO.log( msg )
    }
    def warn( msg : String ) : Unit = {
      mbLog.foreach( _.warn( msg ) )
      WARNING.log( msg )
    }
    val shoeboxDir         = shoebox.Directory_ExistenceAndPermissionsUnenforced.assert
    val shoeboxName        = shoeboxDir.getName
    val shoeboxParent      = shoeboxDir.getParentFile
    val oldShoeboxRenameTo = shoeboxName + s"-superseded-${timestamp}"

    if (! shoeboxDir.exists()) {
      warn( s"Shoebox directory '${shoeboxDir}' does not exist. Restoring." )
    }
    else {
      val renameTo = new File( shoeboxParent, oldShoeboxRenameTo )
      shoeboxDir.renameTo( renameTo )
      warn( s"Superseded existing shoebox directory renamed to '${renameTo}'. Consider deleting, eventually." )
    }
    unzip( shoeboxParent, backupZipFile )
    if (! shoeboxDir.exists() ) {
      val msg = s"Something strange happened. After restoring from a backup, the expected shoebox directory '${shoeboxDir}' does not exist. Please inspect parent directory '${shoeboxParent}'."
      throw nst( new SbtEthereumException( msg ) )
    } else {
      shoebox.repairPermissions
      info( s"sbt-ethereum shoebox restored from '${backupZipFile}" )
    }
  }

  def zip( dest : File, srcDir : File, canonicalFileFilter : ( File ) => Boolean ) : Unit = {
    require( srcDir.exists(), s"Cannot zip ${srcDir}, which does not exist." )
    require( srcDir.canRead(), s"Cannot zip ${srcDir}, we lack read permissions" )
    val srcDirName = {
      val raw = srcDir.getName
      if ( raw.endsWith( fsep ) ) raw.substring(0, raw.length - 1) else raw
    }
    val srcDirParentCanonicalPath = {
      val raw = srcDir.getParentFile().getCanonicalPath()
      if ( raw.endsWith(fsep) ) raw else raw + fsep
    }
    val sdpcpLen = srcDirParentCanonicalPath.length

    val srcDirParent = new File( srcDirParentCanonicalPath )

    def expectedPrefix( cf : File ) : Boolean = {
      if ( cf.getPath.startsWith( srcDirParentCanonicalPath ) ) {
        true
      }
      else {
        WARNING.log( s"Canonicalized file '${cf}' appears not to be a child of our canonical parent ${srcDirParentCanonicalPath}. Ignoring." )
        false
      }
      
    }

    val relativeInputFiles = {
      recursiveListBeneath( srcDir )
        .map( _.getCanonicalFile )
        .filter( expectedPrefix )
        .filter( _.isFile )
        .filter( canonicalFileFilter )
        .map( _.getPath.substring( sdpcpLen ) )
        .map( new File( _ ) )
    }

    zip( dest, srcDirParent, relativeInputFiles )
  }

  // modified from https://stackoverflow.com/questions/9985684/how-do-i-archive-multiple-files-into-a-zip-file-using-scala
  // we could use sbt.io.IO.zip(...) too, but it's nice to see how things work here for now
  private def zip( dest : File, inputFileParent : File, relativeInputFiles : Iterable[File] ) : Unit = {
    borrow( new ZipOutputStream(new BufferedOutputStream( new FileOutputStream(dest) ) ) ) { zip =>
      relativeInputFiles foreach { f =>
        INFO.log( s"zip: Adding '${f}'" )
        zip.putNextEntry( new ZipEntry( f.getPath ) )
        try {
          borrow( new BufferedInputStream(new FileInputStream( new File( inputFileParent, f.getPath ) ) ) ) { in =>
            var b = in.read()
            while (b >= 0) {
              zip.write(b)
              b = in.read()
            }
          }
        }
        finally {
          zip.closeEntry()
        }
      }
    }
  }
  private def unzip( destDir : File, zipFile : File ) : Unit = {
    borrow( new ZipFile( zipFile ) ) { zf =>
      val entries = {
        import collection.JavaConverters._
        zf.entries.asScala
      }
      entries foreach { entry =>
        val path = entry.getName().map( c => if (c == '\\' || c == '/') fsep else c ).mkString
        INFO.log( s"zip: Writing '${path}'" )
        val destFile = new File( destDir, path )
        destFile.getParentFile.mkdirs()
        borrow ( new BufferedInputStream( zf.getInputStream( entry ) ) ) { is =>
          borrow( new BufferedOutputStream( new FileOutputStream( destFile ) ) ) { os =>
            var b = is.read()
            while ( b >= 0 ) {
              os.write(b)
              b = is.read()
            }
          }
        }
      }
    }
  }
}

