from typing import Union

from ai.starlake.dagster import StarlakeDagsterJob

from ai.starlake.job import StarlakePreLoadStrategy, StarlakeSparkConfig

from dagster import Failure, Output, AssetMaterialization, AssetKey, Out, op, RetryPolicy

from dagster._core.definitions import NodeDefinition

from dagster_shell import execute_shell_command

class StarlakeDagsterShellJob(StarlakeDagsterJob):

    def __init__(self, pre_load_strategy: Union[StarlakePreLoadStrategy, str, None]=None, options: dict=None, **kwargs) -> None:
        super().__init__(pre_load_strategy=pre_load_strategy, options=options, **kwargs)

    def sl_job(self, task_id: str, arguments: list, spark_config: StarlakeSparkConfig=None, **kwargs) -> NodeDefinition:
        """Overrides IStarlakeJob.sl_job()
        Generate the Dagster node that will run the starlake command.

        Args:
            task_id (str): The required task id.
            arguments (list): The required arguments of the starlake command to run.

        Returns:
            OpDefinition: The Dagster node.
        """
        found = False

        for index, arg in enumerate(arguments):
            if arg == "--options" and arguments.__len__() > index + 1:
                opts = arguments[index+1]
                if opts.__len__() > 0:
                    options = opts + "," + ",".join([f"{key}={value}" for i, (key, value) in enumerate(self.sl_env_vars.items())])
                else:
                    options = ",".join([f"{key}={value}" for i, (key, value) in enumerate(self.sl_env_vars.items())])
                arguments[index+1] = options
                found = True
                break

        if not found:
            arguments.append("--options")
            arguments.append(",".join([f"{key}={value}" for key, value in self.sl_env_vars.items()]))

        command = self.__class__.get_context_var("SL_STARLAKE_PATH", "starlake", self.options) + f" {' '.join(arguments or [])}"

        asset_key: Union[AssetKey, None] = kwargs.get("asset", None)

        ins=kwargs.get("ins", {})

        out:str=kwargs.get("out", "result")
        failure:str=kwargs.get("failure", None)
        outs=kwargs.get("outs", {out: Out(str, is_required=failure is None)})
        if failure:
            outs.update({failure: Out(str, is_required=False)})

        if self.retries:
            retry_policy = RetryPolicy(max_retries=self.retries, delay=self.retry_delay)
        else:
            retry_policy = None

        @op(
            name=task_id,
            ins=ins,
            out=outs,
            retry_policy=retry_policy,
        )
        def job(context, **kwargs):
            output, return_code = execute_shell_command(
                shell_command=command,
                output_logging="STREAM",
                log=context.log,
                cwd=self.sl_root,
                env=self.sl_env_vars,
                log_shell_command=True,
            )

            if return_code:
                value=f"Starlake command {command} execution failed with output: {output}"
                if failure:
                    if retry_policy:
                        retry_count = context.retry_number
                        if retry_count < retry_policy.max_retries:
                            raise Failure(description=value)
                        else:
                            yield Output(value=value, output_name=failure)
                    else:
                        yield Output(value=value, output_name=failure)
                else:
                    raise Failure(description=value)
            else:
                if asset_key:
                    yield AssetMaterialization(asset_key=asset_key.path, description=kwargs.get("description", f"Starlake command {command} execution succeeded"))

                yield Output(value=output, output_name=out)

        return job
