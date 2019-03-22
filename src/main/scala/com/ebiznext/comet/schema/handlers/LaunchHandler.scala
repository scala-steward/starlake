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

package com.ebiznext.comet.schema.handlers

import com.ebiznext.comet.config.Settings
import com.ebiznext.comet.job.index.IndexConfig
import com.ebiznext.comet.schema.model.{Domain, Schema}
import com.ebiznext.comet.workflow.IngestionWorkflow
import com.typesafe.scalalogging.StrictLogging
import okhttp3._
import okio.Buffer
import org.apache.hadoop.fs.Path

import scala.util.{Failure, Success, Try}

/**
  * Interface required for any cron job launcher
  */
trait LaunchHandler {

  /**
    * Submit to the cron manager a single file for ingestion
    *
    * @param domain : Domain to which belong this dataset
    * @param schema : Schema of the dataset
    * @param path   : absolute path where the source dataset  (JSON / CSV / ...) is located
    * @return success / failure
    */
  def ingest(workflow: IngestionWorkflow, domain: Domain, schema: Schema, path: Path): Boolean =
    ingest(workflow, domain, schema, path :: Nil)

  /**
    * Submit to the cron manager multiple files for ingestion.
    * All the files should have the schema schema and belong to the same domain.
    *
    * @param domain : Domain to which belong this dataset
    * @param schema : Schema of the dataset
    * @param paths  : absolute paths where the source datasets  (JSON / CSV / ...) are located
    * @return success / failure
    */
  def ingest(
              workflow: IngestionWorkflow,
              domain: Domain,
              schema: Schema,
              paths: List[Path]
            ): Boolean

  /**
    * Index into elasticsearch
    *
    * @param config
    */
  def index(workflow: IngestionWorkflow, config: IndexConfig): Boolean
}

/**
  * Simple Launcher will directly invoke the ingestion method wityhout using a cron manager.
  * This is userfull for testing purpose
  */
class SimpleLauncher extends LaunchHandler with StrictLogging {

  /**
    * call directly the main assembly with the "ingest" parameter
    *
    * @param domain : Domain to which belong this dataset
    * @param schema : Schema of the dataset
    * @param paths  : absolute paths where the source datasets  (JSON / CSV / ...) are located
    * @return success / failure
    */
  override def ingest(
                       workflow: IngestionWorkflow,
                       domain: Domain,
                       schema: Schema,
                       paths: List[Path]
                     ): Boolean = {
    paths.foreach { path =>
      logger.info(s"Launch Ingestion: ${domain.name} ${schema.name} ${path.toString} ")
      workflow.ingest(domain.name, schema.name, path.toString)
    }
    true
  }

  /**
    * Index into elasticsearch
    *
    * @param config
    */
  override def index(workflow: IngestionWorkflow, config: IndexConfig): Boolean = {
    logger.info(s"Launch index: ${config}")
    workflow.index(config)
    true
  }
}

/**
  * Airflow Launcher will submit a request for ingestion to Airflow
  * using the REST API. The requested DAG must exist in Airflow first.
  */
class AirflowLauncher extends LaunchHandler with StrictLogging {

  protected def post(url: String, json: String): Try[String] = {
    Try {
      val JSON: MediaType = MediaType.parse("application/json; charset=utf-8")
      val client: OkHttpClient = new OkHttpClient
      val body: RequestBody = RequestBody.create(JSON, json)
      val request: Request = new Request.Builder().url(url).post(body).build
      val buffer = new Buffer()
      request.body().writeTo(buffer)
      logger.debug("Post to Airflow: " + request.toString + "\n" + buffer.readUtf8())
      val response: Response = client.newCall(request).execute
      val responseBody = response.body.string
      logger.debug("Post result from Airflow: " + responseBody)
      responseBody
    }
  }

  /**
    * Request the execution of the "comet-ingest" DAG in Airflow
    *
    * @param domain : Domain to which belong this dataset
    * @param schema : Schema of the dataset
    * @param paths  : absolute paths where the source datasets  (JSON / CSV / ...) are located
    * @return success if request accepted
    */
  override def ingest(
                       workflow: IngestionWorkflow,
                       domain: Domain,
                       schema: Schema,
                       paths: List[Path]
                     ): Boolean = {
    val endpoint = Settings.comet.airflow.endpoint
    val ingest = Settings.comet.airflow.ingest
    val url = s"$endpoint/dags/$ingest/dag_runs"
    val command =
      s"""ingest ${domain.name} ${schema.name} ${paths.mkString(",")}"""
    val json = s"""{"conf":"{\\"command\\":\\"$command\\"}"}"""
    logger.info(s"Post to Airflow: $json")
    post(url, json) match {
      case Success(_) =>
        true
      case Failure(exception) =>
        logger.error("Failed to post request to Airflow", exception)
        false
    }
  }

  /**
    * Index into elasticsearch
    *
    * @param config
    */
  override def index(workflow: IngestionWorkflow, config: IndexConfig): Boolean = {
    val endpoint = Settings.comet.airflow.endpoint
    val url = s"$endpoint/dags/comet_ingest/dag_runs"
    // comet index --domain domain --schema schema --resource index-name/type-name --id type-id --mapping mapping
    //    --format parquet|json|json-array --dataset datasetPath
    //    --conf key=value,key=value,...
    val resource =
    s"--timestamp ${config.timestamp} --domain ${config.domain} --schema ${config.schema} --format ${config.format} --dataset ${config.getDataset()}"
    val id = config.id.map(id => s"--id $id")
    val mapping = config.mapping.map(path => s"--mapping ${path.toString}")
    val params = List(Some(resource), id, mapping).flatten.mkString(" ")
    val command = s"""index $params """
    val json = s"""{"conf":"{\\"command\\":\\"$command\\"}"}"""
    logger.info(s"Post to Airflow: $json")
    post(url, json) match {
      case Success(_) =>
        true
      case Failure(exception) =>
        logger.error("Failed to post request to Airflow", exception)
        false
    }

  }
}
