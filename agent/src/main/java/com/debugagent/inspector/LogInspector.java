package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory log capture tool.
 *
 * Uses reflection to register a custom Logback appender (fail-safe if logback
 * is not on classpath — degrades to no-op). Captures last N log entries in a ring buffer.
 *
 * No hard dependency on Logback — all access via reflection.
 */
public class LogInspector {

    private static final Logger log = LoggerFactory.getLogger(LogInspector.class);
    private static final int MAX_ENTRIES = 500;
    private final Deque<Map<String, Object>> ringBuffer = new ConcurrentLinkedDeque<>();
    private boolean appenderRegistered = false;

    public LogInspector() {
        tryRegisterAppender();
    }

    @SuppressWarnings("unchecked")
    private void tryRegisterAppender() {
        try {
            // Check if Logback is available
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");
            Class<?> appenderBaseClass = Class.forName("ch.qos.logback.core.AppenderBase");
            Class<?> loggingEventClass = Class.forName("ch.qos.logback.classic.spi.ILoggingEvent");

            // Get root logger
            org.slf4j.Logger rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (!loggerClass.isInstance(rootLogger)) return;

            // Create a dynamic proxy appender using reflection
            // We'll create an anonymous class that extends AppenderBase<ILoggingEvent>
            // But since we can't do that without the compile-time class, we'll use a different approach:
            // Create a custom appender via a lambda proxy

            Object logbackLogger = rootLogger;

            // Get LoggerContext
            Method getLoggerContext = loggerClass.getMethod("getLoggerContext");
            Object context = getLoggerContext.invoke(logbackLogger);

            // Build appender dynamically
            Object appender = createDynamicAppender(appenderBaseClass, loggingEventClass, context);

            // Register on root logger
            Method addAppender = loggerClass.getMethod("addAppender",
                    Class.forName("ch.qos.logback.core.Appender"));
            addAppender.invoke(logbackLogger, appender);

            appenderRegistered = true;
            log.info("LogInspector: in-memory log appender registered");
        } catch (Throwable e) {
            log.debug("LogInspector: Logback not available, log capture disabled");
        }
    }

    /**
     * Create a dynamic appender object that extends AppenderBase<ILoggingEvent>
     * using an anonymous subclass via bytecode... actually simpler: use a Proxy.
     */
    private Object createDynamicAppender(Class<?> appenderBaseClass, Class<?> eventClass, Object context) throws Exception {
        // We'll create a concrete subclass at runtime
        // Use a simple approach: create a class that extends AppenderBase dynamically

        // Actually, the simplest approach is to use a separate class that we can load via reflection
        // But that requires the class to be compilable...

        // Alternative: Use Spring's ProxyFactory or just use java.lang.reflect.Proxy
        // But AppenderBase is a class, not an interface...

        // Simplest: Create an instance of our own class that doesn't extend AppenderBase,
        // but implements Appender interface via composition

        Class<?> appenderInterface = Class.forName("ch.qos.logback.core.Appender");
        Class<?> contextInterface = Class.forName("ch.qos.logback.core.Context");
        Class<?> lifeCycleInterface = Class.forName("ch.qos.logback.core.spi.LifeCycle");

        // Use java.lang.reflect.Proxy to implement Appender interface
        // But Appender extends LifeCycle which has start()/stop(), and Appender has doAppend()

        // Create a proxy that implements Appender<ILoggingEvent>
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if (methodName.equals("doAppend")) {
                // Extract log event data
                Object event = args[0];
                Map<String, Object> entry = extractLogEntry(event);
                if (entry != null) {
                    ringBuffer.addLast(entry);
                    while (ringBuffer.size() > MAX_ENTRIES) {
                        ringBuffer.pollFirst();
                    }
                }
                return null;
            } else if (methodName.equals("getName")) {
                return "DEBUG_AGENT_IN_MEMORY";
            } else if (methodName.equals("start") || methodName.equals("stop")) {
                return null;
            } else if (methodName.equals("isStarted")) {
                return true;
            } else if (methodName.equals("setContext")) {
                return null;
            } else if (methodName.equals("getContext")) {
                return context;
            }
            return null;
        };

        return java.lang.reflect.Proxy.newProxyInstance(
                this.getClass().getClassLoader(),
                new Class<?>[]{appenderInterface},
                handler);
    }

    private Map<String, Object> extractLogEntry(Object event) {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();

            // getTimeStamp()
            Method getTimeStamp = event.getClass().getMethod("getTimeStamp");
            long ts = (long) getTimeStamp.invoke(event);
            entry.put("timestamp", Instant.ofEpochMilli(ts).toString());

            // getLevel()
            Method getLevel = event.getClass().getMethod("getLevel");
            Object level = getLevel.invoke(event);
            entry.put("level", level != null ? level.toString() : "UNKNOWN");

            // getLoggerName()
            Method getLoggerName = event.getClass().getMethod("getLoggerName");
            entry.put("logger", getLoggerName.invoke(event));

            // getFormattedMessage()
            Method getFormattedMessage = event.getClass().getMethod("getFormattedMessage");
            entry.put("message", getFormattedMessage.invoke(event));

            // getThrowableProxy()
            Method getThrowableProxy = event.getClass().getMethod("getThrowableProxy");
            Object throwableProxy = getThrowableProxy.invoke(event);
            if (throwableProxy != null) {
                Method getClassName = throwableProxy.getClass().getMethod("getClassName");
                Method getMessage = throwableProxy.getClass().getMethod("getMessage");
                entry.put("exception", getClassName.invoke(throwableProxy) + ": " + getMessage.invoke(throwableProxy));
            }

            return entry;
        } catch (Exception e) {
            return null;
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

        int limit = (maxEntries == null || maxEntries <= 0) ? 50 : Math.min(maxEntries, 200);
        String minLevel = (level == null || level.isBlank()) ? "WARN" : level.toUpperCase().trim();

        List<Map<String, Object>> result = new ArrayList<>();
        Iterator<Map<String, Object>> it = ringBuffer.descendingIterator();
        while (it.hasNext() && result.size() < limit) {
            Map<String, Object> entry = it.next();
            String entryLevel = (String) entry.getOrDefault("level", "");
            if (compareLevels(entryLevel, minLevel) >= 0) {
                result.add(entry);
            }
        }

        return result;
    }

    /**
     * Compare log levels. Returns >= 0 if entryLevel >= minLevel.
     * ERROR > WARN > INFO > DEBUG > TRACE
     */
    private int compareLevels(String entryLevel, String minLevel) {
        int entryRank = levelRank(entryLevel);
        int minRank = levelRank(minLevel);
        return entryRank - minRank;
    }

    private int levelRank(String level) {
        return switch (level.toUpperCase()) {
            case "ERROR" -> 4;
            case "WARN" -> 3;
            case "INFO" -> 2;
            case "DEBUG" -> 1;
            case "TRACE" -> 0;
            default -> 2;  // default INFO
        };
    }
}
