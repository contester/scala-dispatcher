enablePlugins(JavaAppPackaging)

enablePlugins(SbtTwirl)

PB.targets in Compile := Seq(
  scalapb.gen(flatPackage=true, grpc=false, javaConversions=false) -> (sourceManaged in Compile).value
)

name := "dispatcher"

javaOptions in run ++= Seq("-XX:+HeapDumpOnOutOfMemoryError", "-Xloggc:gclog.txt", "-Xms512m", "-Xmx512m",
  "-XX:MaxPermSize=256m", "-XX:+CMSClassUnloadingEnabled")

scalaVersion := "2.12.18"

version := "2023.0.1"

maintainer := "i@stingr.net"

organization := "org.stingray.contester"

ThisBuild / useCoursier := false


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
  Resolver.mavenLocal,
  Resolver.jcenterRepo,
  Resolver.typesafeRepo("releases")
) ++ Resolver.sonatypeOssRepos("snapshots")

val opRabbitVersion = "2.1.0"

val finagleVersion = "22.12.0"

val nettyVersion = "4.1.99.Final"

val playVersion = "2.8.20"

val slickPG = "0.21.1"

// ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-parser-combinators" % "always"

libraryDependencies ++= Seq(
  "org.stingray.contester" %% "contester-dbmodel" % "2023.0.1-SNAPSHOT",
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
  "org.scala-lang.modules" %% "scala-async" % "1.0.1",
  "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided,
  "com.github.nscala-time" %% "nscala-time" % "2.32.0",
  "org.apache.httpcomponents" % "httpclient" % "4.5.14",
  "commons-io" % "commons-io" % "2.7",
  "org.mariadb.jdbc" % "mariadb-java-client" % "3.2.0",
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  "com.typesafe.slick" %% "slick" % "3.4.1",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.4.1",
  "com.typesafe.play" %% "play" % playVersion,
  "com.typesafe.play" %% "play-netty-server" % playVersion,
  "com.typesafe" % "config" % "1.4.2",
  "info.faljse" % "SDNotify" % "1.5",
  "org.postgresql" % "postgresql" % "42.6.0",
  "com.github.tminglei" %% "slick-pg" % slickPG,
  "com.github.tminglei" %% "slick-pg_joda-time" % slickPG,
  "com.github.tminglei" %% "slick-pg_play-json" % slickPG,
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  "org.scalatest" %% "scalatest" % "3.2.17" % "test"
).map(_.exclude("org.slf4j", "slf4j-jdk14")).map(_.exclude("org.slf4j", "slf4j-log4j12"))

libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

libraryDependencySchemes += "com.typesafe.slick" %% "slick" % VersionScheme.Always

libraryDependencySchemes += "com.typesafe.slick" %% "slick-hikaricp" % VersionScheme.Always
