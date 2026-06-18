package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.lang.management.*;
import java.util.*;

/**
 * JVM compilation and memory pool detail inspector.
 * Provides JIT compilation stats and detailed memory pool breakdowns
 * that complement the basic GC stats in JvmInspector.
 */
@Component
public class CompilationInspector {

    @DebugTool(description = "Get JIT compilation statistics: total compilation time, number of compilations, compiler name, and whether compilation is monitoring-supported.")
    public Map<String, Object> getCompilationStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            CompilationMXBean compile = ManagementFactory.getCompilationMXBean();
            result.put("name", compile.getName());
            result.put("totalCompilationTimeMs", compile.getTotalCompilationTime());
            result.put("isCompilationTimeMonitoringSupported", compile.isCompilationTimeMonitoringSupported());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Get detailed memory pool breakdown: each pool's used/committed/max memory, GC usage threshold, collection usage thresholds, and whether thresholds are supported/exceeded.")
    public Map<String, Object> getMemoryPoolDetails(
            @ToolParam(description = "Filter to a specific pool name (e.g., 'G1 Old Gen', 'Metaspace'). Leave empty for all pools.") String poolFilter
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
            List<Map<String, Object>> poolDetails = new ArrayList<>();

            for (MemoryPoolMXBean pool : pools) {
                String name = pool.getName();
                if (poolFilter != null && !poolFilter.isBlank()
                        && !name.toLowerCase().contains(poolFilter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", name);
                p.put("type", pool.getType().name());
                p.put("isValid", pool.isValid());
                p.put("managerNames", Arrays.asList(pool.getMemoryManagerNames()));

                // Current usage
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    p.put("usage", formatMemoryUsage(usage));
                }

                // Peak usage
                MemoryUsage peak = pool.getPeakUsage();
                if (peak != null) {
                    p.put("peakUsage", formatMemoryUsage(peak));
                }

                // Collection usage (post-GC snapshot)
                MemoryUsage collectionUsage = pool.getCollectionUsage();
                if (collectionUsage != null) {
                    p.put("collectionUsage", formatMemoryUsage(collectionUsage));
                }

                // Usage thresholds
                p.put("usageThresholdSupported", pool.isUsageThresholdSupported());
                if (pool.isUsageThresholdSupported()) {
                    p.put("usageThreshold", pool.getUsageThreshold());
                    p.put("usageThresholdExceeded", pool.isUsageThresholdExceeded());
                    p.put("collectionUsageThresholdSupported", pool.isCollectionUsageThresholdSupported());
                    if (pool.isCollectionUsageThresholdSupported()) {
                        p.put("collectionUsageThreshold", pool.getCollectionUsageThreshold());
                        p.put("collectionUsageThresholdExceeded", pool.isCollectionUsageThresholdExceeded());
                    }
                }

                poolDetails.add(p);
            }

            result.put("pools", poolDetails);
            result.put("poolCount", poolDetails.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @DebugTool(description = "Get memory manager (GC algorithm) details: manager name, managed memory pools, and GC statistics for each manager.")
    Map<String, Object> getMemoryManagerStats(
            @ToolParam(description = "Filter to a specific manager name (e.g., 'G1 Young Generation'). Leave empty for all.") String managerFilter
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<MemoryManagerMXBean> managers = ManagementFactory.getMemoryManagerMXBeans();
            List<Map<String, Object>> managerDetails = new ArrayList<>();

            for (MemoryManagerMXBean mgr : managers) {
                String name = mgr.getName();
                if (managerFilter != null && !managerFilter.isBlank()
                        && !name.toLowerCase().contains(managerFilter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("isValid", mgr.isValid());
                m.put("managedMemoryPools", Arrays.asList(mgr.getMemoryPoolNames()));

                // If this is a GarbageCollectorMXBean, add GC stats
                if (mgr instanceof GarbageCollectorMXBean gc) {
                    m.put("gcCount", gc.getCollectionCount());
                    m.put("gcTimeMs", gc.getCollectionTime());
                }

                managerDetails.add(m);
            }

            result.put("managers", managerDetails);
            result.put("managerCount", managerDetails.size());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Helpers ====================

    private Map<String, Object> formatMemoryUsage(MemoryUsage usage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("init", formatBytes(usage.getInit()));
        m.put("used", formatBytes(usage.getUsed()));
        m.put("committed", formatBytes(usage.getCommitted()));
        m.put("max", formatBytes(usage.getMax()));
        if (usage.getMax() > 0) {
            double pct = (double) usage.getUsed() / usage.getMax() * 100;
            m.put("usedPercent", String.format("%.1f%%", pct));
        }
        m.put("usedBytes", usage.getUsed());
        m.put("maxBytes", usage.getMax());
        return m;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "undefined";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
