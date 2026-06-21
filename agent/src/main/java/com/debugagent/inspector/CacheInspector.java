package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.cache.CacheManager;
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

    @DebugTool(description = "Evict all entries from a cache, or clear a specific key. "
            + "Useful for forcing cache refresh or troubleshooting stale data.")
    public Map<String, Object> evictCache(
            @ToolParam(description = "Cache name to evict") String cacheName,
            @ToolParam(description = "Specific key to evict (null to clear entire cache)", required = false) String key
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Object cacheManager = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.cache.CacheManager");
            if (cacheManager == null) {
                result.put("error", "No CacheManager found");
                return result;
            }

            Object cache = ReflectionHelper.invokeMethod(cacheManager, "getCache", cacheName);
            if (cache == null) {
                result.put("error", "Cache not found: " + cacheName);
                return result;
            }

            if (key != null && !key.isEmpty()) {
                ReflectionHelper.invokeMethod(cache, "evict", key);
                result.put("status", "evicted_key");
                result.put("cacheName", cacheName);
                result.put("key", key);
            } else {
                ReflectionHelper.invokeMethod(cache, "clear");
                result.put("status", "cleared");
                result.put("cacheName", cacheName);
            }
        } catch (Exception e) {
            result.put("error", "Failed to evict cache: " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get detailed cache configuration: Caffeine spec, TTL, max size, "
            + "weigher, expireAfterWrite, expireAfterAccess, recordStats settings.")
    public List<Map<String, Object>> getCacheConfig() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            Object cacheManager = ReflectionHelper.getFirstBeanOfType(ctx,
                    "org.springframework.cache.CacheManager");
            if (cacheManager == null) {
                results.add(Map.of("info", "No CacheManager found"));
                return results;
            }

            Map<String, Object> cmInfo = new LinkedHashMap<>();
            cmInfo.put("cacheManagerType", cacheManager.getClass().getName());

            Object cacheNames = ReflectionHelper.invokeMethod(cacheManager, "getCacheNames");
            if (cacheNames instanceof Collection<?> names) {
                for (Object name : names) {
                    Map<String, Object> cfg = new LinkedHashMap<>();
                    cfg.put("cacheName", name.toString());

                    Object cache = ReflectionHelper.invokeMethod(cacheManager, "getCache", name.toString());
                    if (cache != null) {
                        Object nativeCache = ReflectionHelper.invokeMethod(cache, "getNativeCache");
                        cfg.put("nativeType", nativeCache != null ? nativeCache.getClass().getName() : "unknown");

                        // Caffeine: try to read builder spec
                        if (nativeCache != null && nativeCache.getClass().getName().contains("Caffeine")) {
                            Map<String, Object> caffeine = new LinkedHashMap<>();
                            try {
                                Object policy = ReflectionHelper.invokeMethod(nativeCache, "policy");
                                if (policy != null) {
                                    caffeine.put("policy", policy.getClass().getSimpleName());

                                    // Try expireAfterWrite
                                    Object expireVar = ReflectionHelper.invokeMethod(policy, "expireAfterWrite");
                                    if (expireVar != null) {
                                        caffeine.put("expireAfterWrite", ReflectionHelper.safeToString(expireVar));
                                    }
                                    Object expireAcc = ReflectionHelper.invokeMethod(policy, "expireAfterAccess");
                                    if (expireAcc != null) {
                                        caffeine.put("expireAfterAccess", ReflectionHelper.safeToString(expireAcc));
                                    }
                                }
                            } catch (Exception ignored) {}
                            cfg.put("caffeineConfig", caffeine);
                        }
                    }

                    results.add(cfg);
                }
            }
            results.add(0, cmInfo);
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get cache config: " + e.getMessage()));
        }

        return results;
    }
}
