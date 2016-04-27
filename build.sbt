import com.trueaccord.scalapb.{ScalaPbPlugin => PB}

PB.protobufSettings

PB.flatPackage in PB.protobufConfig := true

net.virtualvoid.sbt.graph.Plugin.graphSettings

enablePlugins(JavaAppPackaging)

enablePlugins(SbtTwirl)

name := "dispatcher"

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m",
  "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.11.8"

version := "0.1"

organization := "org.stingray.contester"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-optimise", "-explaintypes", "-Xcheckinit",
  "-Xlint")
//, "-Xfatal-warnings")

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

val opRabbitVersion = "1.3.0"

libraryDependencies ++= Seq(
  "com.spingo" %% "op-rabbit-core"        % opRabbitVersion,
  "com.spingo" %% "op-rabbit-play-json"   % opRabbitVersion,
  "io.netty" % "netty-common" % "4.1.0.CR7",
  "com.twitter" %% "finagle-core" % "6.34.0",
  "com.twitter" %% "finagle-http" % "6.34.0",
  "com.twitter" %% "finagle-memcached" % "6.34.0",
  "com.twitter" %% "finagle-redis" % "6.34.0",
  "com.twitter" %% "util-core" % "6.33.0",
  "com.twitter" %% "bijection-util" % "0.9.2",
  "org.clapper" %% "grizzled-slf4j" % "1.0.4",
  "com.github.nscala-time" %% "nscala-time" % "2.12.0",
  "org.apache.httpcomponents" % "httpclient" % "4.5.2",
  "commons-io" % "commons-io" % "2.4",
  "com.rabbitmq" % "amqp-client" % "3.6.1",
  "mysql" % "mysql-connector-java" % "5.1.38",
  "org.clapper" %% "avsl" % "1.0.4",
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "com.typesafe.play" %% "play" % "2.5.1",
  "com.typesafe.play" %% "play-netty-server" % "2.5.1",
  "com.typesafe" % "config" % "1.3.0",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
).map(_.exclude("org.slf4j", "slf4j-jdk14")).map(_.exclude("org.slf4j", "slf4j-log4j12"))
