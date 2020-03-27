package com.lightbend.benchdb

import scala.collection.JavaConverters._

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, ConfigValueFactory}

class GenerateCharts(go: GlobalOptions, scorePrecision: Int) extends Logging {

  def generate(rs: Seq[RunResult]): String = {
    val groups = rs.groupBy(r => (r.runId, r.db.benchmark, r.name)).toSeq.sortBy(_._1)
    val lineChartOptionsBase = go.config.getConfig("lineChartOptions")
    groups.zipWithIndex.map { case (((_, _, n), rs), idx) =>
      val paramNames = rs.flatMap(_.params.keys).distinct.toIndexedSeq
      if(paramNames.size != 1) {
        logger.error(s"Number of parameters in $n is ${paramNames.size}, must be exactly 1.")
        ""
      } else {
        val pn = paramNames.head
        val paramValues = rs.map(_.getLongParam(pn))
        if(paramValues.exists(_.isEmpty)) {
          logger.error(s"Parameter values for '$pn' nor parseable as Long numbers")
          ""
        } else {
          val optionsM = new java.util.HashMap[String, Any]
          optionsM.put("title", n)
          optionsM.put("hAxis.title", pn)
          optionsM.put("vAxis.title", rs.head.primaryMetric.scoreUnit)
          val options = ConfigFactory.parseMap(optionsM).withFallback(lineChartOptionsBase).resolve().root
          //println("    options: " + options.root().render(ConfigRenderOptions.concise()) + ",")
          val r = rs.head
          val columns = Seq(
            Map("type" -> "number", "label" -> pn).asJava,
            Map("type" -> "number", "label" -> r.name).asJava,
            Map("type" -> "number", "id" -> s"i${idx}l", "role" -> "interval").asJava,
            Map("type" -> "number", "id" -> s"i${idx}h", "role" -> "interval").asJava,
            Map("type" -> "string", "id" -> s"i${idx}t", "role" -> "tooltip", "p" -> Map("html" -> true).asJava).asJava
          ).asJava
          val rows = rs.zip(paramValues.map(_.get)).sortBy(_._2).map { case (r, pv) =>
            val score = r.primaryMetric.score
            val err = r.primaryMetric.scoreError
            val scoreS = ScoreFormatter(score, scorePrecision)
            val errS = ScoreFormatter(err, scorePrecision)
            Seq(pv, score, score-err, score+err, s"$pn: $pv<br/>Score: <b>$scoreS</b> ± $errS ${r.primaryMetric.scoreUnit}").asJava
          }.asJava
          val c = ConfigValueFactory.fromMap(Map("options" -> options, "columns" -> columns, "rows" -> rows).asJava)
          c.render(ConfigRenderOptions.concise())
        }
      }
    }.mkString("[", ",", "]")
  }


  /*
  val fmt3 = new DecimalFormat("###,###.###")

  def fmtTime(ns: Double, by: Double, withUnit: Boolean): String = {
    val (s, u) =
      if(by >= 1000000000d) (fmt3.format(ns/1000000000d), "s")
      else if(by >= 1000000d) (fmt3.format(ns/1000000d), "ms")
      else if(by >= 1000d) (fmt3.format(ns/1000d), "μs")
      else (fmt3.format(ns), "ns")
    if(withUnit) s"$s $u"
    else s
  }

  def fmtSize(i: Int): String = {
    if(i >= 1000000000) s"${i/1000000000}B"
    else if(i >= 1000000) s"${i/1000000}M"
    else if(i >= 1000) s"${i/1000}K"
    else s"$i"
  }

  def printChartData(out: PrintWriter, name: String, rss: IndexedSeq[IndexedSeq[Result]], seriesNames: IndexedSeq[String]): Unit = {
    println(s"""drawChart(new ChartData("$name", benchmarkData.$name));""")
    val sizes = rss.flatten.map(_.size).toSet.toIndexedSeq.sorted
    val bySize = rss.map(_.iterator.map(r => (r.size, r)).toMap)
    val benchmarkNames = rss.map(_.head.name)

    val minScore = rss.flatten.map(_.score).min
    val maxScore = rss.flatten.map(_.score).max
    var timeFactor =
      if(minScore > 1000000d) 1000000L
      else if(minScore > 1000d) 1000L
      else 1L
    val timeUnit = timeFactor match {
      case 1L => "ns"
      case 1000L => "μs"
      case 1000000L => "ms"
    }

    out.println(s"  $name: {")
    out.println(s"    rows: [")
    var first = true
    sizes.foreach { size =>
      if(!first) out.println(",")
      else first = false
      val sizeStr = fmtSize(size)
      val forSize = bySize.map(_.get(size))
      val minScore = forSize.map(_.map(_.score)).flatten.min
      val line = forSize.zipWithIndex.map { case (ro, i) => ro.map { r =>
        Seq(
          r.score/timeFactor,
          (r.score-r.error)/timeFactor,
          (r.score+r.error)/timeFactor,
          "\"Size: " + sizeStr + "<br/>" + seriesNames(i) + ": <b>" + fmtTime(r.score, minScore, true) + "</b> ± " + fmtTime(r.error, minScore, false) + "\""
        )
      }.getOrElse(Seq(null, null, null, null)) }.flatten
      out.print(s"      [$size, ${line.mkString(", ")}]")
    }
    out.println()
    out.println("    ],")
    out.println("    names: [" + benchmarkNames.map(s => "\""+s+"\"").mkString(", ") + "],")
    out.println("    timeUnit: \""+timeUnit+"\"")
    out.print("  }")
  }
  */
}
