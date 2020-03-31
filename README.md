# benchdb - A database and query tool for [JMH](https://openjdk.java.net/projects/code-tools/jmh/) results

When you run benchmarks with JMH you usually look at the results table printed after the run or maybe generate a JSON file and feed it into [JMH Visualizer](https://jmh.morethan.io/) for immediate consumption. This approach does not scale well when you benchmark lots of different changes or want to compare historical data or visualize more complex benchmark results graphically.

benchdb takes a JMH result file plus some captured environment data (platform, Java environment, git data for git-based projects) and stores it in a relational database of your choice. You can later list and retrieve these results and run queries over a single result or combining multiple results. Thanks to the stored environment data you always know when, what and where you ran a benchmark.

## Installation (PRELIMINARY)

- Clone benchdb and run `sbt core/publishLocal`.

- Create the command line app with [Coursier](https://get-coursier.io/), e.g.:

  ```
  > cs bootstrap com.lightbend.benchdb::benchdb-core:latest.release mysql:mysql-connector-java:8.0.19 -o benchdb
  ```
  
  Note that the second dependency is the MySQL JDBC driver. You should replace this with the database driver of your choice. benchdb doesn't use any advanced database features. Any database supported by [Slick](https://scala-slick.org/) should work. 
  
- Create a configuration file `.benchdb.conf` in your home directory (or specify a different file manually when calling benchdb) using HOCON syntax for [Typesafe Config](https://github.com/lightbend/config). It needs to contain at least a [DatabaseConfig](https://scala-slick.org/doc/3.3.1/database.html#databaseconfig) for Slick under the name `db`. Example for a remote MySQL/MariaDB database:

  ```
  db {
    profile = "slick.jdbc.MySQLProfile$"
    db {
      connectionPool = disabled
      dataSourceClass = "com.mysql.cj.jdbc.MysqlDataSource"
      properties = {
        serverName = "hostname"
        portNumber = "3307"
        databaseName = "benchdb"
        user = "benchdb"
        password = "password"
        serverTimezone = "UTC"
      }
    }
  }
  ```

- Run `benchdb init-db --force` to initialize the database schema.

## Usage

- `benchdb --help` shows the list of supported commands. Specifying a command name followed by `--help` shows further options and parameters for that command.

- First you need to insert some benchmark results into the database, e.g.:

  ```
  > benchdb insert-run --project-dir ../scala --msg "Test 1" ../scala/test/benchmarks/jmh-result.json -wm bulk
  ```

  The specified project directory (default: current directory) is used to determine git environment data. Any parameters following the result file (`-wm bulk` in this case) are expected to be JMH command line parameters. They are not parsed by benchdb but stored verbatim in the database for reference.

  TODO: Automate this with an sbt plugin to run sbt-jmh and insert the data with a single command.

- `benchdb list` lists the benchmark runs in the database, e.g.:

  ```
  > benchdb list --git-data
  /----+---------------------+----------+---------+---------------------+----------------------------------+------------------------------------\
  | ID | Timestamp           | Msg      | Git SHA | Git Timestamp       | Git Origin                       | Git Upstream                       |
  |----+---------------------+----------+---------+---------------------+----------------------------------+------------------------------------|
  |  4 | 2020-03-24 14:15:15 | Test 3   | d335189 | 2020-03-09 16:06:23 | git@github.com:szeiger/scala.git | https://github.com/scala/scala.git |
  |  3 | 2020-03-24 14:14:57 | Test 3   | d335189 | 2020-03-09 16:06:23 | git@github.com:szeiger/scala.git | https://github.com/scala/scala.git |
  |  2 | 2020-03-24 13:18:38 | Test 2   | d335189 | 2020-03-09 16:06:23 | git@github.com:szeiger/scala.git | https://github.com/scala/scala.git |
  |  1 | 2020-03-24 13:07:30 | Test job | d335189 | 2020-03-09 16:06:23 | git@github.com:szeiger/scala.git | https://github.com/scala/scala.git |
  \----+---------------------+----------+---------+---------------------+----------------------------------+------------------------------------/
  4 test runs found.
  ```

- `benchdb results` generates a table similar to the one produced by JMH itself. You can specify one or more run IDs to show (defaulting to the latest run if no ID is given) and also filter benchmark names with glob patterns:

  ```
  > benchdb results -r1 -b*100p*
  /----------------------------------------------------------+--------+------+-----+------------+----------+-------\
  | Benchmark                                                | (size) | Mode | Cnt |      Score |    Error | Units |
  |----------------------------------------------------------+--------+------+-----+------------+----------+-------|
  | scala.collection.immutable.VectorBenchmark2.nvFilter100p |      1 | avgt |  20 |      9.046 |    0.073 | ns/op |
  | scala.collection.immutable.VectorBenchmark2.nvFilter100p |     10 | avgt |  20 |     11.558 |    0.099 | ns/op |
  | scala.collection.immutable.VectorBenchmark2.nvFilter100p |    100 | avgt |  20 |    503.222 |   11.211 | ns/op |
  | scala.collection.immutable.VectorBenchmark2.nvFilter100p |   1000 | avgt |  20 |   6163.309 |  278.645 | ns/op |
  | scala.collection.immutable.VectorBenchmark2.nvFilter100p |  10000 | avgt |  20 |  41181.090 | 1407.833 | ns/op |
  | scala.collection.immutable.VectorBenchmark2.nvFilter100p |  50000 | avgt |  20 | 195477.388 | 4077.424 | ns/op |
  \----------------------------------------------------------+--------+------+-----+------------+----------+-------/
  ```

- Extractor patterns can be used to extract additional parameters from benchmark names. They are glob patterns with regular expression-like capture groups. Unnamed groups are discarded, named groups are extracted into parameters. For example:

  ```
  > benchdb results -r1 --extract (*2.)nvFilter(percent=*)p
  /--------------------+--------+-----------+------+-----+------------+----------+-------\
  | Benchmark          | (size) | (percent) | Mode | Cnt |      Score |    Error | Units |
  |--------------------+--------+-----------+------+-----+------------+----------+-------|
  | nvFilter(percent)p |      1 |         0 | avgt |  20 |      8.824 |    0.381 | ns/op |
  | nvFilter(percent)p |     10 |         0 | avgt |  20 |      8.902 |    0.075 | ns/op |
  | nvFilter(percent)p |    100 |         0 | avgt |  20 |     40.800 |    0.544 | ns/op |
  | nvFilter(percent)p |   1000 |         0 | avgt |  20 |    134.629 |    1.996 | ns/op |
  | nvFilter(percent)p |  10000 |         0 | avgt |  20 |   1712.683 |   31.730 | ns/op |
  | nvFilter(percent)p |  50000 |         0 | avgt |  20 |   8186.502 |  130.088 | ns/op |
  | nvFilter(percent)p |      1 |       100 | avgt |  20 |      9.046 |    0.073 | ns/op |
  | nvFilter(percent)p |     10 |       100 | avgt |  20 |     11.558 |    0.099 | ns/op |
  | nvFilter(percent)p |    100 |       100 | avgt |  20 |    503.222 |   11.211 | ns/op |
  | nvFilter(percent)p |   1000 |       100 | avgt |  20 |   6163.309 |  278.645 | ns/op |
  | nvFilter(percent)p |  10000 |       100 | avgt |  20 |  41181.090 | 1407.833 | ns/op |
  | nvFilter(percent)p |  50000 |       100 | avgt |  20 | 195477.388 | 4077.424 | ns/op |
  | nvFilter(percent)p |      1 |        50 | avgt |  20 |     13.321 |    0.302 | ns/op |
  | nvFilter(percent)p |     10 |        50 | avgt |  20 |     62.017 |    1.439 | ns/op |
  | nvFilter(percent)p |    100 |        50 | avgt |  20 |    595.161 |   34.352 | ns/op |
  | nvFilter(percent)p |   1000 |        50 | avgt |  20 |   5951.751 |   56.474 | ns/op |
  | nvFilter(percent)p |  10000 |        50 | avgt |  20 |  43305.200 | 1221.533 | ns/op |
  | nvFilter(percent)p |  50000 |        50 | avgt |  20 | 196567.912 | 8389.588 | ns/op |
  \--------------------+--------+-----------+------+-----+------------+----------+-------/
  ```

- You can then pivot one or more parameters to compare their results side by side:

  ```
  > benchdb results -r1 --extract (*2.)nvFilter(percent=*)p --pivot percent
  /--------------------+--------+------+-----+----------+---------+------------+----------+------------+----------+-------\
  |          (percent) |        |      |     |         0          |          50           |          100          |       |
  | Benchmark          | (size) | Mode | Cnt |    Score |   Error |      Score |    Error |      Score |    Error | Units |
  |--------------------+--------+------+-----+----------+---------+------------+----------+------------+----------+-------|
  | nvFilter(percent)p |      1 | avgt |  20 |    8.824 |   0.381 |     13.321 |    0.302 |      9.046 |    0.073 | ns/op |
  | nvFilter(percent)p |     10 | avgt |  20 |    8.902 |   0.075 |     62.017 |    1.439 |     11.558 |    0.099 | ns/op |
  | nvFilter(percent)p |    100 | avgt |  20 |   40.800 |   0.544 |    595.161 |   34.352 |    503.222 |   11.211 | ns/op |
  | nvFilter(percent)p |   1000 | avgt |  20 |  134.629 |   1.996 |   5951.751 |   56.474 |   6163.309 |  278.645 | ns/op |
  | nvFilter(percent)p |  10000 | avgt |  20 | 1712.683 |  31.730 |  43305.200 | 1221.533 |  41181.090 | 1407.833 | ns/op |
  | nvFilter(percent)p |  50000 | avgt |  20 | 8186.502 | 130.088 | 196567.912 | 8389.588 | 195477.388 | 4077.424 | ns/op |
  \--------------------+--------+------+-----+----------+---------+------------+----------+------------+----------+-------/
  ```

- `benchdb chart` generates line charts (using the [Google Charts](https://developers.google.com/chart) library). The parameters are the same as for `results`. Charts require a single free parameter which must be `Long`-valued (i.e. all instances can be parsed into a `Long` -- the actual types of the benchmark parameters are not preserved by JMH; it stores everything as a string), like `size` in the example above. In case of pivoted results, all pivoted columns are rendered together as individual series in a single chart. The result of `benchdb chart` is a single, self-contained HTML file. If no output file is specified, it is written to a temporary file and opened in the default browser.
