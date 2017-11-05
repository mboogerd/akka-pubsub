package io.demograph.hyparview

import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig

/**
  *
  */
object HyParViewTopology extends MultiNodeConfig {
  val bootstrap: RoleName = role("bootstrap")
  val node1: RoleName = role("node1")
  val node2: RoleName = role("node2")
  testTransport(on = true)
}
