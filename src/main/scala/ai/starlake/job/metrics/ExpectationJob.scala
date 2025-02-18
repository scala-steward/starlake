package ai.starlake.job.metrics

import ai.starlake.config.Settings
import ai.starlake.job.transform.AutoTask
import ai.starlake.schema.handlers.{SchemaHandler, StorageHandler}
import ai.starlake.schema.model._
import ai.starlake.utils._
import org.apache.hadoop.fs.Path

import java.sql.Timestamp
import java.time.Instant
import java.util.regex.Pattern
import scala.util.{Failure, Success, Try}

case class ExpectationReport(
  jobId: String,
  database: Option[String],
  domain: String,
  schema: String,
  timestamp: Timestamp,
  name: String,
  params: String,
  sql: Option[String],
  count: Option[Long],
  exception: Option[String],
  success: Boolean
) {

  def asMap(): Map[String, Any] = {
    Map(
      "jobid"     -> jobId,
      "database"  -> database.getOrElse(""),
      "domain"    -> domain,
      "schema"    -> schema,
      "timestamp" -> timestamp,
      "name"      -> name,
      "params"    -> params,
      "sql"       -> sql.getOrElse(""),
      "count"     -> count.getOrElse(0),
      "exception" -> exception.getOrElse(""),
      "success"   -> success
    )
  }

  override def toString: String = {
    s"""name: $name, params:$params, count:${count.getOrElse(
        0
      )}, success:$success, message: ${exception.getOrElse("")}, sql:$sql""".stripMargin
  }

  def asSelect(engineName: Engine)(implicit settings: Settings): String = {
    import ai.starlake.utils.Formatter._
    timestamp.setNanos(0)
    val template = settings.appConfig.jdbcEngines
      .get(engineName.toString)
      .flatMap(_.tables("expectations").selectSql)
      .getOrElse("""
         |SELECT
         |'{{jobid}}' as JOBID,
         |'{{database}}' as DATABASE,
         |'{{domain}}' as DOMAIN,
         |'{{schema}}' as SCHEMA,
         |TO_TIMESTAMP('{{timestamp}}') as TIMESTAMP,
         |'{{name}}' as NAME,
         |'{{params}}' as PARAMS,
         |'{{sql}}' as SQL,
         |{{count}} as COUNT,
         |'{{exception}}' as EXCEPTION,
         |{{success}} as SUCCESS
         """.stripMargin)
    val selectStatement = template.richFormat(
      Map(
        "jobid"     -> jobId,
        "database"  -> database.getOrElse(""),
        "domain"    -> domain,
        "schema"    -> schema,
        "timestamp" -> timestamp.toString(),
        "name"      -> name,
        "params"    -> params.replaceAll("'", "-").replaceAll("\n", " "),
        "sql"       -> sql.getOrElse("").replaceAll("'", "-").replaceAll("\n", " "),
        "count"     -> count.getOrElse(0L).toString,
        "exception" -> exception.getOrElse("").replaceAll("'", "-").replaceAll("\n", " "),
        "success"   -> success.toString
      ),
      Map.empty
    )
    selectStatement
  }
}

object ExpectationReport {
  val starlakeSchema = Schema(
    name = "expectations",
    pattern = Pattern.compile("ignore"),
    attributes = List(
      Attribute("jobid", "string"),
      Attribute("database", "string"),
      Attribute("domain", "string"),
      Attribute("schema", "string"),
      Attribute("timestamp", "timestamp"),
      Attribute("query", "string"),
      Attribute("expect", "string"),
      Attribute("sql", "string"),
      Attribute("count", "long"),
      Attribute("exception", "string"),
      Attribute("success", "boolean")
    ),
    None,
    None
  )
}

/** Record expectation execution
  */

/** @param domain
  *   : Domain name
  * @param schema
  *   : Schema
  * @param stage
  *   : stage
  * @param storageHandler
  *   : Storage Handler
  */
