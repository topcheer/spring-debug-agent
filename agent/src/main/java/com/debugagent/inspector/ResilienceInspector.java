package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Resilience diagnostic tools.
 * Inspects circuit breakers, retry configs, and rate limiters (Resilience4j).
 * Conditional on Resilience4j being on classpath.
 */
public class ResilienceInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get all circuit breaker instances and their states: CLOSED, OPEN, HALF_OPEN. Shows failure rate, success/failure counts, and circuit breaker config. Essential for diagnosing cascading failures.")
    public List<Map<String, Object>> getCircuitBreakers(
            @ToolParam(description = "Filter by circuit breaker name (leave empty for all)") String name
    ) {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Class<?> registryClass = Class.forName("io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry");
            String[] registryNames = ctx.getBeanNamesForType(registryClass);

            for (String regName : registryNames) {
                Object registry = ctx.getBean(regName);

                // Get all circuit breakers
                Method getAll = registryClass.getMethod("getAllCircuitBreakers");
                @SuppressWarnings("unchecked")
                Set<Object> breakers = (Set<Object>) getAll.invoke(registry);

                for (Object cb : breakers) {
                    Map<String, Object> info = new LinkedHashMap<>();

                    String cbName = (String) ReflectionHelper.invokeMethod(cb, "getName");
                    if (name != null && !name.isBlank() && !cbName.equals(name)) continue;

                    info.put("name", cbName);

                    // State
                    Object state = ReflectionHelper.invokeMethod(cb, "getState");
                    info.put("state", state != null ? state.toString() : "unknown");

                    // Metrics
                    Object metrics = ReflectionHelper.invokeMethod(cb, "getMetrics");
                    if (metrics != null) {
                        info.put("failureRate", ReflectionHelper.invokeMethod(metrics, "getFailureRate"));
                        info.put("successRate", ReflectionHelper.invokeMethod(metrics, "getSuccessRate"));
                        info.put("numberOfCalls", ReflectionHelper.invokeMethod(metrics, "getNumberOfSuccessfulCalls")
                                + " success / "
                                + ReflectionHelper.invokeMethod(metrics, "getNumberOfFailedCalls")
                                + " failed");
                        info.put("bufferedCalls",
                                ReflectionHelper.invokeMethod(metrics, "getNumberOfBufferedCalls"));
                        info.put("notPermittedCalls",
                                ReflectionHelper.invokeMethod(metrics, "getNumberOfNotPermittedCalls"));
                        info.put("slowCallRate",
                                ReflectionHelper.invokeMethod(metrics, "getSlowCallRate"));
                    }

                    // Config
                    Object config = ReflectionHelper.invokeMethod(cb, "getCircuitBreakerConfig");
                    if (config != null) {
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        cfg.put("failureRateThreshold",
                                ReflectionHelper.invokeMethod(config, "getFailureRateThreshold"));
                        cfg.put("slowCallRateThreshold",
                                ReflectionHelper.invokeMethod(config, "getSlowCallRateThreshold"));
                        cfg.put("slowCallDurationThreshold",
                                ReflectionHelper.invokeMethod(config, "getSlowCallDurationThreshold"));
                        cfg.put("maxWaitDurationInHalfOpenState",
                                ReflectionHelper.invokeMethod(config, "getMaxWaitDurationInHalfOpenState"));
                        cfg.put("slidingWindowSize",
                                ReflectionHelper.invokeMethod(config, "getSlidingWindowSize"));
                        cfg.put("minimumNumberOfCalls",
                                ReflectionHelper.invokeMethod(config, "getMinimumNumberOfCalls"));
                        info.put("config", cfg);
                    }

                    result.add(info);
                }
            }

        } catch (ClassNotFoundException e) {
            result.add(Map.of("error", "Resilience4j not on classpath. " +
                    "Add spring-cloud-starter-circuitbreaker-resilience4j to enable."));
        } catch (Exception e) {
            result.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return result;
    }

    @DebugTool(description = "Get retry configuration and statistics. Shows retry names, max attempts, wait duration, and success/failure rates. Useful for diagnosing excessive retries causing cascading delays.")
    public List<Map<String, Object>> getRetryStats() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Class<?> registryClass = Class.forName("io.github.resilience4j.retry.RetryRegistry");
            String[] registryNames = ctx.getBeanNamesForType(registryClass);

            for (String regName : registryNames) {
                Object registry = ctx.getBean(regName);

                Method getAll = registryClass.getMethod("getAllRetries");
                @SuppressWarnings("unchecked")
                Set<Object> retries = (Set<Object>) getAll.invoke(registry);

                for (Object retry : retries) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", ReflectionHelper.invokeMethod(retry, "getName"));

                    // Config
                    Object config = ReflectionHelper.invokeMethod(retry, "getRetryConfig");
                    if (config != null) {
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        cfg.put("maxAttempts", ReflectionHelper.invokeMethod(config, "getMaxAttempts"));
                        cfg.put("waitDuration", ReflectionHelper.invokeMethod(config, "getWaitDuration"));
                        info.put("config", cfg);
                    }

                    // Metrics
                    Object metrics = ReflectionHelper.invokeMethod(retry, "getMetrics");
                    if (metrics != null) {
                        info.put("numberOfSuccessfulCallsWithoutRetry",
                                ReflectionHelper.invokeMethod(metrics, "getNumberOfSuccessfulCallsWithoutRetryAttempt"));
                        info.put("numberOfSuccessfulCallsWithRetry",
                                ReflectionHelper.invokeMethod(metrics, "getNumberOfSuccessfulCallsWithRetryAttempt"));
                        info.put("numberOfFailedCallsWithoutRetry",
                                ReflectionHelper.invokeMethod(metrics, "getNumberOfFailedCallsWithoutRetryAttempt"));
                        info.put("numberOfFailedCallsWithRetry",
                                ReflectionHelper.invokeMethod(metrics, "getNumberOfFailedCallsWithRetryAttempt"));
                    }

                    result.add(info);
                }
            }

            if (result.isEmpty()) {
                result.add(Map.of("note", "No retry instances configured."));
            }

        } catch (ClassNotFoundException e) {
            result.add(Map.of("error", "Resilience4j not on classpath"));
        } catch (Exception e) {
            result.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return result;
    }

    @DebugTool(description = "Get all rate limiter instances and their states. Shows available permissions, current limit, and waiting threads. Useful for diagnosing throttling issues or rate limit misconfiguration.")
    public List<Map<String, Object>> getRateLimiters() {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Class<?> registryClass = Class.forName("io.github.resilience4j.ratelimiter.RateLimiterRegistry");
            String[] registryNames = ctx.getBeanNamesForType(registryClass);

            for (String regName : registryNames) {
                Object registry = ctx.getBean(regName);

                Method getAll = registryClass.getMethod("getAllRateLimiters");
                @SuppressWarnings("unchecked")
                Set<Object> limiters = (Set<Object>) getAll.invoke(registry);

                for (Object limiter : limiters) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", ReflectionHelper.invokeMethod(limiter, "getName"));

                    // Metrics
                    Object metrics = ReflectionHelper.invokeMethod(limiter, "getMetrics");
                    if (metrics != null) {
                        info.put("availablePermissions",
                                ReflectionHelper.invokeMethod(metrics, "getAvailablePermissions"));
                        info.put("numberOfWaitingThreads",
                                ReflectionHelper.invokeMethod(metrics, "getNumberOfWaitingThreads"));
                        info.put("nanosToWait",
                                ReflectionHelper.invokeMethod(metrics, "getNanosToWait"));
                    }

                    // Config
                    Object config = ReflectionHelper.invokeMethod(limiter, "getRateLimiterConfig");
                    if (config != null) {
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        cfg.put("limitForPeriod",
                                ReflectionHelper.invokeMethod(config, "getLimitForPeriod"));
                        cfg.put("limitRefreshPeriod",
                                ReflectionHelper.invokeMethod(config, "getLimitRefreshPeriod"));
                        cfg.put("timeoutDuration",
                                ReflectionHelper.invokeMethod(config, "getTimeoutDuration"));
                        info.put("config", cfg);
                    }

                    result.add(info);
                }
            }

            if (result.isEmpty()) {
                result.add(Map.of("note", "No rate limiter instances configured."));
            }

        } catch (ClassNotFoundException e) {
            result.add(Map.of("error", "Resilience4j not on classpath"));
        } catch (Exception e) {
            result.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return result;
    }

    @DebugTool(description = "Get rate limiter call statistics: successful calls, failed calls (rate-limited), and throttling rate. Useful for verifying rate limiting is working correctly.")
    public List<Map<String, Object>> getRateLimitStats() {
        // For Resilience4j, rate limit stats are combined with getRateLimiters
        // This tool provides a summary-focused view
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            Class<?> registryClass = Class.forName("io.github.resilience4j.ratelimiter.RateLimiterRegistry");
            String[] registryNames = ctx.getBeanNamesForType(registryClass);

            for (String regName : registryNames) {
                Object registry = ctx.getBean(regName);
                Method getAll = registryClass.getMethod("getAllRateLimiters");
                @SuppressWarnings("unchecked")
                Set<Object> limiters = (Set<Object>) getAll.invoke(registry);

                for (Object limiter : limiters) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", ReflectionHelper.invokeMethod(limiter, "getName"));

                    Object metrics = ReflectionHelper.invokeMethod(limiter, "getMetrics");
                    if (metrics != null) {
                        int available = (Integer) ReflectionHelper.invokeMethod(metrics, "getAvailablePermissions");
                        int waiting = (Integer) ReflectionHelper.invokeMethod(metrics, "getNumberOfWaitingThreads");

                        Object config = ReflectionHelper.invokeMethod(limiter, "getRateLimiterConfig");
                        int limit = (Integer) ReflectionHelper.invokeMethod(config, "getLimitForPeriod");

                        info.put("limitPerPeriod", limit);
                        info.put("availablePermissions", available);
                        info.put("usedInCurrentPeriod", limit - available);
                        info.put("threadsWaiting", waiting);
                        info.put("throttling", waiting > 0);
                        info.put("utilizationRate",
                                String.format("%.1f%%", (limit - available) * 100.0 / Math.max(1, limit)));
                    }

                    result.add(info);
                }
            }

            if (result.isEmpty()) {
                result.add(Map.of("note", "No rate limiter instances configured."));
            }

        } catch (Exception e) {
            result.add(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return result;
    }
}
