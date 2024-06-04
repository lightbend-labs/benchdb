lazy val root = project.in(file("."))
  .enablePlugins(JmhPlugin)
  .settings(
    scalaVersion := "2.13.1"
  )

benchdbNoUserConfig := true
benchdbConfig += baseDirectory.value / "benchdb.conf"
// H2 requires an absolute path for the database so we have to add it as an override:
benchdbConfigOverride += "db.db.url" -> ("jdbc:h2:" + (baseDirectory.value / "benchdb-test-data").toPath.toAbsolutePath.toString)
benchdbDependencies += "com.h2database" % "h2" % "2.1.212"
