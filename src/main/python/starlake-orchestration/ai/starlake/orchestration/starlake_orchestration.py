from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, final, Generic, List, Optional, Set, Type, TypeVar, Union

import os
import importlib
import inspect

from ai.starlake.common import sl_cron_start_end_dates, sort_crons_by_frequency, is_valid_cron

from ai.starlake.job import StarlakeSparkConfig, IStarlakeJob, StarlakePreLoadStrategy

from ai.starlake.dataset import StarlakeDataset, AbstractEvent

from ai.starlake.orchestration import StarlakeSchedule, StarlakeDependencies

U = TypeVar("U") # type of DAG

E = TypeVar("E") # type of event

J = TypeVar("J", bound=IStarlakeJob) # type of job

T = TypeVar("T") # type of task

GT = TypeVar("GT") # type of task group

class AbstractDependency(ABC):
    """Abstract interface to define a dependency."""
    def __init__(self, id: str) -> None:
        super().__init__()
        self._id = id

    @property
    def id(self) -> str:
        return self._id

    def __rshift__(self, other: Union[List["AbstractDependency", "AbstractDependency"]]) -> Union[List["AbstractDependency", "AbstractDependency"]]:
        """Add self as an upstream dependency to other.
        Args:
            other (AbstractDependency): the upstream dependency.
        """
        if isinstance(other, list):
            return [TaskGroupContext.current_context().set_dependency(self, dep) for dep in other]
        return TaskGroupContext.current_context().set_dependency(self, other)

    def __lshift__(self, other: Union[List["AbstractDependency", "AbstractDependency"]]) -> Union[List["AbstractDependency", "AbstractDependency"]]:
        """Add other as an upstream dependency to self.
        Args:
            other (AbstractDependency): the upstream dependency.
        """
        if isinstance(other, list):
            return [TaskGroupContext.current_context().set_dependency(dep, self) for dep in other]
        return TaskGroupContext.current_context().set_dependency(other, self)

    def __repr__(self):
        return f"Dependency(id={self.id})"

class AbstractTask(Generic[T], AbstractDependency):
    """Abstract interface to define a task."""
    
    def __init__(self, task_id: str, task: Optional[T] = None) -> None:
        current_context = TaskGroupContext.current_context()
        if not current_context:
            raise ValueError("No task group context found")
        super().__init__(id=task_id)
        self._task_id = task_id
        self._task = task
        # Automatically register the task to the current context
        current_context.add_dependency(self)

    @property
    def task_id(self) -> str:
        return self._task_id

    @property
    def task(self) -> Optional[T]:
        return self._task

    def __repr__(self):
        return f"Task(id={self.task_id})"

