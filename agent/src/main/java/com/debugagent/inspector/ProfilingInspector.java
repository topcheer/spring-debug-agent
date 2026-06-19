package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;

import java.lang.management.*;
import java.util.*;

/**
 * Profiling tools: CPU hotspots and allocation hotspots.
 * Uses built-in JFR (Java Flight Recorder) and ThreadMXBean for sampling.
 */
public class ProfilingInspector {

    private volatile boolean profiling = false;
    private long profileStartNanos;
    private Map<String, Long> cpuSamples = new LinkedHashMap<>();
    private Map<String, Long> allocationSamples = new LinkedHashMap<>();

    @DebugTool(description = "Sample CPU hotspots: capture thread stack traces over a brief interval and identify methods consuming the most CPU time. Returns top hot methods by sample frequency.")
    public Map<String, Object> getCpuHotspots(
            @ToolParam(description = "Sampling duration in seconds (default 5, max 30)") Integer durationSeconds
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        int duration = Math.min(Math.max(durationSeconds != null ? durationSeconds : 5, 1), 30);

        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        if (!tmx.isThreadCpuTimeEnabled()) {
            tmx.setThreadCpuTimeEnabled(true);
        }

        Map<String, Integer> methodCounts = new LinkedHashMap<>();
        int totalSamples = 0;
        long startTime = System.currentTimeMillis();

        // Sample loop
        for (int i = 0; i < duration * 10; i++) { // 10 samples per second
            long[] threadIds = tmx.getAllThreadIds();
            for (long tid : threadIds) {
                java.lang.management.ThreadInfo info = tmx.getThreadInfo(tid, 20);
                if (info == null) continue;
                Thread.State state = info.getThreadState();
                if (state == Thread.State.RUNNABLE) {
                    StackTraceElement[] stack = info.getStackTrace();
                    if (stack.length > 0) {
                        // Get the hottest application frame (skip java.lang, sun.*)
                        for (StackTraceElement frame : stack) {
                            String className = frame.getClassName();
                            if (className.startsWith("java.lang") || className.startsWith("sun.")
                                    || className.startsWith("jdk.")) continue;
                            String key = className + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                            methodCounts.merge(key, 1, Integer::sum);
                            break;
                        }
                        totalSamples++;
                    }
                }
            }

            try {
                Thread.sleep(100); // 100ms interval
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Sort by sample count
        final int total = totalSamples;
        List<Map<String, Object>> hotspots = new ArrayList<>();
        methodCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("method", e.getKey());
                    h.put("samples", e.getValue());
                    h.put("cpuShare", String.format("%.1f%%", e.getValue() * 100.0 / Math.max(1, total)));
                    hotspots.add(h);
                });

        result.put("samplingDurationMs", elapsed);
        result.put("totalSamples", totalSamples);
        result.put("hotspots", hotspots);

        if (hotspots.isEmpty()) {
            result.put("note", "No runnable threads detected during sampling period. " +
                    "The application may be idle.");
        }

        return result;
    }

    @DebugTool(description = "Sample memory allocation hotspots: identify which methods allocate the most objects. Uses ThreadMXBean allocated bytes tracking. Useful for finding memory pressure sources.")
    public Map<String, Object> getAllocationHotspots(
            @ToolParam(description = "Sampling duration in seconds (default 5, max 30)") Integer durationSeconds
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        int duration = Math.min(Math.max(durationSeconds != null ? durationSeconds : 5, 1), 30);

        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean sunTmx = null;

        try {
            sunTmx = (com.sun.management.ThreadMXBean) tmx;
            if (!sunTmx.isThreadAllocatedMemoryEnabled()) {
                sunTmx.setThreadAllocatedMemoryEnabled(true);
            }
        } catch (ClassCastException e) {
            result.put("error", "Allocation tracking requires HotSpot JVM (com.sun.management.ThreadMXBean)");
            result.put("currentJvm", ManagementFactory.getRuntimeMXBean().getVmVendor());
            return result;
        }

        // Sample allocation per thread
        Map<String, Long> threadAllocations = new LinkedHashMap<>();
        Map<Long, Long> previousAllocations = new HashMap<>();
        long[] threadIds = tmx.getAllThreadIds();

        // Baseline
        for (long tid : threadIds) {
            previousAllocations.put(tid, sunTmx.getThreadAllocatedBytes(tid));
        }

        // Wait
        try {
            Thread.sleep(duration * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Measure delta
        threadIds = tmx.getAllThreadIds();
        long totalAllocated = 0;
        for (long tid : threadIds) {
            long current = sunTmx.getThreadAllocatedBytes(tid);
            Long previous = previousAllocations.get(tid);
            if (previous != null) {
                long delta = current - previous;
                if (delta > 0) {
                    java.lang.management.ThreadInfo info = tmx.getThreadInfo(tid);
                    String name = info != null ? info.getThreadName() : "thread-" + tid;
                    threadAllocations.put(name, delta);
                    totalAllocated += delta;
                }
            }
        }

        // Sort by allocation size
        List<Map<String, Object>> top = new ArrayList<>();
        final long finalTotal = totalAllocated;
        threadAllocations.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15)
                .forEach(e -> {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("thread", e.getKey());
                    h.put("allocatedBytes", e.getValue());
                    h.put("allocatedMB", String.format("%.2f", e.getValue() / 1024.0 / 1024.0));
                    h.put("share", String.format("%.1f%%", e.getValue() * 100.0 / Math.max(1, finalTotal)));
                    top.add(h);
                });

        result.put("samplingDurationSeconds", duration);
        result.put("totalAllocatedMB", String.format("%.2f", totalAllocated / 1024.0 / 1024.0));
        result.put("topAllocatingThreads", top);

        return result;
    }
}
