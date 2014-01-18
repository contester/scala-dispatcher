import sbtprotobuf.{ProtobufPlugin=>PB}

import com.typesafe.sbt.SbtProguard._
import com.typesafe.sbt.SbtProguard.ProguardKeys.proguard

import ScalateKeys._

import AssemblyKeys._

assemblySettings

proguardSettings

javaOptions in (Proguard, proguard) := Seq("-Xmx2G")

seq(scalateSettings:_*)

seq(PB.protobufSettings: _*)

net.virtualvoid.sbt.graph.Plugin.graphSettings

name := "dispatcher"

fork in (Compile, run) := true

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m", "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.10.3"

version := "0.1"

organization := "org.stingray.contester"

scalacOptions ++= Seq("-unchecked", "-deprecation")

javacOptions in Compile ++= Seq("-source", "1.6",  "-target", "1.7")

version in PB.protobufConfig := "2.5.0"

ProguardKeys.options in Proguard ++= Seq("-dontnote", "-dontwarn", "-ignorewarnings")

ProguardKeys.options in Proguard += ProguardOptions.keepMain("org.stingray.contester.dispatcher.DispatcherServer")

resolvers ++= Seq(
    "twitter.com" at "http://maven.twttr.com/",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases",
    "scala tools" at "http://scala-tools.org/repo-releases/",
    "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
    "typesafe artefactory" at "http://typesafe.artifactoryonline.com/typesafe/repo",
    "stingr.net" at "http://stingr.net/maven"
)

libraryDependencies ++= Seq(
  "org.stingray" %% "simpleweb" % "0.1-SNAPSHOT",
  "io.netty" % "netty" % "3.9.0.Final",
  "com.twitter" %% "finagle-core" % "6.10.0",
  "com.twitter" %% "finagle-http" % "6.10.0",
  "com.twitter" %% "finagle-memcached" % "6.10.0",
  "org.streum" %% "configrity-core" % "1.0.0",
  "com.twitter" %% "util-core" % "6.10.0",
  "org.mongodb" %% "casbah" % "2.6.4",
  "org.clapper" % "avsl_2.10" % "1.0.1",
  "org.clapper" % "grizzled-slf4j_2.10" % "1.0.1",
  "joda-time" % "joda-time" % "2.3",
  "org.joda" % "joda-convert" % "1.5",
  "org.fusesource.scalate" %% "scalate-core" % "1.6.1",
  "org.apache.httpcomponents" % "httpclient" % "4.3.1",
  "commons-io" % "commons-io" % "2.4",
  "com.rabbitmq" % "amqp-client" % "3.2.2",
  "com.codahale" % "jerkson_2.9.1" % "0.5.0",
  "mysql" % "mysql-connector-java" % "5.1.28",
  "com.googlecode.protobuf-java-format" % "protobuf-java-format" % "1.2",
  "org.scalatest" %% "scalatest" % "1.9.2" % "test"
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

