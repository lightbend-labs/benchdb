lazy val root = project.in(file("."))
  .aggregate(core, plugin)
  .dependsOn(core)
  .settings(inThisBuild(Seq(
    organization := "com.lightbend.benchdb",
    //version := "0.1-SNAPSHOT",
    scalaVersion := "2.12.10",
    scalacOptions ++= Seq("-deprecation", "-unchecked"),
  )))
  .settings(
    publishArtifact := false,
    publish := {},
    publishLocal := {},
  )

lazy val core = project.in(file("core"))
  .settings(
    name := "benchdb-core",
    libraryDependencies ++= Seq(
      "org.eclipse.jgit" % "org.eclipse.jgit" % "5.6.1.202002131546-r",
      "com.monovore" %% "decline" % "1.0.0",
      "com.github.pathikrit" %% "better-files" % "3.8.0",
      "com.typesafe.slick" %% "slick" % "3.3.2",
      "mysql" % "mysql-connector-java" % "8.0.19" % "optional",
      "com.h2database" % "h2" % "1.4.200" % "optional",
      "com.typesafe" % "config" % "1.4.0",
      "org.slf4j" % "slf4j-api" % "1.7.30",
      "ch.qos.logback" % "logback-classic" % "1.1.6",
      "com.novocode" % "junit-interface" % "0.11" % "test"
    ),
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a"),
    fork in Test := true,
    parallelExecution in Test := false
  )

lazy val plugin = project.in(file("plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-benchdb",
    sbtPlugin := true,
    publishMavenStyle := false,
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
    bintrayReleaseOnPublish := false,
    bintrayRepository := "sbt-plugins",
    bintrayOrganization := None,
    scriptedLaunchOpts ++= Seq("-Xmx1024M", "-Dplugin.version=" + version.value),
    scriptedBufferLog := false,
    scriptedDependencies := { val _ = ((publishLocal in core).value, publishLocal.value) },
  )
