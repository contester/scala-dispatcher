// addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.6" exclude ("com.trueaccord.scalapb", "protoc-bridge_2.10"))
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.12" exclude ("com.trueaccord.scalapb", "protoc-bridge_2.10"))

//libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin-shaded" % "0.6.0-pre3"

libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin-shaded" % "0.6.6"
