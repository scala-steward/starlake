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

package ai.starlake.job.ingest

import ai.starlake.exceptions.NullValueFoundException
import ai.starlake.config.{CometColumns, Settings}
import ai.starlake.job.validator.CheckValidityResult
import ai.starlake.schema.handlers.{SchemaHandler, StorageHandler}
import ai.starlake.schema.model.{Domain, Schema, Type}
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.execution.datasources.json.JsonIngestionUtil.compareTypes
import org.apache.spark.sql.functions.input_file_name
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Dataset, Row}

import scala.util.{Failure, Success, Try}

/** Main class to XML file If your json contains only one level simple attribute aka. kind of dsv
  * but in json format please use JSON_FLAT instead. It's way faster
  *
  * @param domain
  *   : Input Dataset Domain
  * @param schema
  *   : Input Dataset Schema
  * @param types
  *   : List of globally defined types
  * @param path
  *   : Input dataset path
  * @param storageHandler
  *   : Storage Handler
  */
class XmlIngestionJob(
  val domain: Domain,
  val schema: Schema,
  val types: List[Type],
  val path: List[Path],
  val storageHandler: StorageHandler,
  val schemaHandler: SchemaHandler,
  val options: Map[String, String],
  val accessToken: Option[String],
  val test: Boolean
)(implicit val settings: Settings)
    extends IngestionJob {

  /** load the json as an RDD of String
    *
    * @return
    *   Spark Dataframe loaded using metadata options
    */
  def loadDataSet(withSchema: Boolean): Try[DataFrame] = {
    val xmlOptions = mergedMetadata.getXmlOptions()
    Try {
      val rowTag = xmlOptions.get("rowTag")
      rowTag.map { _ =>
        val df = path
          .map { singlePath =>
            session.read
              .format("com.databricks.spark.xml")
              .options(xmlOptions)
              .option("inferSchema", value = false)
              .option("encoding", mergedMetadata.resolveEncoding())
              .options(sparkOptions)
              .schema(schema.sparkSchemaUntypedEpochWithoutScriptedFields(schemaHandler))
              .load(singlePath.toString)
          }
          .reduce((acc, df) => acc union df)
        logger.whenInfoEnabled {
          logger.info(df.schemaString())
        }
        df
      } getOrElse (
        throw new Exception(s"rowTag not found for schema ${domain.name}.${schema.name}")
      )
    }
  }

  lazy val schemaSparkType: StructType = schema.sourceSparkSchema(schemaHandler)

  /** Where the magic happen
    *
    * @param dataset
    *   input dataset as a RDD of string
    */
  protected def ingest(dataset: DataFrame): (Dataset[String], Dataset[Row], Long) = {
    import session.implicits._
    val datasetSchema = dataset.schema
    val errorList = compareTypes(schemaSparkType, datasetSchema)
    val rejectedDS = errorList.toDS()
    mergedMetadata.getXmlOptions().get("skipValidation") match {
      case Some(_) =>
        val rejectedDS = errorList.toDS()
        saveRejected(rejectedDS, session.emptyDataset[String])(
          settings,
          storageHandler,
          schemaHandler
        ).flatMap { _ =>
          saveAccepted(
            CheckValidityResult(
              session.emptyDataset[String],
              session.emptyDataset[String],
              dataset
            )
          )
        } match {
          case Failure(exception: NullValueFoundException) =>
            (rejectedDS, dataset, exception.nbRecord)
          case Failure(exception) =>
            throw exception
          case Success(rejectedRecordCount) => (rejectedDS, dataset, rejectedRecordCount);
        }
      case None =>
        val withInputFileNameDS =
          dataset.withColumn(CometColumns.cometInputFileNameColumn, input_file_name())

        val validationSchema =
          schema.sparkSchemaWithoutScriptedFieldsWithInputFileName(schemaHandler)

        val validationResult =
          treeRowValidator.validate(
            session,
            mergedMetadata.resolveFormat(),
            mergedMetadata.resolveSeparator(),
            withInputFileNameDS,
            schema.attributes,
            types,
            validationSchema,
            settings.appConfig.privacy.options,
            settings.appConfig.cacheStorageLevel,
            settings.appConfig.sinkReplayToFile,
            mergedMetadata.emptyIsNull.getOrElse(settings.appConfig.emptyIsNull)
          )

        val allRejected = rejectedDS.union(validationResult.errors)
        saveRejected(allRejected, validationResult.rejected)(
          settings,
          storageHandler,
          schemaHandler
        ).flatMap { _ =>
          saveAccepted(validationResult)
        } match {
          case Failure(exception: NullValueFoundException) =>
            (validationResult.errors, validationResult.accepted, exception.nbRecord)
          case Failure(exception) =>
            throw exception
          case Success(rejectedRecordCount) =>
            (validationResult.errors, validationResult.accepted, rejectedRecordCount);
        }
    }
  }
}
