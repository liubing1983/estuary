package com.neighborhood.aka.laplace.estuary.cluster

import akka.actor.{Actor, ActorLogging}

/**
  * Created by john_liu on 2018/5/15.
  */
trait ClusterNode {
  //self被actor占用了
  thisOne: Actor with ActorLogging =>
  override def preStart(): Unit = {

  }
}
