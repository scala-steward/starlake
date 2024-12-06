from __future__ import annotations

from abc import abstractmethod

from ai.starlake.common import asQueryParameters, sanitize_id, sl_schedule, sl_schedule_format, is_valid_cron

from typing import Generic, List, Optional, TypeVar

class StarlakeResource():
    def __init__(self, uri: str, parameters: Optional[dict] = None, cron: Optional[str] = None, **kwargs):
        """Initializes a new StarlakeResource instance.

        Args:
            uri (str): The required resource uri.
            parameters (dict, optional): The optional resource parameters. Defaults to None.
        """
        if cron is None:
            if parameters is not None and 'cron' in parameters:
                cron = parameters['cron']
                parameters.pop('cron')
            elif 'params' in kwargs:
                cron = kwargs['params'].get('cron', None)
        if cron:
            if cron.lower().strip() == 'none':
                cron = None
            elif not is_valid_cron(cron):
                raise ValueError(f"Invalid cron expression: {cron} for resource {uri}")
        temp_parameters: dict = dict()
        if parameters is not None:
            temp_parameters.update(parameters)
        if cron is not None:
            schedule_parameter_name = kwargs.get('sl_schedule_parameter_name', 'sl_schedule')
            schedule_parameter_value = sl_schedule(cron=cron, format=kwargs.get('sl_schedule_format', sl_schedule_format))
            temp_parameters[schedule_parameter_name] = schedule_parameter_value
        self._cron = cron
        self._uri = sanitize_id(uri).lower()
        self._parameters = temp_parameters
        self._queryParameters = asQueryParameters(temp_parameters)
        self._url = self.uri + self.queryParameters

    @property
    def cron(self) -> Optional[str]:
        return self._cron

    @property
    def uri(self) -> str:
        return self._uri

    @property
    def parameters(self) -> dict:
        return self._parameters

    @property
    def queryParameters(self) -> str:
        return self._queryParameters

    @property
    def url(self) -> str:
        return self._url

    @staticmethod
    def cron_resources(resources: Optional[List[StarlakeResource]]) -> Optional[List[StarlakeResource]]:
        if resources is not None:
            return [resource for resource in resources if resource.cron is not None]
        else:
            return None

E = TypeVar("E")

class AbstractDataset(Generic[E]):
    @classmethod
    @abstractmethod
    def to_event(cls, resource: StarlakeResource, source: Optional[str] = None, **kwargs) -> E:
        pass
