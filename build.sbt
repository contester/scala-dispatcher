
enablePlugins(JavaAppPackaging)

enablePlugins(SbtTwirl)

PB.targets in Compile := Seq(
  scalapb.gen(flatPackage=true) -> (sourceManaged in Compile).value
)

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

updateOptions := updateOptions.value.withCachedResolution(true)

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

val finagleVersion = "6.36.0"

val nettyVersion = "4.1.4.Final"

val playVersion = "2.4.8"

libraryDependencies ++= Seq(
  "com.spingo" %% "op-rabbit-core"        % opRabbitVersion,
  "com.spingo" %% "op-rabbit-play-json"   % opRabbitVersion,
  "io.netty" % "netty-common" % nettyVersion,
  "io.netty" % "netty-transport-native-epoll" % nettyVersion,
  "io.netty" % "netty-codec-http" % nettyVersion,
  "io.netty" % "netty-codec" % nettyVersion,
  "io.netty" % "netty-handler" % nettyVersion,
  "com.twitter" %% "finagle-core" % finagleVersion,
  "com.twitter" %% "finagle-http" % finagleVersion,
  "com.twitter" %% "finagle-memcached" % finagleVersion,
  "com.twitter" %% "finagle-redis" % finagleVersion,
  "com.twitter" %% "util-core" % "6.35.0",
  "com.twitter" %% "bijection-util" % "0.9.2",
  "org.clapper" %% "grizzled-slf4j" % "1.1.0",
  "com.github.nscala-time" %% "nscala-time" % "2.12.0",
  "org.apache.httpcomponents" % "httpclient" % "4.5.2",
  "commons-io" % "commons-io" % "2.5",
  "com.rabbitmq" % "amqp-client" % "3.6.5",
  "org.mariadb.jdbc" % "mariadb-java-client" % "1.4.6",
  "org.clapper" %% "avsl" % "1.0.11",
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "com.typesafe.play" %% "play" % playVersion,
  "com.typesafe.play" %% "play-netty-server" % playVersion,
  "com.typesafe" % "config" % "1.3.0",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
).map(_.exclude("org.slf4j", "slf4j-jdk14")).map(_.exclude("org.slf4j", "slf4j-log4j12"))