class TaskGroupContext(AbstractDependency):
    """Task group context to manage dependencies."""
    _context_stack: List["TaskGroupContext"] = []

    def __init__(self, group_id: str, parent: Optional["TaskGroupContext"] = None):
        super().__init__(id=group_id)
        self._group_id = group_id
        self._dependencies: List[AbstractDependency] = []
        self._dependencies_dict: dict = dict()
        self._upstream_dependencies: dict = dict()
        self._downstream_dependencies: dict = dict()
        self._level: int = len(TaskGroupContext._context_stack) + 1
        current_context = TaskGroupContext.current_context()
        self._parent = current_context if not parent else parent
        if self.parent:
            self.parent.add_dependency(self)

    def __enter__(self):
        TaskGroupContext._context_stack.append(self)
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        TaskGroupContext._context_stack.pop()
        return False

    @property
    def group_id(self) -> str:
        return self._group_id

    @property
    def parent(self) -> Optional["TaskGroupContext"]:
        return self._parent

    @property
    def dependencies(self) -> List[AbstractDependency]:
        return self._dependencies

    @property
    def dependencies_dict(self) -> dict:
        return self._dependencies_dict

    @property
    def upstream_dependencies(self) -> dict:
        return self._upstream_dependencies

    @property
    def downstream_dependencies(self) -> dict:
        return self._downstream_dependencies

    @property
    def level(self) -> int:
        return self._level

    @classmethod
    def current_context(cls) -> Optional["TaskGroupContext"]:
        """Get the current context.
        Returns:
            Optional[TaskGroupContext]: the current context if any, None otherwise.
        """
        return cls._context_stack[-1] if cls._context_stack else None

    def set_dependency(self, upstream_dependency: AbstractDependency, downstream_dependency: AbstractDependency) -> AbstractDependency:
        """Set a dependency between two tasks.
        Args:
            upstream_dependency (AbstractDependency): the upstream dependency.
            downstream_dependency (AbstractDependency): the downstream dependency.
        """
        upstream_dependency_id = upstream_dependency.id
        downstream_dependency_id = downstream_dependency.id
        upstream_deps = self.upstream_dependencies.get(upstream_dependency_id, [])
        if downstream_dependency_id not in upstream_deps:
            upstream_deps.append(downstream_dependency_id)
            self.upstream_dependencies[upstream_dependency_id] = upstream_deps
        downstream_deps = self.downstream_dependencies.get(downstream_dependency_id, [])
        if upstream_dependency_id not in downstream_deps:
            downstream_deps.append(upstream_dependency_id)
            self.downstream_dependencies[downstream_dependency_id] = downstream_deps
        return  downstream_dependency

    @final
    def add_dependency(self, dependency: AbstractDependency) -> AbstractDependency:
        """Add a dependency to the current context.
        Args:
            dependency (AbstractDependency): the dependency to add.
        """
        if dependency.id in self.dependencies_dict.keys():
            raise ValueError(f"Dependency with id '{dependency.id}' already exists within group '{self.group_id}'")
        self.dependencies_dict[dependency.id] = dependency
        self.dependencies.append(dependency)
        return dependency

    @final
    def get_dependency(self, id: str) -> Optional[AbstractDependency]:
        """Get a dependency by its id.
        Args:
            id (str): the dependency id.
        Returns:
            Optional[AbstractDependency]: the dependency if found, None otherwise.
        """
        return self.dependencies_dict.get(id, None)

    @final
    @property
    def roots_keys(self) -> List[str]:
        upstream_keys = set(self.upstream_dependencies.keys())
        downstream_keys = set(self.downstream_dependencies.keys())
        if len(upstream_keys) == 0 and len(downstream_keys) == 0:
            # no dependencies, all tasks are considered roots and leaves
            return list(self.dependencies_dict.keys())
        else:
            return list(upstream_keys - downstream_keys)

    @final
    @property
    def leaves_keys(self) -> List[str]:
        upstream_keys = set(self.upstream_dependencies.keys())
        downstream_keys = set(self.downstream_dependencies.keys())
        if len(upstream_keys) == 0 and len(downstream_keys) == 0:
            # no dependencies, all tasks are considered roots and leaves
            return list(self.dependencies_dict.keys())
        else:
            return list(downstream_keys - upstream_keys)

    @final
    @property
    def roots(self) -> List[AbstractDependency]:
        return [self.get_dependency(id) for id in self.roots_keys]

    @final
    @property
    def leaves(self) -> List[AbstractDependency]:
        return [self.get_dependency(id) for id in self.leaves_keys]

    def __repr__(self):
        return f"TaskGroup(id={self.group_id}, parent={self.parent.id if self.parent else ''}, dependencies=[{','.join([dep.id for dep in self.dependencies])}], roots=[{','.join([key for key in self.roots_keys])}], leaves=[{','.join([key for key in self.get_leaves_keys()])}])"

