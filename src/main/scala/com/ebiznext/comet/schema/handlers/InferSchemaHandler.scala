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

import java.io.StringWriter
import java.util.regex.Pattern

import com.ebiznext.comet.config.Settings
import com.ebiznext.comet.job.Main
import com.ebiznext.comet.schema.model._
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.types.{ArrayType, StructType}

object InferSchemaHandler {

  /** *
    * Traverses the schema and returns a list of attributes.
    *
    * @param schema Schema so that we find all Attributes
    * @return List of Attributes
    */
  def createAttributes(
    schema: StructType
  )(implicit /* TODO: make me explicit */ settings: Settings): List[Attribute] = {
    schema
      .map(
        row =>
          row.dataType.typeName match {

            // if the datatype is a struct {...} containing one or more other field
            case "struct" =>
              Attribute(
                row.name,
                row.dataType.typeName,
                Some(false),
                !row.nullable,
                attributes = Some(createAttributes(row.dataType.asInstanceOf[StructType])),
                settings = settings
              )

            case "array" =>
              val elemType = row.dataType.asInstanceOf[ArrayType].elementType
              if (elemType.typeName.equals("struct"))
                // if the array contains elements of type struct.
                // {people: [{name:Person1, age:22},{name:Person2, age:25}]}
                Attribute(
                  row.name,
                  elemType.typeName,
                  Some(true),
                  !row.nullable,
                  attributes = Some(createAttributes(elemType.asInstanceOf[StructType])),
                  settings = settings
                )
              else
                // if it is a regular array. {ages: [21, 25]}
                Attribute(
                  row.name,
                  elemType.typeName,
                  Some(true),
                  !row.nullable,
                  settings = settings
                )

            // if the datatype is a simple Attribute
            case _ =>
              Attribute(
                row.name,
                row.dataType.typeName,
                Some(false),
                !row.nullable,
                settings = settings
              )
        }
      )
      .toList
  }

  /** *
    * builds the Metadata case class. check case class metadata for attribute definition
    *
    * @param format     : DSV by default
    * @param array      : Is a json stored as a single object array ? false by default
    * @param withHeader : does the dataset has a header ? true bu default
    * @param separator  : the column separator,  ';' by default
    * @return
    */

  def createMetaData(
    format: Option[String] = None,
    array: Option[Boolean],
    withHeader: Option[Boolean],
    separator: Option[String]
  ): Metadata = {
    Metadata(
      Some(Mode.fromString("FILE")),
      Some(Format.fromString(format.getOrElse("DSV"))),
      None,
      multiline = None,
      array,
      withHeader,
      separator
    )
  }

  /** *
    * builds the Schema case class
    *
    * @param name       : Schema name, must be unique in the domain. Will become the hive table name
    * @param pattern    : filename pattern to which this schema must be applied
    * @param attributes : datasets columns
    * @param metadata   : Dataset metadata
    * @return
    */

  def createSchema(
    name: String,
    pattern: Pattern,
    attributes: List[Attribute],
    metadata: Option[Metadata]
  ): Schema = {

    Schema(name, pattern, attributes, metadata, None, None, None, None)
  }

  /** *
    * Builds the Domain case class
    *
    * @param name      : Domain name
    * @param directory : Folder on the local filesystem where incomping files are stored.
    *                  This folder will be scanned regurlaly to move the dataset to the cluster
    * @param metadata  : Default Schema meta data.
    * @param schemas   : List of schema for each dataset in this domain
    * @return
    */

  def createDomain(
    name: String,
    directory: String,
    metadata: Option[Metadata] = None,
    schemas: List[Schema] = Nil
  ): Domain = {

    Domain(name, directory, metadata, schemas)
  }

  /** *
    * Generates the YAML file using the domain object and a savepath
    *
    * @param domain   Domain case class
    * @param savePath path to save files.
    */
  def generateYaml(domain: Domain, savePath: String)(
    implicit /* TODO: make me explicit */ settings: Settings
  ): Unit = {
    val obj = Settings.storageHandler.read(new Path(savePath))
    val objw = new StringWriter()
    objw.write(obj)
    Main.mapper.writeValue(objw, domain)
  }

}
