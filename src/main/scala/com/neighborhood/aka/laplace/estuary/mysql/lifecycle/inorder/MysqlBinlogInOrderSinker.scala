package com.neighborhood.aka.laplace.estuary.mysql.lifecycle.inorder

import akka.actor.{Actor, ActorLogging}
import com.neighborhood.aka.laplace.estuary.bean.support.KafkaMessage
import com.neighborhood.aka.laplace.estuary.core.lifecycle
import com.neighborhood.aka.laplace.estuary.core.lifecycle.{SourceDataSinker, SyncControllerMessage}
import com.neighborhood.aka.laplace.estuary.mysql.task.Mysql2KafkaTaskInfoManager

/**
  * Created by john_liu on 2018/5/8.
  */
class MysqlBinlogInOrderSinker(
                                val mysql2KafkaTaskInfoManager: Mysql2KafkaTaskInfoManager,
                                val num: Int = -1
                              ) extends Actor with SourceDataSinker with ActorLogging {

  val syncTaskId = mysql2KafkaTaskInfoManager.syncTaskId
  val kafkaSinkFunc = mysql2KafkaTaskInfoManager.kafkaSink.fork
  val isSyncWrite = true
  lazy val powerAdapter = mysql2KafkaTaskInfoManager.powerAdapter
  lazy val processingCounter = mysql2KafkaTaskInfoManager.processingCounter

  override def receive: Receive = {
    case message:KafkaMessage => {
      val tableName = message.getBaseDataJsonKey.tableName
      val dbName = message.getBaseDataJsonKey.dbName
      val topic = kafkaSinkFunc.findTopic("")
      kafkaSinkFunc.sink()
    }
  }

  /**
    * 错位次数阈值
    */
  override var errorCountThreshold: Int = _

  /**
    * 错位次数
    */
  override var errorCount: Int = _

  /**
    * 错误处理
    */
  override def processError(e: Throwable, message: lifecycle.WorkerMessage): Unit = ???
}