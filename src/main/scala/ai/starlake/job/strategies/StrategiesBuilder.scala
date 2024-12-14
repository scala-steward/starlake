package ai.starlake.job.strategies

import ai.starlake.config.Settings
import ai.starlake.config.Settings.JdbcEngine
import ai.starlake.job.strategies.StrategiesBuilder.TableComponents
import ai.starlake.schema.generator.WriteStrategyTemplateLoader
import ai.starlake.schema.model._
import ai.starlake.sql.SQLUtils
import ai.starlake.utils.Utils
import com.typesafe.scalalogging.StrictLogging

class StrategiesBuilder extends StrictLogging {

  protected def createTemporaryView(viewName: String): String = {
    s"CREATE OR REPLACE TEMPORARY VIEW $viewName"
  }

  protected def createTable(fullTableName: String, sparkSinkFormat: String): String = {
    s"CREATE TABLE $fullTableName"
  }

  protected def tempViewName(name: String) = name

  protected def buildMainSql(
    sqlWithParameters: String,
    strategy: WriteStrategy,
    materializedView: Boolean,
    tableExists: Boolean,
    truncate: Boolean,
    fullTableName: String,
    sinkConfig: Sink
  )(implicit settings: Settings): List[String] = {
    // The last SQL may be a select. This what we are going to
    // transform into a create table as or merge into or update from / insert as
    val scd2StartTimestamp =
      strategy.startTs.getOrElse(throw new IllegalArgumentException("strategy requires startTs"))
    val scd2EndTimestamp =
      strategy.endTs.getOrElse(throw new IllegalArgumentException("strategy requires endTs"))
    val finalSqls =
      if (!tableExists) {
        // Table may not have been created yet
        // If table does not exist we know for sure that the sql request is a SELECT
        if (materializedView)
          List(s"CREATE MATERIALIZED VIEW $fullTableName AS $sqlWithParameters")
        else {
          if (strategy.getEffectiveType() == WriteStrategyType.SCD2) {
            val startTs =
              s"ALTER TABLE $fullTableName ADD COLUMN $scd2StartTimestamp TIMESTAMP"
            val endTs =
              s"ALTER TABLE $fullTableName ADD COLUMN $scd2EndTimestamp TIMESTAMP"
            List(
              s"CREATE TABLE $fullTableName AS ($sqlWithParameters)",
              startTs,
              endTs
            )
          } else
            List(
              s"CREATE TABLE $fullTableName AS ($sqlWithParameters)"
            )
        }
      } else {
        val mainSql = s"INSERT INTO $fullTableName $sqlWithParameters"
        val insertSqls =
          if (strategy.getEffectiveType() == WriteStrategyType.OVERWRITE) {
            // If we are in overwrite mode we need to drop the table/truncate before inserting
            if (materializedView) {
              List(
                s"DROP MATERIALIZED VIEW $fullTableName",
                s"CREATE MATERIALIZED VIEW $fullTableName AS $sqlWithParameters"
              )
            } else {
              List(s"DELETE FROM $fullTableName WHERE TRUE", mainSql)
            }
          } else {
            val dropSqls =
              if (truncate)
                List(s"DELETE FROM $fullTableName WHERE TRUE")
              else
                Nil
            dropSqls :+ mainSql
          }
        insertSqls
      }
    finalSqls
  }

  protected def buildSqlForSC2(
    sourceTable: String,
    targetTableFullName: String,
    targetTableExists: Boolean,
    targetTableColumns: List[String],
    strategy: WriteStrategy,
    truncate: Boolean,
    materializedView: Boolean,
    jdbcEngine: JdbcEngine,
    sinkConfig: Sink
  )(implicit settings: Settings): String = {
    val startTsCol = strategy.startTs.getOrElse(
      throw new Exception("SCD2 is not supported without a start timestamp column")
    )
    val endTsCol = strategy.endTs.getOrElse(
      throw new Exception("SCD2 is not supported without an end timestamp column")
    )
    val mergeTimestampCol = strategy.timestamp
    val mergeOn = strategy.on.getOrElse(MergeOn.SOURCE_AND_TARGET)
    val quote = jdbcEngine.quote
    val targetColumnsAsSelectString =
      SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)

