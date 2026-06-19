package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom thread pool monitoring tools.
 * Tracks ThreadPoolTaskExecutor and raw ThreadPoolExecutor beans,
 * with in-memory rejection counting via a wrapping RejectedExecutionHandler.
 */
public class ThreadPoolInspector implements ApplicationContextAware {

    private ApplicationContext ctx;
    private final ConcurrentMap<String, AtomicLong> rejectedCounts = new ConcurrentHashMap<>();
    private final Set<String> wrapped = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all registered ThreadPoolTaskExecutor and ThreadPoolExecutor beans with their configuration: core/max pool size, queue capacity, keep-alive, and rejection policy.")
    public List<Map<String, Object>> getThreadPools() {
        List<Map<String, Object>> results = new ArrayList<>();

        // ThreadPoolTaskExecutor beans
        Map<String, ThreadPoolTaskExecutor> taskExecutors = ctx.getBeansOfType(ThreadPoolTaskExecutor.class);
        for (Map.Entry<String, ThreadPoolTaskExecutor> e : taskExecutors.entrySet()) {
            ThreadPoolTaskExecutor exec = e.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("beanName", e.getKey());
            info.put("type", "ThreadPoolTaskExecutor");
            info.put("class", exec.getClass().getSimpleName());
            info.put("corePoolSize", safeGet(exec::getCorePoolSize));
            info.put("maxPoolSize", safeGet(exec::getMaxPoolSize));
            info.put("keepAliveSeconds", safeGet(exec::getKeepAliveSeconds));
            info.put("queueCapacity", safeGet(exec::getQueueCapacity));
            info.put("threadNamePrefix", exec.getThreadNamePrefix());
            info.put("active", exec.getActiveCount());
            // Rejection handler is on the underlying ThreadPoolExecutor; expose when initialized
            try {
                ThreadPoolExecutor underlying = exec.getThreadPoolExecutor();
                info.put("rejectedExecutionHandler", describeHandler(underlying.getRejectedExecutionHandler()));
                info.put("underlyingQueueClass", underlying.getQueue().getClass().getSimpleName());
            } catch (Exception ignored) {}
            results.add(info);
        }

        // Raw ThreadPoolExecutor beans
        Map<String, ThreadPoolExecutor> rawExecutors = ctx.getBeansOfType(ThreadPoolExecutor.class);
        for (Map.Entry<String, ThreadPoolExecutor> e : rawExecutors.entrySet()) {
            ThreadPoolExecutor exec = e.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("beanName", e.getKey());
            info.put("type", "ThreadPoolExecutor");
            info.put("class", exec.getClass().getSimpleName());
            info.put("corePoolSize", exec.getCorePoolSize());
            info.put("maxPoolSize", exec.getMaximumPoolSize());
            info.put("keepAliveSeconds", exec.getKeepAliveTime(java.util.concurrent.TimeUnit.SECONDS));
            info.put("queueCapacity", queueCapacityOf(exec.getQueue()));
            info.put("queueClass", exec.getQueue().getClass().getSimpleName());
            info.put("activeCount", exec.getActiveCount());
            info.put("poolSize", exec.getPoolSize());
            info.put("largestPoolSize", exec.getLargestPoolSize());
            info.put("rejectedExecutionHandler", describeHandler(exec.getRejectedExecutionHandler()));
            results.add(info);
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No ThreadPoolTaskExecutor or ThreadPoolExecutor beans found."));
        }
        return results;
    }

    @DebugTool(description = "Get live statistics for a specific thread pool: active threads, current/largest pool size, queue size, completed task count, and tracked rejection count.")
    public Map<String, Object> getThreadPoolStats(
            @ToolParam(description = "Thread pool bean name") String poolName
    ) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (poolName == null || poolName.isBlank()) {
            info.put("error", "poolName is required");
            return info;
        }
        if (!ctx.containsBean(poolName)) {
            info.put("error", "No bean named '" + poolName + "' found");
            return info;
        }

        Object bean = ctx.getBean(poolName);
        ThreadPoolExecutor pool = null;

        if (bean instanceof ThreadPoolTaskExecutor tpte) {
            try {
                pool = tpte.getThreadPoolExecutor();
            } catch (IllegalStateException ex) {
                info.put("error", "ThreadPoolTaskExecutor not initialized: " + ex.getMessage());
                return info;
            }
        } else if (bean instanceof ThreadPoolExecutor tpe) {
            pool = tpe;
        } else {
            info.put("error", "Bean '" + poolName + "' is not a thread pool (got "
                    + bean.getClass().getName() + ")");
            return info;
        }

