val nexus = "https://oss.sonatype.org/"
val nexusSnapshots = nexus + "content/repositories/snapshots"
val nexusStaging = nexus + "service/local/staging/deploy/maven2"

val consuelaApiBase = settingKey[String]("Base of consuela API docs")
val updateSite = taskKey[Unit]("Updates the project website on tickle")

ThisBuild / organization := "com.mchange"
ThisBuild / version := "0.5.0-SNAPSHOT"

ThisBuild / resolvers += ("releases" at nexusStaging)
ThisBuild / resolvers += ("snapshots" at nexusSnapshots)
ThisBuild / resolvers += ("Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/")

ThisBuild / publishTo := {
  if (isSnapshot.value) Some("snapshots" at nexusSnapshots ) else Some("staging" at nexusStaging )
}

ThisBuild / scalaVersion := "2.12.13"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked" /*,
  "-Xlog-implicits" */
)

val consuelaArtifact : ModuleID = "com.mchange" %% "consuela" % "0.4.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(ParadoxPlugin).settings (
  name := "sbt-ethereum",
  sbtPlugin := true,
  libraryDependencies ++= Seq(
    consuelaArtifact,
    "com.mchange"    %% "etherscan-utils"       % "0.1.0-SNAPSHOT",
    "com.mchange"    %% "mlog-scala"            % "0.3.13",
    "com.mchange"    %% "literal"               % "0.1.0",
    "com.mchange"    %% "danburkert-continuum"  % "0.3.99",
    "com.mchange"    %% "ens-scala"             % "0.4.0-SNAPSHOT",
    "com.mchange"    %% "texttable"             % "0.0.2",
    "com.mchange"    %  "mchange-commons-java"  % "0.2.20",
    "com.mchange"    %  "c3p0"                  % "0.9.5.5",
    "com.h2database" %  "h2"                    % "1.4.192",
    "ch.qos.logback" %  "logback-classic"       % "1.1.7"
  ),
  sourceGenerators in Compile += generateBuildInfoSourceGeneratorTask,
  paradoxTheme := None,
  paradoxNavigationDepth := 3,
  consuelaApiBase := {
    val cVersion = consuelaArtifact.revision
    s"https://www.mchange.com/projects/consuela/version/${cVersion}/apidocs"
  },
  packageOptions in (Compile, packageBin) +=  {
    import java.util.jar.{Attributes, Manifest}
    val manifest = new Manifest
    val entries = manifest.getEntries()
    def seal( pkgpath : String ) = {
      entries.put(pkgpath, new Attributes())
      manifest.getAttributes(pkgpath).put(Attributes.Name.SEALED, "true")
    }
    val bp = "com/mchange/sc/v1/sbtethereum/"
    val pkgpaths = Seq(
      bp,
      bp + "shoebox/",
      bp + "util/",
      bp + "testing/",
      bp + "compile/",
      bp + "lib/",
      bp + "api/",
      bp + "signer/",
    )
    pkgpaths.foreach( seal )
    Package.JarManifest( manifest )
  },
  paradoxProperties += ("scaladoc.com.mchange.sc.v1.consuela.base_url" -> consuelaApiBase.value),
  updateSite := {
    import scala.sys.process._

    val dummy = (Compile / paradox).value // force a build of the site

    val localDir = target.value / "paradox" / "site" / "main"

    val local = localDir.listFiles.map( _.getPath ).mkString(" ")
    val remote = s"tickle.mchange.com:/home/web/public/www.sbt-ethereum.io/version/${version.value}/"
    s"rsync -avz ${local} ${remote}"!
  },
  pomExtra := pomExtraForProjectName( name.value )
)

// embed build-time information into a package object for com.mchange.sc.v1.sbtethereum.generated

val generatedCodePackageDir = Vector( "com", "mchange", "sc", "v1", "sbtethereum", "generated" )

val generateBuildInfoSourceGeneratorTask = Def.task{
  import java.io._
  import java.nio.file.Files

  import scala.io.Codec

  import java.time._
  import java.time.format._

  val srcManaged    = (sourceManaged in Compile).value
  val sbtEthVersion = version.value

  val consuelaOrg           = consuelaArtifact.organization
  val consuelaName          = consuelaArtifact.name
  val consuelaVersion       = consuelaArtifact.revision
  val consuelaMaybeChanging = if (consuelaVersion.endsWith("SNAPSHOT")) " changing()" else ""

  val ts = DateTimeFormatter.RFC_1123_DATE_TIME.withZone( ZoneId.systemDefault() ).format(java.time.Instant.now)

  val sep = File.separator
  val outDir = new File( srcManaged, generatedCodePackageDir.mkString( sep ) )
  val outFile = new File( outDir, "package.scala" )
  val contents = {
    s"""|package ${generatedCodePackageDir.init.mkString(".")}
        |
        |package object ${generatedCodePackageDir.last} {
        |  final object SbtEthereum {
        |    val Version        = "${sbtEthVersion}"
        |    val BuildTimestamp = "${ts}"
        |  }
        |  final object Consuela {
        |    import sbt._
        |    val ModuleID = "${consuelaOrg}" %% "${consuelaName}" % "${consuelaVersion}"${consuelaMaybeChanging}
        |  }
        |}""".stripMargin
  }
  outDir.mkdirs()
  Files.write( outFile.toPath, contents.getBytes( Codec.UTF8.charSet ) )
  outFile :: Nil
}

// publication, pom extra stuff

def pomExtraForProjectName( projectName : String ) = {
    <url>https://github.com/swaldman/{projectName}</url>
    <licenses>
      <license>
        <name>GNU Lesser General Public License, Version 2.1</name>
        <url>http://www.gnu.org/licenses/lgpl-2.1.html</url>
        <distribution>repo</distribution>
      </license>
      <license>
        <name>Eclipse Public License, Version 1.0</name>
        <url>http://www.eclipse.org/org/documents/epl-v10.html</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:swaldman/{projectName}.git</url>
      <connection>scm:git:git@github.com:swaldman/{projectName}</connection>
    </scm>
    <developers>
      <developer>
        <id>swaldman</id>
        <name>Steve Waldman</name>
        <email>swaldman@mchange.com</email>
      </developer>
    </developers>
}



