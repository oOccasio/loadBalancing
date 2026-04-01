package com.alb.analyzer;

public enum TrafficState {
    LOW_TRAFFIC,      // RPS below low threshold — all algorithms perform similarly
    HIGH_STABLE,      // High RPS, latency and error rate stable
    SPIKE,            // Sudden RPS surge (high positive derivative)
    OVERLOADED_NODE,  // One or more servers showing abnormal latency/error rate
    GRADUAL_INCREASE  // RPS rising steadily but not explosively
}
