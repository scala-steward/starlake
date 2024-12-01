from __future__ import annotations

from abc import abstractmethod

from ai.starlake.common import MissingEnvironmentVariable, sl_schedule_format

from ai.starlake.job.starlake_pre_load_strategy import StarlakePreLoadStrategy
from ai.starlake.job.starlake_options import StarlakeOptions
from ai.starlake.job.spark_config import StarlakeSparkConfig

from ai.starlake.resource import StarlakeEvent

import sys

from datetime import timedelta

from typing import final, Generic, List, Optional, TypeVar, Union

T = TypeVar("T")

E = TypeVar("E")

from enum import Enum

StarlakeOrchestrator = Enum("StarlakeOrchestrator", ["airflow", "dagster"])

class IStarlakeJob(Generic[T, E], StarlakeOptions, StarlakeEvent[E]):
    def __init__(self, filename: str, module_name: str, pre_load_strategy: Union[StarlakePreLoadStrategy, str, None], options: dict, **kwargs) -> None:
        """Init the class.
        Args:
            filename (str): The filename from which the job is called.
            module_name (str): The module name from which the job is called.
            pre_load_strategy (Union[StarlakePreLoadStrategy, str, None]): The pre-load strategy to use.
            options (dict): The options to use.
        """
        super().__init__(**kwargs)
        self.options = {} if not options else options
        pre_load_strategy = __class__.get_context_var(
            var_name="pre_load_strategy",
            default_value=StarlakePreLoadStrategy.NONE,
            options=self.options
        ) if not pre_load_strategy else pre_load_strategy

        if isinstance(pre_load_strategy, str):
            pre_load_strategy = \
                StarlakePreLoadStrategy(pre_load_strategy) if StarlakePreLoadStrategy.is_valid(pre_load_strategy) \
                    else StarlakePreLoadStrategy.NONE

        self.pre_load_strategy: StarlakePreLoadStrategy = pre_load_strategy

        self.sl_env_vars = __class__.get_sl_env_vars(self.options)
        self.sl_root = __class__.get_sl_root(self.options)
        self.sl_datasets = __class__.get_sl_datasets(self.options)
        self.sl_schedule_parameter_name = __class__.get_context_var(
            var_name="sl_schedule_parameter_name",
            default_value="sl_schedule",
            options=self.options
        )
        self.sl_schedule_format = __class__.get_context_var(
            var_name="sl_schedule_format",
            default_value=sl_schedule_format,
            options=self.options
        )
        try:
            self.retries = int(__class__.get_context_var(var_name='retries', options=self.options))
        except (MissingEnvironmentVariable, ValueError):
            self.retries = 1
        try:
            self.retry_delay = int(__class__.get_context_var(var_name='retry_delay', options=self.options))
        except (MissingEnvironmentVariable, ValueError):
            self.retry_delay = 300

        # Access the caller file name
        self.caller_filename = filename

        # Access the caller module name
        self.caller_module_name = module_name
        
        # Access the caller's global variables
        self.caller_globals = sys.modules[self.caller_module_name].__dict__

        def default_spark_config(*args, **kwargs) -> StarlakeSparkConfig:
            return StarlakeSparkConfig(
                memory=self.caller_globals.get('spark_executor_memory', None),
                cores=self.caller_globals.get('spark_executor_cores', None),
                instances=self.caller_globals.get('spark_executor_instances', None),
                cls_options=self,
                options=self.options,
                **kwargs
            )

        self.get_spark_config = getattr(self.caller_module_name, "get_spark_config", default_spark_config)

        events: List[E] = []
        self.events = events

    @abstractmethod
    def sl_orchestrator(self) -> StarlakeOrchestrator:
        """Returns the orchestrator to use.

        Returns:
            StarlakeOrchestrator: The orchestrator to use.
        """
        pass

    @abstractmethod
    def sl_events(self, uri: str, **kwargs) -> List[E]:
        pass

    def sl_import(self, task_id: str, domain: str, tables: set=set(), **kwargs) -> T:
        """Import job.
        Generate the scheduler task that will run the starlake `import` command.

        Args:
            task_id (str): The optional task id.
            domain (str): The required domain to import.
            tables (set): The optional tables to import.

        Returns:
            T: The scheduler task.
        """
        task_id = f"import_{domain}" if not task_id else task_id
        kwargs.pop("task_id", None)
        arguments = ["import", "--domains", domain, "--tables", ",".join(tables), "--options", "SL_RUN_MODE=main,SL_LOG_LEVEL=info"]
        return self.sl_job(task_id=task_id, arguments=arguments, **kwargs)

    def sl_pre_load(self, domain: str, tables: set=set(), pre_load_strategy: Union[StarlakePreLoadStrategy, str, None]=None, **kwargs) -> Optional[T]:
        """Pre-load job.
        Generate the scheduler task that will check if the conditions are met to load the specified domain according to the pre-load strategy choosen.

        Args:
            domain (str): The required domain to pre-load.
            tables (set): The optional tables to pre-load.
            pre_load_strategy (Union[StarlakePreLoadStrategy, str, None]): The optional pre-load strategy to use.
        
        Returns:
            Optional[T]: The scheduler task or None.
        """
        if isinstance(pre_load_strategy, str):
            pre_load_strategy = \
                StarlakePreLoadStrategy(pre_load_strategy) if StarlakePreLoadStrategy.is_valid(pre_load_strategy) \
                    else self.pre_load_strategy

        pre_load_strategy = self.pre_load_strategy if not pre_load_strategy else pre_load_strategy

        if pre_load_strategy == StarlakePreLoadStrategy.NONE:
            return None
        else:
            arguments = ["preload", "--domain", domain, "--tables", ",".join(tables), "--strategy", pre_load_strategy.value, "--options", "SL_RUN_MODE=main,SL_LOG_LEVEL=info"]

            if pre_load_strategy == StarlakePreLoadStrategy.IMPORTED:
                task_id = f'check_{domain}_incoming_files'

            elif pre_load_strategy == StarlakePreLoadStrategy.PENDING:
                task_id = f'check_{domain}_pending_files'

            elif pre_load_strategy == StarlakePreLoadStrategy.ACK:
                task_id = f'check_{domain}_ack_file'

                def current_dt():
                    from datetime import datetime
                    return datetime.today().strftime('%Y-%m-%d')

                ack_file = __class__.get_context_var(
                    var_name='global_ack_file_path',
                    default_value=f'{self.sl_datasets}/pending/{domain}/{current_dt()}.ack',
                    options=self.options
                )

                arguments.extend(["--globalAckFilePath", f"{ack_file}"])

                ack_wait_timeout = int(__class__.get_context_var(
                    var_name='ack_wait_timeout',
                    default_value=60*60, # 1 hour
                    options=self.options
                ))

                kwargs.update({'retry_delay': timedelta(seconds=ack_wait_timeout)})

            else:
                task_id = kwargs.get("task_id", f"pre_load_{domain}")

            kwargs.pop("task_id", None)

            return self.sl_job(task_id=task_id, arguments=arguments, **kwargs)

    def sl_load(self, task_id: str, domain: str, table: str, spark_config: StarlakeSparkConfig=None, **kwargs) -> T:
        """Load job.
        Generate the scheduler task that will run the starlake `load` command.

        Args:
            task_id (str): The optional task id.
            domain (str): The required domain of the table to load.
            table (str): The required table to load.
            spark_config (StarlakeSparkConfig): The optional spark configuration to use.
        
        Returns:
            T: The scheduler task.
        """
        task_id = kwargs.get("task_id", f"load_{domain}_{table}") if not task_id else task_id
        kwargs.pop("task_id", None)
        arguments = ["load", "--domains", domain, "--tables", table]
        if spark_config is None:
            spark_config = self.get_spark_config(
                self.__class__.get_context_var(
                    'spark_config_name', 
                    f'{domain}.{table}'.lower(),
                    options=self.options
                ), 
                **self.caller_globals.get('spark_properties', {})
            )
        return self.sl_job(task_id=task_id, arguments=arguments, spark_config=spark_config, **kwargs)

    def sl_transform(self, task_id: str, transform_name: str, transform_options: str=None, spark_config: StarlakeSparkConfig=None, **kwargs) -> T:
        """Transform job.
        Generate the scheduler task that will run the starlake `transform` command.

        Args:
            task_id (str): The optional task id.
            transform_name (str): The transform to run.
            transform_options (str): The optional transform options to use.
            spark_config (StarlakeSparkConfig): The optional spark configuration to use.
        
        Returns:
            T: The scheduler task.
        """
        task_id = kwargs.get("task_id", f"{transform_name}") if not task_id else task_id
        kwargs.pop("task_id", None)
        arguments = ["transform", "--name", transform_name]
        options = list()
        if transform_options:
            options = transform_options.split(",")
        additional_options = self.__class__.get_context_var(transform_name, {}, self.options).get("options", "")
        if additional_options.__len__() > 0:
            options.extend(additional_options.split(","))
        if options.__len__() > 0:
            arguments.extend(["--options", ",".join(options)])
        if spark_config is None:
            spark_config = self.get_spark_config(
                self.__class__.get_context_var(
                    'spark_config_name', 
                    transform_name.lower(),
                    options=self.options
                ), 
                **self.caller_globals.get('spark_properties', {})
            )
        return self.sl_job(task_id=task_id, arguments=arguments, spark_config=spark_config, **kwargs)

    def pre_tasks(self, *args, **kwargs) -> Optional[T]:
        """Pre tasks."""
        return None

    def post_tasks(self, *args, **kwargs) -> Optional[T]:
        """Post tasks."""
        return None

    @abstractmethod
    def dummy_op(self, task_id, events: Optional[List[E]], **kwargs) -> T:
        pass

    @abstractmethod
    def sl_job(self, task_id: str, arguments: list, spark_config: StarlakeSparkConfig=None, **kwargs) -> T:
        """Generic job.
        Generate the scheduler task that will run the starlake command.

        Args:
            task_id (str): The required task id.
            arguments (list): The required arguments of the starlake command to run.
            spark_config (StarlakeSparkConfig): The optional spark configuration to use.
        
        Returns:
            T: The scheduler task.
        """
        pass

    @final
    def sl_env(self, args: Union[str, List[str], None] = None) -> dict:
        """Returns the environment variables to use.

        Args:
            args(str | List[str] | None): The optional arguments to use. Defaults to None.

        Returns:
            dict: The environment variables.
        """
        import os
        env = os.environ.copy() # Copy the current environment variables

        if args is None:
            return env.update(self.sl_env_vars) # Add/overwrite with sl env variables
        elif isinstance(args, str):
            arguments = args.split(" ")
        else:
            arguments = args

        found = False

        for index, arg in enumerate(arguments):
            if arg == "--options" and arguments.__len__() > index + 1:
                opts = arguments[index+1]
                if opts.strip().__len__() > 0:
                    temp = self.sl_env_vars.copy() # Copy the current sl env variables
                    temp.update({
                        key: value
                        for opt in opts.split(",")
                        if "=" in opt  # Only process valid key=value pairs
                        for key, value in [opt.split("=")]
                    })
                    env.update(temp)
                else:
                    env.update(self.sl_env_vars) # Add/overwrite with sl env variables
                found = True
                break

        if not found:
            env.update(self.sl_env_vars) # Add/overwrite with sl env variables
        return env
