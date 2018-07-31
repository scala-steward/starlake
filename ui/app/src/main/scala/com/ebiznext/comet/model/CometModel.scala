package com.ebiznext.comet.model

import java.awt.Color

import com.ebiznext.comet.utils.JsonSupport

object CometModel extends JsonSupport {

  sealed case class Parity(value: String)

  object Parity {
    object ODD extends Parity("ODD")
    object EVEN extends Parity("EVEN")
    object IGNORE extends Parity("IGNORE")

    val values = Seq(ODD, EVEN, IGNORE)
  }

  import Parity._

  case class Tag(
    name: String,
    defaultValue: String,
    parity: Parity = IGNORE,
    min: Int = 1,
    max: Int = Integer.MAX_VALUE,
    color: Color = Color.blue
  )
  case class TagValue(name: String, value: String)
  object TagValue {

    def empty: TagValue = TagValue("", "")
  }

  case class Node(name: String, tags: Set[TagValue])

  object Node {

    def empty: Node = Node("", Set())
  }

  case class Cluster(id: String, inventoryFile: String, tags: Set[Tag], nodes: Set[Node], nodeGroups: Set[Node])

  object Cluster {
    def empty: Cluster = Cluster("", "", Set(), Set(), Set())
  }
  case class User(id: String, clusters: Set[Cluster])

  object User {
    def empty: User = User("", Set())
  }
}
