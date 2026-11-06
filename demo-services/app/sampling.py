from __future__ import annotations

import threading
from collections.abc import Mapping, Sequence
from typing import Optional

from opentelemetry.context import Context
from opentelemetry.sdk.trace.sampling import SamplingResult, Sampler, TraceIdRatioBased
from opentelemetry.trace import Link, SpanKind, TraceState

MIN_TRACE_SAMPLE_RATE = 0.05
MAX_TRACE_SAMPLE_RATE = 1.0


class DynamicTraceSampler(Sampler):
    def __init__(self, sample_rate: float = MAX_TRACE_SAMPLE_RATE) -> None:
        self._lock = threading.Lock()
        self._sample_rate = self._validate_rate(sample_rate)

    @staticmethod
    def _validate_rate(sample_rate: float) -> float:
        if not MIN_TRACE_SAMPLE_RATE <= sample_rate <= MAX_TRACE_SAMPLE_RATE:
            raise ValueError(
                f"trace sample rate must be between {MIN_TRACE_SAMPLE_RATE} and {MAX_TRACE_SAMPLE_RATE}"
            )
        return float(sample_rate)

    @property
    def sample_rate(self) -> float:
        with self._lock:
            return self._sample_rate

    def set_sample_rate(self, sample_rate: float) -> float:
        validated_rate = self._validate_rate(sample_rate)
        with self._lock:
            previous_rate = self._sample_rate
            self._sample_rate = validated_rate
            return previous_rate

    def should_sample(
        self,
        parent_context: Optional[Context],
        trace_id: int,
        name: str,
        kind: Optional[SpanKind] = None,
        attributes: Optional[Mapping] = None,
        links: Optional[Sequence[Link]] = None,
        trace_state: Optional[TraceState] = None,
    ) -> SamplingResult:
        sampler = TraceIdRatioBased(self.sample_rate)
        return sampler.should_sample(
            parent_context,
            trace_id,
            name,
            kind,
            attributes,
            links,
            trace_state,
        )

    def get_description(self) -> str:
        return f"DynamicTraceSampler{{{self.sample_rate}}}"
