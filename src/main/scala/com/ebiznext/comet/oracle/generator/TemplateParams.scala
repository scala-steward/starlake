package com.ebiznext.comet.oracle.generator

import java.time.format.DateTimeFormatter

import better.files.File
import com.ebiznext.comet.schema.model.{Domain, Schema, WriteMode}

/**
  * Params for the script's mustache template
  * @param tableToExport table to export
  * @param columnsToExport cols to export
  * @param isDelta if table is going to be fully or delta exported
  * @param deltaColumn if delta exported, which is the col holding the date of last update
  * @param dsvDelimiter export result dsv delimiter
  * @param exportOutputFileBase export dsv file base name (will be completed by current datetime when executed)
  * @param scriptOutputFile where the script is produced
  */
case class TemplateParams(
  tableToExport: String,
  columnsToExport: List[String],
  isDelta: Boolean,
  deltaColumn: Option[String],
  dsvDelimiter: String,
  exportOutputFileBase: String,
  scriptOutputFile: File
) {

  val paramMap: Map[String, Any] = {
    val exportType: String = if (isDelta) "DELTA" else "FULL"
    deltaColumn
      .map(_.toUpperCase)
      .foldLeft(
        List(
          "table_name"       -> tableToExport.toUpperCase,
          "delimiter"        -> dsvDelimiter,
          "columns"          -> columnsToExport.map(_.toUpperCase).mkString(", "),
          "output_file_base" -> exportOutputFileBase,
          "is_delta"         -> isDelta,
          "export_type"      -> exportType
        )
      ) { case (list, deltaCol) => list :+ ("delta_column" -> deltaCol.toUpperCase) }
      .toMap
  }
}

object TemplateParams {

  val dateFormater: DateTimeFormatter = DateTimeFormatter.ISO_DATE

  /**
    * Generating all the TemplateParams, corresponding to all the schema's tables of the domain
    * @param domain The domain
    * @param scriptsOutputFolder where the scripts are produced
    * @return
    */
  def fromDomain(
    domain: Domain,
    scriptsOutputFolder: File
  ): List[TemplateParams] =
    domain.schemas.map(fromSchema(_, scriptsOutputFolder))

  /**
    * Generate N Oracle sqlplus scripts template parameters, extracting the tables and the columns described in the schema
    * @param schema The schema used to generate the scripts parameters
    * @return The corresponding TemplateParams
    */
  def fromSchema(
    schema: Schema,
    scriptsOutputFolder: File
  ): TemplateParams = {
    val scriptOutputFileName = s"EXTRACT_${schema.name}.sql"
    // exportFileBase is the csv file name base such as EXPORT_L58MA_CLIENT_DELTA_...
    // Considering a pattern like EXPORT_L58MA_CLIENT_*
    // The script which is generated will append the current date time to that base (EXPORT_L58MA_CLIENT_18032020173100).
    val exportFileBase = s"${schema.pattern.toString.split("\\_\\*").head}"
    val isDelta = schema.metadata.flatMap(_.write).contains(WriteMode.APPEND)
    new TemplateParams(
      tableToExport = schema.name,
      columnsToExport = schema.attributes.map(_.name),
      isDelta = isDelta,
      deltaColumn = if (isDelta) schema.merge.flatMap(_.timestamp) else None,
      dsvDelimiter = schema.metadata.flatMap(_.separator).getOrElse(","),
      exportOutputFileBase = exportFileBase,
      scriptOutputFile = scriptsOutputFolder / scriptOutputFileName
    )
  }
}