    val incomingColumnsAsSelectString =
      SQLUtils.incomingColumnsForSelectSql(sourceTable, targetTableColumns, quote)

    val paramsForInsertSql = {
      val targetColumns = SQLUtils.targetColumnsForSelectSql(targetTableColumns, quote)
      val sourceColumns =
        SQLUtils.incomingColumnsForSelectSql(sourceTable, targetTableColumns, quote)
      s"""($targetColumns) VALUES ($sourceColumns)"""
    }

    val mergeKeys =
      strategy.key
        .map(key => s"$quote$key$quote")
        .mkString(",")

    (targetTableExists, mergeTimestampCol, mergeOn) match {
      case (false, Some(_), MergeOn.TARGET) =>
        /*
            The table does not exist, we can just insert the data
         */
        buildMainSql(
          s"SELECT $targetColumnsAsSelectString FROM $sourceTable",
          strategy,
          materializedView,
          targetTableExists,
          truncate,
          targetTableFullName,
          sinkConfig
        ).mkString(";\n")

      case (true, Some(mergeTimestampCol), MergeOn.TARGET) =>
        /*
            First we insert all new rows
            Then we create a temporary table with the updated rows
            Then we update the end_ts of the old rows
            Then we insert the new rows
                INSERT INTO $targetTable
                SELECT $allAttributesSQL, $mergeTimestampCol AS $startTsCol, NULL AS $endTsCol FROM $sourceTable AS $SL_INTERNAL_TABLE
                WHERE $key IN (SELECT DISTINCT $key FROM SL_UPDATED_RECORDS);
         */
        val mergeKeyJoinCondition =
          SQLUtils.mergeKeyJoinCondition(sourceTable, targetTableFullName, strategy.key, quote)

        val mergeKeyJoinCondition2 =
          SQLUtils.mergeKeyJoinCondition(
            "SL_UPDATED_RECORDS",
            targetTableFullName,
            strategy.key,
            quote
          )

        val nullJoinCondition =
          strategy.key
            .map(key => s"$targetTableFullName.$quote$key$quote IS NULL")
            .mkString(" AND ")

        val paramsForUpdateSql =
          SQLUtils.setForUpdateSql("SL_UPDATED_RECORDS", targetTableColumns, quote)

        s"""
           |INSERT INTO $targetTableFullName
           |SELECT $incomingColumnsAsSelectString, NULL AS $startTsCol, NULL AS $endTsCol FROM $sourceTable
           |LEFT JOIN $targetTableFullName ON ($mergeKeyJoinCondition AND $targetTableFullName.$endTsCol IS NULL)
           |WHERE $nullJoinCondition;
           |
           |CREATE TEMPORARY TABLE SL_UPDATED_RECORDS AS
           |SELECT $incomingColumnsAsSelectString FROM $sourceTable, $targetTableFullName
           |WHERE $mergeKeyJoinCondition AND $targetTableFullName.$endTsCol IS NULL AND $sourceTable.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol;
           |
           |MERGE INTO $targetTableFullName USING SL_UPDATED_RECORDS ON ($mergeKeyJoinCondition2)
           |WHEN MATCHED THEN UPDATE $paramsForUpdateSql, $startTsCol = $mergeTimestampCol, $endTsCol = NULL
           |WHEN NOT MATCHED THEN INSERT $paramsForInsertSql -- here just to make the SQL valid. Only the WHEN MATCHED is used
           |""".stripMargin

      case (true, Some(mergeTimestampCol), MergeOn.SOURCE_AND_TARGET) =>
        /*
            First we insert all new rows
            Then we create a temporary table with the updated rows
            Then we update the end_ts of the old rows
            Then we insert the new rows
                INSERT INTO $targetTable
                SELECT $allAttributesSQL, $mergeTimestampCol AS $startTsCol, NULL AS $endTsCol FROM $sourceTable AS $SL_INTERNAL_TABLE
                WHERE $key IN (SELECT DISTINCT $key FROM SL_UPDATED_RECORDS);
         */
        val mergeKeyJoinCondition =
          SQLUtils.mergeKeyJoinCondition(sourceTable, targetTableFullName, strategy.key, quote)

        val mergeKeyJoinCondition2 =
          SQLUtils.mergeKeyJoinCondition(
            tempViewName("SL_DEDUP"),
            targetTableFullName,
            strategy.key,
            quote
          )

        val mergeKeyJoinCondition3 =
          SQLUtils.mergeKeyJoinCondition(
            tempViewName("SL_UPDATED_RECORDS"),
            targetTableFullName,
            strategy.key,
            quote
          )

        val nullJoinCondition =
          strategy.key
            .map(key => s"$targetTableFullName.$quote$key$quote IS NULL")
            .mkString(" AND ")

        val paramsForUpdateSql =
          SQLUtils.setForUpdateSql(tempViewName("SL_UPDATED_RECORDS"), targetTableColumns, quote)

        val viewWithRowNumColumnsAsSelectString =
          SQLUtils.incomingColumnsForSelectSql(
            tempViewName("SL_VIEW_WITH_ROWNUM"),
            targetTableColumns,
            quote
          )

        val dedupColumnsAsSelectString =
          SQLUtils.incomingColumnsForSelectSql(tempViewName("SL_DEDUP"), targetTableColumns, quote)

        s"""
           |INSERT INTO $targetTableFullName
           |SELECT $incomingColumnsAsSelectString, NULL AS $startTsCol, NULL AS $endTsCol FROM $sourceTable
           |LEFT JOIN $targetTableFullName ON ($mergeKeyJoinCondition AND $targetTableFullName.$endTsCol IS NULL)
           |WHERE $nullJoinCondition;
           |
           |${createTemporaryView("SL_VIEW_WITH_ROWNUM")}  AS
           |  SELECT  $incomingColumnsAsSelectString,
           |          ROW_NUMBER() OVER (PARTITION BY $mergeKeys ORDER BY $quote$mergeTimestampCol$quote DESC) AS SL_SEQ
           |  FROM $sourceTable;
           |
           |${createTemporaryView("SL_DEDUP")}  AS
           |SELECT  $viewWithRowNumColumnsAsSelectString
           |  FROM ${tempViewName("SL_VIEW_WITH_ROWNUM")}
           |  WHERE SL_SEQ = 1;
           |
           |${createTemporaryView("SL_UPDATED_RECORDS")}  AS
           |SELECT $dedupColumnsAsSelectString
           |FROM ${tempViewName("SL_DEDUP")}, $targetTableFullName
           |WHERE $mergeKeyJoinCondition2
           |  AND $targetTableFullName.$endTsCol IS NULL
           |  AND ${tempViewName(
            "SL_DEDUP"
          )}.$mergeTimestampCol > $targetTableFullName.$mergeTimestampCol;
           |
           |MERGE INTO $targetTableFullName
           |USING ${tempViewName("SL_UPDATED_RECORDS")}
           |ON ($mergeKeyJoinCondition3)
           |WHEN MATCHED THEN UPDATE $paramsForUpdateSql, $startTsCol = SL_UPDATED_RECORDS.$quote$mergeTimestampCol$quote, $endTsCol = NULL
           |WHEN NOT MATCHED THEN INSERT $paramsForInsertSql -- here just to make the SQL valid. Only the WHEN MATCHED is used
           |""".stripMargin
      case (_, Some(_), MergeOn(_)) =>
        throw new Exception("Should never happen !!!")
      case (_, None, _) =>
        throw new Exception("SCD2 is not supported without a merge timestamp column")

    }
  }
  def buildSqlWithJ2(
    strategy: WriteStrategy,
    selectStatement: String,
    tableComponents: TableComponents,
    targetTableExists: Boolean,
    truncate: Boolean,
    materializedView: Materialization,
    jdbcEngine: JdbcEngine,
    sinkConfig: Sink,
    action: String
  )(implicit settings: Settings): String = {
    val context = StrategiesBuilder.StrategiesGenerationContext(
      strategy,
      selectStatement,
      tableComponents,
      targetTableExists,
      truncate,
      materializedView,
      jdbcEngine,
      sinkConfig
    )
    val templateLoader = new WriteStrategyTemplateLoader()
    val paramMap = context.asMap().asInstanceOf[Map[String, Object]]
    val contentTemplate = templateLoader.loadTemplate(
      s"${jdbcEngine.strategyBuilder.toLowerCase()}/${action.toLowerCase()}.j2"
    )

    // because jinjava does not like # chars and treat them as comments :( we need to replace them before putting them back
    val (contentTemplateUpdated, isSlIncomingRedshiftTempView) =
      if (contentTemplate.contains("#SL_INCOMING")) {
        (contentTemplate.replaceAll("#SL_INCOMING", "__SL_INCOMING__"), true)
      } else {
        (contentTemplate, false)
      }

    val macrosContent = templateLoader.loadMacros().toOption.getOrElse("")
    val jinjaOutput = Utils.parseJinjaTpl(macrosContent + "\n" + contentTemplateUpdated, paramMap)

    val jinjaOutputUpdated =
      if (isSlIncomingRedshiftTempView) {
        jinjaOutput.replaceAll("__SL_INCOMING__", "#SL_INCOMING")
      } else {
        jinjaOutput
      }
    jinjaOutputUpdated
  }

  def run(
    strategy: WriteStrategy,
    selectStatement: String,
    tableComponents: StrategiesBuilder.TableComponents,
    targetTableExists: Boolean,
    truncate: Boolean,
    materializedView: Materialization,
    jdbcEngine: JdbcEngine,
    sinkConfig: Sink
  )(implicit settings: Settings): String = {
    logger.info(
      s"Running Write strategy: ${strategy.`type`} for table ${tableComponents.getFullTableName()} with options: truncate: $truncate, materializedView: $materializedView, targetTableExists: $targetTableExists"
    )
    if (materializedView == Materialization.MATERIALIZED_VIEW) {
      buildSqlWithJ2(
        strategy,
        selectStatement,
        tableComponents,
        targetTableExists,
        truncate,
        materializedView,
        jdbcEngine,
        sinkConfig,
        "VIEW"
      )

    } else if (targetTableExists) {
      buildSqlWithJ2(
        strategy,
        selectStatement,
        tableComponents,
        targetTableExists,
        truncate,
        materializedView,
        jdbcEngine,
        sinkConfig,
        strategy.getEffectiveType().toString
      )
    } else {
      buildSqlWithJ2(
        strategy,
        selectStatement,
        tableComponents,
        targetTableExists,
        truncate,
        materializedView,
        jdbcEngine,
        sinkConfig,
        "CREATE"
      )
    }
  }
}

