package com.lightbend.benchdb

import java.sql.Timestamp
import java.util.UUID

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import slick.jdbc.JdbcProfile

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.collection.JavaConverters._

final case class DbTestRun(uuid: String, runId: Long, timestamp: Timestamp, message: Option[String],
                           gitTimestamp: Option[Timestamp], gitSha: Option[String], gitOrigin: Option[String], gitUpstream: Option[String],
                           hostname: Option[String],
                           javaVendor: Option[String], javaVersion: Option[String], jvmName: Option[String], jvmVersion: Option[String],
                           username: Option[String],
                           gitData: String, platformData: String)

final case class DbRunResult(uuid: String, sequence: Int, testRunUuid: String,
                     jmhVersion: String, benchmark: String, mode: String, forks: Int, jvm: String, jdkVersion: String, vmVersion: String,
                     warmupIterations: Int, warmupTime: String, warmupBatchSize: Int,
                     measurementIterations: Int, measurementTime: String, measurementBatchSize: Int,
                     rawData: String)

final case class DbRunResultParam(id: Option[Int], runResultUuid: String, key: String, value: String)

final case class DbJvmArg(runResultUuid: String, sequence: Int, value: String)

final case class DbJmhArg(testRunUuid: String, sequence: Int, value: String)

final case class DbMeta(key: String, value: String)

