from __future__ import annotations

import json
import os
import platform
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import IsolationForest, RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.metrics import confusion_matrix, f1_score, precision_score, recall_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import RobustScaler

from app.features import FEATURE_COLUMNS
from app.synthetic_data import (
    DEGRADATION_THRESHOLD_SECONDS,
    FORECAST_HORIZON_MINUTES,
    SEED,
    feature_dataset,
    generate_dataset,
    sha256_file,
    write_metadata,
)

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = Path(os.environ.get("AEXA_MODEL_DIR", ROOT / "artifacts"))
MODEL_VERSION = "aexa-phase3-2026.10.26"


def binary_metrics(actual: np.ndarray, predicted: np.ndarray) -> dict:
    tn, fp, fn, tp = confusion_matrix(actual, predicted, labels=[0, 1]).ravel()
    return {
        "precision": float(precision_score(actual, predicted, zero_division=0)),
        "recall": float(recall_score(actual, predicted, zero_division=0)),
        "f1": float(f1_score(actual, predicted, zero_division=0)),
        "false_positive_rate": float(fp / (fp + tn)) if fp + tn else 0.0,
        "true_positives": int(tp),
        "false_positives": int(fp),
        "true_negatives": int(tn),
        "false_negatives": int(fn),
        "samples": int(len(actual)),
    }


def detection_delay_minutes(test: pd.DataFrame, predictions: np.ndarray) -> dict:
    evaluated = test[["timestamp", "service_name", "anomaly_label"]].copy()
    evaluated["predicted"] = predictions
    delays: list[float] = []
    misses = 0
    for service_name, rows in evaluated.groupby("service_name"):
        rows = rows.sort_values("timestamp").reset_index(drop=True)
        active = rows["anomaly_label"].eq(1)
        starts = rows.index[active & ~active.shift(1, fill_value=False)]
        for start in starts:
            end = start
            while end + 1 < len(rows) and rows.loc[end + 1, "anomaly_label"] == 1:
                end += 1
            hits = rows.loc[start:end]
            detected = hits.loc[hits["predicted"].eq(1), "timestamp"]
            if detected.empty:
                misses += 1
            else:
                delays.append((detected.iloc[0] - rows.loc[start, "timestamp"]).total_seconds() / 60)
    return {
        "mean_minutes": float(np.mean(delays)) if delays else None,
        "median_minutes": float(np.median(delays)) if delays else None,
        "detected_incidents": len(delays),
        "missed_incidents": misses,
        "unit": "minutes",
    }


def statistical_baseline(test: pd.DataFrame) -> np.ndarray:
    flags = pd.Series(False, index=test.index)
    config = {
        "request_rate": (4.0, 0.08, True),
        "error_rate": (4.0, 0.025, False),
        "p95_latency_seconds": (4.0, 0.15, False),
    }
    for _, group in test.groupby("service_name", sort=False):
        group = group.sort_values("timestamp")
        for column, (multiplier, floor, lower_is_abnormal) in config.items():
            past = group[column].shift(1).rolling(30, min_periods=10)
            median = past.median()
            deviation = (group[column].shift(1) - median).abs().rolling(30, min_periods=10).median()
            scale = (1.4826 * deviation * multiplier).clip(lower=floor)
            current = group[column]
            high = current > median + scale
            low = (current < median - scale) if lower_is_abnormal else pd.Series(False, index=group.index)
            flags.loc[group.index] |= high.fillna(False) | low.fillna(False)
    return flags.to_numpy(dtype=int)


