from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ml-service"))

from app.rca import METRICS, rank_root_causes
from app.schemas import (
    LogEvidence,
    MetricObservation,
    RootCauseRequest,
    ServiceMetadata,
    TraceEdge,
)

SCENARIOS = (
    {
        "name": "database_latency",
        "ground_truth": "database",
        "onset_order": ["database", "pricing-api", "inventory-api", "checkout-api"],
        "latency": {
            "database": 1.2,
            "pricing-api": 0.7,
            "inventory-api": 1.0,
            "checkout-api": 1.6,
        },
        "error": {},
        "log": "database query timeout during injected latency",
    },
    {
        "name": "downstream_service_failure",
        "ground_truth": "inventory-api",
        "onset_order": ["inventory-api", "checkout-api"],
        "latency": {"inventory-api": 0.8, "checkout-api": 1.7},
        "error": {"inventory-api": 0.35, "checkout-api": 0.2},
        "log": "inventory request failed: dependency unavailable",
    },
    {
        "name": "cpu_memory_pressure_proxy",
        "ground_truth": "checkout-api",
        "onset_order": ["checkout-api"],
        "latency": {"checkout-api": 1.4},
        "error": {"checkout-api": 0.12},
        "log": "resource pressure increased request latency",
    },
    {
        "name": "network_dependency_latency",
        "ground_truth": "pricing-api",
        "onset_order": ["pricing-api", "inventory-api", "checkout-api"],
        "latency": {"pricing-api": 1.3, "inventory-api": 0.9, "checkout-api": 1.6},
        "error": {},
        "log": "upstream dependency timeout",
    },
)

SERVICES = ("checkout-api", "inventory-api", "pricing-api", "database")
SERVICE_EDGES = (
    ("checkout-api", "inventory-api"),
    ("inventory-api", "pricing-api"),
    ("pricing-api", "database"),
)


def build_request(scenario: dict) -> RootCauseRequest:
    incident = datetime(2026, 6, 1, 12, 0, tzinfo=timezone.utc)
    onset = {
        service: incident - timedelta(minutes=4 - index)
        for index, service in enumerate(scenario["onset_order"])
    }
    observations = []
    for service in SERVICES:
        for offset in (-10, -9, -8, -4, -3, -2, -1, 0):
            timestamp = incident + timedelta(minutes=offset)
            fault_active = service in onset and timestamp >= onset[service]
            observations.append(
                MetricObservation(
                    timestamp=timestamp,
                    service_name=service,
                    request_rate=0.8 if not fault_active else 0.55,
                    error_rate=scenario["error"].get(service, 0.005) if fault_active else 0.005,
                    p95_latency_seconds=scenario["latency"].get(service, 0.2) if fault_active else 0.2,
                )
            )
    trace_edges = [
        TraceEdge(
            timestamp=incident - timedelta(minutes=2),
            parent_service=parent,
            child_service=child,
            trace_id=f"{scenario['name']}-trace-{index}",
            call_count=1,
        )
        for index, (parent, child) in enumerate(SERVICE_EDGES)
    ]
    logs = [
        LogEvidence(
            timestamp=onset[scenario["ground_truth"]],
            service_name=scenario["ground_truth"],
            severity="ERROR",
            message=scenario["log"],
            trace_id=f"{scenario['name']}-trace-root",
        )
    ]
    metadata = [
        ServiceMetadata(service_name=service, namespace="demo", environment="synthetic")
        for service in SERVICES
    ]
    return RootCauseRequest(
        incident_timestamp=incident,
        observations=observations,
        trace_edges=trace_edges,
        logs=logs,
        service_metadata=metadata,
    )


def metric_only_ranking(request: RootCauseRequest) -> list[str]:
    start = request.incident_timestamp - timedelta(minutes=5)
    ranking: list[tuple[float, str]] = []
    for service in sorted({row.service_name for row in request.observations}):
        baseline_rows = [
            row for row in request.observations
            if row.service_name == service and row.timestamp < start
        ]
        event_rows = [
            row for row in request.observations
            if row.service_name == service and start <= row.timestamp <= request.incident_timestamp
        ]
        deviations = []
        for metric in METRICS:
            baseline_values = [getattr(row, metric) for row in baseline_rows if getattr(row, metric) is not None]
            event_values = [getattr(row, metric) for row in event_rows if getattr(row, metric) is not None]
            if len(baseline_values) < 3 or not event_values:
                continue
            baseline = sorted(baseline_values)[len(baseline_values) // 2]
            event = sorted(event_values)[len(event_values) // 2]
            floor = 0.01 if metric == "request_rate" else 0.005
            deviations.append(abs(event - baseline) / max(abs(baseline), floor))
        if deviations:
            ranking.append((max(deviations), service))
    return [service for _, service in sorted(ranking, key=lambda item: (-item[0], item[1]))]


def ranking_metrics(ranks: list[int | None], cutoff: int = 3) -> dict:
    count = len(ranks)
    return {
        "top1_accuracy": sum(rank == 1 for rank in ranks) / count if count else None,
        "recall_at_k": sum(rank is not None and rank <= cutoff for rank in ranks) / count if count else None,
        "mean_reciprocal_rank": sum(1 / rank for rank in ranks if rank is not None) / count if count else None,
        "k": cutoff,
        "scenario_count": count,
    }


def evaluate() -> dict:
    rows = []
    rca_ranks = []
    baseline_ranks = []
    for scenario in SCENARIOS:
        request = build_request(scenario)
        result = rank_root_causes(request)
        rca_order = [candidate["service_name"] for candidate in result["candidates"]]
        baseline_order = metric_only_ranking(request)
        expected = scenario["ground_truth"]
        rca_rank = rca_order.index(expected) + 1 if expected in rca_order else None
        baseline_rank = baseline_order.index(expected) + 1 if expected in baseline_order else None
        rca_ranks.append(rca_rank)
        baseline_ranks.append(baseline_rank)
        rows.append(
            {
                "scenario": scenario["name"],
                "scenario_type": "synthetic controlled telemetry; not live fault injection",
                "ground_truth_cause": expected,
                "rca_ranked_services": rca_order,
                "rca_ground_truth_rank": rca_rank,
                "metric_only_ranked_services": baseline_order,
                "metric_only_ground_truth_rank": baseline_rank,
                "causality_confirmed": result["causality_confirmed"],
            }
        )
    return {
        "evaluation": "synthetic_known_cause_rca",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "method": "Production rank_root_causes implementation; no parameter tuning on these scenarios.",
        "baseline": "Dependency/log/temporal-blind ranking by maximum absolute relative metric deviation.",
        "scenarios": rows,
        "metrics": {
            "implemented_rca": ranking_metrics(rca_ranks),
            "metric_only_baseline": ranking_metrics(baseline_ranks),
        },
        "limitations": [
            "The scenarios are generated telemetry fixtures with explicit labels; they do not inject faults into running services.",
            "The dataset contains four hand-constructed cases and is too small to support generalization or uncertainty claims.",
            "CPU/memory pressure is represented by latency/error telemetry rather than measured resource saturation.",
            "The implemented ranking remains observational and does not establish causality.",
        ],
    }


def main() -> None:
    default_path = ROOT / "docs" / "phase-5-results" / (
        f"rca-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    )
    parser = argparse.ArgumentParser(description="Evaluate RCA ranking on known-cause synthetic scenarios.")
    parser.add_argument("--output", type=Path, default=default_path)
    args = parser.parse_args()
    report = evaluate()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