class AbstractTaskGroup(Generic[GT], TaskGroupContext):
    """Abstract interface to define a task group."""

    def __init__(self, group_id: str, group: Optional[GT] = None, **kwargs):
        super().__init__(group_id)
        self._group = group
        self.params = kwargs

    @property
    def group(self) -> Optional[GT]:
        return self._group

    @final
    def print_group(self, level: int) -> int:
        def printTree(upstream_dependencies, root_key, level=level) -> int:
            print(' ' * level, root_key)
            updated_level = level
            root = self.get_dependency(root_key)
            if isinstance(root, AbstractTaskGroup) and root_key != self.group_id:
                updated_level = root.print_group(level + 1)
            if root_key in upstream_dependencies:
                for key in upstream_dependencies[root_key]:
                    updated_level = updated_level + 1
                    printTree(upstream_dependencies, key, updated_level)
            return updated_level
        upstream_keys = self.upstream_dependencies.keys()
        downstream_keys = self.downstream_dependencies.keys()
        root_keys = upstream_keys - downstream_keys
        if not root_keys and len(upstream_keys) == 0 and len(downstream_keys) == 0:
            root_keys = self.dependencies_dict.keys()
        if root_keys:
            return max([printTree(self.upstream_dependencies, root_key) for root_key in root_keys])
        else:
            return level

