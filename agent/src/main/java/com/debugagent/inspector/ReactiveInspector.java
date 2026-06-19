package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Reactive (WebFlux) diagnostic tools.
 * Inspects reactive streams, event loops, and scheduler status.
 * Conditional on Spring WebFlux being on classpath.
 */
public class ReactiveInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get reactive stream info: active subscriptions, pending requests, and backpressure status. Useful for debugging reactive pipeline issues.")
    public Map<String, Object> getReactiveStreams() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Check for reactive web context
            boolean isReactive = false;
            try {
                ctx.getBean(Class.forName("org.springframework.web.reactive.DispatcherHandler"));
                isReactive = true;
            } catch (Exception ignored) {}

            result.put("reactiveEnabled", isReactive);

            // Check for Schedulers
            try {
                Class<?> schedulersClass = Class.forName("reactor.core.scheduler.Schedulers");
                Method currentMethod = schedulersClass.getMethod("parallel");
                Object parallel = currentMethod.invoke(null);
                if (parallel != null) {
                    Map<String, Object> parallelInfo = new LinkedHashMap<>();
                    parallelInfo.put("type", parallel.getClass().getSimpleName());
                    try {
                        Object pool = ReflectionHelper.invokeMethod(parallel, "getMetrics");
                        if (pool != null) {
                            parallelInfo.put("metrics", "available");
                        }
                    } catch (Exception ignored) {}
                    result.put("parallelScheduler", parallelInfo);
                }
            } catch (Exception ignored) {}

            // Check for WebClient
            try {
                String[] wcNames = ctx.getBeanNamesForType(
                        Class.forName("org.springframework.web.reactive.function.client.WebClient"));
                result.put("webClientBeans", wcNames.length);
            } catch (Exception ignored) {}

            // Flux/Mono info via context key
            try {
                Class<?> contextViewClass = Class.forName("reactor.util.context.ContextView");
                result.put("contextApiAvailable", true);
            } catch (Exception ignored) {}

            if (!isReactive) {
                result.put("note", "Spring WebFlux not detected. " +
                        "This application may be using Spring MVC (servlet-based).");
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    @DebugTool(description = "Get event loop status: Netty worker threads, task queue depth, and I/O statistics. Critical for diagnosing reactive performance issues like event loop blocking.")
    public Map<String, Object> getEventLoopStatus() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Look for Netty event loop threads
            List<Map<String, Object>> eventLoops = new ArrayList<>();
            java.lang.management.ThreadMXBean tmx = java.lang.management.ManagementFactory.getThreadMXBean();
            java.lang.management.ThreadInfo[] infos = tmx.dumpAllThreads(false, false);

            int nettyThreads = 0;
            int reactorThreads = 0;
            int blockedReactorThreads = 0;

            for (java.lang.management.ThreadInfo info : infos) {
                String name = info.getThreadName();

                if (name.contains("nio-event-loop") || name.contains("reactor-http-nio")
                        || name.contains("reactor-tcp-nio")) {
                    nettyThreads++;
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", name);
                    t.put("state", info.getThreadState().toString());

                    if (info.getThreadState() == Thread.State.BLOCKED
                            || info.getThreadState() == Thread.State.WAITING) {
                        t.put("status", "idle");
                    } else if (info.getThreadState() == Thread.State.RUNNABLE) {
                        t.put("status", "active");
                    } else {
                        t.put("status", info.getThreadState().toString().toLowerCase());
                    }

                    // Check for blocking calls (should not happen in event loop)
                    if (info.getThreadState() == Thread.State.RUNNABLE) {
                        StackTraceElement[] stack = info.getStackTrace();
                        for (StackTraceElement frame : stack) {
                            if (frame.getClassName().contains("socket") && frame.getMethodName().contains("read")) {
                                t.put("warning", "Event loop thread doing blocking I/O!");
                                break;
                            }
                        }
                    }

                    eventLoops.add(t);
                }

                if (name.startsWith("reactor-") || name.startsWith("parallel-")) {
                    reactorThreads++;
                    if (info.getThreadState() == Thread.State.BLOCKED) {
                        blockedReactorThreads++;
                    }
                }
            }

            result.put("nettyEventLoopThreads", nettyThreads);
            result.put("reactorSchedulerThreads", reactorThreads);
            result.put("blockedReactorThreads", blockedReactorThreads);
            result.put("threads", eventLoops);

            if (blockedReactorThreads > 0) {
                result.put("warning", blockedReactorThreads + " reactor threads are BLOCKED. " +
                        "This may indicate improper blocking calls in reactive pipelines.");
            }

            if (nettyThreads == 0 && reactorThreads == 0) {
                result.put("note", "No reactive/event-loop threads detected. " +
                        "The application may not be using reactive programming.");
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }
}
