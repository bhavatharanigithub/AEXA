from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.synthetic_data import SEED, generate_dataset


if __name__ == "__main__":
    output = ROOT / "artifacts" / "prepared_telemetry.csv"
    output.parent.mkdir(parents=True, exist_ok=True)
    dataset = generate_dataset(SEED)
    dataset.to_csv(
        output,
        index=False,
        date_format="%Y-%m-%dT%H:%M:%S%z",
        float_format="%.9f",
        lineterminator="\n",
    )
    print(f"Saved {len(dataset)} reproducible synthetic samples to {output}")
