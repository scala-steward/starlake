package ai.starlake.tests

import ai.starlake.config.Settings
import ai.starlake.utils.Utils

import scala.jdk.CollectionConverters._
import java.io.File
import java.nio.file.Files
import java.time.format.DateTimeFormatter
import scala.reflect.io.Directory

case class StarlakeTestResult(
  testFolder: String,
  domainName: String,
  tableName: String,
  taskName: String,
  testName: String,
  missingColumns: List[String],
  notExpectedColumns: List[String],
  missingRecords: File,
  notExpectedRecords: File,
  success: Boolean,
  exception: Option[Throwable],
  duration: Long
) {
  // getters for jinjava
  def getTestFolder(): String = testFolder
  def getDomainName(): String = domainName
  def getTableName(): String = tableName
  def getTaskName(): String = taskName
  def getTestName(): String = testName
  def getMissingColumns(): java.util.List[String] = missingColumns.asJava
  def getMissingColumnsCount(): Int = missingColumns.size
  def getNotExpectedColumns(): java.util.List[String] = notExpectedColumns.asJava
  def getNotExpectedColumnsCount(): Int = notExpectedColumns.size
  def getMissingRecords(): String =
    if (missingRecords.exists())
      Files.readAllLines(missingRecords.toPath).asScala.mkString("\n")
    else ""
  def getMissingRecordsCount() = {
    val nbLines = if (missingRecords.exists()) getMissingRecords().split("\n").length else 0
    if (nbLines >= 1) nbLines - 1 else 0
  }

  def getNotExpectedRecords(): String =
    if (notExpectedRecords.exists())
      Files.readAllLines(notExpectedRecords.toPath).asScala.mkString("\n")
    else ""

  def getNotExpectedRecordsCount() = {
    val nbLines = if (notExpectedRecords.exists()) getNotExpectedRecords().split("\n").length else 0
    if (nbLines >= 1) nbLines - 1 else 0

  }

  def getSuccess(): Boolean = success
  def getException(): String = exception.map(Utils.exceptionAsString).getOrElse("")
  def getDuration(): String = {
    val d: Double = duration.toDouble / 1000
    s"$d"
  }

}

object StarlakeTestResult {
  val loader = new StarlakeTestTemplateLoader()

  def copyCssAndJs(toFolder: Directory)(implicit settings: Settings): Unit = {
    val cssAndJs = Array("css/base-style.css", "css/style.css", "js/report.js")
    cssAndJs.foreach { cj =>
      val content = loader.loadTemplate(s"$cj.j2")
      val targetFile = new File(toFolder.path, cj)
      targetFile.getParentFile().mkdirs()
      Files.write(targetFile.toPath, content.getBytes())
    }
  }
  def html(
    loadResults: List[StarlakeTestResult],
    transformResults: List[StarlakeTestResult]
  ): Unit = {
    implicit val originalSettings: Settings = Settings(Settings.referenceConfig)
    val rootFolder = new Directory(new File(originalSettings.appConfig.root, "test-reports"))
    copyCssAndJs(rootFolder)

    val loadSummaries = StarlakeTestsDomainSummary.summaries(loadResults)
    val loadIndex = StarlakeTestsSummary.summaryIndex(loadSummaries)
    val transformSummaries = StarlakeTestsDomainSummary.summaries(transformResults)
    val transformIndex = StarlakeTestsSummary.summaryIndex(transformSummaries)
    val j2Params = Map(
      "loadIndex"          -> loadIndex,
      "loadSummaries"      -> loadSummaries.asJava,
      "transformIndex"     -> transformIndex,
      "transformSummaries" -> transformSummaries.asJava,
      "timestamp"          -> DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())
    )
    val indexJ2 = loader.loadTemplate("root.html.j2")
    val indexContent = Utils.parseJinja(indexJ2, j2Params)
    Files.write(new File(rootFolder.path, "index.html").toPath, indexContent.getBytes())

    val loadFolder = new Directory(new File(rootFolder.jfile, "load"))
    html(loadResults, loadFolder, "Load")
    val transformFolder = new Directory(new File(rootFolder.jfile, "transform"))
    html(transformResults, transformFolder, "Transform")
  }

  def html(results: List[StarlakeTestResult], testsFolder: Directory, loadOrTransform: String)(
    implicit originalSettings: Settings
  ): Unit = {
    val domainSummaries = StarlakeTestsDomainSummary.summaries(results)
    val summaryIndex = StarlakeTestsSummary.summaryIndex(domainSummaries)
    val j2Params = Map(
      "summaryIndex"    -> summaryIndex,
      "domainSummaries" -> domainSummaries.asJava,
      "timestamp"       -> DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()),
      "loadOrTransform" -> loadOrTransform
    )
    val indexJ2 = loader.loadTemplate("index.html.j2")
    val indexContent = Utils.parseJinja(indexJ2, j2Params)
    Files.write(new File(testsFolder.path, "index.html").toPath, indexContent.getBytes())

    domainSummaries.foreach { domainSummary =>
      val tableSummaries = StarlakeTestsTableSummary.summaries(domainSummary.name, results)
      val indexJ2 = loader.loadTemplate("index.domain.html.j2")
      val j2Params = Map(
        "domainSummary"   -> domainSummary,
        "tableSummaries"  -> tableSummaries.asJava,
        "timestamp"       -> DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()),
        "loadOrTransform" -> loadOrTransform
      )
      val domainFolder = new File(testsFolder.path, domainSummary.name)
      domainFolder.mkdir()
      val result = Utils.parseJinja(indexJ2, j2Params)
      Files.write(new File(domainFolder, "index.html").toPath, result.getBytes())

      tableSummaries.foreach { tableSummary =>
        val tableResults =
          results.filter(r => s"${r.domainName}.${r.taskName}" == tableSummary.name)
        val indexJ2 = loader.loadTemplate("index.table.html.j2")
        val j2Params = Map(
          "domainName"      -> domainSummary.name,
          "tableSummary"    -> tableSummary,
          "testResults"     -> tableResults.asJava,
          "timestamp"       -> DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()),
          "loadOrTransform" -> loadOrTransform
        )
        val tableFolder = new File(domainFolder, tableSummary.getTableName())
        tableFolder.mkdir()
        val result = Utils.parseJinja(indexJ2, j2Params)
        Files.write(new File(tableFolder, "index.html").toPath, result.getBytes())
      }
    }
    results.foreach { result =>
      val indexJ2 = loader.loadTemplate("index.test.html.j2")
      val j2Params = Map(
        "domainName"      -> result.domainName,
        "tableName"       -> result.taskName,
        "testResult"      -> result,
        "timestamp"       -> DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()),
        "loadOrTransform" -> loadOrTransform
      )
      val testFolder =
        new File(
          testsFolder.path,
          result.domainName + File.separator + result.taskName + File.separator + result.testName
        )
      val resultContent = Utils.parseJinja(indexJ2, j2Params)
      Files.write(new File(testFolder, "index.html").toPath, resultContent.getBytes())
    }

  }
}
