/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package ai.starlake.schema.model

import ai.starlake.TestHelper

class SinkSpec extends TestHelper {
  new WithSettings() {
    "parsing any sink without options" should "succeed" in {
      mapper.readValue(
        """
          |connectionRef: "sink"
          |type: "Default"
          |""".stripMargin,
        classOf[Sink]
      ) shouldBe DefaultSink(connectionRef = Some("sink"))
    }

    "parsing any sink with options" should "succeed" in {
      mapper.readValue(
        """
          |connectionRef: "sink"
          |type: "Default"
          |""".stripMargin,
        classOf[Sink]
      ) shouldBe DefaultSink(
        connectionRef = Some("sink")
      )
    }

    "writing any sink without options" should "succeed" in {
      mapper.writeValueAsString(
        DefaultSink(connectionRef = Some("sink"))
      ) shouldBe """--- !<Default>
                         |connectionRef: "sink"
                         |type: "Default"
                         |""".stripMargin
    }

    "writing any sink with options" should "succeed" in {
      mapper.writeValueAsString(
        DefaultSink(connectionRef = Some("sink"))
      ) shouldBe """--- !<Default>
                         |connectionRef: "sink"
                         |type: "Default"
                         |""".stripMargin
    }

    "parsing BQ sink" should "succeed" in {
      mapper.readValue(
        """
          |connectionRef: "sink"
          |type: "BQ"
          |timestamp: "timestamp"
          |""".stripMargin,
        classOf[Sink]
      ) shouldBe BigQuerySink(connectionRef = Some("sink"), timestamp = Some("timestamp"))

    }

    "parsing FS sink" should "succeed" in {
      mapper.readValue(
        """
          |connectionRef: "sink"
          |type: "FS"
          |extension: "extension"
          |options:
          |  anyOption: "true"
          |""".stripMargin,
        classOf[Sink]
      ) shouldBe FsSink(
        connectionRef = Some("sink"),
        extension = Some("extension"),
        options = Some(Map("anyOption" -> "true"))
      )
    }

    "parsing ES sink" should "succeed" in {
      mapper.readValue(
        """
          |connectionRef: "sink"
          |type: "ES"
          |timestamp: "timestamp"
          |options:
          |  anyOption: "true"
          |""".stripMargin,
        classOf[Sink]
      ) shouldBe EsSink(
        connectionRef = Some("sink"),
        timestamp = Some("timestamp"),
        options = Some(Map("anyOption" -> "true"))
      )
    }

    "parsing JDBC sink" should "succeed" in {
      mapper.readValue(
        """
          |connectionRef: "sink"
          |type: "JDBC"
          |""".stripMargin,
        classOf[Sink]
      ) shouldBe JdbcSink(connectionRef = Some("sink"))
    }

    "parsing KAFKA sink" should "fail" in {
      assertThrows[Exception] {
        mapper.readValue(
          """
          |name: "sink"
          |type: "KAFKA"
          |options:
          |  anyOption: "true"
          |""".stripMargin,
          classOf[Sink]
        )
      }
    }
  }
}
