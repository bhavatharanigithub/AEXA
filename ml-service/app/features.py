from __future__ import annotations

import pandas as pd

METRIC_COLUMNS = ["request_rate", "error_rate", "p95_latency_seconds"]
FEATURE_COLUMNS = [
    *METRIC_COLUMNS,
    "request_rate_lag_1",
    "error_rate_lag_1",
    "p95_latency_seconds_lag_1",
    "request_rate_mean_5",
    "error_rate_mean_5",
    "p95_latency_seconds_mean_5",
    "request_rate_std_5",
    "error_rate_std_5",
    "p95_latency_seconds_std_5",
]
CADENCE = pd.Timedelta(minutes=1)


def prepare_features(observations: list[dict]) -> pd.DataFrame:
    """Sort, align to one-minute UTC bins and use past-only imputation/features."""
    data = pd.DataFrame(observations)
    if data.empty:
        return pd.DataFrame(columns=["timestamp", "service_name", *FEATURE_COLUMNS])
    data["timestamp"] = pd.to_datetime(data["timestamp"], utc=True, errors="raise")
    data = data.sort_values(["service_name", "timestamp"], kind="stable")
    data["timestamp"] = data["timestamp"].dt.floor("min")
    data = data.groupby(["service_name", "timestamp"], as_index=False)[METRIC_COLUMNS].mean()

    prepared: list[pd.DataFrame] = []
    for service_name, group in data.groupby("service_name", sort=True):
        indexed = group.set_index("timestamp").sort_index()
        complete_index = pd.date_range(indexed.index.min(), indexed.index.max(), freq=CADENCE, tz="UTC")
        indexed = indexed.reindex(complete_index)
        indexed.index.name = "timestamp"
        indexed["service_name"] = service_name
        indexed["sample_observed"] = indexed[METRIC_COLUMNS].notna().any(axis=1)
        for column in METRIC_COLUMNS:
            indexed[f"observed_{column}"] = indexed[column]
        indexed[METRIC_COLUMNS] = indexed[METRIC_COLUMNS].ffill(limit=2)
        indexed["imputed_metrics"] = indexed.apply(
            lambda row: [column for column in METRIC_COLUMNS if pd.isna(row[f"observed_{column}"])],
            axis=1,
        )
        for column in METRIC_COLUMNS:
            indexed[f"{column}_lag_1"] = indexed[column].shift(1)
            indexed[f"{column}_mean_5"] = indexed[column].rolling(5, min_periods=2).mean().shift(1)
            indexed[f"{column}_std_5"] = indexed[column].rolling(5, min_periods=2).std(ddof=0).shift(1)
        prepared.append(indexed.reset_index())

    return pd.concat(prepared, ignore_index=True).sort_values(["timestamp", "service_name"], kind="stable")


def observations_to_records(observations: list) -> list[dict]:
    return [
        {
            "timestamp": row.timestamp,
            "service_name": row.service_name,
            "request_rate": row.request_rate,
            "error_rate": row.error_rate,
            "p95_latency_seconds": row.p95_latency_seconds,
        }
        for row in observations
    ]
