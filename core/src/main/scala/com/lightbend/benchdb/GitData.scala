package com.lightbend.benchdb

import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.{TemporalAccessor, TemporalQuery}
import java.util.Date

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import org.eclipse.jgit.api.{Git, ListBranchCommand}
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.revwalk.RevWalk

class GitData(projectDir: Path) extends Logging {

  private var headDate: Date = _
  private var headSHA: String = _
  private var originURL: String = _
  private var upstreamURL: String = _
  private var headBranches: Set[String] = Set.empty
  private var headSubject: String = _
  private var parents: Seq[(String, String)] = Seq.empty

  def getHeadDate: Option[Date] = Option(headDate)
  def getHeadSHA: Option[String] = Option(headSHA)
  def getOriginURL: Option[String] = Option(originURL)
  def getUpstreamURL: Option[String] = Option(upstreamURL)

  override def toString: String =
    s"GitData(headDate=$headDate, headSHA=$headSHA)"

  try {
    val builder = new FileRepositoryBuilder().findGitDir(projectDir.toFile)
    if(builder.getGitDir == null) logger.warn(s"No git repository found in project dir '$projectDir'")
    else {
      val repo = builder.build
      val git = new Git(repo)
      logger.debug("Found git repository: "+repo.getIdentifier)
      originURL = repo.getConfig.getString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin", ConfigConstants.CONFIG_KEY_URL)
      upstreamURL = repo.getConfig.getString(ConfigConstants.CONFIG_REMOTE_SECTION, "upstream", ConfigConstants.CONFIG_KEY_URL)
      logger.debug(s"Origin: $originURL, upstream: $upstreamURL")
      val head = repo.resolve("HEAD")
      if(head == null) logger.warn("No git HEAD commit found")
      else {
        val walk = new RevWalk(repo)
        val headCommit = walk.parseCommit(head)
        headDate = new Date(headCommit.getCommitTime.toLong * 1000L)
        headSHA = headCommit.getName
        headSubject = headCommit.getShortMessage
        logger.debug(s"git HEAD is $headSHA")
        headBranches = git.branchList.setContains("HEAD").setListMode(ListBranchCommand.ListMode.ALL).call().asScala.map(_.getName).toSet
        logger.debug(s"Branches containing HEAD: "+headBranches.mkString(", "))
        val headDescription = git.describe().setTarget("HEAD").call()
        logger.debug(s"HEAD description: $headDescription")
        parents = headCommit.getParents.iterator.map { r =>
          (r.getName, walk.parseCommit(r).getShortMessage)
        }.toSeq
        logger.debug("Parent commits: "+parents.map { case (u, s) => s"[$u: $s]"}.mkString(", "))
      }
    }
  } catch { case NonFatal(ex) => logger.warn("Error analyzing git data", ex) }

  lazy val javaMapData: java.util.Map[String, _] = {
    Map(
      "head.date" -> headDate.getTime,
      "head.sha" -> headSHA,
      "head.branches" -> headBranches.asJava,
      "head.subject" -> headSubject,
      "originURL" -> originURL,
      "upstreamURL" -> upstreamURL,
      "parents" -> parents.map { case (u, s) => Map("url" -> u, "subject" -> s).asJava }.asJava
    ).asJava
  }

  def toJsonString: String = ConfigFactory.parseMap(javaMapData).root.render(ConfigRenderOptions.concise())
}
