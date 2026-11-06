from __future__ import annotations

from collections import defaultdict, deque
from datetime import timedelta

import numpy as np

from app.schemas import RootCauseRequest

ERROR_TERMS = ("error", "exception", "failed", "timeout", "unavailable", "refused", "critical")
METRICS = ("request_rate", "error_rate", "p95_latency_seconds")


def _trace_path(graph: dict[str, list], source: str, target: str) -> list | None:
    if source == target:
        return []
    queue = deque([source])
    visited = {source}
    previous: dict[str, tuple[str, object]] = {}
    while queue:
        service = queue.popleft()
        for edge in graph[service]:
            downstream = edge.child_service
            if downstream not in visited:
                visited.add(downstream)
                previous[downstream] = (service, edge)
                if downstream == target:
                    path = []
                    current = target
                    while current != source:
                        parent, path_edge = previous[current]
                        path.append(path_edge)
                        current = parent
                    return list(reversed(path))
                queue.append(downstream)
    return None


def rank_root_causes(request: RootCauseRequest) -> dict:
    incident_time = request.incident_timestamp
    event_start = incident_time - timedelta(minutes=5)
    baseline_end = event_start
    records = [
        {
            "timestamp": row.timestamp,
            "service_name": row.service_name,
            "request_rate": row.request_rate,
            "error_rate": row.error_rate,
            "p95_latency_seconds": row.p95_latency_seconds,
        }
        for row in request.observations
    ]
    services = sorted({row["service_name"] for row in records})
    service_metadata = {service.service_name: service.model_dump(exclude_none=True) for service in request.service_metadata}
    baseline: dict[str, dict] = {}
    event: dict[str, list] = defaultdict(list)
    for service in services:
        before = [row for row in records if row["service_name"] == service and row["timestamp"] < baseline_end]
        during = [
            row
            for row in records
            if row["service_name"] == service and event_start <= row["timestamp"] <= incident_time
        ]
        metric_baselines = {}
        if len(before) >= 3 and during:
            for metric in METRICS:
                values = [row[metric] for row in before if row[metric] is not None]
                if len(values) >= 3:
                    metric_baselines[metric] = float(np.median(values))
            if metric_baselines:
                baseline[service] = metric_baselines
                event[service] = sorted(during, key=lambda row: row["timestamp"])

    if not baseline:
        return {
            "incident_timestamp": incident_time,
            "candidates": [],
            "causality_confirmed": False,
            "limitations": ["At least three pre-incident samples and an event-window sample per service are required."],
        }

    metric_evidence: dict[str, dict] = {}
    for service, rows in event.items():
        deviations = []
        for metric in METRICS:
            if metric not in baseline[service]:
                continue
            base = baseline[service][metric]
            values = np.asarray([row[metric] for row in rows if row[metric] is not None], dtype=float)
            if values.size == 0:
                continue
            observed = float(np.median(values))
            scale = max(abs(base), 0.01 if metric == "request_rate" else 0.005)
            relative = abs(observed - base) / scale
            deviation_samples = [
                row["timestamp"]
                for row in rows
                if row[metric] is not None and abs(row[metric] - base) / scale >= 0.5
            ]
            if metric == "request_rate":
                direction = "increased" if observed > base else "decreased"
            else:
                direction = "increased" if observed > base else "decreased"
            deviations.append(
                {
                    "feature": metric,
                    "baseline_median": base,
                    "observed_median": observed,
                    "relative_deviation": float(relative),
                    "direction": direction,
                    "first_event_sample": min(deviation_samples) if deviation_samples else None,
                }
            )
        if not deviations:
            continue
        metric_evidence[service] = {
            "severity": max(item["relative_deviation"] for item in deviations),
            "first_sample": min(
                (item["first_event_sample"] for item in deviations if item["first_event_sample"] is not None),
                default=None,
            ),
            "deviations": sorted(deviations, key=lambda item: item["relative_deviation"], reverse=True),
        }

    affected_service = max(metric_evidence, key=lambda service: metric_evidence[service]["severity"])
    graph: dict[str, list] = defaultdict(list)
    for edge in request.trace_edges:
        if event_start <= edge.timestamp <= incident_time:
            graph[edge.parent_service].append(edge)

    logs_by_service: dict[str, list] = defaultdict(list)
    for log in request.logs:
        text = log.message.casefold()
        if event_start <= log.timestamp <= incident_time and (
            log.severity.casefold() in {"error", "fatal", "critical"} or any(term in text for term in ERROR_TERMS)
        ):
            logs_by_service[log.service_name].append(log)

    candidates = []
    for service, evidence in metric_evidence.items():
        trace_path = _trace_path(graph, affected_service, service)
        metric_strength = min(evidence["severity"] / 3.0, 1.0)
        dependency_strength = 1.0 / len(trace_path) if trace_path else 0.0
        log_strength = min(len(logs_by_service[service]) / 3.0, 1.0)
        affected_onset = metric_evidence[affected_service]["first_sample"]
        temporal_strength = float(
            evidence["first_sample"] is not None
            and affected_onset is not None
            and evidence["first_sample"] < affected_onset
        )
        ranking_score = (
            0.55 * metric_strength
            + 0.25 * dependency_strength
            + 0.10 * log_strength
            + 0.10 * temporal_strength
        )
        if ranking_score <= 0:
            continue
        support = [
            {
                "type": "metric_deviation",
                "features": evidence["deviations"],
            }
        ]
        if trace_path:
            path_edges = [
                {
                    "parent_service": edge.parent_service,
                    "child_service": edge.child_service,
                    "trace_id": edge.trace_id,
                    "timestamp": edge.timestamp,
                    "call_count": edge.call_count,
                }
                for edge in trace_path
            ]
            support.append(
                {
                    "type": "trace_dependency",
                    "path_length": len(trace_path),
                    "impacted_service": affected_service,
                    "edges": path_edges,
                }
            )
        if logs_by_service[service]:
            support.append(
                {
                    "type": "correlated_logs",
                    "entries": [
                        {
                            "timestamp": log.timestamp,
                            "severity": log.severity,
                            "message": log.message,
                            "trace_id": log.trace_id,
                        }
                        for log in logs_by_service[service]
                    ],
                }
            )
        if evidence["first_sample"] is not None and affected_onset is not None:
            support.append(
                {
                    "type": "temporal_order",
                    "candidate_metric_deviation_at": evidence["first_sample"],
                    "affected_service_deviation_at": affected_onset,
                    "candidate_precedes_affected": temporal_strength == 1.0,
                }
            )
        candidates.append(
            {
                "service_name": service,
                "service_metadata": service_metadata.get(service),
                "ranking_score": round(ranking_score, 6),
                "score_semantics": "evidence-ranking score; not a probability or causal confidence",
                "rank": 0,
                "evidence": support,
            }
        )

    candidates.sort(key=lambda candidate: (-candidate["ranking_score"], candidate["service_name"]))
    for rank, candidate in enumerate(candidates, start=1):
        candidate["rank"] = rank
    return {
        "incident_timestamp": incident_time,
        "affected_service": affected_service,
        "candidates": candidates,
        "causality_confirmed": False,
        "method": "metric deviation + trace ancestor relation + correlated error-log evidence",
        "limitations": [
            "Candidate ranking is observational and does not establish causality.",
            "Dependency evidence is limited to the supplied trace edges in the five-minute event window.",
            "Metric comparisons use supplied pre-incident medians; unobserved baselines are not inferred.",
        ],
    }
