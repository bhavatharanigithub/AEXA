from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd

from app.features import FEATURE_COLUMNS, prepare_features

SEED = 20261026
SERVICES = ("checkout-api", "inventory-api", "pricing-api")
ANOMALY_INTERVALS = ((170, 190), (390, 410), (610, 630))
DEGRADATION_THRESHOLD_SECONDS = 1.0
FORECAST_HORIZON_MINUTES = 5


def generate_dataset(seed: int = SEED) -> pd.DataFrame:
    """Build deterministic demo telemetry with labeled, temporally bounded failures."""
    rng = np.random.default_rng(seed)
    start = pd.Timestamp("2026-01-01T00:00:00Z")
    frames: list[pd.DataFrame] = []
    for service_index, service_name in enumerate(SERVICES):
        size = 720
        minute = np.arange(size)
        request_rate = (
            0.8
            + 0.08 * np.sin(minute / 19 + service_index)
            + 0.04 * np.sin(minute / 7)
            + rng.normal(0, 0.025, size)
        )
        error_rate = np.clip(0.006 + rng.normal(0, 0.003, size), 0, 0.025)
        latency = 0.18 + service_index * 0.025 + 0.025 * np.sin(minute / 13) + rng.normal(0, 0.012, size)
        anomaly = np.zeros(size, dtype=bool)
        for interval_index, (left, right) in enumerate(ANOMALY_INTERVALS):
            if (service_index + interval_index) % 2 == 0:
                anomaly[left:right] = True
                request_rate[left:right] *= 0.55
                error_rate[left:right] = np.clip(0.13 + rng.normal(0, 0.025, right - left), 0, 0.3)
                latency[left:right] = 1.35 + service_index * 0.15 + rng.normal(0, 0.18, right - left)
        frame = pd.DataFrame(
            {
                "timestamp": start + pd.to_timedelta(minute, unit="min"),
                "service_name": service_name,
                "request_rate": np.maximum(request_rate, 0),
                "error_rate": error_rate,
                "p95_latency_seconds": np.maximum(latency, 0),
                "anomaly_label": anomaly.astype(int),
            }
        )
        frames.append(frame)

    dataset = pd.concat(frames, ignore_index=True).sort_values(["timestamp", "service_name"])
    # Simulate irregular sampling by removing a deterministic subset of observations.
    missing_indices = rng.choice(dataset.index.to_numpy(), size=18, replace=False)
    dataset = dataset.drop(index=missing_indices).reset_index(drop=True)
    # Missing sensor values remain missing until the past-only feature preparation step.
    value_indices = rng.choice(dataset.index.to_numpy(), size=36, replace=False)
    dataset.loc[value_indices, "p95_latency_seconds"] = np.nan
    return dataset


def add_forecast_target(dataset: pd.DataFrame) -> pd.DataFrame:
    ordered = dataset.sort_values(["service_name", "timestamp"], kind="stable").copy()
    future_peak = ordered.groupby("service_name", sort=False)["p95_latency_seconds"].transform(
        lambda values: values.shift(-1)
        .rolling(FORECAST_HORIZON_MINUTES, min_periods=FORECAST_HORIZON_MINUTES)
        .max()
        .shift(-(FORECAST_HORIZON_MINUTES - 1))
    )
    ordered["degradation_label"] = (
        future_peak.ge(DEGRADATION_THRESHOLD_SECONDS).fillna(False).astype(int)
    )
    ordered.loc[future_peak.isna(), "degradation_label"] = np.nan
    return ordered


def feature_dataset(dataset: pd.DataFrame) -> pd.DataFrame:
    features = prepare_features(dataset.to_dict(orient="records"))
    labels = dataset[["timestamp", "service_name", "anomaly_label"]].copy()
    labels["timestamp"] = pd.to_datetime(labels["timestamp"], utc=True).dt.floor("min")
    labels = labels.groupby(["service_name", "timestamp"], as_index=False)["anomaly_label"].max()
    result = features.merge(labels, on=["service_name", "timestamp"], how="left")
    result["anomaly_label"] = result["anomaly_label"].fillna(0).astype(int)
    return add_forecast_target(result)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_metadata(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
