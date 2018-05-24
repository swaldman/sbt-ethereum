package com.mchange.sc.v1.sbtethereum.repository

import com.mchange.sc.v1.sbtethereum.repository

import java.io.{ BufferedInputStream, BufferedOutputStream, File, FileInputStream, FileOutputStream }
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.log.MLevel._

object Backup {

  private implicit lazy val logger = mlogger( this )

  private val fsep = File.separator

  def backupFileName = {
    val df = new SimpleDateFormat("yyyyMMdd'T'HHmmss.SSSZ")
    val ts = df.format( new Date() )
    s"sbt-ethereum-repository-backup-$ts.zip"
  }

  def perform( mbLog : Option[sbt.Logger], priorDatabaseFailureDetected : Boolean, backupsDir : File ) : Unit = this.synchronized {
    def info( msg : String ) : Unit = {
      mbLog.foreach( _.info( msg ) )
      INFO.log( msg )
    }

    Thread.sleep( 10 ) // with synchronization, kind of a hacksh way to make sure we don't generate backups with identical names

    info( "Backing up sbt-ethereum repository database..." )
    val dbBackup = Database.backup().assert
    info( s"Successfully created sbt-ethereum back up '${dbBackup}'" )

    val outFile = new File( backupsDir, backupFileName )

    val solcJCanonicalPrefix = SolcJ.Directory_ExistenceAndPermissionsUnenforced.assert.getCanonicalFile().getPath

    info( s"Backing up sbt-ethereum repository. Reinstallable compilers will be excluded." )
    zip( outFile, repository.Directory_ExistenceAndPermissionsUnenforced.assert, cf => !cf.getPath.startsWith( solcJCanonicalPrefix ) )
    info( s"sbt-ethereum repository successfully backed up to '${outFile}'." )
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
}

