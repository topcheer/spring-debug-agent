package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.*;

/**
 * System-level control tools (GC trigger, runtime system info).
 */
public class SystemControlInspector {

    @DebugTool(description = "Trigger a garbage collection and show before/after memory comparison. Useful for detecting memory leaks or analyzing object retention.")
    public Map<String, Object> triggerGc(
            @ToolParam(description = "Wait for GC completion (milliseconds, default 1000)", required = false) Integer waitMs
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        int wait = waitMs != null && waitMs > 0 ? waitMs : 1000;

        // Snapshot before
        MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();
        long beforeUsed = beforeHeap.getUsed();
        long beforeTotal = beforeHeap.getCommitted();

        // Trigger GC
        System.gc();

        // Wait briefly
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
        }

        // Snapshot after
        MemoryUsage afterHeap = memoryBean.getHeapMemoryUsage();
        long afterUsed = afterHeap.getUsed();
        long afterTotal = afterHeap.getCommitted();
        long freed = beforeUsed - afterUsed;

        result.put("beforeUsedMB", String.format("%.1f", beforeUsed / 1024.0 / 1024.0));
        result.put("afterUsedMB", String.format("%.1f", afterUsed / 1024.0 / 1024.0));
        result.put("freedMB", String.format("%.1f", Math.max(0, freed) / 1024.0 / 1024.0));
        result.put("beforeTotalMB", String.format("%.1f", beforeTotal / 1024.0 / 1024.0));
        result.put("afterTotalMB", String.format("%.1f", afterTotal / 1024.0 / 1024.0));
        result.put("freedPercent", beforeUsed > 0
                ? String.format("%.1f%%", (double) Math.max(0, freed) / beforeUsed * 100)
                : "0%");
        result.put("note", "System.gc() is a hint, not a guarantee. Actual GC behavior depends on JVM.");

        return result;
    }

    @DebugTool(description = "Capture a full thread dump of all running threads with their stack traces. "
            + "Returns a compact summary (thread name, state, top stack frames) and detect deadlocks.")
    public Map<String, Object> dumpThreadStack(
            @ToolParam(description = "Max stack frames per thread (default 15)", required = false) Integer maxFrames
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        int frames = maxFrames != null && maxFrames > 0 ? maxFrames : 15;

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        result.put("threadCount", threadBean.getThreadCount());
        result.put("peakThreadCount", threadBean.getPeakThreadCount());
        result.put("daemonThreadCount", threadBean.getDaemonThreadCount());

        // Detect deadlocks
        long[] deadlocks = threadBean.findDeadlockedThreads();
        if (deadlocks != null && deadlocks.length > 0) {
            result.put("deadlockDetected", true);
            result.put("deadlockedThreadIds", deadlocks);
        } else {
            result.put("deadlockDetected", false);
        }

        // Get all threads
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        List<Map<String, Object>> threadList = new ArrayList<>();

        for (Thread t : threads) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", t.getName());
            info.put("id", t.getId());
            info.put("state", t.getState().toString());
            info.put("priority", t.getPriority());
            info.put("daemon", t.isDaemon());

            StackTraceElement[] stack = t.getStackTrace();
            if (stack.length > 0) {
                List<String> framesList = new ArrayList<>();
                int limit = Math.min(stack.length, frames);
                for (int i = 0; i < limit; i++) {
                    framesList.add(stack[i].toString());
                }
                info.put("stack", framesList);
            }

            threadList.add(info);
        }

        result.put("threads", threadList);
        return result;
    }

    @DebugTool(description = "Trigger a heap dump (hprof format) to a temporary file. "
            + "Returns the file path. The dump can be analyzed with MAT, VisualVM, or jhat. "
            + "Warning: may be large and cause a brief JVM pause.")
    public Map<String, Object> dumpHeap(
            @ToolParam(description = "Output file path (null for temp file)", required = false) String outputPath
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            String path = outputPath;
            if (path == null || path.isEmpty()) {
                path = System.getProperty("java.io.tmpdir") + "/heapdump-"
                        + System.currentTimeMillis() + ".hprof";
            }

            // Try HotSpot Diagnostic MXBean (Oracle/OpenJDK)
            Object diagBean = ManagementFactory.newPlatformMXBeanProxy(
                    ManagementFactory.getPlatformMBeanServer(),
                    "com.sun.management:type=HotSpotDiagnostic",
                    Class.forName("com.sun.management.HotSpotDiagnosticMXBean"));

            if (diagBean != null) {
                java.lang.reflect.Method dumpHeap = diagBean.getClass()
                        .getMethod("dumpHeap", String.class, boolean.class);
                dumpHeap.invoke(diagBean, path, true);

                java.io.File f = new java.io.File(path);
                result.put("status", "OK");
                result.put("path", path);
                result.put("sizeMB", String.format("%.1f", f.length() / 1024.0 / 1024.0));
                result.put("note", "Analyze with: jhat, Eclipse MAT, or VisualVM");
            } else {
                result.put("error", "HotSpotDiagnosticMXBean not available on this JVM");
            }
        } catch (Exception e) {
            result.put("error", "Failed to dump heap: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }
}
