package com.lightbend.benchdb

object ScoreFormatter {
  def apply(value: Any, precision: Int): String =
    String.format("%." + precision + "f", value.asInstanceOf[AnyRef])
}
