package com.lightbend.benchdb

import cats.implicits._
import com.monovore.decline._
import java.nio.file.{FileSystems, Files, Path}
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.regex.PatternSyntaxException

import better.files._

import scala.collection.JavaConverters._
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigRenderOptions, ConfigValueFactory}

object Main extends Logging {

  def main(args: Array[String]) = try {

    val config = Opts.option[Path]("config", help = "Configuration file.").orNone
    val noUserConfig = Opts.flag("no-user-config", help = "Don't read ~/.benchdb.conf").orFalse
    val props = Opts.options[String]("set", short = "D", help = "Overwrite a configuration option (key=value)").map(_.toList).orElse(Opts(Nil))

    val globalOptions = (config, noUserConfig, props).mapN(GlobalOptions).map(_.validate())

    val force = Opts.flag("force", "Required to actually perform the operation.").orFalse

    val showMetaCommand = Command[GlobalOptions => Unit](name = "show-meta", header =
      """Show the meta data for a project without storing it.
        |Use the current directory if no project-dir is specified.
      """.stripMargin, helpFlag = false) {
      val projectDir = Opts.argument[Path](metavar = "project-dir").withDefault(FileSystems.getDefault.getPath(""))
      projectDir.map { path => showMeta(_, path) }
    }

    val initDbCommand = Command[GlobalOptions => Unit](name = "init-db", header =
      """Create the database schema.
      """.stripMargin) {
      force.map { f => initDb(_, f) }
    }

    val deleteDbCommand = Command[GlobalOptions => Unit](name = "delete-db", header =
      """Drop the database schema.
      """.stripMargin) {
      force.map { f => deleteDb(_, f) }
    }

    val insertRunCommand = Command[GlobalOptions => Unit](name = "insert-run", header =
      "Insert a JMH result file into the database.") {
      val projectDir = Opts.option[Path]("project-dir",
        help = "Project directory from which git data is read (defaults to the current directory)"
      ).withDefault(FileSystems.getDefault.getPath(""))
      val message = Opts.option[String]("msg", help = "Comment message to store with the database entry.").orNone
      val resultFile = Opts.argument[Path](metavar = "result-json-file")
      val jmhArgs = Opts.arguments[String](metavar = "jmh-args").map(_.toList).orElse(Opts(Nil))
      (projectDir, message, resultFile, jmhArgs).mapN { case (projectDir, message, resultFile, jmhArgs) => insertRun(_, projectDir, message, resultFile, jmhArgs) }
    }

    val runs = Opts.options[Long]("run", short = "r", help = "IDs of the test runs to include.").map(_.toList).orElse(Opts(Nil))
    val benchs = Opts.options[String]("benchmark", short = "b", help = "Glob patterns of benchmark names to include.").map(_.toList).orElse(Opts(Nil))
    val extract = Opts.options[String]("extract", help = "Extractor pattern to generate parameters from names.").map(_.toList).orElse(Opts(Nil))
    val scorePrecision = Opts.option[Int]("score-precision", help = "Precision of score and error in tables (default: 3)").withDefault(3)
    val queryResultsCommand = Command[GlobalOptions => Unit](name = "results", header =
      "Query the database for test results and print them.") {
      val raw = Opts.flag("raw", "Print raw JSON data instead of a table.").orFalse
      (runs, benchs, extract, scorePrecision, raw).mapN { case (runs, benchs, extract, sp, raw) => queryResults(_, runs, benchs, extract, sp, raw) }
    }
    val chartCommand = Command[GlobalOptions => Unit](name = "chart", header =
      "Create a line chart of test results.") {
      val template = Opts.option[Path]("template", "HTML template containing file.").orNone
      val out = Opts.option[Path]("out", short = "o", help = "Output file to generate, or '-' for stdout.").orNone
      (runs, benchs, scorePrecision, template, out).mapN { case (runs, benchs, sp, template, out) => createChart(_, runs, benchs, sp, template, out, args) }
    }

    val listRunsCommand = Command[GlobalOptions => Unit](name = "list", header =
      "Query the database for test runs and print them.") {
      val limit = Opts.option[Int]("limit", help = "Maximum number of runs to list.").orNone
      val gitData = Opts.flag("git-data", help = "Include git data.").orFalse
      val platformData = Opts.flag("platform-data", help = "Include platform data.").orFalse
      (limit, gitData, platformData).mapN { case (limit, gd, pd) => listRuns(_, limit, gd, pd) }
    }

    val benchdbCommand = Command("benchdb", "jmh benchmark database client") {
      val sub = Opts.subcommands(showMetaCommand, initDbCommand, deleteDbCommand, insertRunCommand, queryResultsCommand, chartCommand, listRunsCommand)
      (globalOptions, sub).mapN { (go, cmd) => cmd(go) }
    }

    benchdbCommand.parse(args, sys.env) match {
      case Left(help) =>
        System.err.print(help)
        System.exit(1)
      case Right(_) =>
        System.exit(if(ErrorRecognitionAppender.rearm()) 1 else 0)
    }
  } catch { case _: Abort => System.exit(1) }

