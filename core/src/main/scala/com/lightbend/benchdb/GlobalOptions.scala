package com.lightbend.benchdb

import java.awt.Desktop
import java.net.URI

import cats.implicits._
import com.monovore.decline._
import java.nio.file.{FileSystems, Files, Path}
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern

import better.files._

import scala.collection.JavaConverters._
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigRenderOptions}

case class GlobalOptions(configPath: Option[Path], noUserConfig: Boolean, props: Seq[String]) extends Logging {
  val userConfPath = FileSystems.getDefault.getPath(System.getProperty("user.home"), ".benchdb.conf")

  val config = {
    val opts = ConfigParseOptions.defaults().setAllowMissing(false)
    val refConf = ConfigFactory.parseResources(getClass, "/benchdb-reference.conf")
    val userConf =
      if(noUserConfig) refConf
      else if(Files.exists(userConfPath)) {
        logger.debug(s"Parsing config file $userConfPath")
        ConfigFactory.parseFile(userConfPath.toFile, opts).withFallback(refConf)
      } else {
        logger.debug(s"User configuration file $userConfPath not found")
        refConf
      }
    val localConf = (configPath match {
      case Some(p) =>
        if(Files.exists(p)) {
          logger.debug(s"Parsing config file $p")
          ConfigFactory.parseFile(p.toFile, opts).withFallback(userConf)
        } else {
          logger.error(s"Configuration file $userConfPath not found")
          userConf
        }
      case None => userConf
    })
    val overrides = new java.util.HashMap[String, String]
    props.foreach { s =>
      val sep = s.indexOf('=')
      if(sep != -1)
      overrides.put(s.substring(0, sep), s.substring(sep+1))
    }
    ConfigFactory.parseMap(overrides).withFallback(localConf).resolve()
  }

  def checkDbConf(): Unit = {
    if(!config.hasPath("db")) {
      logger.error("No database configuration specified.")
      if(!Files.exists(userConfPath) && !noUserConfig)
        logger.error(s"No user config file '$userConfPath' found. Use `benchdb create-config` to create a default one using an embedded H2 database.")
    }
  }

  def createUserConfig(): Unit = {
    if(Files.exists(userConfPath))
      logger.error(s"Cannot create default user configuration in '$userConfPath'. File already exists.")
    else {
      val dbpath = FileSystems.getDefault.getPath(System.getProperty("user.home"), ".benchdb-data").toAbsolutePath
      val data = Map(
        "db.profile" -> "slick.jdbc.H2Profile$",
        "db.db.url" -> s"jdbc:h2:$dbpath",
        "db.db.driver" -> "org.h2.Driver",
        "db.db.connectionPool" -> "disabled"
      )
      val s = ConfigFactory.parseMap(data.asJava).root.render(ConfigRenderOptions.defaults().setOriginComments(false).setJson(false))
      File(userConfPath).write(s)(charset = "UTF-8")
      println(s"Configuration file '$userConfPath' created.")
      println("Verify the configuration, then use 'benchdb init-db --force' to initialize the database.")
    }
  }

  def validate(): this.type = {
    if(ErrorRecognitionAppender.rearm()) throw new Abort
    this
  }

  lazy val canBrowse =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)

  def openInBrowser(uri: URI) = {
    if(canBrowse) Desktop.getDesktop.browse(uri)
    else logger.warn(s"Cannot open $uri in browser - not supported by platform")
  }

  lazy val colors = config.getBoolean("colors")
  lazy val unicode = config.getBoolean("unicode")

  object format {
    val (cNormal, cBlack, cRed, cGreen, cYellow, cBlue, cMagenta, cCyan) =
      if(colors) ("\u001B[0m", "\u001B[30m", "\u001B[31m", "\u001B[32m", "\u001B[33m", "\u001B[34m", "\u001B[35m", "\u001B[36m")
      else ("", "", "", "", "", "", "", "")
    val (bRed, bGreen, bYellow, bBlue, bMagenta, bCyan) =
      if(colors) ("\u001B[41m", "\u001B[42m", "\u001B[43m", "\u001B[44m", "\u001B[45m", "\u001B[46m")
      else ("", "", "", "", "", "")

    private[this] val multi = if(unicode) "\u2507 " else "  "
    private[this] val multilineBorderPrefix = cYellow + multi + cNormal

    def multilineBorder(s: String): String =
      multilineBorderPrefix + s.replace("\n", "\n" + multilineBorderPrefix)

    val box: String =
      if(unicode) "\u2501\u250f\u2533\u2513\u2523\u254b\u252b\u2517\u253b\u251b\u2503" else "-/+\\|+|\\+/|"
  }
}

class Abort extends RuntimeException
