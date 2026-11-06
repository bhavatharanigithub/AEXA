from __future__ import annotations

import logging
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from lime.lime_tabular import LimeTabularExplainer

from app.features import FEATURE_COLUMNS, observations_to_records, prepare_features
from app.rca import rank_root_causes
from app.schemas import (
    AnomalyRequest,
    AnomalyResponse,
    ForecastRequest,
    ForecastResponse,
    RootCauseRequest,
    RootCauseResponse,
)
from app.synthetic_data import DEGRADATION_THRESHOLD_SECONDS, FORECAST_HORIZON_MINUTES
from app.training import ARTIFACTS, load_artifacts

logger = logging.getLogger("aexa.ml")
runtime: dict = {}


@asynccontextmanager
async def lifespan(_: FastAPI):
    anomaly_path = ARTIFACTS / "anomaly_model.joblib"
    if anomaly_path.is_file():
        runtime.update(load_artifacts())
        logger.info("Loaded ML artifacts version=%s", runtime["metadata"]["model_version"])
    else:
        logger.warning("ML model artifacts are not trained; analysis endpoints will report unavailable")
    yield
    runtime.clear()


app = FastAPI(title="Aexa AI observability service", version="0.3.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


def _models() -> dict:
    if not runtime:
        raise HTTPException(
            status_code=503,
            detail="Trained model artifacts are unavailable; run python scripts/train_models.py",
        )
    return runtime


def _features(observations: list) -> tuple:
    records = observations_to_records(observations)
    frame = prepare_features(records)
    if frame.empty:
        raise HTTPException(status_code=422, detail="No usable timestamped telemetry features")
    return frame


def _feature_values(row) -> dict:
    return {
        name: (float(row[name]) if np.isfinite(row[name]) else None)
        for name in FEATURE_COLUMNS
    }


def _observed_metrics(row) -> dict:
    return {
        name: (float(row[f"observed_{name}"]) if np.isfinite(row[f"observed_{name}"]) else None)
        for name in ("request_rate", "error_rate", "p95_latency_seconds")
    }


def _explain(row: np.ndarray, predictor, training_data: np.ndarray) -> list[dict]:
    lime = LimeTabularExplainer(
        training_data=training_data,
        feature_names=FEATURE_COLUMNS,
        mode="regression",
        random_state=20261026,
        discretize_continuous=False,
    )
    explanation = lime.explain_instance(
        row,
        predictor,
        num_features=min(8, len(FEATURE_COLUMNS)),
        num_samples=400,
    )
    return [
        {
            "feature_condition": feature,
            "contribution": float(contribution),
            "method": "LIME local surrogate",
        }
        for feature, contribution in explanation.as_list()
    ]


@app.get("/health")
def health() -> dict[str, object]:
    model_status = "ready" if runtime else "unavailable"
    if not runtime:
        raise HTTPException(status_code=503, detail="Trained model artifacts are unavailable")
    return {
        "status": "UP",
        "service": "aexa-ml",
        "modelStatus": model_status,
        "modelVersion": runtime.get("metadata", {}).get("model_version"),
        "phase": 3,
    }


@app.get("/model/metadata")
def model_metadata() -> dict:
    models = _models()
    return {
        "metadata": models["metadata"],
        "evaluation": models["evaluation"],
    }


@app.post("/api/v1/anomalies/detect", response_model=AnomalyResponse)
def detect_anomalies(request: AnomalyRequest) -> dict:
    models = _models()
    frame = _features(request.observations)
    model = models["anomaly"]
    matrix = frame[FEATURE_COLUMNS].to_numpy(dtype=float)
    decision_values = model["pipeline"].decision_function(matrix)
    threshold = float(model["score_threshold"])
    abnormal = decision_values < threshold
    results = []
    explained = False
    for index in range(len(frame)):
        row = frame.iloc[index]
        if not bool(row["sample_observed"]):
            continue
        decision_value = float(decision_values[index])
        item = {
            "timestamp": row["timestamp"].to_pydatetime(),
            "service_name": request.service_name,
            "anomalous": bool(abnormal[index]),
            "anomaly_score": -decision_value,
            "score_semantics": "negative Isolation Forest decision_function; higher values are more anomalous, not a probability",
            "threshold": -threshold,
            "detection_method": "robust-scaled-isolation-forest",
            "features": _feature_values(row),
            "observed_metrics": _observed_metrics(row),
            "imputed_metrics": row["imputed_metrics"],
            "model_output": {"decision_function": decision_value},
            "feature_contributions": [],
            "explanation_method": None,
        }
        if abnormal[index] and not explained:
            explain_row = model["pipeline"].named_steps["imputer"].transform(matrix[index : index + 1])[0]
            item["feature_contributions"] = _explain(
                explain_row,
                lambda values: model["pipeline"].decision_function(np.asarray(values, dtype=float)),
                model["training_data"],
            )
            item["explanation_method"] = "LIME local surrogate of Isolation Forest normality score"
            explained = True
        results.append(item)
    return {
        "model_version": models["metadata"]["model_version"],
        "service_name": request.service_name,
        "results": results,
        "limitations": [
            "Isolation Forest scores are not probabilities.",
            "LIME contributions locally approximate the model score and are not causal explanations.",
            "Training and evaluation use synthetic demonstration telemetry.",
        ],
    }


@app.post("/api/v1/forecasts/degradation", response_model=ForecastResponse)
def forecast_degradation(request: ForecastRequest) -> dict:
    models = _models()
    frame = _features(request.observations)
    row = frame.iloc[-1]
    matrix = frame[FEATURE_COLUMNS].to_numpy(dtype=float)
    model = models["forecast"]
    classifier = model["pipeline"]
    probability = float(classifier.predict_proba(matrix[-1:])[0, 1])
    predicted = probability >= 0.5
    explain_row = classifier.named_steps["imputer"].transform(matrix[-1:])[0]
    contributions = _explain(
        explain_row,
        lambda values: classifier.predict_proba(np.asarray(values, dtype=float))[:, 1],
        model["training_data"],
    )
    return {
        "model_version": models["metadata"]["model_version"],
        "service_name": request.service_name,
        "as_of": row["timestamp"].to_pydatetime(),
        "prediction_horizon_minutes": FORECAST_HORIZON_MINUTES,
        "target": f"p95 latency >= {DEGRADATION_THRESHOLD_SECONDS:.1f} seconds within the next five one-minute samples",
        "degradation_probability": probability,
        "operational_probability_threshold": 0.5,
        "degradation_predicted": predicted,
        "features": _feature_values(row),
        "observed_metrics": _observed_metrics(row),
        "imputed_metrics": row["imputed_metrics"],
        "feature_contributions": contributions,
        "explanation_method": "LIME local surrogate of forecast probability",
        "limitations": [
            "Forecast probabilities are model outputs, not calibrated guarantees.",
            "The threshold and horizon were evaluated on synthetic demonstration telemetry.",
        ],
    }


@app.post("/api/v1/root-causes/rank", response_model=RootCauseResponse)
def root_cause(request: RootCauseRequest) -> dict:
    response = rank_root_causes(request)
    response["model_version"] = runtime.get("metadata", {}).get("model_version")
    return response
