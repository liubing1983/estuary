package com.neighborhood.aka.laplace.estuary

import com.neighborhood.aka.laplace.estuary.bean.identity.BaseExtractBean

/**
  * Created by john_liu on 2018/5/14.
  */
package object cluster {

  trait taskStartUpResult

  final case class ClientMessage(msg: Any)

  final case class TaskFailed(reason: String, task: BaseExtractBean) extends taskStartUpResult

  //任务失败相应原因
  final case class TaskSucceeded(reason: String, task: BaseExtractBean) extends taskStartUpResult

  case object BackendRegistration // 后台具体执行任务节点注册事件
}