object StrategiesBuilder {
  def apply(): StrategiesBuilder = {
    new StrategiesBuilder()
    /*
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val classSymbol: ru.ClassSymbol =
      mirror.staticClass(className)
    val classMirror = mirror.reflectClass(classSymbol)

    val consMethodSymbol = classSymbol.primaryConstructor.asMethod
    val consMethodMirror = classMirror.reflectConstructor(consMethodSymbol)

    val strategyBuilder = consMethodMirror.apply().asInstanceOf[StrategiesBuilder]
    strategyBuilder
     */
  }

  def asJavaList[T](l: List[T]): java.util.ArrayList[T] = {
    val res = new java.util.ArrayList[T]()
    l.foreach(key => res.add(key))
    res
  }
  def asJavaMap[K, V](m: Map[K, V]): java.util.Map[K, V] = {
    val res = new java.util.HashMap[K, V]()
    m.foreach { case (k, v) =>
      res.put(k, v)
    }
    res
  }

  implicit class JavaWriteStrategy(writeStrategy: WriteStrategy) {

    def asMap(jdbcEngine: JdbcEngine): Map[String, Any] = {
      Map(
        "strategyType"  -> writeStrategy.`type`.getOrElse(WriteStrategyType.APPEND).toString,
        "strategyTypes" -> asJavaMap(writeStrategy.types.getOrElse(Map.empty[String, String])),
        "strategyKey"   -> asJavaList(writeStrategy.key),
        "quotedStrategyKey" -> asJavaList(
          SQLUtils
            .quoteCols(SQLUtils.unquoteCols(writeStrategy.key, jdbcEngine.quote), jdbcEngine.quote)
        ),
        "strategyTimestamp"   -> writeStrategy.timestamp.getOrElse(""),
        "strategyQueryFilter" -> writeStrategy.queryFilter.getOrElse(""),
        "strategyOn"          -> writeStrategy.on.getOrElse(MergeOn.TARGET).toString,
        "strategyStartTs"     -> writeStrategy.startTs.getOrElse(""),
        "strategyEndTs"       -> writeStrategy.endTs.getOrElse(""),
        "strategyKeyCsv"      -> writeStrategy.keyCsv(jdbcEngine.quote),
        "strategyKeyJoinCondition" -> writeStrategy.keyJoinCondition(
          jdbcEngine.quote,
          "SL_INCOMING",
          "SL_EXISTING"
        )
      )
    }
  }

