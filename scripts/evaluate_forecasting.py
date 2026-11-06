from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.metrics import confusion_matrix, f1_score, precision_score, recall_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import RobustScaler

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ml-service"))

from app.features import FEATURE_COLUMNS
from app.synthetic_data import (
    DEGRADATION_THRESHOLD_SECONDS,
    FORECAST_HORIZON_MINUTES,
    SEED,
    feature_dataset,
    generate_dataset,
)


def binary_metrics(actual: np.ndarray, predicted: np.ndarray) -> dict:
    tn, fp, fn, tp = confusion_matrix(actual, predicted, labels=[0, 1]).ravel()
    return {
        "precision": float(precision_score(actual, predicted, zero_division=0)),
        "recall": float(recall_score(actual, predicted, zero_division=0)),
        "f1": float(f1_score(actual, predicted, zero_division=0)),
        "false_positive_rate": float(fp / (fp + tn)) if tn + fp else 0.0,
        "true_positive": int(tp),
        "false_positive": int(fp),
        "true_negative": int(tn),
        "false_negative": int(fn),
        "samples": int(len(actual)),
    }


def select_threshold(actual: np.ndarray, probabilities: np.ndarray) -> tuple[float, float]:
    candidates = np.unique(np.concatenate((np.arange(0.1, 0.91, 0.05), np.array([0.5]))))
    scored = [(float(f1_score(actual, probabilities >= threshold, zero_division=0)), float(threshold))
              for threshold in candidates]
    return max(scored, key=lambda item: (item[0], item[1]))


def event_warning_metrics(test: pd.DataFrame, predictions: np.ndarray) -> dict:
    frame = test[["timestamp", "service_name", "anomaly_label"]].copy()
    frame["predicted"] = predictions
    leads: list[float] = []
    missed = 0
    for _, rows in frame.groupby("service_name", sort=False):
        rows = rows.sort_values("timestamp").reset_index(drop=True)
        actual_fault = rows["anomaly_label"].eq(1)
        starts = rows.index[actual_fault & ~actual_fault.shift(1, fill_value=False)]
        for start in starts:
            onset = rows.loc[start, "timestamp"]
            warning_window = rows[
                (rows["timestamp"] >= onset - pd.Timedelta(minutes=FORECAST_HORIZON_MINUTES))
                & (rows["timestamp"] < onset)
            ]
            alerts = warning_window.loc[warning_window["predicted"].eq(1), "timestamp"]
            if alerts.empty:
                missed += 1
            else:
                leads.append((onset - alerts.iloc[0]).total_seconds() / 60)
    return {
        "detected_events": len(leads),
        "missed_events": missed,
        "mean_warning_lead_minutes": float(np.mean(leads)) if leads else None,
        "median_warning_lead_minutes": float(np.median(leads)) if leads else None,
        "event_count": len(leads) + missed,
        "definition": "Ground truth comes from synthetic anomaly labels; first positive prediction in the five-minute pre-onset window, with alerts at/after onset excluded.",
    }


