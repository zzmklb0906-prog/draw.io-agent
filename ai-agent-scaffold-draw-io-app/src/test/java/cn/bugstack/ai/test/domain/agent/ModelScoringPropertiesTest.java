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
}
