package com.neighborhood.aka.laplace.estuary.core.util

import com.alibaba.otter.canal.common.utils.JsonUtils
import com.alibaba.otter.canal.common.zookeeper.{ZkClientx, ZookeeperPathUtils}
import com.alibaba.otter.canal.protocol.position.LogPosition
import org.I0Itec.zkclient.exception.ZkNoNodeException
import org.springframework.util.Assert

/**
  * Created by john_liu on 2018/5/3.
  */
class ZooKeeperLogPositionManager[T] {

  private var zkClientx: ZkClientx = null

  def start(): Unit = {

    Assert.notNull(zkClientx)
  }

  def stop(): Unit = {

  }

  def getLatestIndexBy(destination: String): T = {
    val path = ZookeeperPathUtils.getParsePath(destination)
    val data: Array[Byte] = zkClientx.readData(path, true)

    if (data == null || data.length == 0) null.asInstanceOf[T]
    else JsonUtils.unmarshalFromByte(data, classOf[T])
  }

  def persistLogPosition(destination: String, logPosition: T): Unit = {
    val path = ZookeeperPathUtils.getParsePath(destination)
    val data = JsonUtils.marshalToByte(logPosition)
    try
      zkClientx.writeData(path, data)
    catch {
      case e: ZkNoNodeException =>
        zkClientx.createPersistent(path, data, true)
    }
  }

  // ================== setter / getter =================
  def setZkClientx(zkClientx: ZkClientx): Unit = {
    this.zkClientx = zkClientx
  }


}
