package com.lightbend.benchdb

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

class Global(val go: GlobalOptions) {
  private var daoInitialized = false

  lazy val dao = {
    go.checkDbConf()
    go.validate()
    val dbconf = DatabaseConfig.forConfig[JdbcProfile]("db", go.config)
    val dao = new DAO(dbconf.profile, dbconf.db)
    daoInitialized = true
    dao
  }

  def use[U](f: Global => U): U =
    try f(this) finally close()

  def close(): Unit = if(daoInitialized) dao.close()
}
