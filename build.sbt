import AssemblyKeys._

import ScalateKeys._

import sbtprotobuf.{ProtobufPlugin=>PB}

assemblySettings

seq(scalateSettings:_*)

seq(PB.protobufSettings: _*)

net.virtualvoid.sbt.graph.Plugin.graphSettings

name := "dispatcher"

fork in (Compile, run) := true

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m", "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.10.1"

version := "0.1"

organization := "org.stingray.contester"

scalacOptions ++= Seq("-unchecked", "-deprecation")

javacOptions in Compile ++= Seq("-source", "1.6",  "-target", "1.6")

version in PB.protobufConfig := "2.5.0"

resolvers ++= Seq(
    "twitter.com" at "http://maven.twttr.com/",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases",
    "scala tools" at "http://scala-tools.org/repo-releases/",
    "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
    "typesafe artefactory" at "http://typesafe.artifactoryonline.com/typesafe/repo"
)

libraryDependencies ++= Seq(
  "io.netty" % "netty" % "3.6.6.Final",
  "com.twitter" %% "finagle-core" % "6.4.0",
  "com.twitter" %% "finagle-http" % "6.4.0",
  "org.streum" %% "configrity-core" % "1.0.0",
  "com.twitter" %% "util-core" % "6.3.4",
  "org.mongodb" %% "casbah" % "2.6.1",
  "org.clapper" % "avsl_2.10" % "1.0.1",
  "org.clapper" % "grizzled-slf4j_2.10" % "1.0.1",
  "joda-time" % "joda-time" % "2.2",
  "org.joda" % "joda-convert" % "1.3.1",
  "org.fusesource.scalate" %% "scalate-core" % "1.6.1",
  "commons-io" % "commons-io" % "2.4",
  "com.rabbitmq" % "amqp-client" % "3.1.1",
  "com.codahale" % "jerkson_2.9.1" % "0.5.0",
  "mysql" % "mysql-connector-java" % "5.1.25",
  "org.scalatest" %% "scalatest" % "1.9" % "test"
)

// Scalate Precompilation and Bindings
scalateTemplateConfig in Compile <<= (sourceDirectory in Compile){ base =>
  Seq(
    TemplateConfig(
      base / "resources" / "templates",
      Seq(),
      Seq(),
      Some("")
    ))}

excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
  cp.filter { p =>
    val x = p.data
    x.getPath.contains("org.scala-sbt") ||
    x.getName.startsWith("slf4j-nop") ||
    x.getName.startsWith("scalatest") ||
    x.getName.startsWith("sbt-idea") ||
    x.getName.startsWith("sbt-updates")
  }
}

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case "META-INF/NOTICE.txt"     => MergeStrategy.discard
    case "META-INF/LICENSE.txt"     => MergeStrategy.discard
    case x => old(x)
  }
}

