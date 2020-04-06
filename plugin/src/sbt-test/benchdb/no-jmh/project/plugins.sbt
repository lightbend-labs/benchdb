addSbtPlugin("com.lightbend.benchdb" % "sbt-benchdb" % Option(System.getProperty("plugin.version")).getOrElse(
  throw new RuntimeException("System property 'plugin.version' must be set to sbt-benchdb's version")
))
