package com.lightbend.benchdb.sbtplugin

import java.net.URLClassLoader
import java.nio.file.Files

import sbt._
import Keys._

import scala.util.Try

object BenchdbPlugin extends AutoPlugin {
  private val jmhPlugin = Try(Class.forName("pl.project13.scala.sbt.JmhPlugin$").getField("MODULE$").get(null).asInstanceOf[AutoPlugin])

  override def requires =
    if(jmhPlugin.isSuccess) (plugins.IvyPlugin && jmhPlugin.get) else plugins.IvyPlugin
  override def trigger =
    if(jmhPlugin.isSuccess) allRequirements else noTrigger

  object autoImport {
    val benchdb               = inputKey[Unit]("Run benchdb")
    val benchdbRun            = inputKey[Unit]("Run sbt-jmh and insert into benchdb")
    val benchdbConfig         = settingKey[Seq[File]]("Additional configuration files for benchdb")
    val benchdbNoUserConfig   = settingKey[Boolean]("Ignore benchdb user configuration")
    val benchdbConfigOverride = settingKey[Seq[(String, String)]]("Additional key/value pairs to apply on top of the benchdb configuration")
    val benchdbDependencies   = settingKey[Seq[ModuleID]]("Additional dependencies for benchdb")
    lazy val Benchdb          = config("benchdb").hide // provides the classpath for benchdb
  }
  import autoImport._

  val JmhConfig = config("jmh") extend Test

  override lazy val globalSettings = Seq(
    benchdbConfig := Nil,
    benchdbNoUserConfig := false,
    benchdbDependencies := Nil,
    benchdbConfigOverride := Nil,
  )

  override lazy val projectSettings = inConfig(Benchdb)(Defaults.configSettings) ++ inConfig(Benchdb)(Seq(
    scalaVersion := BuildInfo.scalaVersion,
    crossScalaVersions := Seq(BuildInfo.scalaVersion),
    autoScalaLibrary := false
  )) ++ inConfig(JmhConfig)(Seq(
    benchdb := benchdbTask.evaluated,
    benchdbRun := benchdbRunTask.evaluated,
  )) ++ Seq(
    ivyConfigurations += Benchdb,
    libraryDependencies ++= Seq(
      BuildInfo.organization % (BuildInfo.name+"_2.12") % BuildInfo.version % Benchdb,
      "org.scala-lang" % "scala-library" % BuildInfo.scalaVersion % Benchdb,
    ) ++ (benchdbDependencies in JmhConfig).value.map(_ % Benchdb)
  )

  lazy val benchdbTask = Def.inputTask[Unit] {
    import complete.DefaultParsers._
    val log = streams.value.log
    val args = spaceDelimited("<arg>").parsed
    val extraArgs: Seq[String] =
      (if(benchdbNoUserConfig.value) Seq("--no-user-config") else Nil) ++
        benchdbConfig.value.map(_.toPath.toAbsolutePath.toString).flatMap(p => Seq("--config", p)) ++
        benchdbConfigOverride.value.flatMap { case (k, v) => Seq("--set", s"$k=$v") }
    val benchdbArgs: Seq[String] = if(args == Seq("--help")) args else (extraArgs ++ args)
    log.debug("benchdb arguments: "+benchdbArgs)
    val cp = (dependencyClasspath in Benchdb).value.files.map(_.toURI.toURL)
    log.debug("benchdb classpath: "+cp)
    runBenchdb(cp, benchdbArgs)
  }

  lazy val benchdbRunTask = Def.inputTaskDyn[Unit] {
    import complete.DefaultParsers._
    val log = streams.value.log
    val (msg, rest) = (token(Space) ~> (token("--msg") ~ SpaceClass.+ ~> token(StringBasic, "<message>") <~ SpaceClass.*).? ~ any.*.string).parsed
    val tmpfile = Files.createTempFile("benchdb-jmh-", ".json").path.toAbsolutePath.toString
    val qtmpfile = "\"" + quote(tmpfile) + "\""
    val extraArgs: Seq[String] =
      (if(benchdbNoUserConfig.value) Seq("--no-user-config") else Nil) ++
        benchdbConfig.value.map(_.toPath.toAbsolutePath.toString).flatMap(p => Seq("--config", p)) ++
        benchdbConfigOverride.value.flatMap { case (k, v) => Seq("--set", s"$k=$v") }
    val cp = (dependencyClasspath in Benchdb).value.files.map(_.toURI.toURL)
    val projectDir = (baseDirectory in JmhConfig).value.toPath.toAbsolutePath.toString
    val benchdbArgs = extraArgs ++ Seq("insert-run", "--project-dir", projectDir, "--jmh-args", rest) ++
      msg.fold[Seq[String]](Nil)(s => Seq("--msg", s)) ++
      Seq(tmpfile)
    log.debug("benchdb arguments: "+benchdbArgs)
    Def.taskDyn[Unit] {
      (JmhConfig / run).toTask(s" -rf json -rff $qtmpfile $rest").value
      runBenchdb(cp, benchdbArgs)
      Def.task { () }
    }
  }

  def runBenchdb(cp: Seq[URL], args: Seq[String]): Unit = {
    val parent = ClassLoader.getSystemClassLoader.getParent
    val loader = new URLClassLoader(cp.toArray, parent)
    val old = Thread.currentThread.getContextClassLoader
    try {
      Thread.currentThread.setContextClassLoader(loader)
      val cl = loader.loadClass("com.lightbend.benchdb.Main")
      val runToStatus = cl.getMethod("runToStatus", classOf[Array[String]])
      val status = runToStatus.invoke(null, args.toArray).asInstanceOf[Int]
      if(status != 0) throw new MessageOnlyException("benchdb failed with status code "+status)
    } finally {
      Thread.currentThread.setContextClassLoader(old)
      loader.close()
    }
  }

  def quote(s: String): String = {
    val b = new StringBuilder(s.length)
    var i = 0
    while(i < s.length) {
      s.charAt(i) match {
        case '\b' => b.append("\\b")
        case '\t' => b.append("\\t")
        case '\n' => b.append("\\n")
        case '\f' => b.append("\\f")
        case '\r' => b.append("\\r")
        case '\"' => b.append("\\\"")
        case '\'' => b.append("\\'")
        case '\\' => b.append("\\\\")
        case c => b.append(c)
      }
      i += 1
    }
    b.toString
  }
}
