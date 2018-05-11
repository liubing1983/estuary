package com.neighborhood.aka.laplace.estuary.bean.datasink

/**
  * Created by john_liu on 2018/2/7.
  */
trait HBaseBean extends DataSinkBean {
  //  override var dataSinkType: DataSinkType = DataSinkType.KAFKA
  override var dataSinkType: String = SinkDataType.HBASE.toString

}