class AbstractPipeline(Generic[U, E], AbstractTaskGroup[U], AbstractEvent[E]):
    """Abstract interface to define a pipeline."""
    def __init__(self, job: J, dag: Optional[U] = None, schedule: Optional[StarlakeSchedule] = None, dependencies: Optional[StarlakeDependencies] = None, orchestration: Optional[AbstractOrchestration[U, T, GT, E]] = None, **kwargs) -> None:
        if not schedule and not dependencies:
            raise ValueError("Either a schedule or dependencies must be provided")
        pipeline_id = job.caller_filename.replace(".py", "").replace(".pyc", "").lower()
        if schedule:
            schedule_name = schedule.name
        else:
            schedule_name = None
        if schedule_name:
            pipeline_id = f"{pipeline_id}_{schedule_name}"
        super().__init__(group_id=pipeline_id, group=dag, **kwargs)
        self._orchestration = orchestration
        self._job = job
        self._dag = dag
        self._pipeline_id = pipeline_id
        self._schedule = schedule
        self._schedule_name = schedule_name

        tags = self.get_context_var(var_name='tags', default_value="").split()

        catchup: bool = False

        cron: Optional[str] = None

        load_dependencies: Optional[bool] = None
 
        datasets: Optional[List[StarlakeDataset]] = None

        if schedule is not None:
            cron = schedule.cron
            for domain in schedule.domains:
                tags.append(domain.name)

        elif dependencies is not None:
            cron = job.caller_globals.get('cron', None)

            if cron is not None:
                if cron.lower().strip() == "none":
                    cron = None
                elif not is_valid_cron(cron):
                    raise ValueError(f"Invalid cron expression: {cron}")

            catchup = cron is not None and self.get_context_var(var_name='catchup', default_value='False').lower() == 'true'

            load_dependencies = self.get_context_var(var_name='load_dependencies', default_value='False').lower() == 'true'

            filtered_datasets: Set[str] = set(job.caller_globals.get('filtered_datasets', []))

            computed_schedule = dependencies.get_schedule(
                cron=cron, 
                load_dependencies=load_dependencies,
                filtered_datasets=filtered_datasets,
                sl_schedule_parameter_name=job.sl_schedule_parameter_name,
                sl_schedule_format=job.sl_schedule_format
            )

            if computed_schedule is not None:
                if isinstance(computed_schedule, str):
                    cron = computed_schedule
                elif isinstance(computed_schedule, list):
                    datasets = computed_schedule

        self._tags = tags

        self._cron = cron

        self._catchup = catchup

        self._load_dependencies = load_dependencies

        self._datasets = datasets

        ...

    def __exit__(self, exc_type, exc_value, traceback):
        # call the parent class __exit__ method to clean up tasks and groups
        super().__exit__(exc_type, exc_value, traceback)
        if self.orchestration:
            # register the pipeline to the orchestration
            self.orchestration.pipelines.append(self)
        # print the resulting pipeline
        self.print_pipeline()
        return False

    @property
    def orchestration(self) -> Optional[AbstractOrchestration[J, T, GT, E]]:
        return self._orchestration

    @property
    def dag(self) -> U:
        return self._dag

    @dag.setter
    def dag(self, dag: U) -> None:
        self._dag = dag

    @final
    @property
    def job(self) -> J:
        return self._job

    @final
    @property
    def pipeline_id(self) -> str:
        return self._pipeline_id

    @final
    @property
    def schedule(self) -> Optional[StarlakeSchedule]:
        return self._schedule

    @final
    @property
    def schedule_name(self) -> Optional[str]:
        return self._schedule_name

    @final
    @property
    def cron(self) -> Optional[str]:
        return self._cron

    @property
    def catchup(self) -> bool:
        return self._catchup

    @property
    def tags(self) -> List[str]:
        return self._tags

    def sl_transform_options(self, cron_expr: Optional[str] = None) -> Optional[str]:
        if cron_expr:
            return sl_cron_start_end_dates(cron_expr) #FIXME using execution date from context
        return None

    @final
    @property
    def caller_globals(self) -> dict:
        return self.job.caller_globals

    @final
    @property
    def load_dependencies(self) -> Optional[bool]:
        return self._load_dependencies

    @final
    @property
    def datasets(self) -> Optional[List[StarlakeDataset]]:
        return self._datasets

    @property
    def scheduled_datasets(self) -> dict:
        return {dataset.uri: dataset.cron for dataset in self.datasets or [] if dataset.cron is not None and dataset.uri is not None}

    @final
    @property
    def least_frequent_datasets(self) -> List[StarlakeDataset]:
        least_frequent_datasets: List[StarlakeDataset] = []
        if set(self.scheduled_datasets.values()).__len__() > 1: # we have at least 2 distinct cron expressions
            # we sort the cron datasets by frequency (most frequent first)
            sorted_crons = sort_crons_by_frequency(set(self.scheduled_datasets.values()), period=self.get_context_var(var_name='cron_period_frequency', default_value='week'))
            # we exclude the most frequent cron dataset
            least_frequent_crons = set([expr for expr, _ in sorted_crons[1:sorted_crons.__len__()]])
            for dataset, cron in self.scheduled_datasets.items() :
                # we republish the least frequent scheduled datasets
                if cron in least_frequent_crons:
                    least_frequent_datasets.append(StarlakeDataset(uri=dataset, cron=cron))
        return least_frequent_datasets

    @final
    @property
    def events(self) -> Optional[List[E]]:
        return list(map(lambda dataset: self.to_event(dataset=dataset), self.datasets or []))

    @final
    @property
    def pre_load_strategy(self) -> StarlakePreLoadStrategy:
        return self.job.pre_load_strategy

    @final
    def get_context_var(self, var_name: str, default_value: Any) -> Any:
        return self.job.get_context_var(
            var_name=var_name, 
            default_value=default_value, 
            options=self.job.options
        )

    @final
    def sl_spark_config(self, spark_config_name: str) -> StarlakeSparkConfig:
        return self.job.get_spark_config(
            self.get_context_var('spark_config_name', spark_config_name), 
            **self.caller_globals.get('spark_properties', {})
        )

    @final
    def dummy_task(self, task_id: str, **kwargs) -> AbstractTask[T]:
        pipeline_id = self.pipeline_id
        events = kwargs.get('events', [])
        kwargs.pop('events', None)
        output_datasets = kwargs.get('output_datasets', None)
        if output_datasets:
            events += list(map(lambda dataset: self.to_event(dataset=dataset, source=pipeline_id), output_datasets))
            kwargs.pop('output_datasets', None)
        return self.orchestration.sl_create_task(
            task_id, 
            self.job.dummy_op(
                task_id=task_id, 
                events=events,
                **kwargs
            ),
            self
        )

    def trigger_least_frequent_datasets_task(self, **kwargs) -> Optional[AbstractTask[T]]:
        if not self.least_frequent_datasets:
            return None
        task_id = kwargs.get('task_id', 'trigger_least_frequent_datasets')
        kwargs.pop('task_id', None)
        return self.dummy_task(
            task_id=task_id, 
            output_datasets=self.least_frequent_datasets, 
            **kwargs
        )

    @final
    def start_task(self, **kwargs) -> AbstractTask[T]:
        task_id = kwargs.get('task_id', 'start')
        kwargs.pop('task_id', None)
        return self.orchestration.sl_create_task(
            task_id, 
            self.job.dummy_op(
                task_id=task_id, 
                **kwargs
            ),
            self
        )

    @final
    def pre_tasks(self, *args, **kwargs) -> Optional[AbstractTask[T]]:
        task_id = kwargs.get('task_id', 'pre_tasks')
        kwargs.pop('task_id', None)
        return self.orchestration.sl_create_task(
            task_id, 
            self.job.pre_tasks(task_id=task_id, **kwargs),
            self
        )

    @final
    def sl_pre_load(self, domain: str, tables: Set[str], **kwargs) -> AbstractTask[T]:
        task_id = kwargs.get('task_id', IStarlakeJob.get_sl_pre_load_task_id(domain, self.pre_load_strategy, **kwargs))
        kwargs.pop('task_id', None)
        return self.orchestration.sl_create_task(
            task_id, 
            self.job.sl_pre_load(
                domain=domain, 
                tables=tables, 
                task_id=task_id, 
                **kwargs
            ),
            self
        )

    @final
    def skip_or_start(self, task_id: str, upstream_task: AbstractTask[T], **kwargs) -> Optional[AbstractTask[T]]:
        return self.orchestration.sl_create_task(
            task_id, 
            self.job.skip_or_start_op(
                task_id=task_id, 
                upstream_task=upstream_task.task, 
                **kwargs
            ),
            self
        ) 

    @final
    def sl_import(self, task_id: str, domain: str, tables: set=set(), **kwargs) -> AbstractTask[T]:
        return self.orchestration.sl_create_task(
            task_id,
            self.job.sl_import(
                task_id=task_id,
                domain=domain,
                tables=tables,
                **kwargs
            ),
            self
        )

    @final
    def sl_load(self, task_id: str, domain: str, table: str, spark_config: StarlakeSparkConfig, **kwargs) -> AbstractTask[T]:
        return self.orchestration.sl_create_task(
            task_id, 
            self.job.sl_load(
                task_id=task_id, 
                domain=domain, 
                table=table, 
                spark_config=spark_config, 
                **kwargs
            ),
            self
        )

    @final
    def sl_transform(self, task_id: str, transform_name: str, transform_options: str = None, spark_config: StarlakeSparkConfig = None, **kwargs) -> AbstractTask[T]:
        return self.orchestration.sl_create_task(
            task_id, 
                self.job.sl_transform(
                task_id=task_id, 
                transform_name=transform_name, 
                transform_options=transform_options, 
                spark_config=spark_config, 
                **kwargs
            ),
            self
        )

    @final
    def post_tasks(self, **kwargs) -> Optional[AbstractTask[T]]:
        task_id = kwargs.get('task_id', 'post_tasks')
        kwargs.pop('task_id', None)
        return self.orchestration.sl_create_task(
            task_id, 
            self.job.post_tasks(),
            self
        )

    @final
    def end_task(self, output_datasets: Optional[List[StarlakeDataset]] = None, **kwargs) -> AbstractTask:
        pipeline_id = self.pipeline_id
        events = list(map(lambda dataset: self.to_event(dataset=dataset, source=pipeline_id), output_datasets or []))
        task_id = kwargs.get('task_id', 'end')
        kwargs.pop('task_id', None)
        end = self.orchestration.sl_create_task(
            task_id, 
            self.job.dummy_op(
                task_id=task_id, 
                events=events, 
                **kwargs
            ),
            self
        )
        return end

    @final
    def print_pipeline(self) -> None:
        print(f"Pipeline {self.pipeline_id}:")
        self.print_group(0)

    @final
    def __repr__(self):
        return self.print_pipeline()

