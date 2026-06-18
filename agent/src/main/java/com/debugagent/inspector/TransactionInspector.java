package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Spring Transaction monitoring tools.
 * <p>
 * Tracks transaction lifecycle (begin, commit, rollback) via
 * {@link TransactionSynchronizationManager} and {@link TransactionSynchronization}.
 * No hard dependency on any specific transaction manager — works with
 * DataSourceTransactionManager, JpaTransactionManager, etc.
 */
public class TransactionInspector implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(TransactionInspector.class);
    private static final int MAX_ENTRIES = 200;

    private ApplicationContext ctx;
    private final Deque<Map<String, Object>> transactionHistory = new ConcurrentLinkedDeque<>();
    private volatile boolean synchronizationRegistered = false;

    // Statistics
    private long totalCommits = 0;
    private long totalRollbacks = 0;
    private long totalDurationMs = 0;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private void ensureSynchronizationRegistered() {
        if (synchronizationRegistered) return;
        synchronized (this) {
            if (synchronizationRegistered) return;

            // Try to register a synchronization adapter to track transactions
            // We do this lazily — only when a tool is first called while in a transaction
            try {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        private long startTime = System.currentTimeMillis();
                        private String txName = "unknown";

                        @Override
                        public void beforeCommit(boolean readOnly) {
                            txName = TransactionSynchronizationManager.getCurrentTransactionName();
                            if (txName == null) txName = "anonymous";
                        }

                        @Override
                        public void afterCommit() {
                            long duration = System.currentTimeMillis() - startTime;
                            totalCommits++;
                            totalDurationMs += duration;
                            addHistoryEntry(txName, "COMMIT", duration, null);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            long duration = System.currentTimeMillis() - startTime;
                            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                                totalRollbacks++;
                                totalDurationMs += duration;
                                addHistoryEntry(txName, "ROLLBACK", duration, null);
                            }
                        }
                    });
                    synchronizationRegistered = true;
                }
            } catch (Exception e) {
                log.debug("Failed to register transaction synchronization: {}", e.getMessage());
            }
        }
    }

    private void addHistoryEntry(String name, String result, long duration, String error) {
        if (transactionHistory.size() >= MAX_ENTRIES) {
            transactionHistory.pollFirst();
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("name", name);
        entry.put("result", result);
        entry.put("durationMs", duration);
        if (error != null) entry.put("error", error);
        transactionHistory.addLast(entry);
    }

    // ==================== Tools ====================

    @DebugTool(description = "Get current thread's transaction status: whether a transaction is active, its name, isolation level, propagation behavior, and suspended resources. Useful for diagnosing transaction-not-active or unexpected propagation issues.")
    public Map<String, Object> getTransactionInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            boolean isActive = TransactionSynchronizationManager.isActualTransactionActive();
            result.put("transactionActive", isActive);

            if (isActive) {
                String name = TransactionSynchronizationManager.getCurrentTransactionName();
                result.put("transactionName", name != null ? name : "anonymous");

                result.put("isNewTransaction", TransactionSynchronizationManager.isCurrentTransactionReadOnly());

                // Isolation level
                Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
                result.put("isolationLevel", isolation != null ? isolationLevelName(isolation) : "default");

                // Read only
                result.put("readOnly", TransactionSynchronizationManager.isCurrentTransactionReadOnly());

                // Synchronization active
                result.put("synchronizationActive", TransactionSynchronizationManager.isSynchronizationActive());
            }

            // Resources bound to current thread
            List<String> resourceKeys = new ArrayList<>();
            for (Object key : TransactionSynchronizationManager.getResourceMap().keySet()) {
                resourceKeys.add(key.toString());
            }
            result.put("boundResources", resourceKeys);
            result.put("resourceCount", resourceKeys.size());

            // Try to get transaction manager info
            try {
                Object txManager = ctx.getBean("transactionManager");
                if (txManager != null) {
                    Map<String, Object> tmInfo = new LinkedHashMap<>();
                    tmInfo.put("class", txManager.getClass().getSimpleName());
                    // Try to get DataSource
                    Object ds = ReflectionHelper.invokeMethod(txManager, "getDataSource");
                    if (ds != null) {
                        tmInfo.put("dataSource", ds.getClass().getSimpleName());
                    }
                    result.put("transactionManager", tmInfo);
                }
            } catch (Exception ignored) {
                result.put("transactionManager", "not found");
            }

            // Register synchronization if active (for future tracking)
            if (isActive) {
                ensureSynchronizationRegistered();
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get transaction statistics: total commits, rollbacks, average duration, rollback rate. Useful for detecting high rollback rates indicating business logic errors.")
    public Map<String, Object> getTransactionStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        long commits = totalCommits;
        long rollbacks = totalRollbacks;
        long total = commits + rollbacks;

        result.put("totalTransactions", total);
        result.put("totalCommits", commits);
        result.put("totalRollbacks", rollbacks);
        result.put("rollbackRate", total > 0 ? String.format("%.1f%%", rollbacks * 100.0 / total) : "0%");
        result.put("avgDurationMs", total > 0 ? totalDurationMs / total : 0);

        // Recent history stats
        List<Map<String, Object>> snapshot = new ArrayList<>(transactionHistory);
        if (!snapshot.isEmpty()) {
            List<Long> durations = snapshot.stream()
                    .map(e -> (Long) e.getOrDefault("durationMs", 0L))
                    .sorted()
                    .toList();

            result.put("recentCount", snapshot.size());
            result.put("recentAvgMs", durations.stream().mapToLong(l -> l).average().orElse(0));
            result.put("recentP50Ms", percentile(durations, 50));
            result.put("recentP95Ms", percentile(durations, 95));
            result.put("recentP99Ms", percentile(durations, 99));
            result.put("recentMaxMs", durations.get(durations.size() - 1));
        }

        return result;
    }

    @DebugTool(description = "Get recent transaction execution records from memory. Each entry shows transaction name, result (COMMIT/ROLLBACK), duration, and optional error. Requires transactions to have been executed.")
    public List<Map<String, Object>> getRecentTransactions(
            @ToolParam(description = "Maximum entries to return (default 20, max 100)") Integer limit
    ) {
        int max = limit != null ? Math.min(limit, 100) : 20;
        List<Map<String, Object>> snapshot = new ArrayList<>(transactionHistory);
        Collections.reverse(snapshot);
        if (snapshot.size() > max) {
            snapshot = snapshot.subList(0, max);
        }
        return snapshot;
    }

    @DebugTool(description = "Get all rollback records with error details. Shows which methods are rolling back and why. Essential for diagnosing business logic failures or constraint violations.")
    public List<Map<String, Object>> getRollbackHistory(
            @ToolParam(description = "Maximum entries (default 50)") Integer limit
    ) {
        int max = limit != null ? Math.min(limit, 100) : 50;
        List<Map<String, Object>> rollbacks = new ArrayList<>();

        for (Map<String, Object> entry : transactionHistory) {
            if ("ROLLBACK".equals(entry.get("result"))) {
                rollbacks.add(entry);
            }
        }

        Collections.reverse(rollbacks);
        if (rollbacks.size() > max) {
            rollbacks = rollbacks.subList(0, max);
        }

        return rollbacks;
    }

    @DebugTool(description = "Get the slowest transactions sorted by duration. Useful for finding performance bottlenecks in transactional methods (e.g., long-running @Transactional methods holding DB connections).")
    public List<Map<String, Object>> getSlowTransactions(
            @ToolParam(description = "Maximum entries (default 10)") Integer limit,
            @ToolParam(description = "Minimum duration in ms (default 100)") Integer minDurationMs
    ) {
        int max = limit != null ? Math.min(limit, 50) : 10;
        long minMs = minDurationMs != null ? minDurationMs : 100;

        List<Map<String, Object>> slow = new ArrayList<>();
        for (Map<String, Object> entry : transactionHistory) {
            long dur = (Long) entry.getOrDefault("durationMs", 0L);
            if (dur >= minMs) {
                slow.add(entry);
            }
        }

        slow.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("durationMs", 0L),
                (Long) a.getOrDefault("durationMs", 0L)));

        if (slow.size() > max) {
            slow = slow.subList(0, max);
        }

        return slow;
    }

    // ==================== Helpers ====================

    private String isolationLevelName(int level) {
        return switch (level) {
            case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case java.sql.Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case java.sql.Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case java.sql.Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            case java.sql.Connection.TRANSACTION_NONE -> "NONE";
            default -> "UNKNOWN(" + level + ")";
        };
    }

    private long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
