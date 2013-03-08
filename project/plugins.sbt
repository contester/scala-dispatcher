resolvers += "gseitz@github" at "http://gseitz.github.com/maven/"

resolvers += Resolver.url("artifactory", url("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.github.gseitz" % "sbt-protobuf" % "0.2.2")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.8.6")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.0")

addSbtPlugin("com.mojolly.scalate" % "xsbt-scalate-generator" % "0.4.2")
