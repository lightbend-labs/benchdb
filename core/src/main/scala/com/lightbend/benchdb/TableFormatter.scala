package com.lightbend.benchdb

import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.{Date, Locale, TimeZone}

import scala.collection.mutable.ArrayBuffer

class TableFormatter(val go: GlobalOptions) {
  import TableFormatter._
  import go.format._

  protected[this] def formatLine(line: IndexedSeq[Any], formats: IndexedSeq[Format]): IndexedSeq[Cell] = line.lazyZip(formats).map { (v, c) =>
    def fmtSimple(v: Any): String = v match {
      case null => ""
      case Some(v) => fmtSimple(v)
      case None => ""
      case t: Timestamp => formatTimestamp(t)
      case v => String.valueOf(v)
    }
    v match {
      case Formatted(v, colspan, alignOpt, styleOpt) =>
        val text = fmtSimple(v)
        Cell(text, text.length, colspan, Format(alignOpt.getOrElse(c.align), styleOpt.getOrElse(c.style)))
      case v =>
        val text = fmtSimple(v)
        Cell(text, text.length, 1, c)
    }
  }

  def apply(columnFormats: IndexedSeq[Format], data: IndexedSeq[IndexedSeq[Any]]): IndexedSeq[String] = {
    val cells = data.map(l => if(l == null) null else formatLine(l, columnFormats))
    val widths = layout(cells, columnFormats)
    val buf = new ArrayBuffer[String](data.length + 4)
    buf += formatSeparator(widths, 1, 2, 3)
    for(line <- cells) {
      if(line == null) buf += formatSeparator(widths, 4, 5, 6)
      else buf += formatLine(line, widths)
    }
    buf += formatSeparator(widths, 7, 8, 9)
    buf.toIndexedSeq
  }

  private def layout(cells: IndexedSeq[IndexedSeq[Cell]], columnFormats: IndexedSeq[Format]): IndexedSeq[Int] = {
    val widths = ArrayBuffer.fill(columnFormats.length)(0)
    var hasColspan = false
    cells.foreach { line =>
      if(line != null) {
        val cellNum = line.length
        var cellIdx, colIdx = 0
        while(cellIdx < cellNum) {
          val cell = line(cellIdx)
          if(cell.colspan == 1) {
            widths(colIdx) = widths(colIdx) max cell.length
          } else hasColspan = true
          colIdx += cell.colspan
          cellIdx += 1
        }
      }
    }
    if(hasColspan) {
      cells.foreach { line =>
        if(line != null) {
          val cellNum = line.length
          var cellIdx, colIdx = 0
          while(cellIdx < cellNum) {
            val cell = line(cellIdx)
            if(cell.colspan > 1) {
              val current = widths.iterator.drop(colIdx).take(cell.colspan).sum + (3 * (cell.colspan-1))
              var extra = cell.length - current
              if(extra > 0) {
                val end = colIdx+cell.colspan
                var i = colIdx
                while(i < end) {
                  widths(i) += extra/(end-i)
                  i += 1
                }
              }
            }
            colIdx += cell.colspan
            cellIdx += 1
          }
        }
      }
    }
    widths.toIndexedSeq
  }

  private def formatSeparator(widths: IndexedSeq[Int], lIdx: Int, cIdx: Int, rIdx: Int): String = {
    val b = new StringBuilder().append(cBlue).append(box(lIdx))
    var colIdx = 0
    val colNum = widths.length
    while(colIdx < colNum) {
      if(colIdx != 0) b.append(box(cIdx))
      pad(b, "", widths(colIdx)+2, filler = box(0))
      colIdx += 1
    }
    b.append(box(rIdx)).append(cNormal).toString
  }

  private def formatLine(cells: IndexedSeq[Cell], widths: IndexedSeq[Int]): String = {
    var cellIdx, colIdx = 0
    val cellNum = cells.length
    val b = new StringBuilder().append(cBlue).append(box(10))
    while(cellIdx < cellNum) {
      b.append(" ")
      val cell = cells(cellIdx)
      val width = {
        var w = widths(colIdx)
        var cols = cell.colspan
        while(cols > 1) {
          colIdx += 1
          w += widths(colIdx) + 3
          cols -= 1
        }
        w
      }
      cell.format(b, width, cNormal, cYellow)
      b.append(" ")
      b.append(cBlue).append(box(10))
      cellIdx += 1
      colIdx += 1
    }
    b.append(cNormal).toString
  }

  private def formatTimestamp(d: Date): String = {
    val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    df.setTimeZone(TimeZone.getTimeZone("UTC"))
    df.format(d)
  }
}

object TableFormatter {
  sealed trait Align
  object Align {
    case object Left extends Align
    case object Center extends Align
    case object Right extends Align
  }

  sealed trait Style
  object Style {
    case object Content extends Style
    case object Header extends Style
  }

  case class Formatted(value: Any, colspan: Int = 1, align: Option[Align] = None, style: Option[Style] = None)

  def formatHeader(title: String): Formatted = Formatted(title, style = Some(Style.Header))

  case class Cell(text: String, length: Int, colspan: Int, format: Format) {
    def format(out: StringBuilder, width: Int, contentColor: String, headerColor: String): Unit = {
      val color = format.style match {
        case Style.Header => headerColor
        case _ => contentColor
      }
      pad(out, color+text, width, format.align, ' ', length)
    }
  }

  case class Format(align: Align = Align.Left, style: Style = Style.Content)

  private def pad(out: StringBuilder, s: String, len: Int, align: Align = Align.Left, filler: Char = ' ', textLen: Int = -1): Unit = {
    val baseLen = if(textLen == -1) s.length else textLen
    val fill = len-baseLen
    if(fill <= 0) out.append(s)
    else {
      val (fillLeft, fillRight) = align match {
        case Align.Left => (0, fill)
        case Align.Right => (fill, 0)
        case Align.Center => (fill/2, fill-fill/2)
      }
      def append(count: Int): Unit = {
        var p = count
        while(p > 0) {
          out.append(filler)
          p -= 1
        }
      }
      append(fillLeft)
      out.append(s)
      append(fillRight)
    }
  }
}
