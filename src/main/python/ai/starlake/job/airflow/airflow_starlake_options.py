import os

from ai.starlake.common import MissingEnvironmentVariable

from ai.starlake.job import StarlakeOptions

from airflow.models import Variable

class AirflowStarlakeOptions(StarlakeOptions):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)

    @classmethod
    def get_context_var(cls, var_name: str, default_value: any=None, options: dict = None, **kwargs):
        """Overrides IStarlakeJob.get_context_var()"""
        if options and options.get(var_name):
            return options.get(var_name)
        elif default_value is not None:
            return default_value
        elif Variable.get(var_name, default_var=None, **kwargs) is not None:
            return Variable.get(var_name)
        elif os.getenv(var_name) is not None:
            return os.getenv(var_name)
        else:
            raise MissingEnvironmentVariable(f"{var_name} does not exist")