class ExpectationJob(
  appId: Option[String],
  database: Option[String],
  domainName: String,
  schemaName: String,
  expectations: List[ExpectationItem],
  storageHandler: StorageHandler,
  schemaHandler: SchemaHandler,
  sqlRunner: ExpectationAssertionHandler
)(implicit val settings: Settings)
    extends SparkJob {

  private var _failOnError: Boolean = false

  def failOnError(): Boolean = _failOnError

  override def name: String = "Check Expectations"

  override def applicationId(): String = this.appId.getOrElse(this.applicationId())

  def lockPath(path: String): Path = {
    new Path(
      settings.appConfig.lock.path,
      "expectations" + path
        .replace("{{domain}}", domainName)
        .replace("{{schema}}", schemaName)
        .replace(":", "_")
        .replace('/', '_') + ".lock"
    )
  }

  override def run(): Try[JobResult] = {
    val fullTableName = database match {
      case Some(db) => s"$db.$domainName.$schemaName"
      case None     => s"$domainName.$schemaName"
    }
    val bqSlThisCTE = s"WITH SL_THIS AS (SELECT * FROM $fullTableName)\n"

    val macros = schemaHandler.jinjavaMacros
    val expectationReports = expectations.map { expectation =>
      val expectationWithMacroDefinitions = List(macros, expectation.queryCall()).mkString("\n")
      val sql = bqSlThisCTE +
        Utils.parseJinja(
          expectationWithMacroDefinitions,
          schemaHandler.activeEnvVars()
        )
      logger.info(
        s"Applying expectation: ${expectation.expect} with request $sql"
      )
      Try {
        val expectationResult = sqlRunner.handle(sql)
        val success = expectationResult == 0
        if (!success) _failOnError = true
        ExpectationReport(
          applicationId(),
          database,
          domainName,
          schemaName,
          Timestamp.from(Instant.now()),
          "",
          expectation.expect,
          Some(sql),
          Some(expectationResult),
          None,
          success = success
        )
      } match {
        case Failure(e: IllegalArgumentException) =>
          if (expectation.failOnError)
            _failOnError = true
          e.printStackTrace()
          ExpectationReport(
            applicationId(),
            database,
            domainName,
            schemaName,
            Timestamp.from(Instant.now()),
            "",
            expectation.expect,
            None,
            None,
            Some(Utils.exceptionAsString(e)),
            success = false
          )
        case Failure(e) =>
          if (expectation.failOnError)
            _failOnError = true
          e.printStackTrace()
          ExpectationReport(
            applicationId(),
            database,
            domainName,
            schemaName,
            Timestamp.from(Instant.now()),
            "",
            expectation.expect,
            Some(sql),
            None,
            Some(Utils.exceptionAsString(e)),
            success = false
          )
          throw new Exception(e)
        case Success(value) => value
      }
    }
    val result =
      if (expectationReports.nonEmpty) {
        expectationReports.foreach(r => logger.info(r.toString))
        val auditSink = settings.appConfig.audit.getSink()
        auditSink.getConnectionType() match {
          case ConnectionType.GCPLOG =>
            val logName = settings.appConfig.audit.getDomainExpectation()
            expectationReports.foreach { log =>
              GcpUtils.sinkToGcpCloudLogging(log.asMap(), "expectation", logName)
            }
            Success(new JobResult {})
          case _ =>
            val sqls = expectationReports
              .map(
                _.asSelect(
                  settings.appConfig.audit.sink.getSink().getConnection().getJdbcEngineName()
                )
              )
              .mkString("", " UNION ", "")
            val taskDesc = AutoTaskDesc(
              name = applicationId(),
              sql = Some(sqls),
              database = settings.appConfig.audit.getDatabase(),
              domain = settings.appConfig.audit.getDomain(),
              table = "expectations",
              presql = Nil,
              postsql = Nil,
              connectionRef = settings.appConfig.audit.sink.connectionRef,
              sink = Some(settings.appConfig.audit.sink),
              parseSQL = Some(true),
              _auditTableName = Some("expectations")
            )
            val engine =
              taskDesc.getSinkConnection().isJdbcUrl() match {
                case true =>
                  // This handle the case when sparkFormat is true,
                  // we do not want to use spark to write the logs
                  Engine.JDBC
                case false => taskDesc.getSinkConnection().getEngine()
              }
            val task = AutoTask
              .task(
                Option(applicationId()),
                taskDesc,
                Map.empty,
                None,
                truncate = false,
                engine = engine,
                logExecution = false,
                test = false
              )(settings, storageHandler, schemaHandler)
            val res = task.run()
            Utils.logFailure(res, logger)
        }
      } else
        Success(SparkJobResult(None, None))
    val failedCount = expectationReports.count(!_.success)
    if (settings.appConfig.expectations.failOnError && this.failOnError()) {
      Failure(new Exception(s"$failedCount Expectations failed"))
    } else {
      result
    }
  }

  def buildStatementsList(): Try[List[ExpectationItem]] = Try {
    val fullTableName = database match {
      case Some(db) => s"$db.$domainName.$schemaName"
      case None     => s"$domainName.$schemaName"
    }
    val bqSlThisCTE = s"WITH SL_THIS AS (SELECT * FROM $fullTableName)\n"

    val macros = schemaHandler.jinjavaMacros
    val sqls = expectations.map { expectation =>
      val expectationWithMacroDefinitions = List(macros, expectation.queryCall()).mkString("\n")
      val sql = bqSlThisCTE +
        Utils.parseJinja(
          expectationWithMacroDefinitions,
          schemaHandler.activeEnvVars()
        )
      logger.info(
        s"Applying expectation: ${expectation.expect} with request $sql"
      )
      expectation.copy(expect = sql)
    }
    sqls
  }
}
