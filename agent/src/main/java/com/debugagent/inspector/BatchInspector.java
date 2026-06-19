package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Spring Batch diagnostic tools.
 * Inspects registered jobs, executions, step-level statistics and failures.
 * Uses reflection on JobRepository / JobExplorer — no hard runtime dependency.
 */
public class BatchInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all registered Spring Batch jobs: job name, instance count, and latest execution status. Inspects Job beans plus JobRegistry / JobRepository when available.")
    public List<Map<String, Object>> getBatchJobs() {
        List<Map<String, Object>> results = new ArrayList<>();

        Object jobExplorer = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.batch.core.explore.JobExplorer");

        Set<String> jobNames = new LinkedHashSet<>();

        // 1) Job beans directly registered in the context
        for (Object job : ReflectionHelper.getBeansOfType(ctx, "org.springframework.batch.core.Job")) {
            Object name = ReflectionHelper.invokeMethod(job, "getName");
            if (name != null) jobNames.add(name.toString());
        }

        // 2) JobRegistry beans
        for (Object registry : ReflectionHelper.getBeansOfType(ctx,
                "org.springframework.batch.core.launch.JobRegistry")) {
            Object registryNames = ReflectionHelper.invokeMethod(registry, "getJobNames");
            if (registryNames instanceof Collection<?> col) {
                for (Object n : col) jobNames.add(n.toString());
            }
        }

        // 3) JobExplorer.getJobNames() — best effort
        if (jobExplorer != null) {
            Object explorerNames = ReflectionHelper.invokeMethod(jobExplorer, "getJobNames");
            if (explorerNames instanceof Collection<?> col) {
                for (Object n : col) jobNames.add(n.toString());
            }
        }

        if (jobNames.isEmpty()) {
            results.add(Map.of("info", "No Spring Batch jobs found. " +
                    "Add a bean of type org.springframework.batch.core.Job to register a job."));
            return results;
        }

        for (String name : jobNames) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("jobName", name);

            // Count instances & latest execution
            if (jobExplorer != null) {
                try {
                    Object count = invokeWithArgs(jobExplorer, "getJobInstanceCount", name);
                    if (count instanceof Number) {
                        info.put("instanceCount", count);
                    }
                } catch (Exception ignored) {}

                Map<String, Object> latest = latestExecution(jobExplorer, name);
                if (latest != null) {
                    info.put("latestExecution", latest);
                }
            } else {
                info.put("note", "JobExplorer not configured — execution details unavailable.");
            }

            results.add(info);
        }

        return results;
    }

    @DebugTool(description = "List recent executions for a Spring Batch job: start/end time, status, exit code, and step count.")
    public List<Map<String, Object>> getBatchJobExecutions(
            @ToolParam(description = "Job name") String jobName,
            @ToolParam(description = "Maximum executions to return (default 10)", required = false) Integer limit
    ) {
        List<Map<String, Object>> results = new ArrayList<>();
        int max = limit != null && limit > 0 ? limit : 10;

        Object jobExplorer = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.batch.core.explore.JobExplorer");
        if (jobExplorer == null) {
            results.add(Map.of("error", "JobExplorer not configured. " +
                    "Add a JobExplorerFactoryBean or enable Spring Batch metadata tables."));
            return results;
        }
        if (jobName == null || jobName.isBlank()) {
            results.add(Map.of("error", "jobName is required"));
            return results;
        }

        // findJobInstancesByJobName(String, int, int) → List<JobInstance>
        Object instances = invokeWithArgs(jobExplorer, "findJobInstancesByJobName", jobName, 0, max);
        if (!(instances instanceof List<?> instanceList)) {
            results.add(Map.of("error", "Could not retrieve job instances for: " + jobName));
            return results;
        }

        // Reverse so newest is first (Spring Batch returns ascending by creation)
        List<?> reversed = new ArrayList<>(instanceList);
        Collections.reverse(reversed);

        for (Object instance : reversed) {
            Object execs = invokeWithArgs(jobExplorer, "getJobExecutions", instance);
            if (execs instanceof List<?> execList) {
                List<?> sorted = new ArrayList<>(execList);
                sorted.sort((a, b) -> {
                    Object ta = ReflectionHelper.invokeMethod(a, "getCreateTime");
                    Object tb = ReflectionHelper.invokeMethod(b, "getCreateTime");
                    if (ta == null || tb == null) return 0;
                    return tb.toString().compareTo(ta.toString());
                });
                for (Object exec : sorted) {
                    Map<String, Object> info = describeExecution(exec);
                    if (info != null) {
                        results.add(info);
                        if (results.size() >= max) return results;
                    }
                }
            }
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No executions found for job: " + jobName));
        }
        return results;
    }

    @DebugTool(description = "Get step-level statistics for a Spring Batch job: reads, writes, commits, rollbacks, and duration per step.")
    public List<Map<String, Object>> getBatchStepStats(
            @ToolParam(description = "Job name") String jobName
    ) {
        List<Map<String, Object>> results = new ArrayList<>();

        Object jobExplorer = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.batch.core.explore.JobExplorer");
        if (jobExplorer == null) {
            results.add(Map.of("error", "JobExplorer not configured"));
            return results;
        }
        if (jobName == null || jobName.isBlank()) {
            results.add(Map.of("error", "jobName is required"));
            return results;
        }

        Object instances = invokeWithArgs(jobExplorer, "findJobInstancesByJobName", jobName, 0, 1);
        if (!(instances instanceof List<?> instanceList) || instanceList.isEmpty()) {
            results.add(Map.of("info", "No job instances found for: " + jobName));
            return results;
        }

        Object latestInstance = instanceList.get(instanceList.size() - 1);
        Object execs = invokeWithArgs(jobExplorer, "getJobExecutions", latestInstance);
        if (!(execs instanceof List<?> execList) || execList.isEmpty()) {
            results.add(Map.of("info", "No executions for latest instance of: " + jobName));
            return results;
        }

        Object latestExec = execList.get(execList.size() - 1);
        Object stepExecutions = ReflectionHelper.invokeMethod(latestExec, "getStepExecutions");
        if (stepExecutions instanceof Collection<?> steps) {
            for (Object step : steps) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("stepName", ReflectionHelper.invokeMethod(step, "getStepName"));
                info.put("status", ReflectionHelper.safeToString(
                        ReflectionHelper.invokeMethod(step, "getStatus")));
                info.put("readCount", ReflectionHelper.invokeMethod(step, "getReadCount"));
                info.put("writeCount", ReflectionHelper.invokeMethod(step, "getWriteCount"));
                info.put("readSkipCount", ReflectionHelper.invokeMethod(step, "getReadSkipCount"));
                info.put("writeSkipCount", ReflectionHelper.invokeMethod(step, "getWriteSkipCount"));
                info.put("processSkipCount", ReflectionHelper.invokeMethod(step, "getProcessSkipCount"));
                info.put("commitCount", ReflectionHelper.invokeMethod(step, "getCommitCount"));
                info.put("rollbackCount", ReflectionHelper.invokeMethod(step, "getRollbackCount"));
                info.put("filterCount", ReflectionHelper.invokeMethod(step, "getFilterCount"));

                Object start = ReflectionHelper.invokeMethod(step, "getStartTime");
                Object end = ReflectionHelper.invokeMethod(step, "getEndTime");
                info.put("startTime", String.valueOf(start));
                info.put("endTime", String.valueOf(end));
                info.put("durationMs", computeDurationMillis(start, end));
                results.add(info);
            }
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No step executions recorded for: " + jobName));
        }
        return results;
    }

    @DebugTool(description = "List all failed Spring Batch executions across every job with exception details. Useful for triaging batch outages.")
    public List<Map<String, Object>> getBatchFailures() {
        List<Map<String, Object>> results = new ArrayList<>();

        Object jobExplorer = ReflectionHelper.getFirstBeanOfType(ctx,
                "org.springframework.batch.core.explore.JobExplorer");
        if (jobExplorer == null) {
            results.add(Map.of("error", "JobExplorer not configured"));
            return results;
        }

        Object explorerNames = ReflectionHelper.invokeMethod(jobExplorer, "getJobNames");
        if (!(explorerNames instanceof Collection<?> names)) {
            results.add(Map.of("error", "Could not enumerate job names from JobExplorer"));
            return results;
        }

        for (Object name : names) {
            String jobName = name.toString();
            Object instances = invokeWithArgs(jobExplorer, "findJobInstancesByJobName", jobName, 0, 50);
            if (!(instances instanceof List<?> instanceList)) continue;

            for (Object instance : instanceList) {
                Object execs = invokeWithArgs(jobExplorer, "getJobExecutions", instance);
                if (!(execs instanceof List<?> execList)) continue;

                for (Object exec : execList) {
                    Object status = ReflectionHelper.invokeMethod(exec, "getStatus");
                    if (status == null) continue;
                    String statusStr = status.toString();
                    if (statusStr.startsWith("FAILED") || statusStr.startsWith("STOPPED")
                            || statusStr.startsWith("ABANDONED") || statusStr.startsWith("UNKNOWN")) {
                        Map<String, Object> info = describeExecution(exec);
                        if (info != null) {
                            info.put("status", statusStr);

                            // Failure exceptions
                            Object failures = ReflectionHelper.invokeMethod(exec, "getFailureExceptions");
                            List<String> failureList = new ArrayList<>();
                            if (failures instanceof Collection<?> col) {
                                for (Object ex : col) {
                                    failureList.add(ReflectionHelper.safeToString(ex));
                                }
                            }
                            info.put("failureExceptions", failureList);
                            results.add(info);
                        }
                    }
                }
            }
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No failed or stopped batch executions found."));
        }
        return results;
    }

    // ==================== Helpers ====================

    private Map<String, Object> latestExecution(Object jobExplorer, String jobName) {
        try {
            Object instances = invokeWithArgs(jobExplorer, "findJobInstancesByJobName", jobName, 0, 1);
            if (instances instanceof List<?> list && !list.isEmpty()) {
                Object latest = list.get(list.size() - 1);
                Object execs = invokeWithArgs(jobExplorer, "getJobExecutions", latest);
                if (execs instanceof List<?> execList && !execList.isEmpty()) {
                    return describeExecution(execList.get(execList.size() - 1));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Map<String, Object> describeExecution(Object exec) {
        if (exec == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("executionId", ReflectionHelper.invokeMethod(exec, "getId"));
        info.put("status", ReflectionHelper.safeToString(ReflectionHelper.invokeMethod(exec, "getStatus")));
        Object exit = ReflectionHelper.invokeMethod(exec, "getExitStatus");
        if (exit != null) {
            info.put("exitCode", ReflectionHelper.invokeMethod(exit, "getExitCode"));
            info.put("exitDescription", truncate(
                    ReflectionHelper.safeToString(ReflectionHelper.invokeMethod(exit, "getExitDescription")), 300));
        }
        Object start = ReflectionHelper.invokeMethod(exec, "getStartTime");
        Object end = ReflectionHelper.invokeMethod(exec, "getEndTime");
        Object created = ReflectionHelper.invokeMethod(exec, "getCreateTime");
        info.put("startTime", String.valueOf(start));
        info.put("endTime", String.valueOf(end));
        info.put("createTime", String.valueOf(created));
        info.put("durationMs", computeDurationMillis(start, end));

        Object stepExecs = ReflectionHelper.invokeMethod(exec, "getStepExecutions");
        if (stepExecs instanceof Collection<?> steps) {
            info.put("stepCount", steps.size());
        }
        return info;
    }

    private static long computeDurationMillis(Object start, Object end) {
        if (start == null || end == null) return -1;
        try {
            long s = ((java.util.Date) start).getTime();
            long e = ((java.util.Date) end).getTime();
            return e - s;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static Object invokeWithArgs(Object target, String methodName, Object... args) {
        try {
            Class<?> clazz = target.getClass();
            while (clazz != null) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    if (m.getParameterCount() != args.length) continue;
                    Class<?>[] types = m.getParameterTypes();
                    boolean match = true;
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] != null && !types[i].isAssignableFrom(unwrap(args[i].getClass()))) {
                            // Try widening primitives / same-name heuristics
                            if (!types[i].isAssignableFrom(args[i].getClass())) {
                                match = false;
                                break;
                            }
                        }
                    }
                    if (match) {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Class<?> unwrap(Class<?> c) {
        if (c == Integer.class) return int.class;
        if (c == Long.class) return long.class;
        if (c == Boolean.class) return boolean.class;
        if (c == Double.class) return double.class;
        if (c == Float.class) return float.class;
        return c;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
