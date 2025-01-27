package ai.starlake.schema.generator

import ai.starlake.config.{DatasetArea, Settings}
import ai.starlake.lineage.{AutoTaskDependencies, AutoTaskDependenciesConfig}
import ai.starlake.schema.handlers.SchemaHandler
import ai.starlake.schema.model.{Severity, _}
import ai.starlake.utils.Utils
import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.fs.Path

import java.nio.charset.StandardCharsets
import java.util
import scala.jdk.CollectionConverters._
import scala.util.Try

class DagGenerateJob(schemaHandler: SchemaHandler) extends LazyLogging {

  def run(args: Array[String])(implicit settings: Settings): Try[Unit] =
    DagGenerateCmd.run(args.toIndexedSeq, schemaHandler).map(_ => ())

  case class TableWithDagConfig(
    domain: Domain,
    table: Schema,
    dagConfigName: String,
    dagConfig: DagGenerationConfig,
    schedule: Option[String]
  )

  private def tableWithDagConfigs(
    dagConfigs: Map[String, DagGenerationConfig],
    tags: Set[String]
  )(implicit settings: Settings): List[TableWithDagConfig] = {
    logger.info("Starting to generate dags")
    val tableWithDagConfigAndSchedule = schemaHandler.domains().flatMap { domain =>
      domain.tables.filter(tags.isEmpty || _.tags.intersect(tags).nonEmpty).flatMap { table =>
        val mergedMetadata = table.mergedMetadata(domain.metadata)
        val dagConfigRef = mergedMetadata.dagRef
          .orElse(settings.appConfig.dagRef.flatMap(_.load))
        val schedule = mergedMetadata.schedule

        dagConfigRef.map { dagRef =>
          val dagConfig = dagConfigs.getOrElse(
            dagRef,
            throw new Exception(
              s"Could not find dag config $dagRef referenced in ${domain.name}.${table.name}. Dag configs found ${dagConfigs.keys
                  .mkString(",")}"
            )
          )
          TableWithDagConfig(domain, table, dagRef, dagConfig, schedule)
        }
      }
    }
    tableWithDagConfigAndSchedule
  }

  case class TaskWithDagConfig(
    taskDesc: AutoTaskDesc,
    dagConfigName: String,
    dagConfig: DagGenerationConfig,
    schedule: Option[String]
  )

  private def taskWithDagConfigs(
    dagConfigs: Map[String, DagGenerationConfig],
    tags: Set[String]
  )(implicit settings: Settings): List[TaskWithDagConfig] = {
    logger.info("Starting to task generate dags")
    val tasks = schemaHandler.tasks()
    val taskWithDagConfigAndSchedule: List[TaskWithDagConfig] =
      tasks.filter(tags.isEmpty || _.tags.intersect(tags).nonEmpty).flatMap { taskWithDag =>
        val dagConfigRef = taskWithDag.dagRef
          .orElse(settings.appConfig.dagRef.flatMap(_.transform))
        val schedule = taskWithDag.schedule
        dagConfigRef.map { dagRef =>
          val dagConfig = dagConfigs.getOrElse(
            dagRef,
            throw new Exception(
              s"Could not find dag config $dagRef referenced in ${taskWithDag.name}. Dag config found ${dagConfigs.keys
                  .mkString(",")}"
            )
          )
          TaskWithDagConfig(taskWithDag, dagRef, dagConfig, schedule)
        }
      }
    taskWithDagConfigAndSchedule
  }

  def getScheduleName(schedule: String, currentScheduleIndex: Int): (String, Int) = {
    if (schedule.contains(" ")) {
      ("cron" + currentScheduleIndex.toString, currentScheduleIndex + 1)
    } else {
      (schedule, currentScheduleIndex)
    }
  }

  def generateTaskDags(
    config: DagGenerateConfig
  )(implicit settings: Settings): Unit = {
    val outputDir = new Path(
      config.outputDir.getOrElse(DatasetArea.dags.toString + "/generated/")
    )

    val dagConfigs = schemaHandler.loadDagGenerationConfigs()
    val taskConfigs = taskWithDagConfigs(dagConfigs, config.tags.toSet)
    val env = schemaHandler.activeEnvVars(root = Option(settings.appConfig.root))
    val jEnv = env.map { case (k, v) =>
      DagPair(k, v)
    }.toList
    val depsEngine = new AutoTaskDependencies(settings, schemaHandler, settings.storageHandler())
    val taskConfigsGroupedByFilename = taskConfigs
      .map { taskConfig =>
        val envVars = schemaHandler.activeEnvVars(root = Option(settings.appConfig.root)) ++ Map(
          "table"    -> taskConfig.taskDesc.table,
          "domain"   -> taskConfig.taskDesc.domain,
          "name"     -> taskConfig.taskDesc.name, // deprecated
          "task"     -> taskConfig.taskDesc.name,
          "schedule" -> taskConfig.schedule.getOrElse("None")
        )
        val filename = Utils.parseJinja(
          taskConfig.dagConfig.filename,
          envVars
        )
        val comment = Utils.parseJinja(
          taskConfig.dagConfig.comment,
          envVars
        )
        val options =
          taskConfig.dagConfig.options.map { case (k, v) => k -> Utils.parseJinja(v, envVars) }
        (
          filename,
          taskConfig
            .copy(dagConfig = taskConfig.dagConfig.copy(options = options, comment = comment))
        )
      }
      .groupBy { case (filename, _) => filename }
    taskConfigsGroupedByFilename
      .foreach { case (filename, taskConfigs) =>
        val headConfig = taskConfigs.head match { case (_, config) => config }
        val dagConfig = headConfig.dagConfig
        val dagTemplateName = dagConfig.template
        val dagTemplateContent = new Yml2DagTemplateLoader().loadTemplate(dagTemplateName)
        val cron = settings.appConfig.schedulePresets.getOrElse(
          headConfig.schedule.getOrElse("None"),
          headConfig.schedule.getOrElse("None")
        )
        val cronIfNone = if (cron == "None") null else cron
        val configs = taskConfigs.map { case (_, config) => config.taskDesc.name }
        val autoTaskDepsConfig = AutoTaskDependenciesConfig(tasks = Some(configs))
        val deps = depsEngine.jobsDependencyTree(autoTaskDepsConfig)
        val context = TransformDagGenerationContext(
          config = dagConfig,
          deps = deps,
          cron = Option(cronIfNone)
        )
        applyJ2AndSave(
          outputDir,
          jEnv,
          dagTemplateContent,
          optionsWithProjectIdAndName(config, context.asMap),
          filename
        )
      }
  }

  private def airflowAccessControl(projectId: Long): String = {
    assert(projectId > 0)

    // To reduce memory footprint in the SaaS version, we access global variables
    // from the application configuration object instead of the user session
    // configuration object.
    val viewerConfig = Settings.referenceConfig.getString("dagAccess.airflow.viewer")
    val userConfig = Settings.referenceConfig.getString("dagAccess.airflow.user")
    val opsConfig = Settings.referenceConfig.getString("dagAccess.airflow.ops")
    val result =
      s"""
         |{
         |"SL_${projectId}_VIEWER":
         |    $viewerConfig,
         |"SL_${projectId}_USER":
         |    $userConfig,
         |"SL_${projectId}_OPS":
         |    $opsConfig
         |}
         |""".stripMargin
    result
  }
  private def optionsWithProjectIdAndName(
    config: DagGenerateConfig,
    options: util.HashMap[String, Object]
  ): util.HashMap[String, Object] = {
    config.masterProjectId match {
      case Some(masterProjectId) =>
        options.put("sl_project_id", masterProjectId)
        options.put("sl_project_name", config.masterProjectName.getOrElse("[noname]"))
        options.put("sl_airflow_access_control", airflowAccessControl(masterProjectId.toLong))
      case None =>
        options.put("sl_project_id", "-1")
        options.put("sl_project_name", "[noname]")
        options.put("sl_airflow_access_control", "None")
    }
    options
  }

  def clean(dir: Option[String])(implicit settings: Settings): Unit = {
    val outputDir = new Path(
      dir.getOrElse(DatasetArea.dags.toString + "/generated/")
    )
    settings.storageHandler().mkdirs(outputDir)
    logger.info(s"Cleaning output directory $outputDir")
    settings.storageHandler().delete(outputDir)
  }
  def generateDomainDags(
    config: DagGenerateConfig
  )(implicit settings: Settings): Unit = {
    val outputDir = new Path(
      config.outputDir.getOrElse(DatasetArea.dags.toString + "/generated/")
    )

    val dagConfigs = schemaHandler.loadDagGenerationConfigs()
    val tableConfigs = tableWithDagConfigs(dagConfigs, config.tags.toSet)
    val groupedDags = groupByDagConfigScheduleDomain(tableConfigs)

    val env = schemaHandler.activeEnvVars(root = Some(settings.appConfig.root))
    val jEnv = env.map { case (k, v) =>
      DagPair(k, v)
    }.toList

    groupedDags.foreach { case (dagConfigName, groupedBySchedule) =>
      val dagConfig = dagConfigs(dagConfigName)
      val errors = dagConfig.checkValidity().filter(_.severity == Severity.Error)
      errors.foreach { error =>
        logger.error(error.toString())
      }
      if (errors.nonEmpty) {
        throw new Exception(s"Dag config ${dagConfigName} is invalid: ${errors.mkString(",")}")
      }
      val dagTemplateName = dagConfig.template
      val dagTemplateContent = new Yml2DagTemplateLoader().loadTemplate(dagTemplateName)
      val filenameVars = dagConfig.getfilenameVars()
      if (filenameVars.exists(List("table", "finalTable", "renamedTable").contains)) {
        if (!filenameVars.exists(List("domain", "finalDomain", "renamedDomain").contains))
          logger.warn(
            s"Dag Config $dagConfigName: filename contains table but not domain, this will generate multiple dags with the same name if the same table name appear in multiple domains"
          )
        // one dag per table
        var scheduleIndex = 1
        groupedBySchedule.foreach { case (schedule, groupedByDomain) =>
          groupedByDomain.foreach { case (domain, tables) =>
            tables.foreach { table =>
              val dagDomain = DagDomain(
                domain.name,
                domain.finalName,
                java.util.List.of[TableDomain](TableDomain(table.name, table.finalName))
              )
              val (scheduleValue, nextScheduleIndex) = getScheduleName(schedule, scheduleIndex)
              val cron = settings.appConfig.schedulePresets.getOrElse(
                schedule,
                scheduleValue
              )
              val cronIfNone = if (cron == "None") null else cron
              val schedules =
                List(DagSchedule(schedule, cronIfNone, java.util.List.of[DagDomain](dagDomain)))

              val envVars =
                schemaHandler.activeEnvVars(root = Option(settings.appConfig.root)) ++ Map(
                  "schedule"      -> scheduleValue,
                  "domain"        -> domain.name,
                  "finalDomain"   -> domain.finalName,
                  "renamedDomain" -> domain.finalName,
                  "table"         -> table.name,
                  "finalTable"    -> table.finalName,
                  "renamedTable"  -> table.finalName
                )
              val options = dagConfig.options.map { case (k, v) =>
                k -> Utils.parseJinja(v, envVars)
              }
              val comment = Utils.parseJinja(dagConfig.comment, envVars)
              val context = LoadDagGenerationContext(
                config = dagConfig.copy(options = options, comment = comment),
                schedules
              )

              scheduleIndex = nextScheduleIndex
              val filename = Utils.parseJinja(dagConfig.filename, envVars)
              applyJ2AndSave(
                outputDir,
                jEnv,
                dagTemplateContent,
                optionsWithProjectIdAndName(config, context.asMap),
                filename
              )
            }
          }
        }
      } else if (filenameVars.exists(List("domain", "finalDomain", "renamedDomain").contains)) {
        // one dag per domain
        val domains = groupedBySchedule
          .flatMap { case (schedule, groupedByDomain) =>
            groupedByDomain.keys
          }
          .toList
          .sortBy(_.name)
        domains.foreach { domain =>
          val schedules = groupedBySchedule
            .flatMap { case (schedule, groupedByDomain) =>
              val tables = groupedByDomain.get(domain)
              tables.map { table =>
                val dagDomain = DagDomain(
                  domain.name,
                  domain.finalName,
                  table.map(t => TableDomain(t.name, t.finalName)).asJava
                )
                val cron = settings.appConfig.schedulePresets.getOrElse(
                  schedule,
                  schedule
                )
                val cronIfNone = if (cron == "None") null else cron
                DagSchedule(schedule, cronIfNone, java.util.List.of[DagDomain](dagDomain))
              }
            }
            .toList
            .sortBy(_.schedule)
          if (filenameVars.contains("schedule")) {
            var scheduleIndex = 1
            schedules.foreach { schedule =>
              val (scheduleValue, nextScheduleIndex) =
                getScheduleName(schedule.schedule, scheduleIndex)
              scheduleIndex = nextScheduleIndex
              val envVars =
                schemaHandler.activeEnvVars(root = Option(settings.appConfig.root)) ++ Map(
                  "schedule"      -> scheduleValue,
                  "domain"        -> domain.name,
                  "renamedDomain" -> domain.finalName,
                  "finalDomain"   -> domain.finalName
                )
              val options = dagConfig.options.map { case (k, v) =>
                k -> Utils.parseJinja(v, envVars)
              }
              val comment = Utils.parseJinja(dagConfig.comment, envVars)
              val context = LoadDagGenerationContext(
                config = dagConfig.copy(options = options, comment = comment),
                schedules
              )
              val filename = Utils.parseJinja(dagConfig.filename, envVars)
              applyJ2AndSave(
                outputDir,
                jEnv,
                dagTemplateContent,
                optionsWithProjectIdAndName(config, context.asMap),
                filename
              )
            }
          } else {
            val envVars =
              schemaHandler.activeEnvVars(root = Option(settings.appConfig.root)) ++ Map(
                "domain"        -> domain.name,
                "renamedDomain" -> domain.finalName,
                "finalDomain"   -> domain.finalName
              )
            val options = dagConfig.options.map { case (k, v) => k -> Utils.parseJinja(v, envVars) }
            val comment = Utils.parseJinja(dagConfig.comment, envVars)
            val context = LoadDagGenerationContext(
              config = dagConfig.copy(options = options, comment = comment),
              schedules
            )
            val filename = Utils.parseJinja(dagConfig.filename, envVars)
            applyJ2AndSave(
              outputDir,
              jEnv,
              dagTemplateContent,
              optionsWithProjectIdAndName(config, context.asMap),
              filename
            )
          }
        }
      } else {
        // one dag per config
        groupedDags.foreach { case (dagConfigName, groupedBySchedule) =>
          var scheduleIndex = 1
          val dagConfig = dagConfigs(dagConfigName)
          val dagTemplateName = dagConfig.template
          val dagTemplateContent = new Yml2DagTemplateLoader().loadTemplate(dagTemplateName)
          val dagSchedules = groupedBySchedule
            .map { case (schedule, groupedByDomain) =>
              val (scheduleValue, nextScheduleIndex) =
                getScheduleName(schedule, scheduleIndex)
              scheduleIndex = nextScheduleIndex
              val dagDomains = groupedByDomain
                .map { case (domain, tables) =>
                  DagDomain(
                    domain.name,
                    domain.finalName,
                    tables.map(t => TableDomain(t.name, t.finalName)).asJava
                  )
                }
                .toList
                .sortBy(_.name)
              val cron = settings.appConfig.schedulePresets.getOrElse(
                schedule,
                scheduleValue
              )
              val cronIfNone = if (cron == "None") null else cron
              DagSchedule(scheduleValue, cronIfNone, dagDomains.asJava)
            }
            .toList
            .sortBy(_.schedule)
          if (filenameVars.contains("schedule")) {
            dagSchedules.foreach { schedule =>
              val envVars =
                schemaHandler.activeEnvVars(root = Option(settings.appConfig.root)) ++ Map(
                  "schedule" -> schedule.schedule
                )
              val options = dagConfig.options.map { case (k, v) =>
                k -> Utils.parseJinja(v, envVars)
              }
              val comment = Utils.parseJinja(dagConfig.comment, envVars)
              val context = LoadDagGenerationContext(
                config = dagConfig.copy(options = options, comment = comment),
                List(schedule)
              )
              val filename = Utils.parseJinja(dagConfig.filename, envVars)
              applyJ2AndSave(
                outputDir,
                jEnv,
                dagTemplateContent,
                optionsWithProjectIdAndName(config, context.asMap),
                filename
              )
            }
          } else {
            val envVars = schemaHandler.activeEnvVars(root = Option(settings.appConfig.root))
            val options = dagConfig.options.map { case (k, v) => k -> Utils.parseJinja(v, envVars) }
            val comment = Utils.parseJinja(dagConfig.comment, envVars)
            val context = LoadDagGenerationContext(
              config = dagConfig.copy(options = options, comment = comment),
              schedules = dagSchedules
            )
            val filename = Utils.parseJinja(dagConfig.filename, envVars)
            applyJ2AndSave(
              outputDir,
              jEnv,
              dagTemplateContent,
              optionsWithProjectIdAndName(config, context.asMap),
              filename
            )
          }

        }
      }
    }
  }

  private def applyJ2AndSave(
    outputDir: Path,
    jEnv: List[DagPair],
    dagTemplateContent: String,
    context: util.HashMap[String, Object],
    filename: String
  )(implicit settings: Settings): Unit = {
    val paramMap = Map(
      "context" -> context,
      "env"     -> jEnv.asJava
    )
    val jinjaOutput = Utils.parseJinjaTpl(dagTemplateContent, paramMap)
    val dagPath = new Path(outputDir, filename)
    logger.info(s"Writing dag to $dagPath")
    settings.storageHandler().write(jinjaOutput, dagPath)(StandardCharsets.UTF_8)
  }

  /** group tables by dag config name, schedule and domain
    *
    * @return
    *   Map[dagConfigName, Map[schedule, Map[domainName, List[tableName]]]]
    */
  private def groupByDagConfigScheduleDomain(
    tableWithDagConfigs: List[TableWithDagConfig]
  ): Map[String, Map[String, Map[Domain, List[Schema]]]] = {
    val groupByDagConfigName = tableWithDagConfigs.groupBy(_.dagConfigName)
    val groupDagConfigNameAndSchedule = groupByDagConfigName.map {
      case (dagConfigName, tableWithDagConfigs) =>
        val groupedBySchedule = tableWithDagConfigs.groupBy(_.schedule.getOrElse("None"))
        val groupedByScheduleAndDomain = groupedBySchedule.map {
          case (scheduleName, tableWithDagConfigs) =>
            val groupedByDomain = tableWithDagConfigs.groupBy(_.domain)
            val groupedTableNames = groupedByDomain.map { case (domain, tableWithConfig) =>
              domain -> tableWithConfig.map(_.table).sortBy(_.name)
            }
            scheduleName -> groupedTableNames
        }
        (dagConfigName, groupedByScheduleAndDomain)
    }
    groupDagConfigNameAndSchedule
  }

  def normalizeDagNames(config: DagGenerateConfig)(implicit settings: Settings): List[Path] = {
    config.masterProjectId match {
      case Some(projectId) =>
        val outputDir = new Path(
          config.outputDir.getOrElse(throw new Exception("outputDir is required"))
        )
        val dagFiles =
          settings
            .storageHandler()
            .list(
              path = outputDir,
              extension = ".py",
              exclude = Some("_.*".r.pattern),
              recursive = false
            )
        dagFiles.map { file =>
          val fileName = file.path.getName
          val newFileName = s"SL_${projectId}_$fileName"
          val newPath =
            new Path(
              file.path.getParent,
              newFileName.toLowerCase()
            ) // should be lowercase like dag ids
          settings.storageHandler().move(file.path, newPath)
          newPath
        }
      case None =>
        Nil

    }
  }
}

object DagGenerateJob {
  val SCHEDULE = "schedule"
  val name = "generate"
}
