package com.lightbend.benchdb

import scala.collection.JavaConverters._
import com.typesafe.config.{Config, ConfigFactory, ConfigObject, ConfigRenderOptions, ConfigValueFactory}

class GenerateCharts(go: GlobalOptions, scorePrecision: Int, metric: Option[String]) extends Logging {

  def generatePivoted(pivoted: Iterable[(RunResult, IndexedSeq[Option[RunResult]])],
                      pivotSets: Seq[Seq[String]], pivotParams: Seq[String], otherParams: Seq[String]): String = {
    val groups = pivoted.groupBy(_._1.name).toSeq.sortBy(_._1)
    val lineChartOptionsBase = go.config.getConfig("lineChartOptions")
    groups.zipWithIndex.map { case ((n, rs), idx) =>
      val allParamNames = rs.flatMap(_._1.params.keys).toVector.distinct
      val paramNames = {
        val pivotSet = pivotParams.toSet
        allParamNames.filterNot(pivotSet.contains)
      }
      if(paramNames.size != 1) {
        logger.error(s"Number of non-pivoted parameters in $n is ${paramNames.size}, must be exactly 1.")
        ""
      } else {
        val pn = paramNames.head
        val paramValues = rs.map(_._1.getLongParam(pn))
        if(paramValues.exists(_.isEmpty)) {
          logger.error(s"Parameter values for '$pn' not parseable as Long numbers")
          ""
        } else {
          val columns = (Map("type" -> "number", "label" -> pn).asJava +:
            pivotSets.zipWithIndex.flatMap { case (ss, i) => seriesColumns(ss.mkString(", "), idx, i) }).asJava
          val rows = rs.zip(paramValues.map(_.get)).toVector.sortBy(_._2).map { case ((baseR, dataRs), pv) =>
            (pv +: pivotSets.zip(dataRs).flatMap { case (ss, rOpt) =>
              rOpt match {
                case Some(r) =>
                  val seriesName = pivotParams.zip(ss).map { case (k, v) => s"$k = $v" }.mkString(", ")
                  seriesRowData(s"$pn = $pv<br />Series: $seriesName", r)
                case None =>
                  Seq(null, null, null, null)
              }
            }).asJava
          }.asJava
          renderChartData(n, pn, vAxisTitle(rs.head._1.primaryMetricOr(metric).scoreUnit), lineChartOptionsBase, columns, rows)
        }
      }
    }.mkString("[", ",", "]")
  }

  def generate(rs: Seq[RunResult]): String = {
    val groups = rs.groupBy(_.name).toSeq.sortBy(_._1)
    val lineChartOptionsBase = go.config.getConfig("lineChartOptions")
    groups.zipWithIndex.map { case ((n, rs), idx) =>
      val paramNames = rs.flatMap(_.params.keys).distinct.toIndexedSeq
      if(paramNames.size != 1) {
        logger.error(s"Number of parameters in $n is ${paramNames.size}, must be exactly 1.")
        ""
      } else {
        val pn = paramNames.head
        val paramValues = rs.map(_.getLongParam(pn))
        if(paramValues.exists(_.isEmpty)) {
          logger.error(s"Parameter values for '$pn' not parseable as Long numbers")
          ""
        } else {
          val columns = (Map("type" -> "number", "label" -> pn).asJava +: seriesColumns(rs.head.name, idx, 0)).asJava
          val rows = rs.zip(paramValues.map(_.get)).sortBy(_._2).map { case (r, pv) =>
            (pv +: seriesRowData(s"$pn = $pv", r)).asJava
          }.asJava
          renderChartData(n, pn, vAxisTitle(rs.head.primaryMetricOr(metric).scoreUnit), lineChartOptionsBase, columns, rows)
        }
      }
    }.mkString("[", ",", "]")
  }

  private def vAxisTitle(unit: String) = metric match {
    case Some(x) => x + " " + unit
    case None => unit
  }

  private def renderChartData(title: String, hAxis: String, vAxis: String, baseConfig: Config, columns: Any, rows: Any): String = {
    val options = optionsConfig(title, hAxis, vAxis, baseConfig)
    val c = ConfigValueFactory.fromMap(Map("options" -> options, "columns" -> columns, "rows" -> rows).asJava)
    c.render(ConfigRenderOptions.concise())
  }

  private def optionsConfig(title: String, hAxis: String, vAxis: String, base: Config): ConfigObject = {
    val optionsM = new java.util.HashMap[String, Any]
    optionsM.put("title", title)
    optionsM.put("hAxis.title", hAxis)
    optionsM.put("vAxis.title", vAxis)
    ConfigFactory.parseMap(optionsM).withFallback(base).resolve().root
  }

  private def seriesColumns(name: String, idx: Int, idx2: Int): Seq[java.util.Map[String, _]] = Seq(
    Map("type" -> "number", "label" -> name).asJava,
    Map("type" -> "number", "id" -> s"i${idx}l$idx2", "role" -> "interval").asJava,
    Map("type" -> "number", "id" -> s"i${idx}h$idx2", "role" -> "interval").asJava,
    Map("type" -> "string", "id" -> s"i${idx}t$idx2", "role" -> "tooltip", "p" -> Map("html" -> true).asJava).asJava
  )

  private def seriesRowData(name: String, r: RunResult): Seq[Any] = {
    val metric1 = r.primaryMetricOr(metric)
    val score = metric1.score
    val err = metric1.scoreError
    val scoreS = ScoreFormatter(score, scorePrecision)
    val errS = ScoreFormatter(err, scorePrecision)
    Seq(score, score-err, score+err, s"$name<br/>Score: <b>$scoreS</b> ± $errS ${metric1.scoreUnit}")
  }
}
