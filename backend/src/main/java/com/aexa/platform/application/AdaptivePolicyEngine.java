package com.aexa.platform.application;

public final class AdaptivePolicyEngine {

    public static final double MIN_TRACE_SAMPLE_RATE = 0.05;
    public static final double MAX_TRACE_SAMPLE_RATE = 1.0;
    public static final double LOW_RISK_SAMPLE_RATE = 0.10;
    public static final double ELEVATED_RISK_SAMPLE_RATE = 0.50;
    public static final double CRITICAL_RISK_SAMPLE_RATE = 1.0;
    public static final int SAFER_EVALUATIONS_REQUIRED = 3;

    private AdaptivePolicyEngine() {}

    public enum RiskBand {
        LOW(0, LOW_RISK_SAMPLE_RATE),
        ELEVATED(1, ELEVATED_RISK_SAMPLE_RATE),
        CRITICAL(2, CRITICAL_RISK_SAMPLE_RATE);

        private final int level;
        private final double sampleRate;

        RiskBand(int level, double sampleRate) {
            this.level = level;
            this.sampleRate = sampleRate;
        }

        public int level() {
            return level;
        }

        public double sampleRate() {
            return sampleRate;
        }
    }

    public record Signals(Double errorRate, Double p95LatencySeconds, Double forecastProbability) {
        public boolean isValid() {
            return isValidValue(errorRate, 0.0, 1.0)
                    && isValidValue(p95LatencySeconds, 0.0, Double.MAX_VALUE)
                    && isValidValue(forecastProbability, 0.0, 1.0);
        }
    }

    public record Transition(double targetSampleRate, int saferEvaluations) {}

    public static RiskBand classify(Signals signals) {
        if (signals == null || !signals.isValid() || signals.forecastProbability() == null) {
            return RiskBand.CRITICAL;
        }
        double errors = signals.errorRate() == null ? 0.0 : signals.errorRate();
        double latency = signals.p95LatencySeconds() == null ? 0.0 : signals.p95LatencySeconds();
        double forecast = signals.forecastProbability();
        if (errors >= 0.05 || latency >= 1.0 || forecast >= 0.75) {
            return RiskBand.CRITICAL;
        }
        if (errors >= 0.01 || latency >= 0.5 || forecast >= 0.4) {
            return RiskBand.ELEVATED;
        }
        return RiskBand.LOW;
    }

    public static Transition transition(double currentSampleRate, RiskBand risk, int saferEvaluations) {
        if (!Double.isFinite(currentSampleRate)
                || currentSampleRate < MIN_TRACE_SAMPLE_RATE
                || currentSampleRate > MAX_TRACE_SAMPLE_RATE) {
            throw new IllegalArgumentException("current trace sample rate is outside the permitted range");
        }
        if (risk == null || saferEvaluations < 0) {
            throw new IllegalArgumentException("risk and safer evaluation count are required");
        }
        double targetRate = risk.sampleRate();
        if (targetRate >= currentSampleRate) {
            return new Transition(targetRate, 0);
        }
        int nextSaferEvaluations = saferEvaluations + 1;
        if (nextSaferEvaluations >= SAFER_EVALUATIONS_REQUIRED) {
            return new Transition(targetRate, 0);
        }
        return new Transition(currentSampleRate, nextSaferEvaluations);
    }

    private static boolean isValidValue(Double value, double minimum, double maximum) {
        return value == null || (Double.isFinite(value) && value >= minimum && value <= maximum);
    }
}
