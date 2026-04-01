package com.alb.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * In-memory time-series log of every {@link DecisionResult}.
 * Acts as the input data for the AI agent's post-analysis script.
 */
@Component
public class DecisionLog {

    private static final int MAX_ENTRIES = 1000;

    private final LinkedList<DecisionResult> log = new LinkedList<>();

    public synchronized void record(DecisionResult result) {
        log.addLast(result);
        if (log.size() > MAX_ENTRIES) {
            log.pollFirst();
        }
    }

    public synchronized List<DecisionResult> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public synchronized List<DecisionResult> getLast(int n) {
        int size = log.size();
        List<DecisionResult> list = new ArrayList<>(log);
        return Collections.unmodifiableList(list.subList(Math.max(0, size - n), size));
    }

    public synchronized int size() {
        return log.size();
    }
}
