import AssemblyKeys._

import ScalateKeys._

import sbtprotobuf.{ProtobufPlugin=>PB}

assemblySettings

seq(scalateSettings:_*)

seq(PB.protobufSettings: _*)

net.virtualvoid.sbt.graph.Plugin.graphSettings

name := "scala-dispatcher"

fork in run := true

javaOptions in run += "-XX:+HeapDumpOnOutOfMemoryError"

scalaVersion := "2.9.2"

organization := "org.stingray.contester"

scalacOptions ++= Seq("-unchecked", "-deprecation")

resolvers ++= Seq(
    "twitter.com" at "http://maven.twttr.com/",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases",
    "scala tools" at "http://scala-tools.org/repo-releases/",
    "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
    "typesafe artefactory" at "http://typesafe.artifactoryonline.com/typesafe/repo"
)

libraryDependencies ++= Seq(
  "io.netty" % "netty" % "3.5.9.Final",
  "com.twitter" % "finagle-core" % "5.3.9",
  "com.twitter" % "finagle-http" % "5.3.9",
  "org.streum" %% "configrity-core" % "0.10.2",
  "com.twitter" % "util-core" % "5.3.13",
  "org.clapper" %% "grizzled-slf4j" % "0.6.9",
  "org.clapper" %% "avsl" % "0.4",
  "joda-time" % "joda-time" % "2.1",
  "org.joda" % "joda-convert" % "1.2",
  "org.fusesource.scalate" % "scalate-core" % "1.5.3",
  "commons-io" % "commons-io" % "2.3",
  "com.codahale" % "jerkson_2.9.1" % "0.5.0"
)

scalateTemplateDirectory in Compile <<= (baseDirectory) { _ / "src/main/resources/templates" }

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case "META-INF/NOTICE.txt"     => MergeStrategy.discard
    case "META-INF/LICENSE.txt"     => MergeStrategy.discard
    case x => old(x)
  }
}

