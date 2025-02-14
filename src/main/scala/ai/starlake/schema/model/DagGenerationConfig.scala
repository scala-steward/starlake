package ai.starlake.schema.model

import ai.starlake.config.Settings
import ai.starlake.job.transform.TaskSQLStatements
import ai.starlake.lineage.TaskViewDependencyNode
import ai.starlake.schema.generator.Yml2DagTemplateLoader
import ai.starlake.utils.Formatter.RichFormatter
import ai.starlake.utils.JsonSerializer

import java.util
import java.util.Calendar
import scala.jdk.CollectionConverters._
import scala.util.Try

case class DagDesc(version: Int, dag: DagGenerationConfig)

case class DagSchedule(schedule: String, cron: String, domains: java.util.List[DagDomain]) {
  def getSchedule(): String = schedule

  def getCron(): String = cron

  def getDomains(): java.util.List[DagDomain] = domains
}

case class DagDomain(name: String, finalName: String, tables: java.util.List[TableDomain]) {
  def getName(): String = name

  def getFinalName(): String = finalName

  def getTables(): java.util.List[TableDomain] = tables
}

case class TableDomain(name: String, finalName: String) {
  def getName(): String = name

  def getFinalName(): String = finalName
}

case class DagPair(name: String, value: String) {
  def getName(): String = name
  def getValue(): String = value
}

case class DagGenerationConfig(
  comment: String,
  template: String,
  filename: String,
  options: Map[String, String]
) {
  def checkValidity()(implicit settings: Settings): List[ValidationMessage] = {
    var errors = List.empty[ValidationMessage]
    if (template.isEmpty) {
      errors = errors :+ ValidationMessage(
        Severity.Error,
        "template",
        "Template is required"
      )
    }
    Try(new Yml2DagTemplateLoader().loadTemplate(template)).getOrElse {
      errors = errors :+ ValidationMessage(
        Severity.Error,
        "template",
        s"DAG Template '$template' not found"
      )
    }
    if (filename.isEmpty) {
      errors = errors :+ ValidationMessage(
        Severity.Error,
        "filename",
        "Filename is required"
      )
    }
    if (template.contains("..")) {
      errors = errors :+ ValidationMessage(
        Severity.Error,
        "template",
        "Template cannot contain '..'"
      )
    }
    if (template.startsWith("/") && !template.startsWith(settings.appConfig.root)) {
      errors = errors :+ ValidationMessage(
        Severity.Error,
        "template",
        "Template cannot start with '/'"
      )
    }
    if (filename.contains("..")) {
      errors = errors :+ ValidationMessage(
        Severity.Error,
        "filename",
        "Filename cannot contain '..'"
      )
    }
    if (filename.startsWith("/") && !filename.startsWith(settings.appConfig.root)) {
      errors = errors :+ ValidationMessage(
        Severity.Error,
        "filename",
        "Filename cannot start with '/'"
      )
    }
    options.foreach { case (k, v) =>
      if (k.isEmpty) {
        errors = errors :+ ValidationMessage(
          Severity.Error,
          "options",
          "Option key is required"
        )
      }
      if (k.contains("..")) {
        errors = errors :+ ValidationMessage(
          Severity.Error,
          "options",
          "Option key cannot contain '..'"
        )
      }
      if (k.startsWith("/") && !k.startsWith(settings.appConfig.root)) {
        errors = errors :+ ValidationMessage(
          Severity.Error,
          "options",
          "Option key cannot start with '/'"
        )
      }
      if (v.isEmpty) {
        errors = errors :+ ValidationMessage(
          Severity.Error,
          "options",
          "Option value is required"
        )
      }
      if (v.contains("..")) {
        errors = errors :+ ValidationMessage(
          Severity.Error,
          "options",
          "Option value cannot contain '..'"
        )
      }
      if (v.startsWith("/") && !v.startsWith(settings.appConfig.root)) {
        errors = errors :+ ValidationMessage(
          Severity.Error,
          "options",
          "Option value cannot start with '/'"
        )
      }
    }
    errors
  }

  def getfilenameVars()(implicit settings: Settings): Set[String] = filename.extractVars()

  def this() = this("", "", "", Map.empty)

  def this(template: String, filename: String, options: Map[String, String]) =
    this("", template, filename, options)

  def asMap: util.HashMap[String, Object] = {
    val jOptions = new java.util.ArrayList[DagPair]
    options.foreach { case (k, v) =>
      jOptions.add(DagPair(k, v))
    }
    new java.util.HashMap[String, Object]() {
      put("comment", comment)
      put("template", template)
      put("options", jOptions)
    }
  }
}

case class LoadDagGenerationContext(
  config: DagGenerationConfig,
  schedules: List[DagSchedule]
) {
  def asMap: util.HashMap[String, Object] = {
    val updatedOptions = if (!config.options.contains("SL_TIMEZONE")) {
      val cal1 = Calendar.getInstance
      val tz = cal1.getTimeZone();
      config.options + ("SL_TIMEZONE" -> tz.getID)
    } else {
      config.options
    }
    val updatedConfig = config.copy(options = updatedOptions)
    new java.util.HashMap[String, Object]() {
      put("config", updatedConfig.asMap)
      put("schedules", schedules.asJava)
    }
  }
}
case class TransformDagGenerationContext(
  config: DagGenerationConfig,
  deps: List[TaskViewDependencyNode],
  cron: Option[String],
  sqls: List[TaskSQLStatements]
) {
  def asMap: util.HashMap[String, Object] = {
    val sqlsAsMap = sqls.map { taskSQLStatements =>
      taskSQLStatements.name -> taskSQLStatements.asMap
    }.toMap

    val updatedOptions = if (!config.options.contains("SL_TIMEZONE")) {
      val cal1 = Calendar.getInstance
      val tz = cal1.getTimeZone();
      config.options + ("SL_TIMEZONE" -> tz.getID)
    } else {
      config.options
    }
    val updatedConfig = config.copy(options = updatedOptions)
    val depsAsJsonString =
      JsonSerializer.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(deps)
    val sqlsAsJsonString =
      JsonSerializer.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sqlsAsMap)

    new java.util.HashMap[String, Object]() {
      put("config", updatedConfig.asMap)
      put("cron", cron.getOrElse("None"))
      put("dependencies", depsAsJsonString)
      put("statements", sqlsAsJsonString)
    }
  }
}
