package com.alb.analyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Threshold configuration for the pattern analyzer.
 * Values are loaded from {@code alb.analyzer.*} in application.yml.
 */
@ConfigurationProperties(prefix = "alb.analyzer")
public class AnalyzerConfig {

    /** RPS below this → LOW_TRAFFIC */
    private double lowRpsThreshold = 5.0;

    /** RPS change rate above this → SPIKE (e.g. 1.0 = 100% increase) */
    private double spikeRpsDerivativeThreshold = 1.0;

    /** RPS change rate above this (but below spike) → GRADUAL_INCREASE */
    private double gradualRpsDerivativeThreshold = 0.3;

    /** Number of recent windows used for latency trend slope calculation */
    private int latencyTrendWindows = 3;

    /** Latency slope (ms/window) above this considered "rising" */
    private double latencyRisingSlope = 20.0;

    /** Per-server error rate above this → OVERLOADED_NODE */
    private double serverErrorRateThreshold = 0.1;

    /** Per-server avg latency above this (ms) → OVERLOADED_NODE */
    private double serverLatencyThreshold = 500.0;

    /** Minimum snapshots needed before pattern analysis runs */
    private int minSnapshotsRequired = 2;

    public double getLowRpsThreshold() { return lowRpsThreshold; }
    public void setLowRpsThreshold(double v) { this.lowRpsThreshold = v; }

    public double getSpikeRpsDerivativeThreshold() { return spikeRpsDerivativeThreshold; }
    public void setSpikeRpsDerivativeThreshold(double v) { this.spikeRpsDerivativeThreshold = v; }

    public double getGradualRpsDerivativeThreshold() { return gradualRpsDerivativeThreshold; }
    public void setGradualRpsDerivativeThreshold(double v) { this.gradualRpsDerivativeThreshold = v; }

    public int getLatencyTrendWindows() { return latencyTrendWindows; }
    public void setLatencyTrendWindows(int v) { this.latencyTrendWindows = v; }

    public double getLatencyRisingSlope() { return latencyRisingSlope; }
    public void setLatencyRisingSlope(double v) { this.latencyRisingSlope = v; }

    public double getServerErrorRateThreshold() { return serverErrorRateThreshold; }
    public void setServerErrorRateThreshold(double v) { this.serverErrorRateThreshold = v; }

    public double getServerLatencyThreshold() { return serverLatencyThreshold; }
    public void setServerLatencyThreshold(double v) { this.serverLatencyThreshold = v; }

    public int getMinSnapshotsRequired() { return minSnapshotsRequired; }
    public void setMinSnapshotsRequired(int v) { this.minSnapshotsRequired = v; }
}
