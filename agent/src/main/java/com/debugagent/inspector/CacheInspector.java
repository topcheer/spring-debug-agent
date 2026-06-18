package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring Cache statistics inspection tool.
 * Uses reflection on CacheManager and Cache — no hard cache library dependency.
 */
public class CacheInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get Spring Cache statistics: cache names, sizes, and hit/miss ratios. Supports Caffeine, ConcurrentMapCache, and other Spring CacheManager implementations.")
    public List<Map<String, Object>> getCacheStats() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            Object cacheManager = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.cache.CacheManager");
            if (cacheManager == null) {
                results.add(Map.of("info", "No CacheManager found. @EnableCaching may not be enabled."));
                return results;
            }

            // Get cache names
            Object cacheNames = ReflectionHelper.invokeMethod(cacheManager, "getCacheNames");
            if (!(cacheNames instanceof Collection<?> names)) {
                results.add(Map.of("error", "Could not retrieve cache names"));
                return results;
            }

            for (Object name : names) {
                Map<String, Object> cacheInfo = new LinkedHashMap<>();
                cacheInfo.put("name", name.toString());

                Object cache = null;
                try {
                    Method getCache = cacheManager.getClass().getMethod("getCache", String.class);
                    cache = getCache.invoke(cacheManager, name.toString());
                } catch (Exception ignored) {}

                if (cache != null) {
                    cacheInfo.put("type", cache.getClass().getSimpleName());

                    // Try getNativeCache() to access underlying cache
                    Object nativeCache = ReflectionHelper.invokeMethod(cache, "getNativeCache");
                    if (nativeCache != null) {
                        Map<String, Object> nativeStats = inspectNativeCache(nativeCache);
                        if (!nativeStats.isEmpty()) {
                            cacheInfo.put("stats", nativeStats);
                        }
                    }

                    // Try size estimation
                    if (nativeCache instanceof Map<?, ?> map) {
                        cacheInfo.put("size", map.size());
                    } else {
                        Object size = estimateCacheSize(nativeCache);
                        if (size != null) cacheInfo.put("size", size);
                    }
                }

                results.add(cacheInfo);
            }

            if (results.isEmpty()) {
                results.add(Map.of("info", "No caches registered"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get cache stats: " + e.getMessage()));
        }

        return results;
    }

    private Map<String, Object> inspectNativeCache(Object nativeCache) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Try Caffeine cache stats
        try {
            Object estimatedSize = ReflectionHelper.invokeMethod(nativeCache, "estimatedSize");
            if (estimatedSize != null) stats.put("estimatedSize", estimatedSize);

            Object statsObj = ReflectionHelper.invokeMethod(nativeCache, "stats");
            if (statsObj != null) {
                Object hitCount = ReflectionHelper.invokeMethod(statsObj, "hitCount");
                Object missCount = ReflectionHelper.invokeMethod(statsObj, "missCount");
                Object evictionCount = ReflectionHelper.invokeMethod(statsObj, "evictionCount");
                Object requestCount = ReflectionHelper.invokeMethod(statsObj, "requestCount");
                if (hitCount != null) stats.put("hitCount", hitCount);
                if (missCount != null) stats.put("missCount", missCount);
                if (evictionCount != null) stats.put("evictionCount", evictionCount);
                if (requestCount != null) {
                    stats.put("requestCount", requestCount);
                    long hits = hitCount instanceof Number ? ((Number) hitCount).longValue() : 0;
                    long total = requestCount instanceof Number ? ((Number) requestCount).longValue() : 0;
                    if (total > 0) {
                        stats.put("hitRate", String.format("%.2f%%", (double) hits / total * 100));
                    }
                }
            }
        } catch (Exception ignored) {}

        return stats;
    }

    private Object estimateCacheSize(Object nativeCache) {
        if (nativeCache instanceof Map<?, ?> map) return map.size();
        // Try size() method via reflection for JCache or other implementations
        Object size = ReflectionHelper.invokeMethod(nativeCache, "size");
        return size;
    }
}