class DAO(val profile: JdbcProfile, val db: JdbcProfile#Backend#Database) extends Logging {
  import profile.api._

  val DB_VERSION = "1"

  def close(): Unit =
    try db.close()
    catch { case NonFatal(ex) => logger.error("Error closing database connection", ex) }

  class TestRunRow(tag: Tag) extends Table[DbTestRun](tag, "TEST_RUNS") {
    def uuid = column[String]("UUID", O.PrimaryKey, O.Length(36))
    def runId = column[Long]("RUN_ID", O.AutoInc, O.Unique)
    def timestamp = column[Timestamp]("TIMESTAMP")
    def message = column[Option[String]]("MESSAGE")

    def gitTimestamp = column[Option[Timestamp]]("GIT_TIMESTAMP")
    def gitSha = column[Option[String]]("GIT_SHA")
    def gitOrigin = column[Option[String]]("GIT_ORIGIN")
    def gitUpstream = column[Option[String]]("GIT_UPSTREAM")

    def hostname = column[Option[String]]("HOSTNAME")
    def javaVendor = column[Option[String]]("JAVA_VENDOR")
    def javaVersion = column[Option[String]]("JAVA_VERSION")
    def jvmName = column[Option[String]]("JVM_NAME")
    def jvmVersion = column[Option[String]]("JVM_VERSION")
    def username = column[Option[String]]("USERNAME")

    def gitData = column[String]("GIT_DATA")
    def platformData = column[String]("PLATFORM_DATA")

    def * = (uuid, runId, timestamp, message, gitTimestamp, gitSha, gitOrigin, gitUpstream, hostname, javaVendor, javaVersion, jvmName, jvmVersion, username, gitData, platformData).mapTo[DbTestRun]
    def withoutRaw = (uuid, runId, timestamp, message, gitTimestamp, gitSha, gitOrigin, gitUpstream, hostname, javaVendor, javaVersion, jvmName, jvmVersion, username, "", "").mapTo[DbTestRun]
  }

  lazy val testRuns = TableQuery[TestRunRow]

  class RunResultRow(tag: Tag) extends Table[DbRunResult](tag, "RUN_RESULT") {
    def uuid = column[String]("UUID", O.PrimaryKey, O.Length(36))
    def sequence = column[Int]("SEQUENCE")

    def testRunUuid = column[String]("TEST_RUN_UUID", O.Length(36))
    def testRun = foreignKey("RUN_RESULT_TEST_RUN_FK", testRunUuid, testRuns)(_.uuid, onDelete=ForeignKeyAction.Cascade)

    def jmhVersion = column[String]("JMH_VERSION")
    def benchmark = column[String]("BENCHMARK")
    def mode = column[String]("MODE")
    def forks = column[Int]("FORKS")
    def jvm = column[String]("JVM")
    def jdkVersion = column[String]("JDK_VERSION")
    def vmVersion = column[String]("VM_VERSION")
    def warmupIterations = column[Int]("WARMUP_ITERATIONS")
    def warmupTime = column[String]("WARMUP_TIME")
    def warmupBatchSize = column[Int]("WARMUP_BATCH_SIZE")
    def measurementIterations = column[Int]("MEASUREMENT_ITERATIONS")
    def measurementTime = column[String]("MEASUREMENT_TIME")
    def measurementBatchSize = column[Int]("MEASUREMENT_BATCH_SIZE")

    def rawData = column[String]("RAW")

    def * = (uuid, sequence, testRunUuid, jmhVersion, benchmark, mode, forks, jvm, jdkVersion, vmVersion, warmupIterations, warmupTime, warmupBatchSize, measurementIterations, measurementTime, measurementBatchSize, rawData).mapTo[DbRunResult]
  }

  lazy val runResults = TableQuery[RunResultRow]

  class RunResultParamRow(tag: Tag) extends Table[DbRunResultParam](tag, "PARAMS") {
    def id = column[Option[Int]]("ID", O.PrimaryKey, O.AutoInc)
    def runResultUuid = column[String]("RUN_RESULT_UUID", O.Length(36))
    def runResult = foreignKey("PARAMS_RUN_RESULT_FK", runResultUuid, runResults)(_.uuid, onDelete=ForeignKeyAction.Cascade)
    def key = column[String]("KEY")
    def value = column[String]("VALUE")
    def * = (id, runResultUuid, key, value).mapTo[DbRunResultParam]
  }

  lazy val runResultParams = TableQuery[RunResultParamRow]

  class JvmArgRow(tag: Tag) extends Table[DbJvmArg](tag, "JVM_ARGS") {
    def runResultUuid = column[String]("RUN_RESULT_UUID", O.Length(36))
    //def runResult = foreignKey("JVM_ARGS_RUN_RESULT_FK", runResultUuid, runResults)(_.uuid, onDelete=ForeignKeyAction.Cascade)
    def sequence = column[Int]("SEQUENCE")
    def value = column[String]("VALUE")
    def pk = primaryKey("PK_JVM_ARGS", (runResultUuid, sequence))
    def * = (runResultUuid, sequence, value).mapTo[DbJvmArg]
  }

  lazy val jvmArgs = TableQuery[JvmArgRow]

  class JmhArgRow(tag: Tag) extends Table[DbJmhArg](tag, "JMH_ARGS") {
    def testRunUuid = column[String]("TEST_RUN_UUID", O.Length(36))
    //def testRun = foreignKey("JMH_ARGS_TEST_RUN_FK", testRunUuid, testRuns)(_.uuid, onDelete=ForeignKeyAction.Cascade)
    def sequence = column[Int]("SEQUENCE")
    def value = column[String]("VALUE")
    def pk = primaryKey("PK_JMH_ARGS", (testRunUuid, sequence))
    def * = (testRunUuid, sequence, value).mapTo[DbJmhArg]
  }

  lazy val jmhArgs = TableQuery[JmhArgRow]

  class MetaRow(tag: Tag) extends Table[DbMeta](tag, "BENCHDB_META") {
    def key = column[String]("KEY", O.PrimaryKey)
    def value = column[String]("VALUE")
    def * = (key, value).mapTo[DbMeta]
  }

  lazy val meta = TableQuery[MetaRow]

  def createDb: DBIO[Unit] = {
    (testRuns.schema ++ meta.schema ++ runResults.schema ++ runResultParams.schema ++ jvmArgs.schema ++ jmhArgs.schema).create andThen
    meta.forceInsert(DbMeta("version", DB_VERSION)).map(_ => ())
  }.transactionally

  def dropDb: DBIO[Unit] = {
    (testRuns.schema ++ meta.schema ++ runResults.schema ++ runResultParams.schema ++ jvmArgs.schema ++ jmhArgs.schema).dropIfExists
  }.transactionally

  def getDbInfo: DBIO[String] =
    (Functions.user, Functions.database).result.map { case (u, db) => s"Connected to database '$db' as user '$u'." }

  def checkVersion: DBIO[Unit] =
    meta.filter(_.key === "version").map(_.value).result.head.map(v => if(v == DB_VERSION) () else logger.error(s"Unsupported database version $v, expeted $DB_VERSION"))

  def insertRun(run: DbTestRun, runResultsData: Iterable[DbRunResult], jvmArgsData: Iterable[DbJvmArg], runResultParamsData: Iterable[DbRunResultParam], jmhArgsData: Iterable[DbJmhArg]): DBIO[Long] = (for {
    runId <- (testRuns returning testRuns.map(_.runId)) += run
    _ <- runResults.forceInsertAll(runResultsData)
    _ <- runResultParams.forceInsertAll(runResultParamsData)
    _ <- jvmArgs.forceInsertAll(jvmArgsData)
    _ <- jmhArgs.forceInsertAll(jmhArgsData)
  } yield runId).transactionally

  def queryResults(runIds: Seq[Long]): DBIO[Seq[(DbRunResult, Long)]] = {

    val q = for {
      rr <- runResults
      tr <- rr.testRun if tr.runId.inSet(runIds)
    } yield (rr, tr.runId)

    q.sortBy { case (rr, runId) => (runId, rr.sequence) }.result

    //val q = runResults.filterOpt(runId)((rr, id) => rr.testRunUuid.in(testRuns.filter(_.runId === id).map(_.uuid)))
    //q.sortBy(rr => (rr.testRunUuid, rr.sequence)).result
  }

  def countTestRuns(runUuidPrefix: Option[String]): DBIO[Int] = {
    val q = testRuns.filterOpt(runUuidPrefix)((r, uuid) => r.uuid.startsWith(uuid))
    q.length.result
  }

  def listTestRuns(limit: Option[Int]): DBIO[Seq[DbTestRun]] = {
    val q = testRuns.sortBy(_.timestamp.reverse)
    val q2 =
      limit match {
        case Some(i) => q.take(i)
        case None => q
      }
    q2.map(_.withoutRaw).result
  }

  def run[T](action: DBIO[T]): T =
    try Await.result(db.run(action), Duration.Inf)
    catch { case NonFatal(ex) =>
      logger.error("Error while executing database query", ex)
      throw new Abort
    }
}
