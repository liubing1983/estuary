package com.neighborhood.aka.laplace.estuary.mysql.lifecycle

import java.io.IOException
import java.nio.ByteBuffer
import java.util
import java.util.concurrent.Executors

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props}
import com.alibaba.otter.canal.parse.driver.mysql.packets.HeaderPacket
import com.alibaba.otter.canal.parse.driver.mysql.packets.client.BinlogDumpCommandPacket
import com.alibaba.otter.canal.parse.driver.mysql.packets.server.ResultSetPacket
import com.alibaba.otter.canal.parse.driver.mysql.utils.PacketManager
import com.alibaba.otter.canal.parse.exception.{CanalParseException, TableIdNotFoundException}
import com.alibaba.otter.canal.parse.inbound.mysql.MysqlConnection
import com.alibaba.otter.canal.parse.inbound.mysql.dbsync.{DirectLogFetcher, TableMetaCache}
import com.alibaba.otter.canal.protocol.CanalEntry
import com.alibaba.otter.canal.protocol.position.EntryPosition
import com.neighborhood.aka.laplace.estuary.core.lifecycle.{SourceDataFetcher, Status}
import com.neighborhood.aka.laplace.estuary.core.task.TaskManager
import com.neighborhood.aka.laplace.estuary.mysql.{Mysql2KafkaTaskInfoManager, MysqlBinlogParser}
import com.neighborhood.aka.laplace.estuary.core.lifecycle.Status.Status
import com.neighborhood.aka.laplace.estuary.core.lifecycle._
import com.neighborhood.aka.laplace.estuary.core.task.TaskManager
import com.neighborhood.aka.laplace.estuary.mysql.{Mysql2KafkaTaskInfoManager, MysqlBinlogParser}
import com.taobao.tddl.dbsync.binlog.event.FormatDescriptionLogEvent
import com.taobao.tddl.dbsync.binlog.{LogContext, LogDecoder, LogEvent, LogPosition}
import org.I0Itec.zkclient.exception.ZkTimeoutException
import org.apache.commons.lang.StringUtils

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Created by john_liu on 2018/2/5.
  *
  * @todo 将fetcher和actor解耦
  */

class MysqlBinlogFetcher(mysql2KafkaTaskInfoManager: Mysql2KafkaTaskInfoManager, binlogEventBatcher: ActorRef) extends Actor with SourceDataFetcher with ActorLogging {

  implicit val transTaskPool = Executors.newSingleThreadExecutor()
  /**
    * binlogParser 解析binlog
    */
  lazy val binlogParser: MysqlBinlogParser = mysql2KafkaTaskInfoManager.binlogParser
  /**
    * 重试机制
    */
  override var errorCountThreshold: Int = 3
  override var errorCount: Int = 0
  /**
    * 模拟的从库id
    */
  val slaveId = mysql2KafkaTaskInfoManager.slaveId
  /**
    * mysql链接
    * 必须是fork 出来的
    */
  val mysqlConnection: Option[MysqlConnection] = Option(mysql2KafkaTaskInfoManager.mysqlConnection.fork())
  /**
    * 寻址处理器
    */
  val logPositionHandler = mysql2KafkaTaskInfoManager.logPositionHandler
  /**
    * 暂存的entryPosition
    */
  var entryPosition: Option[EntryPosition] = None
  /**
    * mysql的checksum校验机制
    */
  var binlogChecksum = 0
  /**
    * 是否记录耗时
    *
    * @todo 改成atomtic
    */
  var isProfiling = mysql2KafkaTaskInfoManager.taskInfo.isProfiling

  var isCounting = mysql2KafkaTaskInfoManager.taskInfo.isCounting
  /**
    * 数据fetch用
    */
  var fetcher: DirectLogFetcher = null
  var logContext: LogContext = null
  var decoder: LogDecoder = null
  /**
    * 元数据链接
    */
  var mysqlMetaConnection: Option[MysqlConnection] = None

  //offline
  override def receive: Receive = {

    case FetcherMessage(msg) => {
      msg match {
        case "restart" => {
          log.info("fetcher restarting")
          self ! SyncControllerMessage("start")
        }
        case str: String => {
          log.warning(s"fetcher offline  unhandled message:$str")
        }
      }

    }
    case SyncControllerMessage(msg) => {
      msg match {
        case "stop" => {
          context.become(receive)
          fetcherChangeStatus(Status.OFFLINE)
        }
        case "start" => {
          try {
            mysqlConnection.map(_.connect())
            entryPosition = Option(logPositionHandler.findStartPosition(mysqlConnection.get)(errorCount > 0))

            if (entryPosition.isDefined) {
              //寻找完后必须reconnect一下
              mysqlConnection.get.synchronized {
                mysqlConnection.get.reconnect
              }
              log.info(s"fetcher find start position,binlogFileName:${entryPosition.get.getJournalName},${entryPosition.get.getPosition}")
              context.become(online)
              log.info(s"fetcher switch to online")
              self ! FetcherMessage("start")
            } else {
              log.error("fetcher find entryPosition is null")
              throw new Exception("entryPosition is null")
            }
          }
          catch {
            case e: Exception => processError(e, SyncControllerMessage("start"))
          }

        }
      }
    }
  }