  def showMeta(go: GlobalOptions, projectDir: Path): Unit = {
    val gd = new GitData(projectDir)
    val pd = new PlatformData
    val m = new java.util.HashMap[String, AnyRef]
    m.put("git", gd.javaMapData)
    m.put("platform", pd.javaMapData)
    println(ConfigFactory.parseMap(m).root.render(ConfigRenderOptions.defaults().setOriginComments(false)))
  }

  def initDb(go: GlobalOptions, force: Boolean): Unit = {
    new Global(go).use { g =>
      if(force) {
        g.dao.run(g.dao.createDb)
        println("Database initialized.")
      } else {
        println(g.dao.run(g.dao.getDbInfo))
        println("Use --force to actually initialize the database.")
      }
    }
  }

  def deleteDb(go: GlobalOptions, force: Boolean): Unit = {
    new Global(go).use { g =>
      if(force) {
        g.dao.run(g.dao.dropDb)
        println("Database deleted.")
      } else {
        println(g.dao.run(g.dao.getDbInfo))
        println("Use --force to actually delete the database.")
      }
    }
  }

  def insertRun(go: GlobalOptions, projectDir: Path, message: Option[String], resultFile: Path, jmhArgs: Seq[String]): Unit = {
    new Global(go).use { g =>
      val gd = new GitData(projectDir)
      val pd = new PlatformData
      val run = new DbTestRun(UUID.randomUUID().toString, -1, Timestamp.from(Instant.now()), message,
        gd.getHeadDate.map(d => Timestamp.from(Instant.ofEpochMilli(d.getTime))), gd.getHeadSHA, gd.getOriginURL, gd.getUpstreamURL,
        Option(pd.hostname), Option(pd.javaVendor), Option(pd.javaVersion), Option(pd.javaVmName), Option(pd.javaVmVersion),
        Option(pd.userName),
        gd.toJsonString, pd.toJsonString)
      if(!Files.isRegularFile(resultFile))
        logger.error(s"Result file s$resultFile not found.")
      else {
        val resultJson = "data = " + File(resultFile).contentAsString(charset = "UTF-8")
        val resultConfigs = ConfigFactory.parseString(resultJson).getConfigList("data").asScala
        val daoData = resultConfigs.iterator.zipWithIndex.map { case (rc, idx) =>
          val rr = RunResult.fromRaw(UUID.randomUUID().toString, idx, run.uuid, rc)
          (rr.db, rr.dbJvmArgs, rr.dbParams)
        }.toSeq
        val runResults = daoData.map(_._1)
        val jvmArgs = daoData.flatMap(_._2)
        val runResultParams = daoData.flatMap(_._3)
        val dJmhArgs = jmhArgs.zipWithIndex.map { case (s, idx) =>
          new DbJmhArg(run.uuid, idx, s)
        }
        val runId = g.dao.run(g.dao.checkVersion andThen g.dao.insertRun(run, runResults, jvmArgs, runResultParams, dJmhArgs))
        println(s"Test run #${runId} with ${runResults.size} results inserted.")
      }
    }
  }

