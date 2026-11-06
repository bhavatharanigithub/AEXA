# Phase 4 adaptive sampling replay evaluation

Reproduce the deterministic synthetic replay and its machine-readable output from the repository root:

```powershell
python scripts\test_adaptive_sampling_replay.py
python scripts\adaptive_sampling_replay.py
```

The checked-in machine-readable result is [phase-4-evaluation.json](./phase-4-evaluation.json). The replay uses 300 minutes of synthetic workload, 15-second controller intervals, 600 requests per interval, six four-minute injected incidents, 0.2% normal request failures, and 8% incident failures. The fixed baseline samples at 50%; the adaptive policy uses its low/elevated/critical tiers, hysteresis, cooldown, automatic-change limit, and nine deliberately stale intervals.

## Observed replay results

| Measure | Fixed 50% | Adaptive |
| --- | ---: | ---: |
| Sampled-span proxy | 360,000 | 139,527 |
| Change in sampled spans vs fixed | — | 61.24% fewer |
| Injected incidents detected | 6/6 | 6/6 |
| Incident recall | 1.00 | 1.00 |
| Mean detection delay | 0 seconds | 0 seconds |
| False-positive interval rate on normal workload | 11.87% | 1.90% |

The detector used only for this replay marks an interval when at least two sampled failed-request spans are present. This makes incident-capture and false-positive differences measurable; it is not a claim that sampling itself implements the production anomaly detector.

## Interpretation and limitations

These are measured outputs of a reproducible synthetic replay, not production evaluation. The sampled-span count is an overhead proxy, not measured CPU, memory, network, or storage usage. The six injected incidents are intentionally clear (8% failure rate and 1.2-second p95) and do not establish general detection performance. Lower sampling can also hide low-volume events; representative traffic, known real incidents, and hardware/resource measurements are required before production claims or rollout.

Nine stale intervals intentionally exercise the full-sampling safe fallback. The replay's policy thresholds and transition behavior mirror the deterministic Java policy. Unit tests separately verify its rate boundaries, escalation, and three-safer-evaluation reduction hysteresis.
