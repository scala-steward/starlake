package ai.starlake.schema.generator

import ai.starlake.config.{DatasetArea, Settings}
import ai.starlake.utils.AnyTemplateLoader
import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.fs.Path

class LoadStrategyTemplateLoader extends AnyTemplateLoader with LazyLogging {

  protected def RESOURCE_TEMPLATE_FOLDER: String = s"templates/load-strategies"
  protected def EXTERNAL_TEMPLATE_BASE_PATH(implicit settings: Settings): Path =
    DatasetArea.loadStrategies

}
