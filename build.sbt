enablePlugins(JavaAppPackaging)

enablePlugins(SbtTwirl)

PB.targets in Compile := Seq(
  scalapb.gen(flatPackage=true, grpc=false, javaConversions=false) -> (sourceManaged in Compile).value
)

// PB.runProtoc := (args => Process("/Users/stingray/bin/protoc", args)!)

name := "dispatcher"

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m",
  "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.12.6"

version := "2018.0.1"

organization := "org.stingray.contester"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-explaintypes", "-Xcheckinit",
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

val opRabbitVersion = "2.1.0"

val finagleVersion = "18.7.0"

val nettyVersion = "4.1.27.Final"

val playVersion = "2.6.17"

libraryDependencies ++= Seq(
  "javax.mail" % "javax.mail-api" % "1.6.1",
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
  "com.twitter" %% "util-core" % finagleVersion,
  "com.twitter" %% "bijection-util" % "0.9.6",
  "org.clapper" %% "grizzled-slf4j" % "1.3.2",
  "com.github.nscala-time" %% "nscala-time" % "2.20.0",
  "org.apache.httpcomponents" % "httpclient" % "4.5.6",
  "commons-io" % "commons-io" % "2.6",
  // "com.rabbitmq" % "amqp-client" % "4.1.0",
  "org.mariadb.jdbc" % "mariadb-java-client" % "2.2.6",
  "org.clapper" %% "avsl" % "1.0.15",
  "com.typesafe.slick" %% "slick" % "3.2.3",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.2.3",
  "com.typesafe.play" %% "play" % playVersion,
  "com.typesafe.play" %% "play-netty-server" % playVersion,
  "com.google.protobuf" % "protobuf-java" % "3.5.1" % "protobuf",
  "com.typesafe" % "config" % "1.3.3",
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
).map(_.exclude("org.slf4j", "slf4j-jdk14")).map(_.exclude("org.slf4j", "slf4j-log4j12"))