  implicit class JavaJdbcEngine(jdbcEngine: JdbcEngine) {
    def asMap(): Map[String, Any] = {
      Map(
        "engineQuote"           -> jdbcEngine.quote,
        "engineViewPrefix"      -> jdbcEngine.viewPrefix.getOrElse(""),
        "enginePreActions"      -> jdbcEngine.preActions.getOrElse(""),
        "engineStrategyBuilder" -> jdbcEngine.strategyBuilder
      )
    }
  }
  case class TableComponents(
    database: String,
    domain: String,
    name: String,
    columnNames: List[String]
  ) {
    def getFullTableName(): String = {
      (database, domain, name) match {
        case ("", "", _) => name
        case ("", _, _)  => s"$domain.$name"
        case (_, _, _)   => s"$database.$domain.$name"
      }
    }
    def paramsForInsertSql(quote: String): String = {
      val targetColumns = SQLUtils.targetColumnsForSelectSql(columnNames, quote)
      val tableIncomingColumnsCsv =
        SQLUtils.incomingColumnsForSelectSql("SL_INCOMING", columnNames, quote)
      s"""($targetColumns) VALUES ($tableIncomingColumnsCsv)"""
    }

    def asMap(jdbcEngine: JdbcEngine): Map[String, Any] = {
      val tableIncomingColumnsCsv =
        SQLUtils.incomingColumnsForSelectSql("SL_INCOMING", columnNames, jdbcEngine.quote)
      val tableInsert = "INSERT " + paramsForInsertSql(jdbcEngine.quote)
      val tableUpdateSetExpression =
        SQLUtils.setForUpdateSql("SL_INCOMING", columnNames, jdbcEngine.quote)

      Map(
        "tableDatabase"    -> database,
        "tableDomain"      -> domain,
        "tableName"        -> name,
        "tableColumnNames" -> asJavaList(columnNames),
        "quotedTableColumnNames" -> asJavaList(
          columnNames.map(col => s"${jdbcEngine.quote}$col${jdbcEngine.quote}")
        ),
        "tableFullName"           -> getFullTableName(),
        "tableParamsForInsertSql" -> paramsForInsertSql(jdbcEngine.quote),
        "tableParamsForUpdateSql" -> SQLUtils
          .setForUpdateSql("SL_INCOMING", columnNames, jdbcEngine.quote),
        "tableInsert"              -> tableInsert,
        "tableUpdateSetExpression" -> tableUpdateSetExpression,
        "tableColumnsCsv" -> SQLUtils.targetColumnsForSelectSql(columnNames, jdbcEngine.quote),
        "tableIncomingColumnsCsv" -> tableIncomingColumnsCsv,
        "quote"                   -> jdbcEngine.quote
      )
    }
  }

  case class StrategiesGenerationContext(
    strategy: WriteStrategy,
    selectStatement: String,
    tableComponents: StrategiesBuilder.TableComponents,
    targetTableExists: Boolean,
    truncate: Boolean,
    materializedView: Materialization,
    jdbcEngine: JdbcEngine,
    sinkConfig: Sink
  ) {

    def asMap()(implicit settings: Settings): Map[String, Any] = {
      val tableFormat = sinkConfig
        .toAllSinks()
        .format
        .getOrElse(settings.appConfig.defaultWriteFormat)
      strategy.asMap(jdbcEngine) ++ tableComponents.asMap(jdbcEngine) ++ Map(
        "selectStatement"  -> selectStatement,
        "tableExists"      -> targetTableExists,
        "tableTruncate"    -> truncate,
        "materializedView" -> materializedView.toString,
        "tableFormat"      -> tableFormat
      ) ++ jdbcEngine.asMap() ++ sinkConfig.toAllSinks().asMap(jdbcEngine)

    }
  }
}