        info.put("beanName", poolName);
        info.put("activeCount", pool.getActiveCount());
        info.put("poolSize", pool.getPoolSize());
        info.put("corePoolSize", pool.getCorePoolSize());
        info.put("maxPoolSize", pool.getMaximumPoolSize());
        info.put("largestPoolSize", pool.getLargestPoolSize());
        info.put("queueSize", pool.getQueue().size());
        info.put("queueRemainingCapacity", pool.getQueue().remainingCapacity());
        info.put("completedTaskCount", pool.getCompletedTaskCount());
        info.put("taskCount", pool.getTaskCount());
        info.put("rejectedTaskCount", rejectedCounts.getOrDefault(poolName, new AtomicLong()).get());
        info.put("isShutdown", pool.isShutdown());
        info.put("isTerminated", pool.isTerminated());
        return info;
    }

    @DebugTool(description = "Return aggregated rejected task counts across all monitored thread pools. Wraps each pool's RejectedExecutionHandler on first call so counts accumulate from then on.")
    public List<Map<String, Object>> getRejectedTasks() {
        List<Map<String, Object>> results = new ArrayList<>();

        installWrappers();

        // Report counts for every known pool
        Map<String, ThreadPoolTaskExecutor> taskExecutors = ctx.getBeansOfType(ThreadPoolTaskExecutor.class);
        for (String name : taskExecutors.keySet()) {
            long count = rejectedCounts.getOrDefault(name, new AtomicLong()).get();
            if (count > 0) {
                results.add(poolEntry(name, "ThreadPoolTaskExecutor", count));
            }
        }
        Map<String, ThreadPoolExecutor> rawExecutors = ctx.getBeansOfType(ThreadPoolExecutor.class);
        for (String name : rawExecutors.keySet()) {
            long count = rejectedCounts.getOrDefault(name, new AtomicLong()).get();
            if (count > 0) {
                results.add(poolEntry(name, "ThreadPoolExecutor", count));
            }
        }

        long total = results.stream().mapToLong(r -> ((Number) r.get("rejectedCount")).longValue()).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRejected", total);
        summary.put("poolsWithRejections", results.size());
        summary.put("note", "Wrapping RejectedExecutionHandler is installed lazily on first call. " +
                "Rejections that occur before the first invocation are not counted.");
        results.add(0, summary);
        return results;
    }

    // ==================== Helpers ====================

    private void installWrappers() {
        try {
            Map<String, ThreadPoolTaskExecutor> taskExecutors = ctx.getBeansOfType(ThreadPoolTaskExecutor.class);
            for (Map.Entry<String, ThreadPoolTaskExecutor> e : taskExecutors.entrySet()) {
                wrapHandler(e.getKey(), e.getValue().getThreadPoolExecutor());
            }
        } catch (Exception ignored) {}

        try {
            Map<String, ThreadPoolExecutor> rawExecutors = ctx.getBeansOfType(ThreadPoolExecutor.class);
            for (Map.Entry<String, ThreadPoolExecutor> e : rawExecutors.entrySet()) {
                // Skip beans that are also the underlying executor of a ThreadPoolTaskExecutor — they share state
                if (!taskExecutorWrappersContain(e.getValue())) {
                    wrapHandler(e.getKey(), e.getValue());
                }
            }
        } catch (Exception ignored) {}
    }

    private final Set<ThreadPoolExecutor> knownTaskExecutorUnderlying =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private boolean taskExecutorWrappersContain(ThreadPoolExecutor pool) {
        return knownTaskExecutorUnderlying.contains(pool);
    }

    private void wrapHandler(String beanName, ThreadPoolExecutor pool) {
        if (pool == null) return;
        if (!wrapped.add(beanName + "@" + System.identityHashCode(pool))) return;

        try {
            RejectedExecutionHandler original = pool.getRejectedExecutionHandler();
            if (original instanceof CountingRejectedHandler) return;

            AtomicLong counter = rejectedCounts.computeIfAbsent(beanName, k -> new AtomicLong());
            knownTaskExecutorUnderlying.add(pool);
            pool.setRejectedExecutionHandler(new CountingRejectedHandler(original, counter));
        } catch (Exception ignored) {}
    }

    private static String describeHandler(RejectedExecutionHandler handler) {
        if (handler == null) return "null";
        if (handler instanceof CountingRejectedHandler crh) {
            return "CountingRejectedHandler(wrapping " + crh.describeDelegate() + ")";
        }
        return handler.getClass().getSimpleName();
    }

    private static int queueCapacityOf(java.util.concurrent.BlockingQueue<Runnable> queue) {
        int remaining = queue.remainingCapacity();
        if (remaining == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return queue.size() + remaining;
    }

    private static Map<String, Object> poolEntry(String name, String type, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("beanName", name);
        m.put("type", type);
        m.put("rejectedCount", count);
        return m;
    }

    private interface IntSupplier { int get() throws Exception; }
    private static Object safeGet(IntSupplier s) {
        try { return s.get(); } catch (Exception e) { return null; }
    }

    /**
     * Wraps a RejectedExecutionHandler to count rejections per pool.
     */
    private static final class CountingRejectedHandler implements RejectedExecutionHandler {
        private final RejectedExecutionHandler delegate;
        private final AtomicLong counter;

        CountingRejectedHandler(RejectedExecutionHandler delegate, AtomicLong counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            counter.incrementAndGet();
            if (delegate != null) {
                delegate.rejectedExecution(r, executor);
            }
        }

        String describeDelegate() {
            return delegate == null ? "null" : delegate.getClass().getSimpleName();
        }
    }
}
