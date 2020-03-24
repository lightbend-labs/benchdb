package com.lightbend.benchdb

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class PlatformData extends Logging {

  val hostname: String = {
    try java.net.InetAddress.getLocalHost.getHostName
    catch { case NonFatal(ex) =>
      logger.warn("Error determining host name", ex)
      null
    }
  }

  val systemProps = System.getProperties.asScala.asInstanceOf[scala.collection.Map[String, String]]

  val javaVendor = systemProps.getOrElse("java.vendor", null)
  val javaVersion = systemProps.getOrElse("java.version", null)
  val javaVmName = systemProps.getOrElse("java.vm.name", null)
  val javaVmVersion = systemProps.getOrElse("java.vm.version", null)
  val osName = systemProps.getOrElse("os.name", null)
  val osArch = systemProps.getOrElse("os.arch", null)
  val osVersion = systemProps.getOrElse("os.version", null)
  val userName = systemProps.getOrElse("user.name", null)

  logger.debug(s"Host name: $hostname, user name: $userName, OS: $osName $osArch $osVersion")
  logger.debug(s"Java: $javaVendor $javaVersion, VM: $javaVmName $javaVmVersion")

  lazy val javaMapData: java.util.Map[String, _] = {
    Map(
      "hostname" -> hostname,
      /*"java.vendor" -> javaVendor,
      "java.version" -> javaVersion,
      "java.vm.name" -> javaVmName,
      "java.vm.version" -> javaVmVersion,
      "os.name" -> osName,
      "os.arch" -> osArch,
      "os.version" -> osVersion,
      "username" -> userName,*/
      "props" -> systemProps.map { case (k, v) => (k.replace('.', '_'), v) }.asJava,
    ).asJava
  }

  def toJsonString: String = ConfigFactory.parseMap(javaMapData).root.render(ConfigRenderOptions.concise())
}