  def online: Receive = {
    case FetcherMessage(msg) => {
      msg match {
        case "start" => {
          fetcherChangeStatus(Status.ONLINE)
          self ! FetcherMessage("predump")
        }
        case "predump" => {
          log.debug("fetcher predump")
          mysqlMetaConnection = Option(preDump(mysqlConnection.get))
          mysqlConnection.get.connect
          val startPosition = entryPosition.get
          try {
            if (StringUtils.isEmpty(startPosition.getJournalName) && Option(startPosition.getTimestamp).isEmpty) {
              log.error("unsupported operation: dump by timestamp is not supported yet")
              throw new UnsupportedOperationException("unsupported operation: dump by timestamp is not supported yet")
            } else {
              log.info("start dump binlog")
              dump(startPosition.getJournalName, startPosition.getPosition)

            }
            self ! FetcherMessage("fetch")
          } catch {
            case e: Exception => processError(e, FetcherMessage("start"))
          }
        }
        case "fetch" => {
          //如果两分钟还不能拿到数据，发起重连
          val flag = Await.result(Future(fetcher.fetch()), 2 minutes)
          try {

            //            println(flag)
            //            println("before fetch")
            if (flag) {
              fetchOne

              context.system.scheduler.scheduleOnce(0 second, self, FetcherMessage("fetch"))
              //              println("after fetch")
            } else {

            }
          } catch {
            case e: TableIdNotFoundException => {
              entryPosition = Option(logPositionHandler.findStartPositionWithinTransaction(mysqlConnection.get)(errorCount > 0))
              self ! FetcherMessage("start")
            }
            case e: Exception => processError(e, FetcherMessage("fetch"))
          }
        }
        case "restart" => {
          context.become(receive)
          self ! FetcherMessage("restart")
        }
        case str: String => {
          println(s"fetcher online unhandled command:$str")
        }
      }
    }
    case SyncControllerMessage(msg) => {

    }
  }

  /**
    * @param binlogFileName binlog文件名称
    * @param binlogPosition binlogEntry位置
    *
    */
  def dump(binlogFileName: String, binlogPosition: Long) = {
    updateSettings(mysqlConnection.get)
    loadBinlogChecksum(mysqlConnection.get)
    sendBinlogDump(binlogFileName, binlogPosition)(mysqlConnection.get)
    val connector = mysqlConnection.get.getConnector
    fetcher = new DirectLogFetcher(connector.getReceiveBufferSize)
    fetcher.start(connector.getChannel)
    decoder = new LogDecoder(LogEvent.UNKNOWN_EVENT, LogEvent.ENUM_END_EVENT)
    logContext = new LogContext
    logContext.setLogPosition(new LogPosition(binlogFileName, binlogPosition))
    logContext.setFormatDescription(new FormatDescriptionLogEvent(4, binlogChecksum))
  }

  @deprecated("use `fetchOne`")
  @tailrec
  final def loopFetchAll: Unit = {
    if (fetcher.fetch()) {
      fetchOne
      loopFetchAll
    }
  }

  /**
    * 从连接中取数据
    */
  def fetchOne = {
    val before = System.currentTimeMillis()
    val event = decoder.decode(fetcher, logContext)
    val entry = try {
      binlogParser.parseAndProfilingIfNecessary(event, false)
    } catch {
      case e: CanalParseException => {
        log.warning(s"table has been removed")
        None
      }
    }
    if (filterEntry(entry)) {
      val after = System.currentTimeMillis()
      //      println(after-before)
      // Thread.sleep(2)
      log.debug(s"fetch entry: ${entry.get.getHeader.getLogfileName},${entry.get.getHeader.getLogfileOffset},${after - before}")
      binlogEventBatcher ! entry.get
      if (isCounting) mysql2KafkaTaskInfoManager.fetchCount.incrementAndGet()
    } else {
      //throw new Exception("the fetched data is null")
    }
  }

  /**
    * 从binlog拉取数据之前的设置
    */
  def updateSettings(mysqlConnection: MysqlConnection): Unit = {
    val settings = List(
      "set wait_timeout=9999999",
      "set net_write_timeout=1800",
      "set net_read_timeout=1800",
      "set names 'binary'",
      "set @master_binlog_checksum= @@global.binlog_checksum"
    )
    settings.map(mysqlConnection.update(_))

  }

