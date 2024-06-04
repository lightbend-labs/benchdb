package com.lightbend.benchdb

import java.lang.reflect.{Field, Method}
import java.util.regex.{Pattern, PatternSyntaxException}
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

final class RunResult(val db: DbRunResult, val rc: Config, val runId: Long, val name: String, overrideParams: Option[Map[String, String]]) {
  lazy val jvmArgs: Seq[String] = rc.getStringList("jvmArgs").asScala.toSeq

  def withExtracted(name: String, params: Map[String, String]) =
    new RunResult(db, rc, runId, name, Some(this.params ++ params))

  private[this] lazy val _params: Map[String, String] = rc.getConfig("params").entrySet().asScala.iterator.map { case me =>
    (me.getKey, String.valueOf(me.getValue.unwrapped))
  }.toMap
  lazy val params: Map[String, String] = overrideParams match {
    case Some(m) => m
    case None => _params
  }

  def getLongParam(key: String): Option[Long] =
    try Some(params(key).toLong) catch { case _: Exception => None }

  lazy val dbJvmArgs: Seq[DbJvmArg] = jvmArgs.zipWithIndex.map { case (s, idx) => new DbJvmArg(db.uuid, idx, s) }
  lazy val dbParams: Seq[DbRunResultParam] = params.iterator.map { case (k, v) => new DbRunResultParam(None, db.uuid, k, v) }.toSeq

  final def primaryMetricOr(secondary: Option[String]): RunResult.Metric = {
    secondary match {
      case Some(m) => secondaryMetrics(m)
      case None => primaryMetric
    }
  }

  private lazy val primaryMetric: RunResult.Metric =
    parseMetric(rc.getConfig("primaryMetric"))

  private lazy val secondaryMetrics: Map[String, RunResult.Metric] = {
    val path = "secondaryMetrics"
    val sc = rc.getConfig(path)
    val keys = rc.getAnyRef(path).asInstanceOf[java.util.Map[String, _]].keySet()
    keys.asScala.iterator.map { k =>
      (k, parseMetric(sc.getConfig("\"" + k + "\"")))
    }.toMap
  }

  def cnt: Int =
    db.forks * db.measurementIterations * db.measurementBatchSize

  private def parseStatistics(c: Config): Map[String, Double] =
    c.entrySet().asScala.iterator.map { me => (me.getKey, c.getDouble(me.getKey)) }.toMap

  private def parseMetric(c: Config): RunResult.Metric = {
    RunResult.Metric(c.getDouble("score"), c.getDouble("scoreError"), c.getDoubleList("scoreConfidence").asScala.toSeq.asInstanceOf[Seq[Double]],
      parseStatistics(c.getConfig("scorePercentiles")), c.getString("scoreUnit"))
  }
}

object RunResult extends Logging {
  def fromRaw(uuid: String, sequence: Int, testRunUuid: String, rc: Config): RunResult = {
    val db = new DbRunResult(uuid, sequence, testRunUuid,
      rc.getString("jmhVersion"), rc.getString("benchmark"), rc.getString("mode"), rc.getInt("forks"), rc.getString("jvm"),
      rc.getString("jdkVersion"), rc.getString("vmVersion"),
      rc.getInt("warmupIterations"), rc.getString("warmupTime"), rc.getInt("warmupBatchSize"),
      rc.getInt("measurementIterations"), rc.getString("measurementTime"), rc.getInt("measurementBatchSize"),
      rc.root.render(ConfigRenderOptions.concise()))
    new RunResult(db, rc, -1, db.benchmark, None)
  }

  def fromDb(db: DbRunResult, runId: Long, multi: Boolean): RunResult = {
    val n = if(multi) s"#$runId:${db.benchmark}" else db.benchmark
    new RunResult(db, ConfigFactory.parseString(db.rawData), runId, n, None)
  }

  final case class Metric(score: Double, scoreError: Double, scoreConfidence: Seq[Double], scorePercentiles: Map[String, Double], scoreUnit: String)

