from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
import httpx
from fastapi.encoders import jsonable_encoder
from fastapi.testclient import TestClient
import pytest

from app.features import FEATURE_COLUMNS, prepare_features
from app.main import app
from app.synthetic_data import feature_dataset, generate_dataset
from scripts.extract_prometheus_history import extract
from scripts.prepare_features import load_history

@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


def metric_rows(count: int = 35) -> list[dict]:
    start = datetime(2026, 2, 1, tzinfo=timezone.utc)
    return [
        {
            "timestamp": start + timedelta(minutes=index),
            "service_name": "checkout-api",
            "request_rate": 0.8 + 0.02 * np.sin(index),
            "error_rate": 0.005,
            "p95_latency_seconds": 0.2 + 0.01 * np.cos(index),
        }
        for index in range(count)
    ]


def test_health_and_metadata_report_trained_phase_three_models(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["phase"] == 3
    assert response.json()["modelStatus"] == "ready"

    metadata = client.get("/model/metadata")
    assert metadata.status_code == 200
    body = metadata.json()
    assert body["evaluation"]["dataset"]["type"] == "deterministic synthetic demonstration telemetry"
    assert body["evaluation"]["anomaly_detection"]["isolation_forest"]["samples"] > 0


def test_feature_engineering_is_ordered_and_uses_past_only_values() -> None:
    rows = metric_rows(12)
    prefix = prepare_features(rows[:9])
    extended = prepare_features(rows)
    column = "p95_latency_seconds_mean_5"
    earlier = extended.loc[extended["timestamp"] <= rows[8]["timestamp"], column].to_numpy()
    assert np.allclose(prefix[column].to_numpy(), earlier, equal_nan=True)
    assert extended["timestamp"].is_monotonic_increasing


def test_feature_preparation_handles_irregular_samples_and_missing_values() -> None:
    rows = metric_rows(12)
    rows = [row for index, row in enumerate(rows) if index != 5]
    rows[7]["p95_latency_seconds"] = None
    frame = prepare_features(rows)
    assert len(frame) == 12
    assert not frame.loc[frame["timestamp"] == metric_rows(1)[0]["timestamp"] + timedelta(minutes=5), "sample_observed"].iloc[0]
    assert frame.loc[frame["timestamp"] == metric_rows(1)[0]["timestamp"], FEATURE_COLUMNS].isna().any(axis=None)
    assert frame.loc[frame["timestamp"] == rows[7]["timestamp"], "p95_latency_seconds"].notna().all()


def test_missing_required_metrics_and_naive_timestamps_are_rejected(client: TestClient) -> None:
    rows = metric_rows(8)
    rows[0].pop("request_rate")
    rows[0].pop("error_rate")
    rows[0].pop("p95_latency_seconds")
    assert client.post(
        "/api/v1/anomalies/detect",
        json=jsonable_encoder({"service_name": "checkout-api", "observations": rows}),
    ).status_code == 422

    rows = metric_rows(8)
    rows[0]["timestamp"] = "2026-02-01T00:00:00"
    response = client.post(
        "/api/v1/anomalies/detect",
        json=jsonable_encoder({"service_name": "checkout-api", "observations": rows}),
    )
    assert response.status_code == 422


def test_known_failure_and_unseen_noisy_input_are_detected_reproducibly(client: TestClient) -> None:
    rows = metric_rows()
    rows[15]["error_rate"] = None
    for index in range(5):
        rows.append(
            {
                "timestamp": rows[-1]["timestamp"] + timedelta(minutes=1),
                "service_name": "checkout-api",
                "request_rate": 0.04,
                "error_rate": 0.85,
                "p95_latency_seconds": 12.0 + index,
            }
        )
    payload = {"service_name": "checkout-api", "observations": rows}
    first = client.post("/api/v1/anomalies/detect", json=jsonable_encoder(payload))
    second = client.post("/api/v1/anomalies/detect", json=jsonable_encoder(payload))
    assert first.status_code == 200
    results = first.json()["results"]
    assert any(result["anomalous"] for result in results[-5:])
    assert [row["anomaly_score"] for row in results] == [
        row["anomaly_score"] for row in second.json()["results"]
    ]
    missing_observation = next(result for result in results if result["timestamp"].startswith("2026-02-01T00:15:"))
    assert missing_observation["observed_metrics"]["error_rate"] is None
    assert "error_rate" in missing_observation["imputed_metrics"]
    explanation = next(result for result in results if result["feature_contributions"])
    assert explanation["explanation_method"].startswith("LIME")
    repeated_explanation = next(
        result for result in second.json()["results"] if result["feature_contributions"]
    )
    assert explanation["feature_contributions"] == repeated_explanation["feature_contributions"]
    assert all(
        item["feature_condition"] in FEATURE_COLUMNS
        for item in explanation["feature_contributions"]
    )


def test_forecast_has_fixed_horizon_threshold_and_explanation(client: TestClient) -> None:
    rows = metric_rows(40)
    response = client.post(
        "/api/v1/forecasts/degradation",
        json=jsonable_encoder({"service_name": "checkout-api", "observations": rows}),
    )
    repeated_response = client.post(
        "/api/v1/forecasts/degradation",
        json=jsonable_encoder({"service_name": "checkout-api", "observations": rows}),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["prediction_horizon_minutes"] == 5
    assert body["operational_probability_threshold"] == 0.5
    assert 0 <= body["degradation_probability"] <= 1
    assert body["feature_contributions"]
    assert body["feature_contributions"] == repeated_response.json()["feature_contributions"]
    assert all(
        item["feature_condition"] in FEATURE_COLUMNS
        for item in body["feature_contributions"]
    )


def test_root_cause_ranking_uses_supplied_trace_log_and_metric_evidence(client: TestClient) -> None:
    rows = []
    start = datetime(2026, 3, 1, tzinfo=timezone.utc)
    for index in range(40):
        for service in ("pricing-api", "inventory-api", "checkout-api"):
            degraded = service == "inventory-api" and index >= 36
            impacted = service == "checkout-api" and index >= 37
            rows.append(
                {
                    "timestamp": start + timedelta(minutes=index),
                    "service_name": service,
                    "request_rate": 0.8,
                    "error_rate": 0.1 if degraded else 0.005,
                    "p95_latency_seconds": 2.0 if degraded else 4.5 if impacted else 0.2,
                }
            )
    incident = start + timedelta(minutes=39)
    body = {
        "incident_timestamp": incident.isoformat(),
        "observations": rows,
        "trace_edges": [
            {
                "timestamp": (start + timedelta(minutes=38)).isoformat(),
                "parent_service": "checkout-api",
                "child_service": "inventory-api",
                "trace_id": "trace-actual-a",
                "call_count": 4,
            },
            {
                "timestamp": (start + timedelta(minutes=38)).isoformat(),
                "parent_service": "inventory-api",
                "child_service": "pricing-api",
                "trace_id": "trace-actual-b",
                "call_count": 4,
            },
        ],
        "logs": [
            {
                "timestamp": (start + timedelta(minutes=38)).isoformat(),
                "service_name": "inventory-api",
                "severity": "ERROR",
                "message": "upstream dependency timeout",
                "trace_id": "trace-actual-b",
            }
        ],
        "service_metadata": [
            {"service_name": service, "namespace": "demo", "environment": "test", "version": "0.1.0"}
            for service in ("pricing-api", "inventory-api", "checkout-api")
        ],
    }
    response = client.post("/api/v1/root-causes/rank", json=jsonable_encoder(body))
    assert response.status_code == 200
    result = response.json()
    assert result["causality_confirmed"] is False
    assert result["candidates"]
    assert result["candidates"][0]["service_name"] == "inventory-api"
    assert any(
        evidence["type"] == "trace_dependency"
        for candidate in result["candidates"]
        for evidence in candidate["evidence"]
    )
    assert any(
        evidence["type"] == "correlated_logs"
        for candidate in result["candidates"]
        for evidence in candidate["evidence"]
    )
    assert all("ranking_score" in candidate for candidate in result["candidates"])


def test_synthetic_labels_and_forecast_target_are_reproducible_and_ordered() -> None:
    first = generate_dataset()
    second = generate_dataset()
    pd.testing.assert_frame_equal(first, second)
    prepared = feature_dataset(first)
    assert prepared["anomaly_label"].sum() > 0
    assert prepared["degradation_label"].sum() > 0
    assert all(group["timestamp"].is_monotonic_increasing for _, group in prepared.groupby("service_name"))


def test_prometheus_history_extraction_merges_real_range_series(tmp_path) -> None:
    calls = []

    def respond(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        feature_index = len(calls)
        metric = {
            1: {"request_rate": 0.8},
            2: {"error_rate": 0.01},
            3: {"p95_latency_seconds": 0.2},
        }[feature_index]
        return httpx.Response(
            200,
            json={
                "status": "success",
                "data": {
                    "result": [
                        {
                            "metric": {"service_name": "checkout-api"},
                            "values": [[1793020800, str(next(iter(metric.values())))]],
                        }
                    ]
                },
            },
        )

    transport = httpx.MockTransport(respond)
    count = extract(
        "http://prometheus.test",
        datetime(2026, 10, 26, tzinfo=timezone.utc),
        datetime(2026, 10, 26, 1, tzinfo=timezone.utc),
        tmp_path / "history.csv",
        transport=transport,
    )
    assert count == 1
    assert len(calls) == 3
    extracted = pd.read_csv(tmp_path / "history.csv")
    assert extracted.loc[0, "service_name"] == "checkout-api"
    assert extracted.loc[0, "request_rate"] == 0.8
    assert extracted.loc[0, "error_rate"] == 0.01
    assert extracted.loc[0, "p95_latency_seconds"] == 0.2


def test_historical_feature_preparation_requires_aware_timestamps(tmp_path) -> None:
    source = tmp_path / "history.csv"
    pd.DataFrame(
        [
            {
                "timestamp": "2026-10-26T12:00:00",
                "service_name": "checkout-api",
                "request_rate": 0.8,
                "error_rate": 0.01,
                "p95_latency_seconds": 0.2,
            }
        ]
    ).to_csv(source, index=False)
    with pytest.raises(ValueError, match="UTC offset"):
        load_history(source)
