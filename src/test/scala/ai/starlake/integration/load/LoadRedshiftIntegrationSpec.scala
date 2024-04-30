package ai.starlake.integration.load

import ai.starlake.integration.JDBCIntegrationSpecBase
import ai.starlake.job.Main

class LoadRedshiftIntegrationSpec extends JDBCIntegrationSpecBase {
  override def templates = starlakeDir / "samples"

  override def localDir = templates / "spark"

  override def sampleDataDir = localDir / "sample-data"
  if (sys.env.getOrElse("SL_REMOTE_TEST", "false").toBoolean && sys.env.contains("REDSHIFT_USER")) {
    "Import / Load / Transform Redshift" should "succeed" in {
      withEnvs(
        "SL_ROOT" -> localDir.pathAsString,
        "SL_ENV"  -> "REDSHIFT_SPARK"
      ) {
        cleanup()
        copyFilesToIncomingDir(sampleDataDir)

        assert(
          new Main().run(
            Array("import")
          )
        )
        assert(
          new Main().run(
            Array("load")
          )
        )
      }
    }
    "Import / Load / Transform Redshift 2" should "succeed" in {
      withEnvs(
        "SL_ROOT" -> localDir.pathAsString,
        "SL_ENV"  -> "REDSHIFT"
      ) {
        val sampleDataDir2 = localDir / "sample-data2"
        copyFilesToIncomingDir(sampleDataDir2)
        assert(
          new Main().run(
            Array("import")
          )
        )
        assert(
          new Main().run(
            Array("load")
          )
        )
      }
    }
  }
}