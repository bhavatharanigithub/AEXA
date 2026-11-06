import argparse
import hashlib
import json
import random
from pathlib import Path


STEP_SECONDS = 15
STEPS = 1200
REQUESTS_PER_STEP = 600
FIXED_SAMPLE_RATE = 0.5
INCIDENTS = [(120, 136), (320, 336), (520, 536), (720, 736), (920, 936), (1120, 1136)]
STALE_STEPS = {80, 81, 82, 600, 601, 602, 1000, 1001, 1002}
MAX_CHANGES_PER_HOUR = 8
COOLDOWN_SECONDS = 60


def in_incident(step: int) -> bool:
    return any(start <= step < end for start, end in INCIDENTS)


def risk_band(error_rate: float, latency_seconds: float, forecast_probability: float) -> int:
    if error_rate >= 0.05 or latency_seconds >= 1.0 or forecast_probability >= 0.75:
        return 2
    if error_rate >= 0.01 or latency_seconds >= 0.5 or forecast_probability >= 0.4:
        return 1
    return 0


def next_policy_rate(current_rate: float, risk: int, safer_evaluations: int) -> tuple[float, int]:
    target = (0.1, 0.5, 1.0)[risk]
    if target >= current_rate:
        return target, 0
    safer_evaluations += 1
    if safer_evaluations >= 3:
        return target, 0
    return current_rate, safer_evaluations


