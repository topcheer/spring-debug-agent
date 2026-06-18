package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
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
}
