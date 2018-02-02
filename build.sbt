val nexus = "https://oss.sonatype.org/"
val nexusSnapshots = nexus + "content/repositories/snapshots"
val nexusReleases = nexus + "service/local/staging/deploy/maven2"

val sbtCoreNext = Def.setting {
  Defaults.sbtPluginExtra("org.scala-sbt" % "sbt-core-next" % "0.1.1", sbtBinaryVersion.value, scalaBinaryVersion.value)
}

organization := "com.mchange"

name := "sbt-ethereum"

version := "0.1.0-SNAPSHOT" // 0.0.x is for sbt-0.13 versions

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

// libraryDependencies += sbtCoreNext.value

libraryDependencies ++= Seq(
  "com.mchange"    %% "consuela"              % "0.0.4",
  "com.mchange"    %% "mchange-commons-scala" % "0.4.4",
  "com.mchange"    %% "mlog-scala"            % "0.3.10",
  "com.mchange"    %% "literal"               % "0.0.2",
  "com.mchange"    %% "danburkert-continuum"  % "0.3.99",
  "com.mchange"    %  "c3p0"                  % "0.9.5.2",
  "com.h2database" %  "h2"                    % "1.4.192",
  "ch.qos.logback" %  "logback-classic"       % "1.1.7"
)

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

