from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.training import train_and_evaluate


if __name__ == "__main__":
    report = train_and_evaluate()
    print(json.dumps(report, indent=2, sort_keys=True))
