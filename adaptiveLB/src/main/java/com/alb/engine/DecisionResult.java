package com.alb.engine;

import com.alb.algorithm.AlgorithmType;
import com.alb.analyzer.TrafficState;
import com.alb.metrics.MetricsSnapshot;

import java.time.Instant;

/**
 * Immutable record of a single algorithm-selection decision.
 *
 * The {@code reason} field is the key explainability artifact:
 * it captures WHY this algorithm was chosen at this moment,
 * combining the pattern-analysis rationale with the rule-matching logic.
 *
 * All decisions are appended to {@link DecisionLog} and later consumed
 * by the AI agent's post-analysis script.
 */
public record DecisionResult(
        TrafficState state,
        AlgorithmType selectedAlgorithm,
        double confidence,
        String reason,
        Instant timestamp,
        MetricsSnapshot snapshot
) {}
