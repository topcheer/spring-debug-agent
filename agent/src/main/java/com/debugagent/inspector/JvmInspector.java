package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.lang.management.*;
import java.util.*;

/**
 * JVM-level diagnostic tools.
 * Uses java.lang.management MXBeans to access JVM internals directly.
 */
@Component
public class JvmInspector {

    // ==================== Thread Tools ====================

    @DebugTool(description = "Get a summary of all thread states. Returns counts of RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, etc. Use this first to get an overview of thread health.")
    public Map<String, Object> getThreadSummary() {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("totalThreadCount", tb.getThreadCount());
        result.put("daemonThreadCount", tb.getDaemonThreadCount());
        result.put("peakThreadCount", tb.getPeakThreadCount());

        // Count by state
        Map<Thread.State, Integer> stateCounts = new LinkedHashMap<>();
        long[] allIds = tb.getAllThreadIds();
        for (long id : allIds) {
            ThreadInfo info = tb.getThreadInfo(id);
            if (info != null) {
                stateCounts.merge(info.getThreadState(), 1, Integer::sum);
            }
        }
        result.put("threadStates", stateCounts);

        // Deadlock check
        long[] deadlocks = tb.findDeadlockedThreads();
        result.put("deadlockedThreads", deadlocks != null ? deadlocks.length : 0);

        return result;
    }

    @DebugTool(description = "Get detailed thread dump with stack traces. Use this to investigate blocked threads, deadlocks, or high CPU usage.")
    public List<Map<String, Object>> getThreadDump(
            @ToolParam(description = "Whether to include full stack traces (default true)") Boolean includeStack,
            @ToolParam(description = "Filter to only show threads in this state (e.g. BLOCKED, WAITING). Leave empty for all.") String stateFilter
    ) {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        boolean withStack = includeStack == null || includeStack;
        int maxDepth = withStack ? 100 : 0;

        ThreadInfo[] threads = tb.dumpAllThreads(true, true, maxDepth);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ThreadInfo info : threads) {
            if (stateFilter != null && !stateFilter.isBlank()
                    && !info.getThreadState().name().equalsIgnoreCase(stateFilter)) {
                continue;
            }

            Map<String, Object> thread = new LinkedHashMap<>();
            thread.put("name", info.getThreadName());
            thread.put("threadId", info.getThreadId());
            thread.put("state", info.getThreadState().name());
            thread.put("blockedCount", info.getBlockedCount());
            thread.put("waitedCount", info.getWaitedCount());
            thread.put("blockedTime", info.getBlockedTime());
            thread.put("waitedTime", info.getWaitedTime());

            if (info.getLockName() != null) {
                thread.put("waitingOnLock", info.getLockName());
                thread.put("lockOwner", info.getLockOwnerName());
            }

            if (withStack) {
                StackTraceElement[] stack = info.getStackTrace();
                List<String> stackLines = new ArrayList<>();
                int limit = Math.min(stack.length, 30);
                for (int i = 0; i < limit; i++) {
                    stackLines.add(stack[i].toString());
                }
                if (stack.length > limit) {
                    stackLines.add("... " + (stack.length - limit) + " more frames");
                }
                thread.put("stackTrace", stackLines);
            }

            result.add(thread);
        }

