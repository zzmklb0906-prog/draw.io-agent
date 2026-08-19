package cn.bugstack.ai.domain.agent.service.llm.routing.eval.analysis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for offline routing evaluation analysis.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.agent.model-routing.analysis")
public class RoutingEvaluationAnalysisProperties {

    /**
     * Minimum sample size required before generating advisory calibration recommendations.
     */
    private int minSampleSize = 30;

    /**
     * Margin threshold below which a routing decision is deemed low-margin.
     */
    private double lowMarginThreshold = 5.0;

    /**
     * Warning threshold for low-margin decision rate.
     */
    private double lowMarginRateThreshold = 0.40;

    /**
     * Warning threshold for rate of dynamic recommendations being more expensive than actual.
     */
    private double costIncreaseRateThreshold = 0.60;

    /**
     * Warning threshold for catalog lookup failure rate.
     */
    private double catalogFailureRateThreshold = 0.05;

    /**
     * Warning threshold for pricing metadata missing rate.
     */
    private double pricingMissingRateThreshold = 0.10;

    /**
     * Warning threshold for actual model hard rejected rate.
     */
    private double actualHardRejectedRateThreshold = 0.15;

    /**
     * Threshold to consider a requirement dimension saturated/high-demand.
     */
    private int highDemandScoreThreshold = 85;

    /**
     * Warning threshold for rate of high-demand scores in a requirement dimension.
     */
    private double highDemandRateThreshold = 0.70;
}
