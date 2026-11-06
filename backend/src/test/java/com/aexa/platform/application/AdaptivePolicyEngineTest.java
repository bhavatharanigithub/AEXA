package com.aexa.platform.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptivePolicyEngineTest {

    @Test
    void classifiesBoundedSignalsIntoDeterministicRiskBands() {
        assertThat(AdaptivePolicyEngine.classify(new AdaptivePolicyEngine.Signals(0.0, 0.2, 0.1)))
                .isEqualTo(AdaptivePolicyEngine.RiskBand.LOW);
        assertThat(AdaptivePolicyEngine.classify(new AdaptivePolicyEngine.Signals(0.01, 0.2, 0.1)))
                .isEqualTo(AdaptivePolicyEngine.RiskBand.ELEVATED);
        assertThat(AdaptivePolicyEngine.classify(new AdaptivePolicyEngine.Signals(0.0, 1.0, 0.1)))
                .isEqualTo(AdaptivePolicyEngine.RiskBand.CRITICAL);
        assertThat(AdaptivePolicyEngine.classify(new AdaptivePolicyEngine.Signals(0.0, 0.2, null)))
                .isEqualTo(AdaptivePolicyEngine.RiskBand.CRITICAL);
    }

    @Test
    void reducesSamplingOnlyAfterThreeSaferEvaluationsAndEscalatesImmediately() {
        var first = AdaptivePolicyEngine.transition(1.0, AdaptivePolicyEngine.RiskBand.LOW, 0);
        var second = AdaptivePolicyEngine.transition(first.targetSampleRate(), AdaptivePolicyEngine.RiskBand.LOW, first.saferEvaluations());
        var third = AdaptivePolicyEngine.transition(second.targetSampleRate(), AdaptivePolicyEngine.RiskBand.LOW, second.saferEvaluations());

        assertThat(first.targetSampleRate()).isEqualTo(1.0);
        assertThat(second.targetSampleRate()).isEqualTo(1.0);
        assertThat(third.targetSampleRate()).isEqualTo(0.1);
        assertThat(third.saferEvaluations()).isZero();

        var escalation = AdaptivePolicyEngine.transition(0.1, AdaptivePolicyEngine.RiskBand.CRITICAL, 0);
        assertThat(escalation.targetSampleRate()).isEqualTo(1.0);
        assertThat(escalation.saferEvaluations()).isZero();
    }

    @Test
    void rejectsOutOfRangeCurrentSampleRates() {
        assertThatThrownBy(() ->
                AdaptivePolicyEngine.transition(0.01, AdaptivePolicyEngine.RiskBand.LOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
