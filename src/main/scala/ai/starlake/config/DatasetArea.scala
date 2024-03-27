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

package ai.starlake.config

import ai.starlake.schema.handlers.StorageHandler
import ai.starlake.utils.Utils
import com.typesafe.scalalogging.StrictLogging
import org.apache.hadoop.fs.Path

/** Utilities methods to reference datasets paths Datasets paths are constructed as follows :
  *   - root path defined by the SL_DATASETS env var or datasets application property
  *   - followed by the area name
  *   - followed by the the domain name
  */
object DatasetArea extends StrictLogging {

  def path(domain: String, area: String)(implicit settings: Settings): Path = {
    if (area.contains("://"))
      new Path(area)
    else if (settings.appConfig.datasets.contains("://"))
      new Path(
        s"${settings.appConfig.datasets}/$area/$domain"
      )
    else
      new Path(
        s"${settings.appConfig.fileSystem}/${settings.appConfig.datasets}/$area/$domain"
      )
  }

  def path(domain: String)(implicit settings: Settings): Path =
    if (settings.appConfig.datasets.contains("://"))
      new Path(
        s"${settings.appConfig.datasets}/$domain"
      )
    else
      new Path(
        s"${settings.appConfig.fileSystem}/${settings.appConfig.datasets}/$domain"
      )

  def path(domainPath: Path, schema: String) = new Path(domainPath, schema)

  /** datasets waiting to be ingested are stored here
    *
    * @param domain
    *   : Domain Name
    * @return
    *   Absolute path to the pending folder of domain
    */
  def stage(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.appConfig.area.stage)

  /** datasets with a file name that could not match any schema file name pattern in the specified
    * domain are marked unresolved by being stored in this folder.
    *
    * @param domain
    *   : Domain name
    * @return
    *   Absolute path to the pending unresolved folder of domain
    */
  def unresolved(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.appConfig.area.unresolved)

  /** Once ingested datasets are archived in this folder.
    *
    * @param domain
    *   : Domain name
    * @return
    *   Absolute path to the archive folder of domain
    */
  def archive(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.appConfig.area.archive)

  /** Datasets of the specified domain currently being ingested are located in this folder
    *
    * @param domain
    *   : Domain name
    * @return
    *   Absolute path to the ingesting folder of domain
    */
  def ingesting(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.appConfig.area.ingesting)

  def `export`(domain: String)(implicit settings: Settings): Path = {
    path(domain, "export")
  }

  def `export`(domain: String, table: String)(implicit settings: Settings): Path = {
    new Path(`export`(domain), table)
  }

  def metrics(domain: String, schema: String)(implicit settings: Settings): Path =
    substituteDomainAndSchemaInPath(domain, schema, settings.appConfig.metrics.path)

  def audit(domain: String, schema: String)(implicit settings: Settings): Path =
    substituteDomainAndSchemaInPath(domain, schema, settings.appConfig.audit.path)

  def expectations(domain: String, schema: String)(implicit settings: Settings): Path =
    substituteDomainAndSchemaInPath(domain, schema, settings.appConfig.expectations.path)

  def replay(domain: String)(implicit settings: Settings): Path =
    path(domain, settings.appConfig.area.replay)

  def substituteDomainAndSchemaInPath(
    domain: String,
    schema: String,
    path: String
  ): Path = {
    new Path(
      substituteDomainAndSchema(domain, schema, path)
    )
  }

  def substituteDomainAndSchema(domain: String, schema: String, template: String): String = {
    val normalizedDomainName = Utils.keepAlphaNum(domain)
    template
      .replace("{{domain}}", domain)
      .replace("{{normalized_domain}}", normalizedDomainName)
      .replace("{{schema}}", schema)
  }

  def discreteMetrics(domain: String, schema: String)(implicit settings: Settings): Path =
    DatasetArea.metrics(domain, "discrete")

  def continuousMetrics(domain: String, schema: String)(implicit settings: Settings): Path =
    DatasetArea.metrics(domain, "continuous")

  def frequenciesMetrics(domain: String, schema: String)(implicit settings: Settings): Path =
    DatasetArea.metrics(domain, "frequencies")

  def metadata(implicit settings: Settings): Path =
    new Path(s"${settings.appConfig.metadata}")

  def types(implicit settings: Settings): Path =
    new Path(metadata, "types")

  def dags(implicit settings: Settings): Path =
    new Path(settings.appConfig.dags)

  def writeStrategies(implicit settings: Settings): Path =
    new Path(settings.appConfig.writeStrategies)

  def expectations(implicit settings: Settings): Path =
    new Path(metadata, "expectations")

  def mapping(implicit settings: Settings): Path =
    new Path(metadata, "mapping")

  def tests(implicit settings: Settings): Path =
    new Path(metadata, "tests")

  def load(implicit settings: Settings): Path =
    new Path(metadata, "load")

  def external(implicit settings: Settings): Path =
    new Path(metadata, "external")

  def extract(implicit settings: Settings): Path =
    new Path(metadata, "extract")

  def transform(implicit settings: Settings): Path =
    new Path(metadata, "transform")

  def iamPolicyTags()(implicit settings: Settings): Path =
    new Path(DatasetArea.metadata, "iam-policy-tags.sl.yml")

  /** @param storage
    */
  def initMetadata(
    storage: StorageHandler
  )(implicit settings: Settings): Unit = {
    List(metadata, types, load, external, extract, transform, expectations, mapping)
      .foreach(
        storage.mkdirs
      )

  }

  def initDomains(storage: StorageHandler, domains: Iterable[String])(implicit
    settings: Settings
  ): Unit = {
    domains.foreach { domain =>
      List(stage _, unresolved _, archive _, replay _)
        .map(_(domain))
        .foreach(storage.mkdirs)
    }
  }
}
