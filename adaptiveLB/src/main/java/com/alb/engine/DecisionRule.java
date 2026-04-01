package com.alb.engine;

import com.alb.algorithm.AlgorithmType;
import com.alb.analyzer.TrafficState;

/**
 * Maps a single {@link TrafficState} to a recommended {@link AlgorithmType}.
 * Loaded from {@code decision-rules.yml}.
 */
public class DecisionRule {

    private TrafficState state;
    private AlgorithmType algorithm;
    private double confidenceThreshold;
    private String description;

    public DecisionRule() {}

    public DecisionRule(TrafficState state, AlgorithmType algorithm,
                        double confidenceThreshold, String description) {
        this.state = state;
        this.algorithm = algorithm;
        this.confidenceThreshold = confidenceThreshold;
        this.description = description;
    }

    public TrafficState getState() { return state; }
    public void setState(TrafficState state) { this.state = state; }
    public AlgorithmType getAlgorithm() { return algorithm; }
    public void setAlgorithm(AlgorithmType algorithm) { this.algorithm = algorithm; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