def evaluate_replay() -> dict:
    adaptive_rate = 1.0
    safer_evaluations = 0
    last_change_step: int | None = None
    changes: list[int] = []
    adaptive_spans = 0
    fixed_spans = 0
    adaptive_incident_spans = 0
    fixed_incident_spans = 0
    adaptive_failed_spans = [0] * STEPS
    fixed_failed_spans = [0] * STEPS
    fallback_count = 0
    rates = []
    rate_interval_counts: dict[str, int] = {}
    rng = random.Random(20261026)

    for step in range(STEPS):
        incident = in_incident(step)
        error_probability = 0.08 if incident else 0.002
        failed_requests = [
            request
            for request in range(REQUESTS_PER_STEP)
            if rng.random() < error_probability
        ]
        observed_error_rate = len(failed_requests) / REQUESTS_PER_STEP
        latency = 1.2 if incident else 0.2 + rng.random() * 0.1

        if step in STALE_STEPS:
            if adaptive_rate != 1.0:
                last_change_step = step
            requested_rate = 1.0
            next_safer = 0
            fallback_count += 1
        else:
            risk = risk_band(observed_error_rate, latency, 0.85 if incident else 0.05)
            requested_rate, next_safer = next_policy_rate(
                adaptive_rate, risk, safer_evaluations
            )
            changes = [changed_at for changed_at in changes if (step - changed_at) * STEP_SECONDS < 3600]
            is_decrease = requested_rate < adaptive_rate
            cooling_down = (
                last_change_step is not None
                and (step - last_change_step) * STEP_SECONDS < COOLDOWN_SECONDS
            )
            if is_decrease and (cooling_down or len(changes) >= MAX_CHANGES_PER_HOUR):
                requested_rate = adaptive_rate
            elif requested_rate != adaptive_rate:
                changes.append(step)
                last_change_step = step

        adaptive_rate = requested_rate
        safer_evaluations = next_safer
        rates.append(adaptive_rate)
        rate_key = f"{adaptive_rate:.2f}"
        rate_interval_counts[rate_key] = rate_interval_counts.get(rate_key, 0) + 1
        fixed_spans += int(REQUESTS_PER_STEP * FIXED_SAMPLE_RATE)

        for request in range(REQUESTS_PER_STEP):
            failed = request in failed_requests
            selected_fixed = rng.random() < FIXED_SAMPLE_RATE
            selected_adaptive = rng.random() < adaptive_rate
            if selected_fixed:
                fixed_failed_spans[step] += int(failed)
                fixed_incident_spans += int(incident)
            if selected_adaptive:
                adaptive_spans += 1
                adaptive_failed_spans[step] += int(failed)
                adaptive_incident_spans += int(incident)

    fixed_detected, fixed_delays, fixed_false_positives = score_detections(fixed_failed_spans)
    adaptive_detected, adaptive_delays, adaptive_false_positives = score_detections(adaptive_failed_spans)
    incident_count = len(INCIDENTS)
    normal_steps = STEPS - sum(end - start for start, end in INCIDENTS)
    report = {
        "evaluation": "deterministic_synthetic_replay",
        "seed_method": "Python random.Random (Mersenne Twister), seed=20261026",
        "duration_minutes": STEPS * STEP_SECONDS // 60,
        "evaluation_interval_seconds": STEP_SECONDS,
        "requests_per_interval": REQUESTS_PER_STEP,
        "synthetic_normal_error_probability": 0.002,
        "synthetic_incident_error_probability": 0.08,
        "synthetic_normal_p95_latency_seconds": "0.2-0.3",
        "synthetic_incident_p95_latency_seconds": 1.2,
        "incident_count": incident_count,
        "incident_duration_seconds": (INCIDENTS[0][1] - INCIDENTS[0][0]) * STEP_SECONDS,
        "fixed_sampling_rate": FIXED_SAMPLE_RATE,
        "adaptive_policy_rates": {"low": 0.1, "elevated": 0.5, "critical": 1.0},
        "stale_telemetry_fallback_intervals": len(STALE_STEPS),
        "adaptive_safe_fallback_intervals": fallback_count,
        "sampled_span_proxy": {
            "fixed_count": fixed_spans,
            "adaptive_count": adaptive_spans,
            "adaptive_reduction_percent": round(
                100 * (1 - adaptive_spans / fixed_spans), 2
            ),
            "interpretation": "Count of sampled synthetic request spans; not measured CPU, memory, network, or storage overhead.",
        },
        "incident_detection": {
            "detector": "at least two sampled failed request spans in an evaluation interval",
            "fixed_detected_incidents": len(fixed_detected),
            "adaptive_detected_incidents": len(adaptive_detected),
            "fixed_recall": round(len(fixed_detected) / incident_count, 4),
            "adaptive_recall": round(len(adaptive_detected) / incident_count, 4),
            "fixed_mean_detection_delay_seconds": mean(fixed_delays),
            "adaptive_mean_detection_delay_seconds": mean(adaptive_delays),
            "fixed_false_positive_rate_per_normal_interval": round(
                fixed_false_positives / normal_steps, 6
            ),
            "adaptive_false_positive_rate_per_normal_interval": round(
                adaptive_false_positives / normal_steps, 6
            ),
            "normal_intervals": normal_steps,
        },
        "limitations": [
            "Synthetic request failures and latency are generated by this replay, not collected from production.",
            "The sampled-span count is an overhead proxy; host resource consumption was not measured.",
            "The replay policy models the Java risk thresholds, three-safer-evaluation hysteresis, cooldown, hourly change limit, and stale-data full-sampling fallback.",
            "Trace sampling is not itself an incident detector; the replay uses a simple sampled-error threshold to compare event capture.",
        ],
        "adaptive_sample_rate_range": [round(min(rates), 4), round(max(rates), 4)],
        "adaptive_sample_rate_intervals": dict(sorted(rate_interval_counts.items())),
        "synthetic_incident_sampled_spans": {
            "fixed": fixed_incident_spans,
            "adaptive": adaptive_incident_spans,
        },
    }
    stable_payload = json.dumps(report, sort_keys=True, separators=(",", ":")).encode("utf-8")
    report["report_sha256"] = hashlib.sha256(stable_payload).hexdigest()
    return report


def mean(values: list[int]) -> float | None:
    return round(sum(values) / len(values), 2) if values else None


def score_detections(failed_spans: list[int]) -> tuple[set[int], list[int], int]:
    detected_incidents = set()
    delays = []
    false_positive_intervals = 0
    interval_detections = [count >= 2 for count in failed_spans]
    for incident_index, (start, end) in enumerate(INCIDENTS):
        detected_step = next(
            (step for step in range(start, end) if interval_detections[step]),
            None,
        )
        if detected_step is not None:
            detected_incidents.add(incident_index)
            delays.append((detected_step - start) * STEP_SECONDS)
    for step, detected in enumerate(interval_detections):
        if detected and not in_incident(step):
            false_positive_intervals += 1
    return detected_incidents, delays, false_positive_intervals


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("docs/phase-4-evaluation.json"),
        help="Path for the reproducible replay report.",
    )
    args = parser.parse_args()
    report = evaluate_replay()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
