# Phase 3 model evaluation

## Scope and reproducibility

The saved report in [`../ml-service/artifacts/evaluation.json`](../ml-service/artifacts/evaluation.json) is the output of an actual training/evaluation run. It uses a fixed seed (`20261026`) and deterministic synthetic telemetry for checkout, inventory, and pricing services. Dataset SHA-256: `e2c8567840c75d1bb5fa7bfdbd927e414d501d422ae8c61d702b029464fc1e1e`. Hash serialization normalizes timestamps to UTC and numeric values to nine decimal places to avoid platform-specific floating-point text differences. The dataset has 2,142 rows, 36 missing latency measurements, and 18 removed timestamps to exercise missing/irregular input.

The earliest 70% of UTC timestamps train the models; the latest 30% are held out. Feature windows use only current/past data. Imputers/scalers are fitted from the training window only. Isolation Forest is fitted on normal training rows only. Forecast labels inspect the next five one-minute samples; training rows within five minutes of the chronological split are excluded.

## Measured held-out results

Metrics below are read from the generated evaluation JSON. Pointwise anomaly metrics are calculated on 645 observed samples from the chronological test window. Delay is measured over labeled test incidents.

| Task / method | Precision | Recall | F1 | False-positive rate | Detection delay |
| --- | ---: | ---: | ---: | ---: | --- |
| Statistical rolling median/MAD baseline | 96.77% | 76.92% | 85.71% | 0.17% | 0 minutes median; 2 detected, 0 missed |
| RobustScaler + Isolation Forest | 46.99% | 100.00% | 63.93% | 7.26% | 0 minutes median; 2 detected, 0 missed |

Forecast target: any p95 latency `>= 1.0` second within the next five one-minute samples. The operational probability threshold is 0.5. On 630 held-out forecast samples:

| Method | Precision | Recall | F1 | False-positive rate |
| --- | ---: | ---: | ---: | ---: |
| Random Forest classifier | 94.87% | 78.72% | 86.05% | 0.34% |
| Current-latency persistence baseline | 94.87% | 78.72% | 86.05% | 0.34% |

The classifier exactly ties the persistence baseline on these data; it shows no measured improvement. The statistical anomaly baseline outperforms Isolation Forest on F1 and false-positive rate in this test. Both comparisons are reported rather than hiding the weaker ML results.

## Methods and limitations

- Metrics are synthetic and deliberately generated with labeled failure intervals. They are not measurements from production incidents and do not establish real-world generalization.
- A zero-minute detection delay means the first observed sample of the labeled synthetic test episode was flagged; it does not imply sub-minute production detection.
- Isolation Forest anomaly score is a signed decision-function transform, not a probability. Its threshold was selected from the 5th percentile of normal training scores.
- Forecast probabilities are not calibrated guarantees. The selected threshold and horizon have only been evaluated on the synthetic holdout.
- LIME reports deterministic, feature-level local surrogate contributions to the detector score or forecast probability using each model's time-ordered training data. It is an approximation, depends on the training distribution, and does not establish causality.
- RCA ranks observational evidence only. It compares a five-minute event window with at least three pre-window metric samples and combines normalized metric deviation, observed trace-ancestor distance, correlated error-log count, and temporal precedence. The returned score is not confidence/probability, and causal confirmation is always false.
- The API consumes timestamped metric windows, service metadata, trace edges, and logs supplied by its caller. The current backend proxy does not automatically query Jaeger or Loki and does not persist analysis results. Do not describe it as a fully automated live RCA pipeline.
- Extreme values are retained rather than clipped because they may be failure evidence. Robust scaling and robust baseline statistics reduce sensitivity to training outliers.

## Reproduce and extract historical metrics

```powershell
cd D:\Final_year_project\ml-service
.\.venv\Scripts\Activate.ps1
python scripts\prepare_dataset.py
python scripts\train_models.py
pytest
```

To export actual historical metric samples for external preparation or analysis (not labeled training/evaluation data):

```powershell
$env:PROMETHEUS_URL = "http://localhost:9090"
python scripts\extract_prometheus_history.py `
  --start "2026-10-26T19:00:00+05:30" `
  --end "2026-10-26T20:00:00+05:30"
```

The exporter queries request rate, 5xx ratio, and p95 latency by `service_name` using Prometheus `query_range`. It fails on invalid/unavailable responses and preserves missing features as empty CSV values. Historical unlabeled data is not silently treated as ground truth; labeled incidents are needed for a valid real-data evaluation.

Align a Prometheus export into UTC one-minute bins and generate the same past-only feature columns without assigning ground-truth labels:

```powershell
python scripts\prepare_features.py --input artifacts\prometheus_history.csv
```

This real-history path prepares features only. The evaluated training pipeline uses known synthetic labels until reviewed real incident labels are available.
