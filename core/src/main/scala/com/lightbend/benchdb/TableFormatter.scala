package com.lightbend.benchdb

import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.{Date, Locale, TimeZone}

import scala.collection.mutable.ArrayBuffer

class TableFormatter(val go: GlobalOptions) {
  import TableFormatter._
  import go.format._

  protected[this] def formatLine(line: IndexedSeq[Any], columns: IndexedSeq[Column]): IndexedSeq[String] = (line, columns).zipped.map { (v, c) =>
    def fmtSimple(v: Any): String = v match {
      case Some(v) => fmtSimple(v)
      case None => ""
      case t: Timestamp => formatTimestamp(t)
      case v => String.valueOf(v)
    }
    c match {
      case ScoreColumn(_, prec) => ScoreFormatter(v, prec)
      case _ => fmtSimple(v)
    }
  }

  def apply(columns: IndexedSeq[Column], data: IndexedSeq[IndexedSeq[Any]]): IndexedSeq[String] = {
    val texts = columns.map(_.title) +: data.map(l => formatLine(l, columns))
    val widths = 0.until(columns.length).map { idx => texts.map(_.apply(idx).length).max }
    val buf = new ArrayBuffer[String](data.length + 4)
    buf += cBlue + widths.map(l => pad("", l+2, filler = box(0))).mkString(boxS(1), boxS(2), boxS(3)) + cNormal
    for((line, lno) <- texts.zipWithIndex) {
      val color = if(lno == 0) cYellow else cNormal
      val padded = (line, widths, columns).zipped.map((s, len, c) => color+" "+pad(s, len, c.rightAlign)+" ")
      buf += padded.mkString(cBlue+box(10), cBlue+box(10), cBlue+box(10)+cNormal)
      if(lno == 0)
        buf += cBlue + widths.map(l => pad("", l+2, filler = box(0))).mkString(boxS(4), boxS(5), boxS(6)) + cNormal
    }
    buf += cBlue + widths.map(l => pad("", l+2, filler = box(0))).mkString(boxS(7), boxS(8), boxS(9)) + cNormal
    buf.toIndexedSeq
  }

  def pad(s: String, len: Int, left: Boolean = false, filler: Char = ' '): String = {
    if(s.length >= len) s
    else {
      val b = new StringBuilder(len)
      if(!left) b.append(s)
      var p = len - s.length
      while(p > 0) {
        b.append(filler)
        p -= 1
      }
      if(left) b.append(s)
      b.toString
    }
  }

  def formatTimestamp(d: Date): String = {
    val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    df.setTimeZone(TimeZone.getTimeZone("UTC"))
    df.format(d)
  }
}

object TableFormatter {
  trait Column {
    def title: String
    def rightAlign: Boolean
  }
  case class TextColumn(title: String, rightAlign: Boolean = false) extends Column
  case class ScoreColumn(title: String, precision: Int) extends Column { def rightAlign = true }
}

object ScoreFormatter {
  def apply(value: Any, precision: Int): String =
    String.format("%." + precision + "f", value.asInstanceOf[AnyRef])
}
