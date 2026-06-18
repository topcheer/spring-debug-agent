package com.debugagent.inspector;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * In-memory log capture via a real Logback appender.
 * <p>
 * Uses a ring buffer appender that is attached to the root logger
 * at construction time. Requires Logback on the classpath
 * (the default logging backend in Spring Boot).
 */
public class LogInspector {

    private static final int MAX_ENTRIES = 500;
    private final List<Map<String, Object>> ringBuffer = Collections.synchronizedList(new LinkedList<>());
    private boolean appenderRegistered = false;

    public LogInspector() {
        tryRegisterAppender();
    }

    private void tryRegisterAppender() {
        try {
            org.slf4j.Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (!(root instanceof Logger logbackLogger)) return;

            InMemoryAppender appender = new InMemoryAppender();
            appender.setContext(logbackLogger.getLoggerContext());
            appender.setName("DEBUG_AGENT_IN_MEMORY");
            appender.start();
            logbackLogger.addAppender(appender);
            appenderRegistered = true;
        } catch (Throwable e) {
            // Logback not on classpath — skip silently
        }
    }

    /**
     * Logback appender that stores events in an in-memory ring buffer.
     */
    private class InMemoryAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            try {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
                entry.put("level", event.getLevel() != null ? event.getLevel().toString() : "UNKNOWN");
                entry.put("logger", event.getLoggerName());
                entry.put("thread", event.getThreadName());
                entry.put("message", event.getFormattedMessage());

                if (event.getThrowableProxy() != null) {
                    entry.put("exception", event.getThrowableProxy().getClassName()
                            + ": " + event.getThrowableProxy().getMessage());
                }

                // MDC properties
                Map<String, String> mdc = event.getMDCPropertyMap();
                if (mdc != null && !mdc.isEmpty()) {
                    entry.put("mdc", mdc);
                }

                ringBuffer.add(entry);
                while (ringBuffer.size() > MAX_ENTRIES) {
                    ringBuffer.remove(0);
                }
            } catch (Exception ignored) {
                // Never let appender errors propagate
            }
        }
    }

    @DebugTool(description = "Get recent application logs from memory. Filter by log level (ERROR, WARN, INFO, DEBUG). Returns timestamp, level, logger name, and message.")
    public List<Map<String, Object>> getRecentLogs(
            @ToolParam(description = "Maximum number of entries to return (default 50, max 200)") Integer maxEntries,
            @ToolParam(description = "Minimum log level: ERROR, WARN, INFO, DEBUG (default WARN)") String level
    ) {
        if (!appenderRegistered) {
            return Collections.singletonList(Map.of(
                    "error", "In-memory log appender not available. Logback is required on classpath."));
        }

        int max = maxEntries != null ? Math.min(maxEntries, 200) : 50;
        String minLevel = level != null ? level.toUpperCase().trim() : "WARN";

        List<Map<String, Object>> filtered = new ArrayList<>();
        List<Map<String, Object>> snapshot;
        synchronized (ringBuffer) {
            snapshot = new ArrayList<>(ringBuffer);
        }
        // Reverse to show newest first
        Collections.reverse(snapshot);

        for (Map<String, Object> entry : snapshot) {
            String entryLevel = (String) entry.get("level");
            if (matchesLevel(entryLevel, minLevel)) {
                filtered.add(entry);
                if (filtered.size() >= max) break;
            }
        }

        return filtered;
    }

    private boolean matchesLevel(String entryLevel, String minLevel) {
        if (minLevel == null || minLevel.isBlank()) return true;
        Map<String, Integer> priority = Map.of(
                "ERROR", 4, "WARN", 3, "INFO", 2, "DEBUG", 1, "TRACE", 0);
        int entryPri = priority.getOrDefault(entryLevel, 2);
        int minPri = priority.getOrDefault(minLevel, 3);
        return entryPri >= minPri;
    }

    @DebugTool(description = "Search log entries by keyword. Searches message and exception text. Returns matching entries with context.")
    public Map<String, Object> searchLogs(
            @ToolParam(description = "Search keyword (case-insensitive)", required = true) String keyword,
            @ToolParam(description = "Maximum results (default 50)") Integer maxEntries
    ) {
        if (!appenderRegistered) {
            return Map.of("error", "In-memory log appender not available. Logback is required on classpath.");
        }

        int max = maxEntries != null ? Math.min(maxEntries, 100) : 50;
        String kw = keyword.toLowerCase();

        List<Map<String, Object>> matches = new ArrayList<>();
        List<Map<String, Object>> snapshot;
        synchronized (ringBuffer) {
            snapshot = new ArrayList<>(ringBuffer);
        }
        Collections.reverse(snapshot);

        for (Map<String, Object> entry : snapshot) {
            String msg = ((String) entry.getOrDefault("message", "")).toLowerCase();
            String exc = ((String) entry.getOrDefault("exception", "")).toLowerCase();
            if (msg.contains(kw) || exc.contains(kw)) {
                matches.add(entry);
                if (matches.size() >= max) break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("matchCount", matches.size());
        result.put("matches", matches);
        return result;
    }

    @DebugTool(description = "Get log statistics: count per level, most active loggers, error rate trend. Useful for spotting anomalies in log patterns.")
    public Map<String, Object> getLogStats(
            @ToolParam(description = "Number of recent entries to analyze (default 500, max 2000)") Integer sampleSize
    ) {
        if (!appenderRegistered) {
            return Map.of("error", "In-memory log appender not available. Logback is required on classpath.");
        }

        int sample = sampleSize != null ? Math.min(sampleSize, 2000) : 500;

        List<Map<String, Object>> snapshot;
        synchronized (ringBuffer) {
            int start = Math.max(0, ringBuffer.size() - sample);
            snapshot = new ArrayList<>(ringBuffer.subList(start, ringBuffer.size()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleSize", snapshot.size());

        // Count by level
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byLogger = new HashMap<>();
        int errorCount = 0;

        for (Map<String, Object> entry : snapshot) {
            String level = (String) entry.getOrDefault("level", "UNKNOWN");
            byLevel.merge(level, 1, Integer::sum);
            if ("ERROR".equals(level)) errorCount++;

            String logger = (String) entry.getOrDefault("logger", "unknown");
            // Simplify logger name to last 2 segments
            String shortLogger = logger;
            int lastDot = logger.lastIndexOf('.');
            if (lastDot > 0) {
                int prevDot = logger.lastIndexOf('.', lastDot - 1);
                if (prevDot > 0) shortLogger = logger.substring(prevDot + 1);
            }
            byLogger.merge(shortLogger, 1, Integer::sum);
        }

        result.put("countByLevel", byLevel);
        result.put("totalEntries", snapshot.size());
        result.put("errorCount", errorCount);
        result.put("errorRate", String.format("%.1f%%",
                snapshot.isEmpty() ? 0 : (errorCount * 100.0 / snapshot.size())));

        // Top 10 most active loggers
        List<Map<String, Object>> topLoggers = byLogger.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("logger", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
        result.put("topLoggers", topLoggers);

        return result;
    }
}
