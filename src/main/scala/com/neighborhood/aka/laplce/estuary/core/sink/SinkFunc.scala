package com.neighborhood.aka.laplce.estuary.core.sink

import scala.concurrent.Future

/**
  * Created by john_liu on 2018/2/7.
  */
trait SinkFunc[Source] {
  def sink(source: Source): Boolean

  def asyncSink(source: Source):Future[Boolean]
}