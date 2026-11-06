import unittest

from adaptive_sampling_replay import evaluate_replay, next_policy_rate, risk_band


class AdaptiveSamplingReplayTest(unittest.TestCase):
    def test_policy_escalates_immediately_and_reduces_after_three_safe_windows(self):
        self.assertEqual(next_policy_rate(0.1, 2, 0), (1.0, 0))
        self.assertEqual(next_policy_rate(1.0, 0, 0), (1.0, 1))
        self.assertEqual(next_policy_rate(1.0, 0, 1), (1.0, 2))
        self.assertEqual(next_policy_rate(1.0, 0, 2), (0.1, 0))

    def test_policy_risk_boundaries(self):
        self.assertEqual(risk_band(0.0, 0.2, 0.05), 0)
        self.assertEqual(risk_band(0.01, 0.2, 0.05), 1)
        self.assertEqual(risk_band(0.05, 0.2, 0.05), 2)
        self.assertEqual(risk_band(0.0, 1.0, 0.05), 2)

    def test_replay_is_repeatable_and_reports_computed_results(self):
        first = evaluate_replay()
        second = evaluate_replay()
        self.assertEqual(first, second)
        self.assertEqual(first["incident_count"], 6)
        self.assertEqual(first["sampled_span_proxy"]["fixed_count"], 360000)
        self.assertGreater(first["sampled_span_proxy"]["adaptive_count"], 0)
        self.assertLess(first["sampled_span_proxy"]["adaptive_count"], 720000)
        self.assertGreater(first["sampled_span_proxy"]["adaptive_reduction_percent"], 0)
        self.assertEqual(first["incident_detection"]["adaptive_recall"], 1.0)
        self.assertEqual(first["incident_detection"]["normal_intervals"], 1104)
        self.assertEqual(first["adaptive_safe_fallback_intervals"], 9)
        self.assertEqual(first["adaptive_sample_rate_range"][1], 1.0)
        self.assertLess(
            first["incident_detection"]["adaptive_false_positive_rate_per_normal_interval"],
            first["incident_detection"]["fixed_false_positive_rate_per_normal_interval"],
        )


if __name__ == "__main__":
    unittest.main()