class AbstractOrchestration(Generic[U, T, GT, E]):
    def __init__(self, job: J, **kwargs) -> None:
        super().__init__(**kwargs)
        self._job = job
        self._pipelines = []

    def __enter__(self):
        self.pipelines.clear()
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    @classmethod
    def sl_orchestrator(cls) -> str:
        return None

    @property
    def job(self) -> J:
        return self._job

    @property
    def pipelines(self) -> List[AbstractPipeline[U, E]]:
        return self._pipelines

    @abstractmethod
    def sl_create_pipeline(self, schedule: Optional[StarlakeSchedule] = None, dependencies: Optional[StarlakeDependencies] = None, **kwargs) -> AbstractPipeline[U, E]:
        """Create a pipeline."""
        pass

    def sl_create_task(self, task_id: str, task: Optional[T], pipeline: AbstractPipeline[U, E]) -> Optional[AbstractTask[T]]:
        if task is None:
            return None
        return AbstractTask(task_id, task)

    @abstractmethod
    def sl_create_task_group(self, group_id: str, pipeline: AbstractPipeline[U, E], **kwargs) -> AbstractTaskGroup[GT]:
        pass

class OrchestrationFactory:
    _registry = {}

    _initialized = False

    @classmethod
    def register_orchestrations_from_package(cls, package_name: str = "ai.starlake") -> None:
        """
        Dynamically load all classes implementing AbstractOrchestration from the given root package, including sub-packages,
        and register them in the OrchestrationRegistry.
        """
        print(f"Registering orchestrations from package {package_name}")
        package = importlib.import_module(package_name)
        package_path = os.path.dirname(package.__file__)

        for root, dirs, files in os.walk(package_path):
            # Convert the filesystem path back to a Python module path
            relative_path = os.path.relpath(root, package_path)
            if relative_path == ".":
                module_prefix = package_name
            else:
                module_prefix = f"{package_name}.{relative_path.replace(os.path.sep, '.')}"

            for file in files:
                if file.endswith(".py") and file != "__init__.py":
                    module_name = os.path.splitext(file)[0]
                    full_module_name = f"{module_prefix}.{module_name}"

                    try:
                        module = importlib.import_module(full_module_name)
                    except ImportError as e:
                        print(f"Failed to import module {full_module_name}: {e}")
                        continue
                    except AttributeError as e:
                        print(f"Failed to import module {full_module_name}: {e}")
                        continue

                    for name, obj in inspect.getmembers(module, inspect.isclass):
                        if issubclass(obj, AbstractOrchestration) and obj is not AbstractOrchestration:
                            OrchestrationFactory.register_orchestration(obj)

    @classmethod
    def register_orchestration(cls, orchestration_class: Type[AbstractOrchestration]):
        orchestrator = orchestration_class.sl_orchestrator()
        if orchestrator is None:
            raise ValueError("Orchestration must define a valid orchestrator")
        cls._registry.update({orchestrator: orchestration_class})
        print(f"Registered orchestration {orchestration_class} for orchestrator {orchestrator}")

    @classmethod
    def create_orchestration(cls, job: J, **kwargs) -> AbstractOrchestration[U, T, GT, E]:
        if not cls._initialized:
            cls.register_orchestrations_from_package()
            cls._initialized = True
        orchestrator = job.sl_orchestrator()
        if orchestrator not in cls._registry:
            raise ValueError(f"Unknown orchestrator type: {orchestrator}")
        return cls._registry[orchestrator](job, **kwargs)
