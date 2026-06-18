package com.debugagent.inspector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Static holder for watch point data. Accessed by ByteBuddy-generated interceptor code.
 *
 * This class is intentionally static and stateless regarding Spring — it's called
 * from instrumented application code and must not depend on Spring-managed beans.
 */
public class WatchPointHolder {

    /** Active watch point IDs → metadata */
    private static final Map<String, WatchPointInfo> activeWatchPoints = new ConcurrentHashMap<>();

    /** Ring buffer of recent records per watch point ID */
    private static final Map<String, Queue<WatchPointRecord>> records = new ConcurrentHashMap<>();

    private static final int MAX_RECORDS_PER_WATCH = 100;

    /**
     * Register a watch point as active.
     */
    public static void activate(String watchPointId, String className, String methodName) {
        activeWatchPoints.put(watchPointId, new WatchPointInfo(watchPointId, className, methodName));
        records.put(watchPointId, new ConcurrentLinkedQueue<>());
    }

    /**
     * Deactivate a watch point (interceptors become no-ops, records retained).
     */
    public static void deactivate(String watchPointId) {
        activeWatchPoints.remove(watchPointId);
    }

    /**
     * Check if any watch point matches the given class+method.
     * Returns the watch point ID if active, null otherwise.
     */
    public static String findActiveWatchPoint(String className, String methodName) {
        for (Map.Entry<String, WatchPointInfo> entry : activeWatchPoints.entrySet()) {
            WatchPointInfo info = entry.getValue();
            if (info.matches(className, methodName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Record a captured invocation. Called from the interceptor.
     */
    public static void record(String watchPointId, String className, String methodName,
                              Object[] args, Object returnValue, Throwable thrown,
                              long durationMs, Map<String, Object> thisFields) {
        Queue<WatchPointRecord> queue = records.get(watchPointId);
        if (queue == null) return;

        WatchPointRecord record = new WatchPointRecord(
                watchPointId, className, methodName,
                System.currentTimeMillis(),
                args, returnValue, thrown, durationMs, thisFields);

        queue.add(record);
        // Trim if too many
        while (queue.size() > MAX_RECORDS_PER_WATCH) {
            queue.poll();
        }
    }

    /**
     * Get all records for a watch point.
     */
    public static List<WatchPointRecord> getRecords(String watchPointId) {
        Queue<WatchPointRecord> queue = records.get(watchPointId);
        if (queue == null) return Collections.emptyList();
        return new ArrayList<>(queue);
    }

    /**
     * Clear records for a watch point.
     */
    public static void clearRecords(String watchPointId) {
        Queue<WatchPointRecord> queue = records.get(watchPointId);
        if (queue != null) queue.clear();
    }

    /**
     * Get all active watch point IDs.
     */
    public static Set<String> getActiveWatchPointIds() {
        return Collections.unmodifiableSet(activeWatchPoints.keySet());
    }

    /**
     * Get info about an active watch point.
     */
    public static WatchPointInfo getWatchPointInfo(String watchPointId) {
        return activeWatchPoints.get(watchPointId);
    }

    // ==================== Inner Classes ====================

    public static class WatchPointInfo {
        private final String watchPointId;
        private final String className;
        private final String methodName;
        private final long createdAt;

        public WatchPointInfo(String watchPointId, String className, String methodName) {
            this.watchPointId = watchPointId;
            this.className = className;
            this.methodName = methodName;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean matches(String cls, String method) {
            return className.equals(cls) && methodName.equals(method);
        }

        public String getWatchPointId() { return watchPointId; }
        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public long getCreatedAt() { return createdAt; }
    }
}
