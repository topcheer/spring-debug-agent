package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Quartz Scheduler diagnostic tools.
 * Lists jobs and triggers, plus captures recent execution history via a JobListener.
 */
public class QuartzInspector implements ApplicationContextAware {

    private static final int MAX_HISTORY = 200;
    private static final String LISTENER_NAME = "debug-agent-quartz-inspector";

    private final Deque<Map<String, Object>> recentHistory = new ConcurrentLinkedDeque<>();
    private final Set<String> registeredSchedulers =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private volatile boolean listenerInstalled = false;

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "List all Quartz jobs (JobDetail): name, group, job class, durability, requestsRecovery, and description.")
    public List<Map<String, Object>> getQuartzJobs() {
        List<Map<String, Object>> results = new ArrayList<>();

        for (Scheduler scheduler : schedulers()) {
            Map<String, Object> schedulerInfo = new LinkedHashMap<>();
            schedulerInfo.put("scheduler", safeName(scheduler));

            try {
                List<Map<String, Object>> jobs = new ArrayList<>();
                for (String group : scheduler.getJobGroupNames()) {
                    GroupMatcher<JobKey> matcher = GroupMatcher.jobGroupEquals(group);
                    Set<JobKey> keys = scheduler.getJobKeys(matcher);
                    if (keys == null) continue;
                    for (JobKey key : keys) {
                        try {
                            JobDetail detail = scheduler.getJobDetail(key);
                            if (detail == null) continue;
                            jobs.add(describeJob(detail));
                        } catch (SchedulerException ignored) {}
                    }
                }
                // Include jobs without an explicit group as well
                try {
                    Set<JobKey> allKeys = scheduler.getJobKeys(GroupMatcher.anyJobGroup());
                    if (allKeys != null) {
                        Set<JobKey> seen = new HashSet<>();
                        for (Map<String, Object> j : jobs) {
                            seen.add(JobKey.jobKey(
                                    (String) j.get("name"),
                                    (String) j.get("group")));
                        }
                        for (JobKey k : allKeys) {
                            if (seen.contains(k)) continue;
                            try {
                                JobDetail detail = scheduler.getJobDetail(k);
                                if (detail != null) jobs.add(describeJob(detail));
                            } catch (SchedulerException ignored) {}
                        }
                    }
                } catch (SchedulerException ignored) {}

                schedulerInfo.put("jobs", jobs);
                schedulerInfo.put("jobCount", jobs.size());
            } catch (SchedulerException e) {
                schedulerInfo.put("error", "SchedulerException: " + e.getMessage());
            }
            results.add(schedulerInfo);
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No Quartz Scheduler beans found. " +
                    "Add spring-boot-starter-quartz and define jobs to enable."));
        }
        return results;
    }

    @DebugTool(description = "List all Quartz triggers: name, group, type (Simple/Cron), next/previous fire time, priority, and misfire instruction.")
    public List<Map<String, Object>> getQuartzTriggers() {
        List<Map<String, Object>> results = new ArrayList<>();

        for (Scheduler scheduler : schedulers()) {
            Map<String, Object> schedulerInfo = new LinkedHashMap<>();
            schedulerInfo.put("scheduler", safeName(scheduler));

            try {
                List<Map<String, Object>> triggers = new ArrayList<>();
                Set<org.quartz.TriggerKey> seen = new HashSet<>();

                for (String group : scheduler.getTriggerGroupNames()) {
                    try {
                        Set<org.quartz.TriggerKey> keys = scheduler.getTriggerKeys(
                                GroupMatcher.triggerGroupEquals(group));
                        if (keys == null) continue;
                        for (org.quartz.TriggerKey key : keys) {
                            if (!seen.add(key)) continue;
                            try {
                                Trigger trigger = scheduler.getTrigger(key);
                                if (trigger != null) {
                                    triggers.add(describeTrigger(scheduler, trigger));
                                }
                            } catch (SchedulerException ignored2) {}
                        }
                    } catch (SchedulerException ignored2) {}
                }

                // Catch triggers with no explicit group (default group)
                try {
                    Set<org.quartz.TriggerKey> all = scheduler.getTriggerKeys(GroupMatcher.anyTriggerGroup());
                    if (all != null) {
                        for (org.quartz.TriggerKey key : all) {
                            if (!seen.add(key)) continue;
                            try {
                                Trigger trigger = scheduler.getTrigger(key);
                                if (trigger != null) {
                                    triggers.add(describeTrigger(scheduler, trigger));
                                }
                            } catch (SchedulerException ignored3) {}
                        }
                    }
                } catch (SchedulerException ignored3) {}

                schedulerInfo.put("triggers", triggers);
                schedulerInfo.put("triggerCount", triggers.size());
            } catch (SchedulerException e) {
                schedulerInfo.put("error", "SchedulerException: " + e.getMessage());
            }
            results.add(schedulerInfo);
        }

        if (results.isEmpty()) {
            results.add(Map.of("info", "No Quartz Scheduler beans found."));
        }
        return results;
    }

    @DebugTool(description = "Recently executed Quartz jobs: name, group, start/end time, duration, result, and any exception. Captured via an internal JobListener installed on first call.")
    public List<Map<String, Object>> getQuartzJobHistory(
            @ToolParam(description = "Filter by job name (case-insensitive partial match)", required = false) String jobNameFilter,
            @ToolParam(description = "Maximum entries to return (default 50)", required = false) Integer limit
    ) {
        installListener();

        int max = limit != null && limit > 0 ? limit : 50;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : recentHistory) {
            String name = (String) entry.get("name");
            if (jobNameFilter != null && !jobNameFilter.isBlank()
                    && (name == null || !name.toLowerCase().contains(jobNameFilter.toLowerCase()))) {
                continue;
            }
            result.add(entry);
            if (result.size() >= max) break;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("returnedCount", result.size());
        summary.put("totalCaptured", recentHistory.size());
        summary.put("note", "Job executions are captured in-memory (max " + MAX_HISTORY + ") " +
                "after the listener is installed. Triggers fired before the first call are not recorded.");
        result.add(0, summary);
        return result;
    }

    // ==================== Helpers ====================

    private List<Scheduler> schedulers() {
        List<Scheduler> schedulers = new ArrayList<>();
        try {
            Map<String, Scheduler> beans = ctx.getBeansOfType(Scheduler.class);
            schedulers.addAll(beans.values());
        } catch (Exception ignored) {}
        return schedulers;
    }

    private void installListener() {
        if (listenerInstalled) return;
        synchronized (this) {
            if (listenerInstalled) return;
            try {
                for (Scheduler scheduler : schedulers()) {
                    String key = safeName(scheduler) + "@" + System.identityHashCode(scheduler);
                    if (!registeredSchedulers.add(key)) continue;

                    try {
                        if (scheduler.getListenerManager().getJobListeners()
                                .stream().noneMatch(l -> LISTENER_NAME.equals(
                                        safeListenerName(l)))) {
                            scheduler.getListenerManager().addJobListener(
                                    new HistoryJobListener(),
                                    GroupMatcher.anyJobGroup());
                        }
                    } catch (Exception ignored) {}
                }
                listenerInstalled = true;
            } catch (Exception ignored) {}
        }
    }

    private static String safeListenerName(JobListener l) {
        try { return l.getName(); } catch (Exception e) { return ""; }
    }

    private static String safeName(Scheduler scheduler) {
        try { return scheduler.getSchedulerName(); } catch (SchedulerException e) { return "unknown"; }
    }

    private static Map<String, Object> describeJob(JobDetail detail) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", detail.getKey().getName());
        info.put("group", detail.getKey().getGroup());
        info.put("jobClass", detail.getJobClass().getName());
        info.put("durable", detail.isDurable());
        info.put("requestsRecovery", detail.requestsRecovery());
        info.put("concurrentDisallowed", detail.isConcurrentExectionDisallowed());
        info.put("persistJobDataAfterExecution", detail.isPersistJobDataAfterExecution());
        String description = detail.getDescription();
        if (description != null && !description.isBlank()) {
            info.put("description", description);
        }
        if (!detail.getJobDataMap().isEmpty()) {
            info.put("jobDataMapKeys", new ArrayList<>(detail.getJobDataMap().keySet()));
        }
        return info;
    }

    private static Map<String, Object> describeTrigger(Scheduler scheduler, Trigger trigger) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", trigger.getKey().getName());
        info.put("group", trigger.getKey().getGroup());
        info.put("type", trigger.getClass().getSimpleName());

        try {
            info.put("state", scheduler.getTriggerState(trigger.getKey()).name());
        } catch (SchedulerException ignored) {}

        // Detect Cron vs Simple
        String triggerType = "Custom";
        try {
            if (trigger instanceof org.quartz.CronTrigger cron) {
                triggerType = "Cron";
                info.put("cronExpression", cron.getCronExpression());
                info.put("timeZone", cron.getTimeZone().getID());
            } else if (trigger instanceof org.quartz.SimpleTrigger simple) {
                triggerType = "Simple";
                info.put("repeatIntervalMs", simple.getRepeatInterval());
                info.put("repeatCount", simple.getRepeatCount());
                info.put("timesTriggered", simple.getTimesTriggered());
            }
        } catch (Exception ignored) {}
        info.put("triggerType", triggerType);

        Date nextFire = trigger.getNextFireTime();
        Date prevFire = trigger.getPreviousFireTime();
        Date start = trigger.getStartTime();
        Date end = trigger.getEndTime();
        info.put("nextFireTime", nextFire != null ? Instant.ofEpochMilli(nextFire.getTime()).toString() : null);
        info.put("previousFireTime", prevFire != null ? Instant.ofEpochMilli(prevFire.getTime()).toString() : null);
        info.put("startTime", start != null ? Instant.ofEpochMilli(start.getTime()).toString() : null);
        info.put("endTime", end != null ? Instant.ofEpochMilli(end.getTime()).toString() : null);
        info.put("priority", trigger.getPriority());
        info.put("misfireInstruction", describeMisfireInstruction(trigger));
        info.put("mayFireAgain", trigger.mayFireAgain());
        info.put("jobKey", trigger.getJobKey() != null ? trigger.getJobKey().toString() : null);
        return info;
    }

    private static String describeMisfireInstruction(Trigger trigger) {
        int inst = trigger.getMisfireInstruction();
        try {
            if (inst == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY) return "IGNORE_MISFIRE_POLICY";
            if (inst == Trigger.MISFIRE_INSTRUCTION_SMART_POLICY) return "SMART_POLICY";
        } catch (Throwable ignored) {}
        // Cron/Simple-specific constants
        try {
            if (trigger instanceof org.quartz.CronTrigger) {
                if (inst == org.quartz.CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW) return "FIRE_ONCE_NOW";
                if (inst == org.quartz.CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING) return "DO_NOTHING";
            } else if (trigger instanceof org.quartz.SimpleTrigger) {
                if (inst == org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW) return "FIRE_NOW";
                if (inst == org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT) return "RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT";
                if (inst == org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT) return "RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT";
                if (inst == org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT) return "RESCHEDULE_NEXT_WITH_REMAINING_COUNT";
                if (inst == org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT) return "RESCHEDULE_NEXT_WITH_EXISTING_COUNT";
            }
        } catch (Throwable ignored) {}
        return String.valueOf(inst);
    }

    /**
     * Captures Quartz job execution events into the in-memory history ring buffer.
     */
    private final class HistoryJobListener implements JobListener {
        @Override
        public String getName() { return LISTENER_NAME; }

        @Override
        public void jobToBeExecuted(org.quartz.JobExecutionContext context) {
            Map<String, Object> entry = new LinkedHashMap<>();
            JobDetail detail = context.getJobDetail();
            entry.put("name", detail.getKey().getName());
            entry.put("group", detail.getKey().getGroup());
            entry.put("jobClass", detail.getJobClass().getSimpleName());
            entry.put("scheduler", safeName(context.getScheduler()));
            entry.put("fireInstanceId", context.getFireInstanceId());
            entry.put("fireTime", Instant.ofEpochMilli(
                    context.getFireTime() != null ? context.getFireTime().getTime() : 0L).toString());
            entry.put("scheduledFireTime", context.getScheduledFireTime() != null
                    ? Instant.ofEpochMilli(context.getScheduledFireTime().getTime()).toString() : null);
            entry.put("refireCount", context.getRefireCount());
            entry.put("startTime", Instant.now().toString());
            context.put("debug-agent-start-ms", System.currentTimeMillis());
            recentHistory.addFirst(entry);
            trim();
        }

        @Override
        public void jobExecutionVetoed(org.quartz.JobExecutionContext context) {
            // Trigger listener territory — recorded on completion with result
        }

        @Override
        public void jobWasExecuted(org.quartz.JobExecutionContext context,
                                   JobExecutionException jobException) {
            // Find the matching entry by fireInstanceId (most recent first)
            String fireInstanceId = context.getFireInstanceId();
            for (Map<String, Object> entry : recentHistory) {
                if (fireInstanceId != null && fireInstanceId.equals(entry.get("fireInstanceId"))) {
                    entry.put("endTime", Instant.now().toString());
                    Object startMs = context.get("debug-agent-start-ms");
                    long duration = startMs instanceof Long s ? System.currentTimeMillis() - s : -1L;
                    entry.put("durationMs", duration);
                    Object result = context.getResult();
                    if (result != null) entry.put("result", ReflectionHelper.safeToString(result));
                    if (jobException != null) {
                        entry.put("exceptionClass", jobException.getClass().getName());
                        entry.put("exceptionMessage", jobException.getMessage());
                        entry.put("refireImmediately", jobException.refireImmediately());
                    } else {
                        entry.put("exception", "none");
                    }
                    break;
                }
            }
        }

        private void trim() {
            while (recentHistory.size() > MAX_HISTORY) {
                recentHistory.removeLast();
            }
        }
    }
}
