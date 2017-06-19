
enablePlugins(JavaAppPackaging)

enablePlugins(SbtTwirl)

PB.targets in Compile := Seq(
  scalapb.gen(flatPackage=true, grpc=false, javaConversions=false) -> (sourceManaged in Compile).value
)

PB.runProtoc := (args => Process("protoc", args)!)

name := "dispatcher"

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m",
  "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.11.11"

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

val opRabbitVersion = "2.0.0-rc1"

val finagleVersion = "6.44.0"

val nettyVersion = "4.1.12.Final"

val playVersion = "2.6.0-M2"

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
  "com.twitter" %% "util-core" % "6.43.0",
  "com.twitter" %% "bijection-util" % "0.9.5",
  "org.clapper" %% "grizzled-slf4j" % "1.3.1",
  "com.github.nscala-time" %% "nscala-time" % "2.16.0",
  "org.apache.httpcomponents" % "httpclient" % "4.5.3",
  "commons-io" % "commons-io" % "2.5",
  "com.rabbitmq" % "amqp-client" % "4.1.0",
  "org.mariadb.jdbc" % "mariadb-java-client" % "1.6.1",
  "org.clapper" %% "avsl" % "1.0.13",
  "com.typesafe.slick" %% "slick" % "3.2.0",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.2.0",
  "com.zaxxer" % "HikariCP" % "2.4.5",
  "com.typesafe.play" %% "play" % playVersion,
  "com.typesafe.play" %% "play-netty-server" % playVersion,
  "com.typesafe" % "config" % "1.3.1",
  "com.trueaccord.scalapb" %% "scalapb-runtime" % com.trueaccord.scalapb.compiler.Version.scalapbVersion % "protobuf",
  "org.scalatest" %% "scalatest" % "3.0.3" % "test"
).map(_.exclude("org.slf4j", "slf4j-jdk14")).map(_.exclude("org.slf4j", "slf4j-log4j12")).map(_.exclude("com.zaxxer", "HikariCP-java6"))
