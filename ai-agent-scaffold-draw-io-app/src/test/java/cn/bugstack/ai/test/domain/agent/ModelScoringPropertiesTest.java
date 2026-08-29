package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.llm.routing.scoring.ModelScoringProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelScoringProperties} validation rules.
 */
class ModelScoringPropertiesTest {

    @Test
    void defaultProperties_passValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        assertDoesNotThrow(props::validate);
    }

    @Test
    void negativeCapabilityWeight_failsValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        props.setCapabilityWeight(-0.1);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void negativeUnknownPenalty_failsValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        props.setUnknownPenalty(-5.0);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void allZeroWeights_failsValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        props.setCapabilityWeight(0.0);
        props.setCostWeight(0.0);
        props.setContextHeadroomWeight(0.0);
        props.setOutputHeadroomWeight(0.0);
        props.setCertaintyWeight(0.0);
        assertThrows(IllegalStateException.class, props::validate);
    }

    @Test
    void nonUnitSum_positiveWeights_passValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        props.setCapabilityWeight(8.0);
        props.setCostWeight(1.0);
        props.setContextHeadroomWeight(0.5);
        props.setOutputHeadroomWeight(0.5);
        props.setCertaintyWeight(0.5);
        assertDoesNotThrow(props::validate, "Sum not equal to 1.0 is allowed as scorer normalizes weights automatically");
    }

    @Test
    void defaultSufficiencyThreshold_isZeroPointEightFive() {
        ModelScoringProperties props = new ModelScoringProperties();
        assertEquals(0.85, props.getSufficiencyThreshold(), 0.0001);
        assertDoesNotThrow(props::validate);
    }

    @Test
    void sufficiencyThreshold_zeroOrNegative_failsValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        props.setSufficiencyThreshold(0.0);
        assertThrows(IllegalArgumentException.class, props::validate);

        props.setSufficiencyThreshold(-0.1);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void sufficiencyThreshold_greaterThanOne_failsValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        props.setSufficiencyThreshold(1.01);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void sufficiencyThreshold_validBoundaries_passValidation() {
        ModelScoringProperties props = new ModelScoringProperties();
        props.setSufficiencyThreshold(1.0);
        assertDoesNotThrow(props::validate);

        props.setSufficiencyThreshold(0.01);
        assertDoesNotThrow(props::validate);
    }
}
