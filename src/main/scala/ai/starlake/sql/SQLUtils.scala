package ai.starlake.sql

import ai.starlake.config.Settings
import ai.starlake.config.Settings.Connection
import ai.starlake.schema.model._
import ai.starlake.transpiler.{JSQLColumResolver, JSQLTranspiler}
import ai.starlake.utils.Utils
import com.manticore.jsqlformatter.JSQLFormatter
import com.typesafe.scalalogging.StrictLogging
import net.sf.jsqlparser.parser.{CCJSqlParser, CCJSqlParserUtil}
import net.sf.jsqlparser.statement.select.{
  PlainSelect,
  Select,
  SelectVisitorAdapter,
  SetOperationList
}
import net.sf.jsqlparser.statement.{Statement, StatementVisitorAdapter}
import net.sf.jsqlparser.util.TablesNamesFinder

import java.util.UUID
import java.util.function.Consumer
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object SQLUtils extends StrictLogging {
  val fromsRegex = "(?i)\\s+FROM\\s+([_\\-a-z0-9`./(]+\\s*[ _,a-z0-9`./(]*)".r
  val joinRegex = "(?i)\\s+JOIN\\s+([_\\-a-z0-9`./]+)".r

  /** Syntax parser
    *
    * identifier = X | X.Y.Z | `X` | `X.Y.Z` | `X`.Y.Z
    *
    * FROM identifier
    *
    * FROM parquet.`/path-to/file`
    *
    * FROM
    *
    * JOIN identifier
    */
  //
  def extractTableNamesUsingRegEx(sql: String): List[String] = {
    val froms =
      fromsRegex
        .findAllMatchIn(sql)
        .map(_.group(1))
        .toList
        .flatMap(_.split(",").map(_.trim))
        .map {
          // because the regex above is not powerful enough
          table =>
            val space = table.replaceAll("\\s", " ").indexOf(' ')
            if (space > 0)
              table.substring(0, space)
            else
              table
        }
        .filter(!_.contains("(")) // we remove constructions like 'year from date(...)'

    val joins = joinRegex.findAllMatchIn(sql).map(_.group(1)).toList
    // val ctes = cteRegex.findAllMatchIn(sql).map(_.group(1)).toList
    // val cteRegex = "(?i)\\s+WITH\\s+([_\\-a-z0-9`./(]+\\s*[ _,a-z0-9`./(]*)".r
    // val cteNameRegex = "(?i)\\s+([a-z0-9]+)+\\s+AS\\s*\\(".r

    // def extractCTEsFromSQL(sql: String): List[String] = {
    //  val ctes = cteRegex.findAllMatchIn(sql).map(_.group(1)).toList
    //  ctes.map(_.replaceAll("`", ""))
    // }

    (froms ++ joins).map(_.replaceAll("`", ""))
  }

  def extractTableNamesFromCTEsUsingRegEx(sql: String): List[String] = {
    val cteRegex = "(?i)\\s+WITH\\s+([_\\-a-z0-9`./(]+\\s*[ _,a-z0-9`./(]*)".r
    val ctes = cteRegex.findAllMatchIn(sql).map(_.group(1)).toList
    ctes
  }

  def extractColumnNames(sql: String): List[String] = {
    var result: List[String] = Nil
    def extractColumnsFromPlainSelect(plainSelect: PlainSelect): Unit = {
      val selectItems = Option(plainSelect.getSelectItems).map(_.asScala).getOrElse(Nil)
      result = selectItems.map { selectItem =>
        selectItem.getASTNode.jjtGetLastToken().image
      }.toList
    }
    val selectVisitorAdapter = new SelectVisitorAdapter[Any]() {
      override def visit[T](plainSelect: PlainSelect, context: T): Any = {
        extractColumnsFromPlainSelect(plainSelect)
      }
      override def visit[T](setOpList: SetOperationList, context: T): Any = {
        val plainSelect = setOpList.getSelect(0).getPlainSelect
        extractColumnsFromPlainSelect(plainSelect)
      }
    }
    val statementVisitor = new StatementVisitorAdapter[Any]() {
      override def visit[T](select: Select, context: T): Any = {
        select.accept(selectVisitorAdapter, null)
      }
    }
    val select = jsqlParse(sql)
    select.accept(statementVisitor, null)
    result
  }

  def extractTableNames(sql: String): List[String] = {
    val select = jsqlParse(sql)
    val finder = new TablesNamesFinder()
    val tableList = Option(finder.getTables(select)).map(_.asScala).getOrElse(Nil)
    tableList.toList
  }

  def extractCTENames(sql: String): List[String] = {
    var result: ListBuffer[String] = ListBuffer()
    val statementVisitor = new StatementVisitorAdapter[Any]() {
      override def visit[T](select: Select, context: T): Any = {
        val ctes = Option(select.getWithItemsList()).map(_.asScala).getOrElse(Nil)
        ctes.foreach { withItem =>
          val alias = Option(withItem.getAlias).map(_.getName).getOrElse("")
          if (alias.nonEmpty)
            result += alias
        }
        null
      }
    }
    val select = jsqlParse(sql)
    select.accept(statementVisitor, null)
    result.toList
  }

  def jsqlParse(sql: String): Statement = {
    // remove TABLE keyword in order for sqlparser to be able to parse it
    val parseable =
      sql
        .replaceAll("(?i)APPENDS\\s*\\(\\s*TABLE", "APPENDS(")
        .replaceAll("(?i)CHANGES\\s*\\(\\s*TABLE", "CHANGES(")
        .replaceAll("(?i)GAP_FILL\\s*\\(\\s*TABLE", "GAP_FILL(")

    /*
        .replaceAll("(?i)WHEN NOT MATCHED AND (.*) THEN ", "WHEN NOT MATCHED THEN ")
        .replaceAll("(?i)WHEN MATCHED (.*) THEN ", "WHEN MATCHED THEN ")
     */
    val features = new Consumer[CCJSqlParser] {
      override def accept(t: CCJSqlParser): Unit = {
        t.withTimeOut(60 * 1000)
      }
    }
    try {
      CCJSqlParserUtil.parse(parseable, features)
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to parse $sql")
        throw exception
    }
  }

  def substituteRefInSQLSelect(
    sql: String,
    refs: RefDesc,
    domains: List[Domain],
    tasks: List[AutoTaskDesc],
    connection: Connection
  )(implicit
    settings: Settings
  ): String = {
    logger.debug(s"Source SQL: $sql")
    val fromResolved =
      buildSingleSQLQueryForRegex(
        sql,
        refs,
        domains,
        tasks,
        SQLUtils.fromsRegex,
        "FROM",
        connection
      )
    logger.debug(s"fromResolved SQL: $fromResolved")
    val joinAndFromResolved =
      buildSingleSQLQueryForRegex(
        fromResolved,
        refs,
        domains,
        tasks,
        SQLUtils.joinRegex,
        "JOIN",
        connection
      )
    logger.debug(s"joinAndFromResolved SQL: $joinAndFromResolved")
    joinAndFromResolved
  }

  def buildSingleSQLQueryForRegex(
    sql: String,
    refs: RefDesc,
    domains: List[Domain],
    tasks: List[AutoTaskDesc],
    fromOrJoinRegex: Regex,
    keyword: String,
    connection: Connection
  )(implicit
    settings: Settings
  ): String = {
    val ctes = SQLUtils.extractCTENames(sql)
    var resolvedSQL = ""
    var startIndex = 0
    val fromMatches = fromOrJoinRegex
      .findAllMatchIn(sql)
      .toList
    if (fromMatches.isEmpty) {
      sql
    } else {
      val tablesList = this.extractTableNames(sql)
      fromMatches
        .foreach { regex =>
          var source = regex.source.toString.substring(regex.start, regex.end)

          val tablesFound =
            source
              .substring(
                source.toUpperCase().indexOf(keyword.toUpperCase()) + keyword.length
              ) // SKIP FROM AND JOIN
              .split(",")
              .map(_.trim.split("\\s").head) // get expressions in FROM / JOIN
              .filter(tablesList.contains(_)) // we remove CTEs
              .sortBy(_.length) // longer tables first because we need to make sure
              .reverse // that we are replacing the whole word

          tablesFound.foreach { tableFound =>
            val resolvedTableName = resolveTableNameInSql(
              tableFound,
              refs,
              domains,
              tasks,
              ctes,
              connection
            )
            source = source.replaceAll(tableFound, resolvedTableName)
          }
          resolvedSQL += sql.substring(startIndex, regex.start) + source
          startIndex = regex.end
        }
      resolvedSQL = resolvedSQL + sql.substring(startIndex)
      resolvedSQL
    }
  }

  private def resolveTableNameInSql(
    tableName: String,
    refs: RefDesc,
    domains: List[Domain],
    tasks: List[AutoTaskDesc],
    ctes: List[String],
    connection: Connection
  )(implicit
    settings: Settings
  ): String = {
    def cteContains(table: String): Boolean = ctes.exists(cte => cte.equalsIgnoreCase(table))
    if (tableName.contains('/')) {
      // This is a file in the form of parquet.`/path/to/file`
      tableName
    } else {
      val quoteFreeTName: String = quoteFreeTableName(tableName)
      val tableTuple = quoteFreeTName.split("\\.").toList

      // We need to find it in the refs
      val activeEnvRefs = refs
      val databaseDomainTableRef =
        activeEnvRefs
          .getOutputRef(tableTuple)
          .map(_.toSQLString(connection))
      val resolvedTableName = databaseDomainTableRef.getOrElse {
        resolveTableRefInDomainsAndJobs(tableTuple, domains, tasks) match {
          case Success((database, domain, table)) =>
            ai.starlake.schema.model
              .OutputRef(database, domain, table)
              .toSQLString(connection)
          case Failure(e) =>
            Utils.logException(logger, e)
            throw e
        }
      }
      resolvedTableName
    }
  }

  def quoteFreeTableName(tableName: String) = {
    val quoteFreeTableName = List("\"", "`", "'").foldLeft(tableName) { (tableName, quote) =>
      tableName.replaceAll(quote, "")
    }
    quoteFreeTableName
  }

  private def resolveTableRefInDomainsAndJobs(
    tableComponents: List[String],
    domains: List[Domain],
    tasks: List[AutoTaskDesc]
  )(implicit
    settings: Settings
  ): Try[(String, String, String)] = Try {
    val (database, domain, table): (Option[String], Option[String], String) =
      tableComponents match {
        case table :: Nil =>
          (None, None, table)
        case domain :: table :: Nil =>
          (None, Some(domain), table)
        case database :: domain :: table :: Nil =>
          (Some(database), Some(domain), table)
        case _ =>
          throw new Exception(
            s"Invalid table reference ${tableComponents.mkString(".")}"
          )
      }
    (database, domain, table) match {
      case (Some(db), Some(dom), table) =>
        (db, dom, table)
      case (None, domainComponent, table) =>
        val domainsByFinalName = domains
          .filter { dom =>
            val domainOK = domainComponent.forall(_.equalsIgnoreCase(dom.finalName))
            domainOK && dom.tables.exists(_.finalName.equalsIgnoreCase(table))
          }
        val tasksByTable =
          tasks.find { task =>
            val domainOK = domainComponent.forall(_.equalsIgnoreCase(task.domain))
            domainOK && task.table.equalsIgnoreCase(table)
          }.toList

        val nameCountMatch =
          domainsByFinalName.length + tasksByTable.length
        val (database, domain) = if (nameCountMatch > 1) {
          val domainNames = domainsByFinalName.map(_.finalName).mkString(",")
          logger.error(s"Table $table is present in domain(s): $domainNames.")

          val taskNamesByTable = tasksByTable.map(_.table).mkString(",")
          logger.error(s"Table $table is present as a table in tasks(s): $taskNamesByTable.")

          val taskNames = tasksByTable.map(_.name).mkString(",")
          logger.error(s"Table $table is present as a table in tasks(s): $taskNames.")
          throw new Exception("Table is present in multiple domains and/or tasks")
        } else if (nameCountMatch == 1) {
          domainsByFinalName.headOption
            .map(dom => (dom.database, dom.finalName))
            .orElse(tasksByTable.headOption.map { task =>
              (task.database, task.domain)
            })
            .getOrElse((None, ""))
        } else { // nameCountMatch == 0
          logger.info(
            s"Table $table not found in any domain or task; This is probably a CTE or a temporary table"
          )
          (None, domainComponent.getOrElse(""))
        }
        val databaseName = database
          .orElse(settings.appConfig.getDefaultDatabase())
          .getOrElse("")
        (databaseName, domain, table)
      case _ =>
        throw new Exception(
          s"Invalid table reference ${tableComponents.mkString(".")}"
        )
    }

  }

  def temporaryTableName(tableName: String): String =
    "zztmp_" + tableName + "_" + UUID.randomUUID().toString.replace("-", "")

  def stripComments(sql: String): String = {

    // Remove single line comments
    val sql1 = sql.split("\n").map(_.replaceAll("--.*$", "")).mkString("\n")
    // Remove multi-line comments
    val sql2 = sql1.replaceAll("(?s)/\\*.*?\\*/", "")
    sql2.trim
  }

  def quoteCols(cols: List[String], quote: String): List[String] = {
    unquoteCols(cols, quote).map(col => s"${quote}$col${quote}")
  }

  def unquoteCols(cols: List[String], quote: String): List[String] = {
    cols.map { col =>
      if (quote.nonEmpty && col.startsWith(quote) && col.endsWith(quote))
        col.substring(1, col.length - 1)
      else
        col
    }
  }

  def unquoteAgressive(cols: List[String]): List[String] = {
    val quotes = List("\"", "'", "`")
    cols.map { col =>
      var result = col.trim
      quotes.foreach { quote =>
        if (result.startsWith(quote) && result.endsWith(quote)) {
          result = result.substring(1, result.length - 1)
        }
      }
      result
    }
  }

  def targetColumnsForSelectSql(targetTableColumns: List[String], quote: String): String =
    quoteCols(unquoteCols(targetTableColumns, quote), quote).mkString(",")

  def incomingColumnsForSelectSql(
    incomingTable: String,
    targetTableColumns: List[String],
    quote: String
  ): String =
    unquoteCols(targetTableColumns, quote)
      .map(col => s"$incomingTable.$quote$col$quote")
      .mkString(",")

  def setForUpdateSql(
    incomingTable: String,
    targetTableColumns: List[String],
    quote: String
  ): String =
    unquoteCols(targetTableColumns, quote)
      .map(col => s"$quote$col$quote = $incomingTable.$quote$col$quote")
      .mkString("SET ", ",", "")

  def mergeKeyJoinCondition(
    incomingTable: String,
    targetTable: String,
    columns: List[String],
    quote: String
  ): String =
    unquoteCols(columns, quote)
      .map(col => s"$incomingTable.$quote$col$quote = $targetTable.$quote$col$quote")
      .mkString(" AND ")

  def format(input: String, outputFormat: JSQLFormatter.OutputFormat): String = {
    val sql = input.trim
    val uppercaseSQL = sql.toUpperCase
    val formatted =
      if (
        uppercaseSQL.startsWith("SELECT") || uppercaseSQL.startsWith("WITH") || uppercaseSQL
          .startsWith("MERGE")
      ) {
        val preformat = sql.replaceAll("}}", "______\n").replaceAll("\\{\\{", "___\n")
        Try(
          JSQLFormatter.format(
            preformat,
            s"outputFormat=${outputFormat.name()}",
            "statementTerminator=NONE"
          )
        ).getOrElse(s"-- failed to format start\n$sql\n-- failed to format end")
      } else {
        sql
      }

    val result =
      if (formatted.startsWith("-- failed to format start")) {
        sql
      } else {
        val postFormat = formatted.replaceAll("______", "}}").replaceAll("___", "{{")
        if (outputFormat == JSQLFormatter.OutputFormat.HTML) {
          val startIndex = postFormat.indexOf("<body>") + "<body>".length
          val endIndex = postFormat.indexOf("</body>")
          if (startIndex > 0 && endIndex > 0) {
            postFormat.substring(startIndex, endIndex)
          } else {
            postFormat
          }
        } else {
          postFormat
        }
      }
    // remove extra ';' added by JSQLFormatter
    val trimmedResult = result.trim
    if (!sql.endsWith(";") && trimmedResult.endsWith(";")) {
      trimmedResult.substring(0, trimmedResult.length - 1)
    } else {
      result
    }
  }

  def transpilerDialect(conn: Connection): JSQLTranspiler.Dialect =
    conn._transpileDialect match {
      case Some(dialect) => JSQLTranspiler.Dialect.valueOf(dialect)
      case None =>
        if (conn.isSpark())
          JSQLTranspiler.Dialect.DATABRICKS
        if (conn.isBigQuery())
          JSQLTranspiler.Dialect.GOOGLE_BIG_QUERY
        else if (conn.isSnowflake())
          JSQLTranspiler.Dialect.SNOWFLAKE
        else if (conn.isRedshift())
          JSQLTranspiler.Dialect.AMAZON_REDSHIFT
        else if (conn.isDuckDb())
          JSQLTranspiler.Dialect.DUCK_DB
        else if (conn.isPostgreSql())
          JSQLTranspiler.Dialect.ANY
        else if (conn.isMySQLOrMariaDb())
          JSQLTranspiler.Dialect.ANY
        else if (conn.isJdbcUrl())
          JSQLTranspiler.Dialect.ANY
        else
          JSQLTranspiler.Dialect.ANY // Should not happen
    }

  def resolve(sql: String)(implicit settings: Settings): String = {
    val schemaDefinition = settings.schemaHandler().objectDefinitions()
    val resolved = Try(
      new JSQLColumResolver(schemaDefinition)
        .getResolvedStatementText(sql)
        .replaceAll("/\\* Resolved Column\\*/", "")
    )

    resolved.getOrElse(sql)
  }

  def transpile(sql: String, conn: Connection, timestamps: Map[String, AnyRef]): String = {
    if (timestamps.nonEmpty) {
      logger.info(s"Transpiling SQL with timestamps: $timestamps")
    }
    val dialect = transpilerDialect(conn)
    val unpipedQuery = Try {
      if (dialect != JSQLTranspiler.Dialect.GOOGLE_BIG_QUERY) {
        JSQLTranspiler.unpipe(sql)
      } else {
        sql
      }
    } match {
      case Success(unpiped) =>
        unpiped
      case Failure(e) =>
        logger.error(s"Failed to unpipe SQL, sending as is to the dataware: $sql")
        Utils.logException(logger, e)
        sql
    }
    Try(
      JSQLTranspiler.transpileQuery(unpipedQuery, transpilerDialect(conn), timestamps.asJava)
    ) match {
      case Success(transpiled) =>
        transpiled
      case Failure(e) =>
        logger.error(s"Failed to transpile SQL: $sql")
        Utils.logException(logger, e)
        sql
    }
  }
}
