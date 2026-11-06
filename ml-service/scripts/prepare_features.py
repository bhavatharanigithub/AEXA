from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
import sys

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.features import FEATURE_COLUMNS, METRIC_COLUMNS, prepare_features

REQUIRED_COLUMNS = {"timestamp", "service_name", *METRIC_COLUMNS}


def load_history(path: Path) -> pd.DataFrame:
    frame = pd.read_csv(path)
    missing = REQUIRED_COLUMNS.difference(frame.columns)
    if missing:
        raise ValueError(f"Historical telemetry is missing required columns: {', '.join(sorted(missing))}")
    parsed_timestamps = []
    for raw_timestamp in frame["timestamp"]:
        parsed = datetime.fromisoformat(str(raw_timestamp).replace("Z", "+00:00"))
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            raise ValueError("Historical timestamps must include a UTC offset or Z")
        parsed_timestamps.append(parsed)
    frame["timestamp"] = parsed_timestamps
    if frame["service_name"].isna().any() or frame["service_name"].astype(str).str.strip().eq("").any():
        raise ValueError("service_name must be present for every historical sample")
    for column in METRIC_COLUMNS:
        frame[column] = pd.to_numeric(frame[column], errors="raise")
        values = frame[column].dropna().to_numpy(dtype=float)
        if not np.isfinite(values).all() or (values < 0).any():
            raise ValueError(f"{column} values must be finite and non-negative")
    if frame["error_rate"].dropna().gt(1).any():
        raise ValueError("error_rate must be between 0 and 1")
    return frame


def main() -> None:
    parser = argparse.ArgumentParser(description="Align historical telemetry and compute past-only ML features.")
    parser.add_argument("--input", required=True, type=Path, help="Prometheus history CSV")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "artifacts" / "prepared_prometheus_features.csv",
    )
    args = parser.parse_args()
    history = load_history(args.input)
    features = prepare_features(history.to_dict(orient="records"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    features.to_csv(args.output, index=False, date_format="%Y-%m-%dT%H:%M:%S%z", lineterminator="\n")
    print(
        f"Saved {len(features)} aligned feature rows ({len(FEATURE_COLUMNS)} model features) "
        f"to {args.output}"
    )


if __name__ == "__main__":
    main()