  def queryResults(go: GlobalOptions, runs: Seq[Long], benchs: Seq[String], extract: Seq[String], scorePrecision: Int, raw: Boolean): Unit = try {
    new Global(go).use { g =>
      //TODO best behavior when `run` is empty?
      //val count = g.dao.run(g.dao.checkVersion andThen g.dao.countTestRuns(run))
      //if(count == 0)
      //  logger.error(s"No test run with UUID prefix ${run.get} found")
      val multi = runs.size > 1
      val allRs = g.dao.run(g.dao.checkVersion andThen g.dao.queryResults(runs))
        .map { case (rr, runId) => RunResult.fromDb(rr, runId, multi) }
      val rs = RunResult.extract(extract, RunResult.filterByName(benchs, allRs)).toSeq
      if(raw) {
        print("[")
        rs.zipWithIndex.foreach { case (r, idx) =>
          if(idx == 0) println()
          else println(",")
          val c = ConfigFactory.parseString(r.db.rawData)
          val c2 = ConfigFactory.parseMap(
            (Map("benchmark" -> r.name) ++ r.params.map { case (k, v) => ("params."+k, v) }).asJava
          ).withFallback(c).resolve()
          val lines = c2.root.render(ConfigRenderOptions.defaults().setOriginComments(false)).lines.filterNot(_.isEmpty).toIndexedSeq
          print(lines.mkString("    ", "\n    ", ""))
        }
        println()
        println("]")
      } else {
        import TableFormatter._
        val paramNames = rs.flatMap(_.params.keys).distinct.toIndexedSeq
        val columns = IndexedSeq(
          Seq(TextColumn("Benchmark")),
          paramNames.map(s => TextColumn(s"($s)", true)),
          Seq(TextColumn("Mode"), TextColumn("Cnt", true), ScoreColumn("Score", scorePrecision), ScoreColumn("Error", scorePrecision), TextColumn("Units"))
        ).flatten
        val data = rs.iterator.map { r =>
          IndexedSeq(
            Seq(r.name),
            paramNames.map(k => r.params.getOrElse(k, "")),
            Seq(r.db.mode, r.db.forks * r.db.measurementIterations * r.db.measurementBatchSize, r.primaryMetric.score, r.primaryMetric.scoreError, r.primaryMetric.scoreUnit)
          ).flatten
        }.toIndexedSeq
        val table = new TableFormatter(go).apply(columns, data)
        table.foreach(println)
      }
    }
  } catch {
    case ex: PatternSyntaxException => logger.error(ex.toString)
  }

  def createChart(go: GlobalOptions, runs: Seq[Long], benchs: Seq[String], scorePrecision: Int, template: Option[Path], out: Option[Path], cmdLine: Array[String]): Unit = {
    new Global(go).use { g =>
      if(out.isEmpty)
        logger.error(s"No output file given.")
      else {
        val multi = runs.size > 1
        val allRs = g.dao.run(g.dao.checkVersion andThen g.dao.queryResults(runs))
          .map { case (rr, runId) => RunResult.fromDb(rr, runId, multi) }
        val rs = RunResult.filterByName(benchs, allRs).toSeq
        val data = new GenerateCharts(go, scorePrecision).generate(rs)
        val templateHtml = template match {
          case Some(p) =>
            if(!Files.isRegularFile(p)) {
              logger.error(s"Template file $p not found.")
              None
            } else Some(File(p).contentAsString(charset = "UTF-8"))
          case None => Some(Resource.getAsString("chart-template.html")(charset = "UTF-8"))
        }
        templateHtml.foreach { tmpl =>
          val html = tmpl.replaceAllLiterally("@benchmarkData", data).replaceAllLiterally("@commandLine", cmdLine.mkString(" "))
          if(out.get.toString == "-") println(html.trim)
          else {
            File(out.get).write(html)(charset = "UTF-8")
            println(s"Charts written to '${out.get}'.")
          }
        }
      }
    }
  }

  def listRuns(go: GlobalOptions, limit: Option[Int], gitData: Boolean, platformData: Boolean): Unit = {
    new Global(go).use { g =>
      val runs = g.dao.run(g.dao.checkVersion andThen g.dao.listTestRuns(limit))
      import TableFormatter._
      var columns = IndexedSeq(
        TextColumn("ID", true),
        TextColumn("Timestamp"),
        TextColumn("Msg")
      )
      if(gitData) columns = columns ++ IndexedSeq(
        TextColumn("Git SHA"),
        TextColumn("Git Timestamp"),
        TextColumn("Git Origin"),
        TextColumn("Git Upstream")
      )
      if(platformData) columns = columns ++ IndexedSeq(
        TextColumn("Host Name"),
        TextColumn("User Name"),
        TextColumn("Java Vendor"),
        TextColumn("Java Version"),
        TextColumn("JVM Name"),
        TextColumn("JVM Version")
      )
      val data = runs.iterator.map { r =>
        var line = IndexedSeq(r.runId, r.timestamp, r.message)
        if(gitData) line = line ++ IndexedSeq(r.gitSha.map(_.take(7)), r.gitTimestamp, r.gitOrigin, r.gitUpstream)
        if(platformData) line = line ++ IndexedSeq(r.hostname, r.username, r.javaVendor, r.javaVersion, r.jvmName, r.jvmVersion)
        line
      }.toIndexedSeq
      val table = new TableFormatter(go).apply(columns, data)
      table.foreach(println)
      val reached = if(limit.getOrElse(-1) == runs.size) " (limit reached)" else ""
      println(s"${runs.size} test runs found$reached.")
    }
  }
}