def evaluate() -> dict:
    raw = generate_dataset(SEED)
    validation_start = raw["timestamp"].min() + pd.Timedelta(minutes=500)
    validation_end = validation_start + pd.Timedelta(minutes=20)
    validation_event = (
        raw["service_name"].eq("inventory-api")
        & raw["timestamp"].ge(validation_start)
        & raw["timestamp"].lt(validation_end)
    )
    raw.loc[validation_event, "p95_latency_seconds"] = 1.35
    raw.loc[validation_event, "error_rate"] = 0.08
    raw.loc[validation_event, "anomaly_label"] = 1
    dataset = feature_dataset(raw)
    rows = dataset.loc[
        dataset["sample_observed"] & dataset["degradation_label"].notna()
    ].copy()
    timeline = pd.DatetimeIndex(sorted(rows["timestamp"].dropna().unique()))
    first_boundary = pd.Timestamp(timeline[int(len(timeline) * 0.60)])
    second_boundary = pd.Timestamp(timeline[int(len(timeline) * 0.80)])
    horizon = pd.Timedelta(minutes=FORECAST_HORIZON_MINUTES)
    train = rows.loc[rows["timestamp"] < first_boundary - horizon].copy()
    validation = rows.loc[
        (rows["timestamp"] >= first_boundary)
        & (rows["timestamp"] < second_boundary - horizon)
    ].copy()
    test = rows.loc[rows["timestamp"] >= second_boundary].copy()
    if any(part.empty or part["degradation_label"].nunique() < 2 for part in (train, validation, test)):
        raise RuntimeError("Chronological train/validation/test splits must each contain both classes.")

    model = Pipeline(
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
    model.fit(train[FEATURE_COLUMNS].to_numpy(dtype=float), train["degradation_label"].to_numpy(dtype=int))
    validation_probability = model.predict_proba(validation[FEATURE_COLUMNS].to_numpy(dtype=float))[:, 1]
    validation_f1, selected_threshold = select_threshold(
        validation["degradation_label"].to_numpy(dtype=int),
        validation_probability,
    )
    test_probability = model.predict_proba(test[FEATURE_COLUMNS].to_numpy(dtype=float))[:, 1]
    actual = test["degradation_label"].to_numpy(dtype=int)
    model_prediction = (test_probability >= selected_threshold).astype(int)
    persistence_prediction = (
        test["p95_latency_seconds"].to_numpy(dtype=float) >= DEGRADATION_THRESHOLD_SECONDS
    ).astype(int)
    test = test.assign(_model_prediction=model_prediction, _persistence_prediction=persistence_prediction)
    return {
        "evaluation": "chronological_synthetic_forecast_holdout",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "dataset": {
            "source": "deterministic generated telemetry plus a scripted validation-only inventory degradation",
            "seed": SEED,
            "rows": int(len(dataset)),
            "observed_labeled_rows": int(len(rows)),
            "validation_only_fault": {
                "service": "inventory-api",
                "timestamp_start": validation_start.isoformat(),
                "timestamp_end_exclusive": validation_end.isoformat(),
                "metrics": {"p95_latency_seconds": 1.35, "error_rate": 0.08},
            },
        },
        "target": f"any p95 latency >= {DEGRADATION_THRESHOLD_SECONDS} seconds in the next {FORECAST_HORIZON_MINUTES} one-minute samples",
        "splits": {
            "ordering": "global chronological; train 60%, validation 20%, test 20% by unique timestamp",
            "boundary_gap_minutes": FORECAST_HORIZON_MINUTES,
            "train_rows": int(len(train)),
            "validation_rows": int(len(validation)),
            "test_rows": int(len(test)),
            "train_end": train["timestamp"].max().isoformat(),
            "validation_start": validation["timestamp"].min().isoformat(),
            "validation_end": validation["timestamp"].max().isoformat(),
            "test_start": test["timestamp"].min().isoformat(),
            "test_end": test["timestamp"].max().isoformat(),
            "leakage_controls": [
                "Only current/past features are used by feature_dataset.",
                "Future five-minute labels are excluded from the preceding split at both boundaries.",
                "Imputation/scaling/model fit occur on train only.",
                "Classification threshold is selected on validation only; test is evaluated once.",
            ],
        },
        "model": {
            "algorithm": "RandomForestClassifier; 250 trees; min_samples_leaf=3; class_weight=balanced_subsample",
            "threshold_selected_on_validation": selected_threshold,
            "validation_f1_at_selected_threshold": validation_f1,
            "test": {
                **binary_metrics(actual, model_prediction),
                "event_warning": event_warning_metrics(test, model_prediction),
            },
        },
        "persistence_threshold_baseline": {
            "method": f"Alert when current observed p95 latency >= {DEGRADATION_THRESHOLD_SECONDS} seconds.",
            "test": {
                **binary_metrics(actual, persistence_prediction),
                "event_warning": event_warning_metrics(test, persistence_prediction),
            },
        },
        "limitations": [
            "This is deterministic synthetic telemetry, not a held-out production or incident dataset.",
            "The test period contains few controlled degradation episodes; event-warning estimates are correspondingly unstable.",
            "No probability calibration or prediction interval is evaluated.",
            "If the Random Forest does not outperform the simpler baseline, the simpler method is preferable unless another operational criterion justifies the model.",
        ],
    }


def main() -> None:
    default_path = ROOT / "docs" / "phase-5-results" / (
        f"forecast-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    )
    parser = argparse.ArgumentParser(description="Evaluate forecast model with chronological holdout and persistence baseline.")
    parser.add_argument("--output", type=Path, default=default_path)
    args = parser.parse_args()
    report = evaluate()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
