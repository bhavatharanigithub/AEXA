from fastapi.testclient import TestClient
import pytest
from opentelemetry.sdk.trace.sampling import Decision

from app.main import app
from app.sampling import DynamicTraceSampler
from app.telemetry import trace_sampler


def test_health_identifies_the_instrumented_service():
    response = TestClient(app).get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.json()["service"] == "demo-service"


def test_controlled_failure_can_be_enabled_and_reset():
    client = TestClient(app)

    enabled = client.put("/demo/fault", json={"mode": "error"})
    failed_work = client.get("/work")
    reset = client.put("/demo/fault", json={"mode": "none"})
    recovered_work = client.get("/work")

    assert enabled.status_code == 200
    assert failed_work.status_code == 503
    assert reset.status_code == 200
    assert recovered_work.status_code == 200


def test_resource_pressure_mode_applies_a_bounded_workload_and_resets():
    client = TestClient(app)
    try:
        enabled = client.put("/demo/fault", json={"mode": "resource"})
        pressured_work = client.get("/work")

        assert enabled.status_code == 200
        assert pressured_work.status_code == 200
        assert pressured_work.json()["service"] == "demo-service"
    finally:
        client.put("/demo/fault", json={"mode": "none"})


def test_trace_sampler_is_deterministic_and_respects_rate_bounds():
    sampler = DynamicTraceSampler(0.25)
    first = [
        sampler.should_sample(None, trace_id * ((1 << 64) // 10_000), "sample-test").decision
        for trace_id in range(10_000)
    ]
    second = [
        sampler.should_sample(None, trace_id * ((1 << 64) // 10_000), "sample-test").decision
        for trace_id in range(10_000)
    ]

    assert first == second
    sampled = sum(decision is Decision.RECORD_AND_SAMPLE for decision in first)
    assert abs(sampled - 2500) <= 1
    with pytest.raises(ValueError):
        sampler.set_sample_rate(0.01)
    with pytest.raises(ValueError):
        sampler.set_sample_rate(1.01)


def test_sampling_control_endpoint_updates_and_validates_runtime_sampler():
    client = TestClient(app)
    try:
        updated = client.put(
            "/internal/telemetry/sampling",
            json={"trace_sample_rate": 0.25},
        )
        assert updated.status_code == 200
        assert updated.json()["trace_sample_rate"] == 0.25

        invalid = client.put(
            "/internal/telemetry/sampling",
            json={"trace_sample_rate": 0.01},
        )
        assert invalid.status_code == 422
    finally:
        trace_sampler.set_sample_rate(1.0)