  def filterByName(globs: Seq[String], rs: Iterable[RunResult]): Iterable[RunResult] = {
    if(globs.isEmpty) rs else {
      val patterns = globs.map(compileGlobPattern)
      rs.filter(r => patterns.exists(p => p.matcher(r.name).matches()))
    }
  }

  private def compileGlobPattern(expr: String): Pattern = {
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

  private def compileExtractorPattern(expr: String, regex: Boolean): (Pattern, ArrayBuffer[String]) = {
    if (regex) {
      val patternLiteral = expr.replaceAll("\\(([^)=]+)=", "(?<$1>")
      val pattern = Pattern.compile(patternLiteral)
      val namedGroups = pattern.namedGroups.toSeq.sortBy(_._2)
      val groups = ArrayBuffer.empty[String]
      var i = 1
      for ((name, n) <- namedGroups) {
        while (i < n) {
          groups.append("")
          i += 1
        }
        groups.append(name)
        i += 1
      }
      val m = pattern.groupCount - 1
      while (i < m) {
        groups.append("")
        i += 1
      }
      logger.debug(s"Compiled regex extractor '$expr' to $groups, $namedGroups")
      (pattern, groups)
    } else {
      val b = new StringBuilder
      var i = 0
      var inGroup = false
      val groups = new ArrayBuffer[String]
      while(i < expr.length) {
        val c = expr.charAt(i)
        i += 1
        c match {
          case '(' if inGroup => throw new PatternSyntaxException("Capture groups must not be nested", expr, i-1)
          case ')' if !inGroup => throw new PatternSyntaxException("Unexpected end of capture group", expr, i-1)
          case '(' =>
            b.append('(')
            inGroup = true
            var nextEq = expr.indexOf('=', i)
            val nextClose = expr.indexOf(')', i)
            if(nextClose == -1) throw new PatternSyntaxException("Capture group not closed", expr, i)
            if(nextEq == -1 || nextEq > nextClose) groups += ""
            else {
              val n = expr.substring(i, nextEq)
              groups += n
              i = nextEq+1
            }
          case ')' =>
            b.append(')')
            inGroup = false
          case '*' => b.append(".*")
          case c => b.append(Pattern.quote(String.valueOf(c)))
        }
      }
      logger.debug(s"Compiled extractor '$expr' to $b, $groups")
        (Pattern.compile(b.toString), groups)
    }
  }

  def extract(extractors: Seq[String], regex: Boolean, rs: Iterable[RunResult]): Iterable[RunResult] = {
    if(extractors.isEmpty) rs else {
      val patterns = extractors.map(extractor => compileExtractorPattern(extractor, regex)).filter(_._2.nonEmpty)
      rs.map { r =>
        val name = r.name
        val matcherOpt = patterns.iterator.map(p => (p._1.matcher(name), p._2)).find(_._1.matches)
        matcherOpt match {
          case Some((m, groups)) =>
            logger.debug(s"Matched ${name}")
            val groupData = groups.zipWithIndex.map { case (n, i) => (n, m.group(i+1), m.start(i+1), m.end(i+1)) }
            logger.debug(s"Group data: "+groupData)
            val params = groupData.iterator.filter(_._1.nonEmpty).map { case (k, v, _, _) => (k, v) }.toMap
            logger.debug(s"Extracted parameters: "+params)
            val b = new StringBuilder
            var copied = 0
            groupData.foreach { case (n, _, start, end) =>
              if(copied < start) b.append(name.substring(copied, start))
              if(n.nonEmpty) b.append('(').append(n).append(')')
              copied = end
            }
            if(copied < name.length) b.append(name.substring(copied))
            logger.debug("New name: "+b)
            r.withExtracted(b.toString, params)
          case None => r
        }
      }
    }
  }

  def pivot(rs: Iterable[RunResult], pivot: Seq[String], other: Seq[String], metric: Option[String]): (Iterable[(RunResult, IndexedSeq[Option[RunResult]])], Seq[Seq[String]]) = {
    val pivotValues = pivot.map { p => rs.iterator.map(r => r.params.getOrElse(p, null)).toVector.distinct }
    val otherValues = other.map { p => rs.iterator.map(r => r.params.getOrElse(p, null)).toVector.distinct }
    def types(values: Seq[Seq[String]]) = values.map { ss =>
      var asLong, asDouble = true
      def isLong(s: String) = try { s.toLong; true } catch { case _: NumberFormatException => false }
      def isDouble(s: String) = try { s.toDouble; true } catch { case _: NumberFormatException => false }
      ss.foreach { s =>
        if(s != null) {
          if(asLong) asLong = isLong(s)
          if(asDouble) asDouble = isDouble(s)
        }
      }
      (asLong, asDouble)
    }
    val pivotTypes = types(pivotValues)
    val otherTypes = types(otherValues)

    def valueCombinations(idx: Int, len: Int): Seq[Seq[String]] = {
      if(idx == len) Seq(Nil)
      else {
        val sub = valueCombinations(idx+1, len)
        pivotValues(idx).flatMap(s => sub.map(s +: _))
      }
    }
    val pivotColumns = valueCombinations(0, pivot.length).sorted(new ParamOrdering(pivotTypes))

    val fixedTypes = Seq((false, false), (false, false), (true, true), (false, false))
    val groupsMap = rs.groupBy(r => Seq(r.name, r.db.mode, r.cnt.toString, r.primaryMetricOr(metric).scoreUnit) ++ other.map(p => r.params.getOrElse(p, null)))
    val groups = groupsMap.toSeq.sortBy(_._1)(new ParamOrdering(fixedTypes ++ otherTypes))
    //groups.foreach { case (groupParams, groupData) => println(s"group: $groupParams -> ${groupData.size}") }
    val grouped = groups.map { case (gr, data) =>
      val dataColumns = pivotColumns.iterator.map { pc =>
        val cols = data.filter(r => pc.zip(pivot).forall { case (v, k) => r.params.getOrElse(k, null) == v })
        if(cols.size > 1)
          logger.error(s"Group (${gr.mkString(",")}) has ${cols.size} pivoted data sets.")
        cols.headOption
      }.toIndexedSeq
      (data.head, dataColumns)
    }

    (grouped, pivotColumns)
  }

  class ParamOrdering(paramTypes: Seq[(Boolean, Boolean)], nullsFirst: Boolean = false) extends Ordering[Seq[String]]{
    def compare(x: Seq[String], y: Seq[String]): Int = {
      val it = x.lazyZip(y).lazyZip(paramTypes).iterator
      while(it.hasNext) {
        val (x, y, (asLong, asDouble)) = it.next()
        if(x != null || y != null) {
          if(x == null) return (if(nullsFirst) -1 else 1)
          else if(y == null) return (if(nullsFirst) -1 else 1)
          else if(asLong) {
            val diff = Ordering.Long.compare(x.toLong, y.toLong)
            if(diff != 0) return diff
          } else if(asDouble) {
            val diff = Ordering.Double.TotalOrdering.compare(x.toDouble, y.toDouble)
            if(diff != 0) return diff
          } else {
            val diff = Ordering.String.compare(x, y)
            if(diff != 0) return diff
          }
        }
      }
      0
    }
  }

  object RichPattern {
    lazy val namedGroupsMethod: Method = {
      val m = classOf[Pattern].getDeclaredMethod("namedGroups")
      m.setAccessible(true)
      m
    }
    lazy val capturingGroupCount: Field = {
      val f = classOf[Pattern].getDeclaredField("capturingGroupCount")
      f.setAccessible(true)
      f
    }
  }
  implicit class RichPattern(val pattern: Pattern) extends AnyVal {
    import RichPattern._
    import collection.JavaConverters._
    def namedGroups: Map[String, Int] = {
      Map.empty ++ namedGroupsMethod.invoke(pattern).asInstanceOf[java.util.Map[String, Int]].asScala
    }
    def groupCount: Int = {
      capturingGroupCount.get(pattern).asInstanceOf[Int]
    }
  }
}
