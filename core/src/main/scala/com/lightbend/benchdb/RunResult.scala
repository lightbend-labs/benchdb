package com.lightbend.benchdb

import java.util.regex.Pattern

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}

import scala.collection.JavaConverters._

final class RunResult(val db: DbRunResult, val rc: Config, val runId: Long, val name: String) {
  lazy val jvmArgs: Seq[String] = rc.getStringList("jvmArgs").asScala.toSeq
  lazy val params: Map[String, String] = rc.getConfig("params").entrySet().asScala.iterator.map { case me =>
    (me.getKey, String.valueOf(me.getValue.unwrapped))
  }.toMap

  def getLongParam(key: String): Option[Long] =
    try Some(rc.getConfig("params").getString(key).toLong) catch { case _: Exception => None }

  lazy val dbJvmArgs: Seq[DbJvmArg] = jvmArgs.zipWithIndex.map { case (s, idx) => new DbJvmArg(db.uuid, idx, s) }
  lazy val dbParams: Seq[DbRunResultParam] = params.iterator.map { case (k, v) => new DbRunResultParam(None, db.uuid, k, v) }.toSeq

  lazy val primaryMetric: RunResult.Metric =
    parseMetric(rc.getConfig("primaryMetric"))

  lazy val secondaryMetrics: Map[String, RunResult.Metric] = {
    val sc = rc.getConfig("secondaryMetrics")
    sc.entrySet().asScala.iterator.map { me =>
      (me.getKey, parseMetric(sc.getConfig(me.getKey)))
    }.toMap
  }

  private def parseStatistics(c: Config): Map[String, Double] =
    c.entrySet().asScala.iterator.map { me => (me.getKey, c.getDouble(me.getKey)) }.toMap

  private def parseMetric(c: Config): RunResult.Metric = {
    RunResult.Metric(c.getDouble("score"), c.getDouble("scoreError"), c.getDoubleList("scoreConfidence").asScala.toSeq.asInstanceOf[Seq[Double]],
      parseStatistics(c.getConfig("scorePercentiles")), c.getString("scoreUnit"))
  }
}

object RunResult {
  def fromRaw(uuid: String, sequence: Int, testRunUuid: String, rc: Config): RunResult = {
    val db = new DbRunResult(uuid, sequence, testRunUuid,
      rc.getString("jmhVersion"), rc.getString("benchmark"), rc.getString("mode"), rc.getInt("forks"), rc.getString("jvm"),
      rc.getString("jdkVersion"), rc.getString("vmVersion"),
      rc.getInt("warmupIterations"), rc.getString("warmupTime"), rc.getInt("warmupBatchSize"),
      rc.getInt("measurementIterations"), rc.getString("measurementTime"), rc.getInt("measurementBatchSize"),
      rc.root.render(ConfigRenderOptions.concise()))
    new RunResult(db, rc, -1, db.benchmark)
  }

  def fromDb(db: DbRunResult, runId: Long, multi: Boolean): RunResult = {
    val n = if(multi) s"#$runId:${db.benchmark}" else db.benchmark
    new RunResult(db, ConfigFactory.parseString(db.rawData), runId, n)
  }

  final case class Metric(score: Double, scoreError: Double, scoreConfidence: Seq[Double], scorePercentiles: Map[String, Double], scoreUnit: String)

  def filterByName(globs: Seq[String], rs: Iterable[RunResult]): Iterable[RunResult] = {
    if(globs.isEmpty) rs else {
      val patterns = globs.map(compileGlobPattern)
      rs.filter(r => patterns.exists(p => p.matcher(r.name).matches()))
    }
  }

  private def compileGlobPattern(expr: String) = {
    val a = expr.split("\\*", -1)
    val b = new StringBuilder
    var i = 0
    while(i < a.length) {
      if(i != 0) b.append(".*")
      if(!a(i).isEmpty)
        b.append(Pattern.quote(a(i).replaceAll("\n", "\\n")))
      i += 1
    }
    Pattern.compile(b.toString)
  }
}
