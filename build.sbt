val nexus = "https://oss.sonatype.org/"
val nexusSnapshots = nexus + "content/repositories/snapshots"
val nexusReleases = nexus + "service/local/staging/deploy/maven2"

organization := "com.mchange"

name := "sbt-ethereum"

version := "0.1.5"

sbtPlugin := true

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked" /*,
  "-Xlog-implicits" */
)

resolvers += ("releases" at nexusReleases)

resolvers += ("snapshots" at nexusSnapshots)

resolvers += ("Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/")

publishTo := version {
  (v: String) => {
    if (v.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexusSnapshots )
    else
      Some("releases"  at nexusReleases )
  }
}.value

val consuelaArtifact : ModuleID = "com.mchange" %% "consuela" % "0.0.9"

libraryDependencies ++= Seq(
  consuelaArtifact,
  "com.mchange"    %% "etherscan-utils"       % "0.0.1",
  "com.mchange"    %% "mlog-scala"            % "0.3.10",
  "com.mchange"    %% "literal"               % "0.0.2",
  "com.mchange"    %% "danburkert-continuum"  % "0.3.99",
  "com.mchange"    %% "ens-scala"             % "0.0.6",
  "com.mchange"    %% "texttable"             % "0.0.2",
  "com.mchange"    %  "c3p0"                  % "0.9.5.2",
  "com.h2database" %  "h2"                    % "1.4.192",
  "ch.qos.logback" %  "logback-classic"       % "1.1.7"
)

val generatedCodePackageDir = Vector( "com", "mchange", "sc", "v1", "sbtethereum", "generated" )

// embed build-time information into a package object for com.mchange.sc.v1.sbtethereum.generated

sourceGenerators in Compile += Def.task{
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

pomExtra := {
    <url>https://github.com/swaldman/{name.value}</url>
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
      <url>git@github.com:swaldman/{name.value}.git</url>
      <connection>scm:git:git@github.com:swaldman/{name.value}</connection>
    </scm>
    <developers>
      <developer>
        <id>swaldman</id>
        <name>Steve Waldman</name>
        <email>swaldman@mchange.com</email>
      </developer>
    </developers>
}

