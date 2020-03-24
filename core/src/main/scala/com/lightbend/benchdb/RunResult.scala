package com.lightbend.benchdb

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import scala.collection.JavaConverters._

final class RunResult(val db: DbRunResult, val rc: Config) {
  lazy val jvmArgs: Seq[String] = rc.getStringList("jvmArgs").asScala.toSeq
  lazy val params: Map[String, String] = rc.getConfig("params").entrySet().asScala.iterator.map { case me =>
    (me.getKey, String.valueOf(me.getValue.unwrapped))
  }.toMap

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
    new RunResult(db, rc)
  }

  def fromDb(db: DbRunResult): RunResult =
    new RunResult(db, ConfigFactory.parseString(db.rawData))

  final case class Metric(score: Double, scoreError: Double, scoreConfidence: Seq[Double], scorePercentiles: Map[String, Double], scoreUnit: String)
}