  /**
    * 加载mysql的binlogChecksum机制
    *
    */
  private def loadBinlogChecksum(mysqlConnection: MysqlConnection): Unit = {
    var rs: ResultSetPacket = null
    try
      rs = mysqlConnection.query("select @master_binlog_checksum")
    catch {
      case e: IOException =>
        throw new CanalParseException(e)
    }
    val columnValues: util.List[String] = rs.getFieldValues
    binlogChecksum = if (columnValues != null && columnValues.size >= 1 && columnValues.get(0).toUpperCase == "CRC32") LogEvent.BINLOG_CHECKSUM_ALG_CRC32
    else LogEvent.BINLOG_CHECKSUM_ALG_OFF
  }

  /**
    * 准备dump数据
    */
  @throws[IOException]
  private def sendBinlogDump(binlogfilename: String, binlogPosition: Long)(mysqlConnection: MysqlConnection) = {
    val binlogDumpCmd = new BinlogDumpCommandPacket
    binlogDumpCmd.binlogFileName = binlogfilename
    binlogDumpCmd.binlogPosition = binlogPosition
    binlogDumpCmd.slaveServerId = this.slaveId
    val cmdBody = binlogDumpCmd.toBytes
    //todo logstash
    val binlogDumpHeader = new HeaderPacket
    binlogDumpHeader.setPacketBodyLength(cmdBody.length)
    binlogDumpHeader.setPacketSequenceNumber(0x00.toByte)
    PacketManager.write(mysqlConnection.getConnector.getChannel, Array[ByteBuffer](ByteBuffer.wrap(binlogDumpHeader.toBytes), ByteBuffer.wrap(cmdBody)))
    mysqlConnection.getConnector.setDumping(true)
  }

  /**
    * Canal中，preDump中做了binlogFormat/binlogImage的校验
    * 这里暂时忽略，可以再以后有必要的时候进行添加
    *
    */
  def preDump(mysqlConnection: MysqlConnection): MysqlConnection = {
    //设置tableMetaCache
    val metaConnection = mysqlConnection.fork()
    metaConnection.connect()
    val tableMetaCache: TableMetaCache = new TableMetaCache(metaConnection)
    binlogParser.setTableMetaCache(tableMetaCache)
    metaConnection
  }

  /**
    * @param entryOption 得到的entry
    *                    对entry在类型级别上进行过滤
    */
  def filterEntry(entryOption: Option[CanalEntry.Entry]): Boolean = {
    if (!entryOption.isDefined) {
      return false
    }
    val entry = entryOption.get
    //todo 对entry过滤做明确设定
    if ((entry.getEntryType == CanalEntry.EntryType.ROWDATA) || (entry.getEntryType == CanalEntry.EntryType.TRANSACTIONEND)) true else {
      false
    }
  }

  /**
    * 错误处理
    */
  override def processError(e: Throwable, message: WorkerMessage): Unit = {
    //todo 记录log
    errorCount += 1
    if (isCrashed) {
      fetcherChangeStatus(Status.ERROR)
      errorCount = 0
      println(message.msg)
      e.printStackTrace()
      throw new Exception("fetching data failure for 3 times")
    } else {
      self ! message
    }
  }

  /**
    * ********************* 状态变化 *******************
    */
  private def changeFunc(status: Status) = TaskManager.changeFunc(status, mysql2KafkaTaskInfoManager)

  private def onChangeFunc = Mysql2KafkaTaskInfoManager.onChangeStatus(mysql2KafkaTaskInfoManager)

  private def fetcherChangeStatus(status: Status) = TaskManager.changeStatus(status, changeFunc, onChangeFunc)

  /**
    * ********************* Actor生命周期 *******************
    */
  override def preStart(): Unit = {
    if (mysqlMetaConnection.isDefined && mysqlConnection.get.isConnected) mysqlConnection.get.disconnect()
    //状态置为offline
    fetcherChangeStatus(Status.OFFLINE)
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)

    log.info("fetcher will restart in 1 min")
  }

  override def postStop(): Unit = {
    mysqlConnection.get.disconnect()
    if (mysqlMetaConnection.isDefined) mysqlMetaConnection.get.disconnect()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {

    context.become(receive)
    fetcherChangeStatus(Status.RESTARTING)
    super.preRestart(reason, message)
  }

  override def supervisorStrategy = {
    OneForOneStrategy() {
      case e: ZkTimeoutException => {
        fetcherChangeStatus(Status.ERROR)
        Restart
        //todo log
      }
      case e: Exception => {
        fetcherChangeStatus(Status.ERROR)
        Restart
      }
      case error: Error => {
        fetcherChangeStatus(Status.ERROR)
        Restart
      }
      case _ => {
        fetcherChangeStatus(Status.ERROR)
        Restart
      }
    }
  }

}

object MysqlBinlogFetcher {
  def props(mysql2KafkaTaskInfoManager: Mysql2KafkaTaskInfoManager, binlogEventBatcher: ActorRef): Props = {
    Props(new MysqlBinlogFetcher(mysql2KafkaTaskInfoManager, binlogEventBatcher))
  }
}
