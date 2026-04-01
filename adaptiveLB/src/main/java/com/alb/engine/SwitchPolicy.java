package com.alb.engine;

import com.alb.analyzer.TrafficState;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Guards algorithm switches with cooldown, confidence, and hysteresis checks.
 *
 * <ul>
 *   <li><b>Cooldown</b>: prevents switching more often than once per N seconds.</li>
 *   <li><b>Confidence</b>: blocks switches when the analyzer isn't confident.</li>
 *   <li><b>Rate limit</b>: caps switches per minute to avoid oscillation.</li>
 *   <li><b>Hysteresis</b>: requires the new state to be observed across N
 *       consecutive windows before acting — filters transient noise.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "alb.switch-policy")
public class SwitchPolicy {

    private int cooldownSeconds = 10;
    private double minConfidence = 0.6;
    private int maxSwitchesPerMinute = 3;
    private int sustainedWindows = 3;       // default hysteresis window count
    private int spikeSustainedWindows = 2;  // SPIKE gets faster reaction

    // Runtime state
    private volatile Instant lastSwitchTime = Instant.EPOCH;
    private final Queue<Instant> recentSwitches = new LinkedList<>();

    // Hysteresis tracking
    private volatile TrafficState pendingState = null;
    private volatile int consecutiveCount = 0;

    /**
     * Evaluate whether we should switch from {@code currentState} to {@code newState}.
     *
     * @param newState    proposed new traffic state
     * @param currentState currently active traffic state
     * @param confidence  analyzer confidence for {@code newState}
     * @return true if the switch should proceed
     */
    public synchronized boolean shouldSwitch(TrafficState newState,
                                              TrafficState currentState,
                                              double confidence) {
        // No-op: same state
        if (newState == currentState) {
            resetPending();
            return false;
        }

        // Confidence gate
        if (confidence < minConfidence) {
            resetPending();
            return false;
        }

        // Cooldown gate
        if (Duration.between(lastSwitchTime, Instant.now()).getSeconds() < cooldownSeconds) {
            return false;
        }

        // Rate-limit gate
        purgeOldSwitches();
        if (recentSwitches.size() >= maxSwitchesPerMinute) {
            return false;
        }

        // Hysteresis gate: new state must be observed consecutively
        if (newState.equals(pendingState)) {
            consecutiveCount++;
        } else {
            pendingState = newState;
            consecutiveCount = 1;
        }

        int required = (newState == TrafficState.SPIKE) ? spikeSustainedWindows : sustainedWindows;
        return consecutiveCount >= required;
    }

    /** Call this after a switch is confirmed to update cooldown and rate-limit state. */
    public synchronized void recordSwitch() {
        lastSwitchTime = Instant.now();
        recentSwitches.add(lastSwitchTime);
        resetPending();
    }

    private void resetPending() {
        pendingState = null;
        consecutiveCount = 0;
    }

    private void purgeOldSwitches() {
        Instant oneMinuteAgo = Instant.now().minusSeconds(60);
        recentSwitches.removeIf(t -> t.isBefore(oneMinuteAgo));
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    public int getMaxSwitchesPerMinute() { return maxSwitchesPerMinute; }
    public void setMaxSwitchesPerMinute(int maxSwitchesPerMinute) { this.maxSwitchesPerMinute = maxSwitchesPerMinute; }
    public int getSustainedWindows() { return sustainedWindows; }
    public void setSustainedWindows(int sustainedWindows) { this.sustainedWindows = sustainedWindows; }
    public int getSpikeSustainedWindows() { return spikeSustainedWindows; }
    public void setSpikeSustainedWindows(int spikeSustainedWindows) { this.spikeSustainedWindows = spikeSustainedWindows; }
}
