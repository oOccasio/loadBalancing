package com.alb.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports metrics snapshots to JSON files so the AI agent can consume them offline.
 */
@Component
public class MetricsExporter {

    private static final Logger log = LoggerFactory.getLogger(MetricsExporter.class);
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final MetricsCollector collector;

    public MetricsExporter(MetricsCollector collector) {
        this.collector = collector;
    }

    /**
     * Export all current snapshots to {@code benchmark/results/metrics_<timestamp>.json}.
     *
     * @return path of the written file
     */
    public String exportToJson() throws IOException {
        List<MetricsSnapshot> snapshots = collector.getSnapshots();
        String timestamp = FMT.format(Instant.now());
        String path = "benchmark/results/metrics_" + timestamp + ".json";

        File file = new File(path);
        file.getParentFile().mkdirs();
        mapper.writeValue(file, snapshots);

        log.info("Metrics exported to {}: {} snapshots", path, snapshots.size());
        return path;
    }
}
