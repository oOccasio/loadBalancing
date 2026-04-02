package com.alb.engine;

import com.alb.algorithm.AlgorithmType;
import com.alb.analyzer.PatternAnalyzer;
import com.alb.analyzer.TrafficState;
import com.alb.metrics.MetricsCollector;
import com.alb.metrics.MetricsSnapshot;
import com.alb.server.ServerPool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core decision engine: runs on every metrics window, classifies traffic state,
 * matches a rule, applies switch policy, and switches the algorithm if warranted.
 *
 * Every evaluation produces a {@link DecisionResult} (with full explainability)
 * that is appended to {@link DecisionLog}.
 */
@Component
public class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);

    private final MetricsCollector metricsCollector;
    private final PatternAnalyzer patternAnalyzer;
    private final RuleConfigLoader ruleConfigLoader;
    private final SwitchPolicy switchPolicy;
    private final ServerPool serverPool;
    private final DecisionLog decisionLog;

    private volatile Map<TrafficState, DecisionRule> rules;
    private volatile TrafficState currentState = TrafficState.LOW_TRAFFIC;



    public DecisionEngine(MetricsCollector metricsCollector,
                          PatternAnalyzer patternAnalyzer,
                          RuleConfigLoader ruleConfigLoader,
                          SwitchPolicy switchPolicy,
                          ServerPool serverPool,
                          DecisionLog decisionLog) {
        this.metricsCollector = metricsCollector;
        this.patternAnalyzer = patternAnalyzer;
        this.ruleConfigLoader = ruleConfigLoader;
        this.switchPolicy = switchPolicy;
        this.serverPool = serverPool;
        this.decisionLog = decisionLog;
    }

    @PostConstruct
    public void init() {
        this.rules = ruleConfigLoader.load();
    }

    @Scheduled(fixedDelay = 5000)
    public void reload() {
        this.rules = ruleConfigLoader.load();
    }

    /**
     * Runs after each metrics window flushes (same period as MetricsCollector).
     * Offset by 1s to ensure the snapshot is available.
     */
    @Scheduled(fixedDelayString = "${alb.metrics.window-size-seconds:10}000",
               initialDelay = 11000)
    public void evaluate() {
        List<MetricsSnapshot> snapshots = metricsCollector.getSnapshots();
        if (snapshots.isEmpty()) return;

        MetricsSnapshot latest = snapshots.get(snapshots.size() - 1);
        PatternAnalyzer.StateAnalysis analysis = patternAnalyzer.analyze(snapshots);

        TrafficState newState = analysis.state();
        double confidence = analysis.confidence();
        DecisionRule rule = rules.getOrDefault(newState,
                rules.getOrDefault(TrafficState.LOW_TRAFFIC,
                        new DecisionRule(TrafficState.LOW_TRAFFIC, AlgorithmType.ROUND_ROBIN, 0.5, "Default fallback")));

        AlgorithmType currentAlgo = serverPool.getCurrentAlgorithmType();
        boolean switched = false;

        if (switchPolicy.shouldSwitch(newState, currentState, confidence)) {
            AlgorithmType targetAlgo = rule.getAlgorithm();
            serverPool.switchAlgorithm(targetAlgo);
            switchPolicy.recordSwitch();
            currentState = newState;
            switched = true;
        }

        String reason = buildReason(analysis, rule, currentAlgo, switched);
        DecisionResult result = new DecisionResult(
                newState, serverPool.getCurrentAlgorithmType(),
                confidence, reason, Instant.now(), latest);

        decisionLog.record(result);

        log.info("[Decision] state={} algorithm={} confidence={} switched={} | {}",
                newState, serverPool.getCurrentAlgorithmType(),
                String.format("%.2f", confidence), switched, truncate(reason, 120));
    }

    public void reloadRulesManual() {
        this.rules = ruleConfigLoader.load();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private String buildReason(PatternAnalyzer.StateAnalysis analysis,
                                DecisionRule rule,
                                AlgorithmType prevAlgo,
                                boolean switched) {
        StringBuilder sb = new StringBuilder();
        sb.append(analysis.reason());
        sb.append(" → Rule: ").append(rule.getDescription());
        if (switched) {
            sb.append(String.format(" | Switched %s → %s.", prevAlgo, rule.getAlgorithm()));
        } else {
            sb.append(" | No switch (policy gate or same algorithm).");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public TrafficState getCurrentState() { return currentState; }
    public Map<TrafficState, DecisionRule> getRules() { return rules; }
}
