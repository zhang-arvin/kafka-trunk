/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.coordinator.transaction

import java.nio.charset.StandardCharsets

import kafka.utils.{Json, Logging}
import kafka.zk.{KafkaZkClient, ProducerIdBlockZNode}
import org.apache.kafka.common.KafkaException

import scala.jdk.CollectionConverters._

/**
 * ProducerIdManager is the part of the transaction coordinator that provides ProducerIds in a unique way
 * such that the same producerId will not be assigned twice across multiple transaction coordinators.
 *
 * ProducerIds are managed via ZooKeeper, where the latest producerId block is written on the corresponding ZK
 * path by the manager who claims the block, where the written block_start and block_end are both inclusive.
 */
object ProducerIdManager extends Logging {
  val CurrentVersion: Long = 1L
  val PidBlockSize: Long = 1000L

  def generateProducerIdBlockJson(producerIdBlock: ProducerIdBlock): Array[Byte] = {
    Json.encodeAsBytes(Map("version" -> CurrentVersion,
      "broker" -> producerIdBlock.brokerId,
      "block_start" -> producerIdBlock.blockStartId.toString,
      "block_end" -> producerIdBlock.blockEndId.toString).asJava
    )
  }

  def parseProducerIdBlockData(jsonData: Array[Byte]): ProducerIdBlock = {
    try {
      Json.parseBytes(jsonData).map(_.asJsonObject).flatMap { js =>
        val brokerId = js("broker").to[Int]
        val blockStart = js("block_start").to[String].toLong
        val blockEnd = js("block_end").to[String].toLong
        Some(ProducerIdBlock(brokerId, blockStart, blockEnd))
      }.getOrElse(throw new KafkaException(s"Failed to parse the producerId block json $jsonData"))
    } catch {
      case e: java.lang.NumberFormatException =>
        // this should never happen: the written data has exceeded long type limit
        fatal(s"Read jason data $jsonData contains producerIds that have exceeded long type limit")
        throw e
    }
  }
}

case class ProducerIdBlock(brokerId: Int, blockStartId: Long, blockEndId: Long) {
  override def toString: String = {
    val producerIdBlockInfo = new StringBuilder
    producerIdBlockInfo.append("(brokerId:" + brokerId)
    producerIdBlockInfo.append(",blockStartProducerId:" + blockStartId)
    producerIdBlockInfo.append(",blockEndProducerId:" + blockEndId + ")")
    producerIdBlockInfo.toString()
  }
}

class ProducerIdManager(val brokerId: Int, val zkClient: KafkaZkClient) extends Logging {

  this.logIdent = "[ProducerId Manager " + brokerId + "]: "

  private var currentProducerIdBlock: ProducerIdBlock = null
  private var nextProducerId: Long = -1L

  // grab the first block of producerIds
  this synchronized {
    getNewProducerIdBlock()
    nextProducerId = currentProducerIdBlock.blockStartId
  }

  private def getNewProducerIdBlock(): Unit = {
    var zkWriteComplete = false
    // 这里从zk
    while (!zkWriteComplete) {
      // refresh current producerId block from zookeeper again
      val (dataOpt, zkVersion) = zkClient.getDataAndVersion(ProducerIdBlockZNode.path)

      // generate the new producerId block
      currentProducerIdBlock = dataOpt match {
        case Some(data) =>
          // 从 zk获取当前最新的pid信息，如果后面更新失败，这里也会重新从zk获取
          val currProducerIdBlock = ProducerIdManager.parseProducerIdBlockData(data)
          debug(s"Read current producerId block $currProducerIdBlock, Zk path version $zkVersion")

          if (currProducerIdBlock.blockEndId > Long.MaxValue - ProducerIdManager.PidBlockSize) {
            // 当PID 分配超过限制时，直接报错了（每秒分配一个 够用200年）
            // we have exhausted all producerIds (wow!), treat it as a fatal error
            fatal(s"Exhausted all producerIds as the next block's end producerId is will has exceeded long type limit (current block end producerId is ${currProducerIdBlock.blockEndId})")
            throw new KafkaException("Have exhausted all producerIds.")
          }

          ProducerIdBlock(brokerId, currProducerIdBlock.blockEndId + 1L, currProducerIdBlock.blockEndId + ProducerIdManager.PidBlockSize)
        case None =>
          //该节点还不存在，第一次初始化时
          debug(s"There is no producerId block yet (Zk path version $zkVersion), creating the first block")
          ProducerIdBlock(brokerId, 0L, ProducerIdManager.PidBlockSize - 1)
      }

      val newProducerIdBlockData = ProducerIdManager.generateProducerIdBlockJson(currentProducerIdBlock)

      // try to write the new producerId block into zookeeper
      // 尝试将新的pid 写入到zk，如果写入失败重新申请（写入之前会比对zkVersion，如果这个有变动，证明期间有别的Broker再操作，那么写入失败）
      val (succeeded, version) = zkClient.conditionalUpdatePath(ProducerIdBlockZNode.path,
        newProducerIdBlockData, zkVersion, Some(checkProducerIdBlockZkData))
      zkWriteComplete = succeeded

      if (zkWriteComplete)
        info(s"Acquired new producerId block $currentProducerIdBlock by writing to Zk with path version $version")
    }
  }

  private def checkProducerIdBlockZkData(zkClient: KafkaZkClient, path: String, expectedData: Array[Byte]): (Boolean, Int) = {
    try {
      val expectedPidBlock = ProducerIdManager.parseProducerIdBlockData(expectedData)
      zkClient.getDataAndVersion(ProducerIdBlockZNode.path) match {
        case (Some(data), zkVersion) =>
          val currProducerIdBLock = ProducerIdManager.parseProducerIdBlockData(data)
          (currProducerIdBLock == expectedPidBlock, zkVersion)
        case (None, _) => (false, -1)
      }
    } catch {
      case e: Exception =>
        warn(s"Error while checking for producerId block Zk data on path $path: expected data " +
          s"${new String(expectedData, StandardCharsets.UTF_8)}", e)
        (false, -1)
    }
  }

  def generateProducerId(): Long = {
    this synchronized {
      // grab a new block of producerIds if this block has been exhausted
      if (nextProducerId > currentProducerIdBlock.blockEndId) {
        //如果分配的 pid 用完了，重新再向 zk 申请一批
        getNewProducerIdBlock()
        nextProducerId = currentProducerIdBlock.blockStartId + 1
      } else {
        nextProducerId += 1
      }

      nextProducerId - 1
    }
  }

  def shutdown(): Unit = {
    info(s"Shutdown complete: last producerId assigned $nextProducerId")
  }
}
