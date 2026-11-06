from __future__ import annotations

import argparse
import math
import os
from datetime import datetime, timezone
from pathlib import Path
import sys

import httpx
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

QUERIES = {
    "request_rate": "sum by (service_name) (rate(aexa_service_requests_total[5m]))",
    "error_rate": (
        'sum by (service_name) (rate(aexa_service_requests_total{status_code=~"5.."}[5m])) '
        '/ clamp_min(sum by (service_name) (rate(aexa_service_requests_total[5m])), 0.000001)'
    ),
    "p95_latency_seconds": (
        "histogram_quantile(0.95, sum by (service_name, le) "
        "(rate(aexa_request_duration_seconds_bucket[5m])))"
    ),
}


def parse_instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise argparse.ArgumentTypeError("timestamps must include a UTC offset or Z")
    return parsed.astimezone(timezone.utc)


def extract(
    prometheus_url: str,
    start: datetime,
    end: datetime,
    output: Path,
    transport: httpx.BaseTransport | None = None,
) -> int:
    if start >= end:
        raise ValueError("--start must be earlier than --end")
    samples: dict[tuple[str, float], dict] = {}
    with httpx.Client(base_url=prometheus_url.rstrip("/"), timeout=10, transport=transport) as client:
        for feature, query in QUERIES.items():
            response = client.get(
                "/api/v1/query_range",
                params={
                    "query": query,
                    "start": start.timestamp(),
                    "end": end.timestamp(),
                    "step": "60s",
                },
            )
            response.raise_for_status()
            body = response.json()
            if body.get("status") != "success" or not isinstance(body.get("data", {}).get("result"), list):
                raise RuntimeError(f"Prometheus returned an invalid query_range response for {feature}")
            for series in body["data"]["result"]:
                service = series.get("metric", {}).get("service_name")
                if not service:
                    raise RuntimeError(f"Prometheus returned {feature} without service_name")
                for timestamp, raw_value in series.get("values", []):
                    value = float(raw_value)
                    if not math.isfinite(value) or value < 0:
                        raise RuntimeError(f"Prometheus returned an invalid {feature} sample")
                    key = (service, float(timestamp))
                    samples.setdefault(key, {"service_name": service, "timestamp": float(timestamp)})[
                        feature
                    ] = value
    if not samples:
        raise RuntimeError("Prometheus returned no historical samples for the requested range")
    rows = []
    for sample in sorted(samples.values(), key=lambda row: (row["timestamp"], row["service_name"])):
        rows.append(
            {
                "timestamp": datetime.fromtimestamp(sample.pop("timestamp"), tz=timezone.utc).isoformat(),
                **sample,
            }
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(
        rows,
        columns=["timestamp", "service_name", "request_rate", "error_rate", "p95_latency_seconds"],
    ).to_csv(output, index=False)
    return len(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="Export historical service metrics from Prometheus query_range.")
    parser.add_argument("--start", required=True, type=parse_instant, help="ISO-8601 start time with UTC offset")
    parser.add_argument("--end", required=True, type=parse_instant, help="ISO-8601 end time with UTC offset")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "artifacts" / "prometheus_history.csv",
    )
    args = parser.parse_args()
    url = os.environ.get("PROMETHEUS_URL", "http://localhost:9090")
    count = extract(url, args.start, args.end, args.output)
    print(f"Saved {count} Prometheus service/time rows to {args.output}")


if __name__ == "__main__":
    main()