def train_and_evaluate(output_dir: Path = ARTIFACTS) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    raw = generate_dataset(SEED)
    raw_path = output_dir / "prepared_telemetry.csv"
    raw.to_csv(
        raw_path,
        index=False,
        date_format="%Y-%m-%dT%H:%M:%S%z",
        float_format="%.9f",
        lineterminator="\n",
    )
    dataset = feature_dataset(raw)
    timeline = sorted(dataset["timestamp"].dropna().unique())
    cutoff = timeline[int(len(timeline) * 0.70)]
    cutoff = pd.Timestamp(cutoff)
    horizon = pd.Timedelta(minutes=FORECAST_HORIZON_MINUTES)
    normal_train = dataset.loc[
        (dataset["timestamp"] < cutoff)
        & dataset["sample_observed"]
        & dataset["anomaly_label"].eq(0)
        & dataset[FEATURE_COLUMNS].notna().any(axis=1)
    ].copy()
    anomaly_test = dataset.loc[
        (dataset["timestamp"] >= cutoff)
        & dataset["sample_observed"]
        & dataset["anomaly_label"].notna()
        & dataset[FEATURE_COLUMNS].notna().any(axis=1)
    ].copy()
    if normal_train.empty or anomaly_test.empty or anomaly_test["anomaly_label"].nunique() < 2:
        raise RuntimeError("Time-ordered anomaly split must contain normal training and both test classes")

    anomaly_pipeline = Pipeline(
        [
            ("imputer", SimpleImputer(strategy="median")),
            ("scaler", RobustScaler()),
            (
                "detector",
                IsolationForest(
                    n_estimators=250,
                    contamination=0.03,
                    random_state=SEED,
                    n_jobs=1,
                ),
            ),
        ]
    )
    train_matrix = normal_train[FEATURE_COLUMNS].to_numpy(dtype=float)
    anomaly_pipeline.fit(train_matrix)
    train_scores = anomaly_pipeline.decision_function(train_matrix)
    score_threshold = float(np.quantile(train_scores, 0.05))
    anomaly_scores = anomaly_pipeline.decision_function(anomaly_test[FEATURE_COLUMNS].to_numpy(dtype=float))
    isolation_predictions = (anomaly_scores < score_threshold).astype(int)
    baseline_predictions = statistical_baseline(anomaly_test)

    forecast_rows = dataset.loc[
        dataset["sample_observed"] & dataset["degradation_label"].notna()
    ].copy()
    forecast_train = forecast_rows.loc[forecast_rows["timestamp"] < cutoff - horizon]
    forecast_test = forecast_rows.loc[forecast_rows["timestamp"] >= cutoff]
    if forecast_train.empty or forecast_test.empty or forecast_train["degradation_label"].nunique() < 2:
        raise RuntimeError("Time-ordered forecast split must contain both training target classes")
    forecast_pipeline = Pipeline(
        [
            ("imputer", SimpleImputer(strategy="median")),
            ("scaler", RobustScaler()),
            (
                "classifier",
                RandomForestClassifier(
                    n_estimators=250,
                    min_samples_leaf=3,
                    class_weight="balanced_subsample",
                    random_state=SEED,
                    n_jobs=1,
                ),
            ),
        ]
    )
    forecast_train_matrix = forecast_train[FEATURE_COLUMNS].to_numpy(dtype=float)
    forecast_pipeline.fit(
        forecast_train_matrix,
        forecast_train["degradation_label"].to_numpy(dtype=int),
    )
    forecast_probabilities = forecast_pipeline.predict_proba(
        forecast_test[FEATURE_COLUMNS].to_numpy(dtype=float)
    )[:, 1]
    forecast_predictions = (forecast_probabilities >= 0.5).astype(int)

    anomaly_model_path = output_dir / "anomaly_model.joblib"
    forecast_model_path = output_dir / "forecast_model.joblib"
    joblib.dump(
        {
            "pipeline": anomaly_pipeline,
            "score_threshold": score_threshold,
            "feature_columns": FEATURE_COLUMNS,
            "training_data": anomaly_pipeline.named_steps["imputer"].transform(train_matrix),
        },
        anomaly_model_path,
    )
    joblib.dump(
        {
            "pipeline": forecast_pipeline,
            "feature_columns": FEATURE_COLUMNS,
            "training_data": forecast_pipeline.named_steps["imputer"].transform(forecast_train_matrix),
        },
        forecast_model_path,
    )
    evaluation = {
        "model_version": MODEL_VERSION,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "dataset": {
            "type": "deterministic synthetic demonstration telemetry",
            "seed": SEED,
            "sha256": sha256_file(raw_path),
            "hash_precision": "timestamps in UTC; numeric values serialized to 9 decimal places",
            "rows": int(len(raw)),
            "services": sorted(raw["service_name"].unique().tolist()),
            "time_start": raw["timestamp"].min().isoformat(),
            "time_end": raw["timestamp"].max().isoformat(),
            "missing_latency_values": int(raw["p95_latency_seconds"].isna().sum()),
            "irregular_samples_removed": 18,
        },
        "validation": {
            "method": "global chronological holdout; earliest 70% train, latest 30% test",
            "cutoff": cutoff.isoformat(),
            "forecast_boundary_gap_minutes": FORECAST_HORIZON_MINUTES,
            "anomaly_training_rows": int(len(normal_train)),
            "anomaly_test_rows": int(len(anomaly_test)),
            "forecast_training_rows": int(len(forecast_train)),
            "forecast_test_rows": int(len(forecast_test)),
            "leakage_controls": [
                "features use only the current and earlier measurements",
                "missing values are forward-filled for at most two minutes; remaining values use training-fitted medians",
                "anomaly detector is fitted on normal rows from the training period only",
                "forecast targets look forward five minutes; the training boundary excludes the five-minute label overlap",
            ],
        },
        "anomaly_detection": {
            "statistical_baseline": {
                **binary_metrics(anomaly_test["anomaly_label"].to_numpy(dtype=int), baseline_predictions),
                "method": "past-only rolling median and MAD with per-metric absolute floors",
                "detection_delay": detection_delay_minutes(anomaly_test, baseline_predictions),
            },
            "isolation_forest": {
                **binary_metrics(anomaly_test["anomaly_label"].to_numpy(dtype=int), isolation_predictions),
                "method": "RobustScaler + IsolationForest; threshold is 5th percentile of normal training scores",
                "score_threshold": score_threshold,
                "detection_delay": detection_delay_minutes(anomaly_test, isolation_predictions),
            },
        },
        "predictive_monitoring": {
            "target": "any p95 latency >= 1.0 second in the next five one-minute samples",
            "horizon_minutes": FORECAST_HORIZON_MINUTES,
            "operational_probability_threshold": 0.5,
            "model": "class-weighted RandomForestClassifier",
            "random_forest": binary_metrics(forecast_test["degradation_label"].to_numpy(dtype=int), forecast_predictions),
            "persistence_baseline": binary_metrics(
                forecast_test["degradation_label"].to_numpy(dtype=int),
                (forecast_test["p95_latency_seconds"].to_numpy(dtype=float) >= DEGRADATION_THRESHOLD_SECONDS).astype(int),
            ),
        },
        "limitations": [
            "Evaluation is on deterministic synthetic demo data, not production incidents.",
            "Synthetic anomaly intervals and labels do not establish real-world generalization.",
            "The Isolation Forest ranking score is not a calibrated probability.",
            "Forecast probabilities and local LIME contributions depend on this training distribution.",
        ],
    }
    write_metadata(output_dir / "evaluation.json", evaluation)
    metadata = {
        "model_version": MODEL_VERSION,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "training_source": "synthetic demo telemetry; not production data",
        "seed": SEED,
        "python": platform.python_version(),
        "scikit_learn": sklearn.__version__,
        "feature_columns": FEATURE_COLUMNS,
        "anomaly_model": anomaly_model_path.name,
        "forecast_model": forecast_model_path.name,
        "evaluation_file": "evaluation.json",
        "dataset_sha256": evaluation["dataset"]["sha256"],
        "isolation_score_threshold": score_threshold,
        "forecast_horizon_minutes": FORECAST_HORIZON_MINUTES,
        "forecast_degradation_latency_threshold_seconds": DEGRADATION_THRESHOLD_SECONDS,
        "forecast_operational_probability_threshold": 0.5,
    }
    write_metadata(output_dir / "model_metadata.json", metadata)
    return evaluation


def load_artifacts(artifact_dir: Path = ARTIFACTS) -> dict:
    anomaly_path = artifact_dir / "anomaly_model.joblib"
    forecast_path = artifact_dir / "forecast_model.joblib"
    metadata_path = artifact_dir / "model_metadata.json"
    evaluation_path = artifact_dir / "evaluation.json"
    required = [anomaly_path, forecast_path, metadata_path, evaluation_path]
    missing = [path.name for path in required if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"Trained model artifacts are missing: {', '.join(missing)}")
    return {
        "anomaly": joblib.load(anomaly_path),
        "forecast": joblib.load(forecast_path),
        "metadata": json.loads(metadata_path.read_text(encoding="utf-8")),
        "evaluation": json.loads(evaluation_path.read_text(encoding="utf-8")),
    }
