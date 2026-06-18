package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Scheduled tasks and async thread pool inspection tools.
 * Uses reflection on Spring's ScheduledTaskHolder and ThreadPoolTaskExecutor.
 */
public class TaskInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    // ==================== Scheduled Tasks ====================

    @DebugTool(description = "List all @Scheduled tasks in the application: method name, cron expression, fixed rate, initial delay, and next scheduled run time.")
    public List<Map<String, Object>> getScheduledTasks() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // ScheduledTaskHolder is implemented by ScheduledAnnotationBeanPostProcessor
            List<Object> holders = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor");

            if (holders.isEmpty()) {
                results.add(Map.of("info", "No @Scheduled task processor found. @EnableScheduling may not be enabled."));
                return results;
            }

            for (Object holder : holders) {
                // getScheduledTasks() returns Set<ScheduledTask>
                Object tasks = ReflectionHelper.invokeMethod(holder, "getScheduledTasks");
                if (tasks instanceof Set<?> taskSet) {
                    for (Object task : taskSet) {
                        Map<String, Object> info = extractScheduledTaskInfo(task);
                        if (info != null) results.add(info);
                    }
                }
            }

            if (results.isEmpty()) {
                results.add(Map.of("info", "No @Scheduled tasks registered"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to list scheduled tasks: " + e.getMessage()));
        }

        return results;
    }

    private Map<String, Object> extractScheduledTaskInfo(Object scheduledTask) {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            // ScheduledTask.getTask() returns Task (Runnable)
            Object task = ReflectionHelper.invokeMethod(scheduledTask, "getTask");
            if (task == null) return null;

            // Get the Runnable — could be ScheduledMethodRunnable
            Object runnable = ReflectionHelper.invokeMethod(task, "getRunnable");
            if (runnable == null) return null;

            // ScheduledMethodRunnable has getMethod() and getTarget()
            Method getMethod = ReflectionHelper.findMethod(runnable.getClass(), "getMethod");
            Method getTarget = ReflectionHelper.findMethod(runnable.getClass(), "getTarget");

            if (getMethod != null) {
                getMethod.setAccessible(true);
                Method method = (Method) getMethod.invoke(runnable);
                info.put("method", method.getName());
                info.put("class", method.getDeclaringClass().getSimpleName());
            }

            // Try to get future for next execution time
            Object future = ReflectionHelper.invokeMethod(scheduledTask, "getFuture");
            if (future != null) {
                info.put("schedulerType", future.getClass().getSimpleName());
                // Try scheduledExecutionTime on ScheduledFuture
                try {
                    Method nextTime = ReflectionHelper.findMethod(future.getClass(), "getDelay", java.util.concurrent.TimeUnit.class);
                    if (nextTime != null) {
                        nextTime.setAccessible(true);
                        long delayMs = (long) nextTime.invoke(future, java.util.concurrent.TimeUnit.MILLISECONDS);
                        info.put("nextRunInMs", delayMs);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return null;
        }
        return info.isEmpty() ? null : info;
    }

    // ==================== Async Thread Pools ====================

    @DebugTool(description = "Get async thread pool statistics: active threads, queue size, completed task count, max pool size. Monitors ThreadPoolTaskExecutor and Java ExecutorService beans.")
    public List<Map<String, Object>> getAsyncTaskInfo() {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // Find all ThreadPoolTaskExecutor beans
            List<Object> executors = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor");

            for (Object exec : executors) {
                Map<String, Object> info = new LinkedHashMap<>();
                // Try to get the underlying ThreadPoolExecutor
                Object pool = ReflectionHelper.invokeMethod(exec, "getThreadPoolExecutor");
                if (pool != null) {
                    info.put("type", "ThreadPoolTaskExecutor");
                    info.put("activeCount", ReflectionHelper.invokeMethod(pool, "getActiveCount"));
                    info.put("poolSize", ReflectionHelper.invokeMethod(pool, "getPoolSize"));
                    info.put("maxPoolSize", ReflectionHelper.invokeMethod(pool, "getMaximumPoolSize"));
                    info.put("corePoolSize", ReflectionHelper.invokeMethod(pool, "getCorePoolSize"));
                    info.put("queueSize", ReflectionHelper.invokeMethod(pool, "getQueue") != null
                            ? ReflectionHelper.invokeMethod(ReflectionHelper.invokeMethod(pool, "getQueue"), "size")
                            : "unknown");
                    info.put("completedTaskCount", ReflectionHelper.invokeMethod(pool, "getCompletedTaskCount"));
                    info.put("taskCount", ReflectionHelper.invokeMethod(pool, "getTaskCount"));
                    results.add(info);
                }
            }

            // Also check raw ExecutorService beans
            List<Object> rawExecutors = ReflectionHelper.getBeansOfType(ctx,
                    "java.util.concurrent.ThreadPoolExecutor");
            for (Object exec : rawExecutors) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("type", "ThreadPoolExecutor");
                info.put("beanClass", exec.getClass().getSimpleName());
                info.put("activeCount", ReflectionHelper.invokeMethod(exec, "getActiveCount"));
                info.put("poolSize", ReflectionHelper.invokeMethod(exec, "getPoolSize"));
                info.put("maxPoolSize", ReflectionHelper.invokeMethod(exec, "getMaximumPoolSize"));
                info.put("queueSize", ReflectionHelper.invokeMethod(
                        ReflectionHelper.invokeMethod(exec, "getQueue"), "size"));
                info.put("completedTaskCount", ReflectionHelper.invokeMethod(exec, "getCompletedTaskCount"));
                results.add(info);
            }

            if (results.isEmpty()) {
                results.add(Map.of("info", "No thread pool executors found"));
            }
        } catch (Exception e) {
            results.add(Map.of("error", "Failed to get async info: " + e.getMessage()));
        }

        return results;
    }
}
