package com.neighborhood.aka.laplace.estuary.cluster

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorRef
import com.neighborhood.aka.laplace.estuary.bean.identity.BaseExtractBean
import com.neighborhood.aka.laplace.estuary.core.akkaUtil.theActorSystem

/**
  * Created by john_liu on 2018/5/15.
  */
object SyncTaskActorContext extends theActorSystem{

  val taskBeanActorRefMap = new ConcurrentHashMap[BaseExtractBean,ActorRef]()

}
