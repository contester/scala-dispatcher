enablePlugins(JavaAppPackaging)

enablePlugins(SbtTwirl)

PB.targets in Compile := Seq(
  scalapb.gen(flatPackage=true, grpc=false, javaConversions=false) -> (sourceManaged in Compile).value
)

// PB.runProtoc := (args => Process("/Users/stingray/bin/protoc", args)!)

name := "dispatcher"

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m",
  "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.12.12"

version := "2020.0.1"

maintainer := "i@stingr.net"

organization := "org.stingray.contester"

scalacOptions ++= Seq(
  "-Xfatal-warnings",  // New lines for each options
  "-deprecation",
  "-Xasync",
  "-unchecked",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:existentials",
  "-language:postfixOps",
  "-opt:l:method",
  "-opt:l:inline",
  "-opt-inline-from:<sources>"
)

updateOptions := updateOptions.value.withCachedResolution(true)

resolvers ++= Seq(
  Resolver.jcenterRepo,
  Resolver.sonatypeRepo("snapshots"),
  Resolver.typesafeRepo("releases")
)

val opRabbitVersion = "2.1.0"

val finagleVersion = "20.5.0"

val nettyVersion = "4.1.50.Final"

val playVersion = "2.7.5"

val slickPG = "0.19.0"

libraryDependencies ++= Seq(
  "javax.mail" % "javax.mail-api" % "1.6.2",
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
  "com.twitter" %% "bijection-util" % "0.9.7",
  "org.scala-lang.modules" %% "scala-async" % "0.10.0",
  "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided,
  "com.github.nscala-time" %% "nscala-time" % "2.24.0",
  "org.apache.httpcomponents" % "httpclient" % "4.5.12",
  "commons-io" % "commons-io" % "2.7",
  "org.mariadb.jdbc" % "mariadb-java-client" % "2.6.0",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.slick" %% "slick" % "3.3.2",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.2",
  "com.typesafe.play" %% "play" % playVersion,
  "com.typesafe.play" %% "play-netty-server" % playVersion,
  "com.typesafe" % "config" % "1.4.0",
  "info.faljse" % "SDNotify" % "1.3",
  "org.postgresql" % "postgresql" % "42.2.12",
  "com.github.tminglei" %% "slick-pg" % slickPG,
  "com.github.tminglei" %% "slick-pg_joda-time" % slickPG,
  "com.github.tminglei" %% "slick-pg_play-json" % slickPG,
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
).map(_.exclude("org.slf4j", "slf4j-jdk14")).map(_.exclude("org.slf4j", "slf4j-log4j12"))
