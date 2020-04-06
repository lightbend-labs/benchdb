package com.example

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
@Threads(1)
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
class TestBenchmark {

  @Param(Array("1", "10"))
  var size: Int = _

  @Benchmark def bench1(bh: Blackhole): Any = {
    bh.consume(size)
  }
}