        return result;
    }

    @DebugTool(description = "Detect deadlocked threads. Returns details about any threads involved in deadlocks.")
    public Map<String, Object> detectDeadlocks() {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = tb.findDeadlockedThreads();

        Map<String, Object> result = new LinkedHashMap<>();

        if (deadlockedIds == null || deadlockedIds.length == 0) {
            result.put("deadlockDetected", false);
            result.put("message", "No deadlocks detected.");
            return result;
        }

        result.put("deadlockDetected", true);
        List<Map<String, Object>> deadlockedThreads = new ArrayList<>();
        for (long id : deadlockedIds) {
            ThreadInfo info = tb.getThreadInfo(id);
            if (info != null) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", info.getThreadName());
                t.put("state", info.getThreadState());
                t.put("waitingOnLock", info.getLockName());
                t.put("lockOwner", info.getLockOwnerName());
                List<String> stack = new ArrayList<>();
                for (StackTraceElement el : info.getStackTrace()) {
                    stack.add(el.toString());
                }
                t.put("stackTrace", stack);
                deadlockedThreads.add(t);
            }
        }
        result.put("deadlockedThreads", deadlockedThreads);
        return result;
    }

    @DebugTool(description = "Get CPU time consumed by each thread. Useful for finding CPU-intensive threads.")
    public List<Map<String, Object>> getCpuConsumingThreads(
            @ToolParam(description = "Maximum number of threads to return (default 10)") Integer limit
    ) {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        if (!tb.isThreadCpuTimeSupported()) {
            return List.of(Map.of("error", "Thread CPU time is not supported by this JVM"));
        }
        tb.setThreadCpuTimeEnabled(true);

        int max = limit != null ? limit : 10;
        long[] ids = tb.getAllThreadIds();

        List<Map<String, Object>> threads = new ArrayList<>();
        for (long id : ids) {
            long cpuTime = tb.getThreadCpuTime(id);
            long userTime = tb.getThreadUserTime(id);
            ThreadInfo info = tb.getThreadInfo(id);
            if (info != null && cpuTime >= 0) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", info.getThreadName());
                t.put("state", info.getThreadState().name());
                t.put("cpuTimeMs", cpuTime / 1_000_000);
                t.put("userTimeMs", userTime / 1_000_000);
                threads.add(t);
            }
        }

        threads.sort((a, b) -> Long.compare(
                (Long) b.get("cpuTimeMs"), (Long) a.get("cpuTimeMs")));

        return threads.subList(0, Math.min(max, threads.size()));
    }

    // ==================== Memory Tools ====================

    @DebugTool(description = "Get JVM heap and non-heap memory usage summary, including eden, survivor, old gen breakdown.")
    public Map<String, Object> getMemorySummary() {
        MemoryMXBean mb = ManagementFactory.getMemoryMXBean();
        Map<String, Object> result = new LinkedHashMap<>();

        // Heap
        MemoryUsage heap = mb.getHeapMemoryUsage();
        result.put("heap", formatMemoryUsage(heap));

        // Non-heap
        MemoryUsage nonHeap = mb.getNonHeapMemoryUsage();
        result.put("nonHeap", formatMemoryUsage(nonHeap));

        // Object pending finalization
        result.put("objectsPendingFinalization", mb.getObjectPendingFinalizationCount());

        // Detailed memory pools (Eden, Survivor, Old, Metaspace, etc.)
        Map<String, Object> pools = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                Map<String, Object> poolInfo = formatMemoryUsage(usage);
                poolInfo.put("type", pool.getType().name());
                pools.put(pool.getName(), poolInfo);
            }
        }
        result.put("memoryPools", pools);

        return result;
    }

    @DebugTool(description = "Get GC (garbage collection) statistics including collection count, time, and which memory pools were affected.")
    public List<Map<String, Object>> getGcStats() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", gc.getName());
            info.put("collectionCount", gc.getCollectionCount());
            info.put("collectionTimeMs", gc.getCollectionTime());
            info.put("memoryPools", Arrays.asList(gc.getMemoryPoolNames()));
            result.add(info);
        }

        return result;
    }

    // ==================== Runtime Info ====================

    @DebugTool(description = "Get JVM runtime info: Java version, JVM name, uptime, loaded classes, available processors, etc.")
    public Map<String, Object> getRuntimeInfo() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        Runtime runtime = Runtime.getRuntime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", rt.getVmVersion());
        result.put("javaVendor", rt.getVmVendor());
        result.put("jvmName", rt.getVmName());
        result.put("uptimeMs", rt.getUptime());
        result.put("uptimeHuman", formatDuration(rt.getUptime()));

        result.put("availableProcessors", runtime.availableProcessors());

        // Class loading
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        result.put("loadedClassCount", cl.getLoadedClassCount());
        result.put("totalLoadedClassCount", cl.getTotalLoadedClassCount());
        result.put("unloadedClassCount", cl.getUnloadedClassCount());

        // JVM arguments
        result.put("jvmArguments", rt.getInputArguments());

        return result;
    }

    // ==================== Helpers ====================

    private Map<String, Object> formatMemoryUsage(MemoryUsage usage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("used", formatBytes(usage.getUsed()));
        m.put("usedBytes", usage.getUsed());
        m.put("committed", formatBytes(usage.getCommitted()));
        m.put("committedBytes", usage.getCommitted());
        m.put("max", formatBytes(usage.getMax()));
        m.put("maxBytes", usage.getMax());
        if (usage.getMax() > 0) {
            double usedPct = (double) usage.getUsed() / usage.getMax() * 100;
            m.put("usedPercent", String.format("%.1f%%", usedPct));
        }
        return m;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "undefined";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, secs);
        if (minutes > 0) return String.format("%dm %ds", minutes, secs);
        return String.format("%ds", secs);
    }
}
