import logging
import os
import socket
from opentelemetry import _logs, metrics, trace
from opentelemetry.exporter.otlp.proto.grpc._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.sampling import ParentBased
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from app.sampling import DynamicTraceSampler, MAX_TRACE_SAMPLE_RATE

SERVICE_NAME = os.getenv("SERVICE_NAME", "demo-service")
OTLP_ENDPOINT = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317")
EXPORT_TELEMETRY = os.getenv("AEXA_DISABLE_OTEL_EXPORT", "false").lower() != "true"

resource = Resource.create(
    {
        "service.name": SERVICE_NAME,
        "service.namespace": "demo",
        "service.version": "0.2.0",
        "service.instance.id": f"{socket.gethostname()}-{os.getpid()}",
        "deployment.environment": os.getenv("DEPLOYMENT_ENVIRONMENT", "local"),
    }
)

trace_sampler = DynamicTraceSampler(MAX_TRACE_SAMPLE_RATE)
tracer_provider = TracerProvider(
    resource=resource,
    sampler=ParentBased(root=trace_sampler),
)
if EXPORT_TELEMETRY:
    tracer_provider.add_span_processor(
        BatchSpanProcessor(OTLPSpanExporter(endpoint=OTLP_ENDPOINT, insecure=True))
    )
trace.set_tracer_provider(tracer_provider)

metric_readers = []
if EXPORT_TELEMETRY:
    metric_readers.append(
        PeriodicExportingMetricReader(
            OTLPMetricExporter(endpoint=OTLP_ENDPOINT, insecure=True),
            export_interval_millis=10_000,
        )
    )
meter_provider = MeterProvider(resource=resource, metric_readers=metric_readers)
metrics.set_meter_provider(meter_provider)

logger_provider = LoggerProvider(resource=resource)
if EXPORT_TELEMETRY:
    logger_provider.add_log_record_processor(
        BatchLogRecordProcessor(OTLPLogExporter(endpoint=OTLP_ENDPOINT, insecure=True))
    )
_logs.set_logger_provider(logger_provider)

logger = logging.getLogger("aexa.demo")
logger.setLevel(logging.INFO)
logger.addHandler(LoggingHandler(level=logging.INFO, logger_provider=logger_provider))
logger.propagate = False

meter = metrics.get_meter("aexa.demo")
request_counter = meter.create_counter(
    "aexa_service_requests",
    description="Completed HTTP requests handled by a demo service",
    unit="{request}",
)
request_duration = meter.create_histogram(
    "aexa_request_duration_seconds",
    description="HTTP request duration in seconds",
    unit="s",
)
