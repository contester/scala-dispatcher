import sbtprotobuf.{ProtobufPlugin=>PB}

seq(PB.protobufSettings: _*)

net.virtualvoid.sbt.graph.Plugin.graphSettings

enablePlugins(JavaAppPackaging)

enablePlugins(SbtTwirl)

name := "dispatcher"

fork in (Compile, run) := true

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m",
  "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.11.7"

version := "0.1"

organization := "org.stingray.contester"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-optimise", "-explaintypes", "-Xcheckinit",
  "-Xlint")

// javacOptions in Compile ++= Seq("-source", "1.6",  "-target", "1.7")

version in PB.protobufConfig := "2.6.1"

resolvers ++= Seq(
    "twitter.com" at "http://maven.twttr.com/",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases",
    "scala tools" at "http://scala-tools.org/repo-releases/",
    "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
    "typesafe artefactory" at "http://typesafe.artifactoryonline.com/typesafe/repo",
    "SpinGo OSS" at "http://spingo-oss.s3.amazonaws.com/repositories/releases",
    "stingr.net" at "http://stingr.net/maven"
)

val opRabbitVersion = "1.0.0-M11"

libraryDependencies ++= Seq(
  "com.foursquare" %% "twitter-util-async" % "1.1.0-SNAPSHOT",
  "com.spingo" %% "op-rabbit-core"        % opRabbitVersion,
  "com.spingo" %% "op-rabbit-play-json"   % opRabbitVersion,
  "io.netty" % "netty" % "3.10.1.Final",
  "com.twitter" %% "finagle-core" % "6.26.0",
  "com.twitter" %% "finagle-http" % "6.26.0",
  "com.twitter" %% "finagle-memcachedx" % "6.26.0",
  "com.twitter" %% "finagle-mysql" % "6.24.0",
  "com.twitter" %% "util-core" % "6.23.0",
  "com.twitter" %% "bijection-util" % "0.8.1",
  "org.mongodb" %% "casbah" % "2.7.4",
  "org.clapper" %% "grizzled-slf4j" % "1.0.2",
  "joda-time" % "joda-time" % "2.6",
  "org.joda" % "joda-convert" % "1.7",
  "org.apache.httpcomponents" % "httpclient" % "4.3.6",
  "commons-io" % "commons-io" % "2.4",
  "com.rabbitmq" % "amqp-client" % "3.4.2",
  "com.codahale" % "jerkson_2.9.1" % "0.5.0",
  "mysql" % "mysql-connector-java" % "5.1.34",
  "com.typesafe.play" %% "play" % "2.4.2",
  "com.typesafe.play" %% "play-netty-server" % "2.4.2",
  "com.typesafe" % "config" % "1.3.0",
  "com.googlecode.protobuf-java-format" % "protobuf-java-format" % "1.2",
  "org.scalatest" %% "scalatest" % "2.2.3" % "test"
).map(_.exclude("org.slf4j", "slf4j-jdk14"))

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

