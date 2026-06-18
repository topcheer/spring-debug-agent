package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Spring Application Events inspector.
 * Captures recent events via an in-memory ring buffer listener
 * and can list all registered event listeners.
 */
@Component
public class EventInspector implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(EventInspector.class);
    private static final int MAX_EVENTS = 200;

    private final Deque<Map<String, Object>> recentEvents = new ConcurrentLinkedDeque<>();
    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @EventListener
    public void onApplicationEvent(ApplicationEvent event) {
        try {
            // Avoid infinite recursion — skip our own internal events
            if (event.getSource() != null && event.getSource().getClass().getName().startsWith("com.debugagent")) {
                return;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("eventType", event.getClass().getSimpleName());
            entry.put("eventTypeFull", event.getClass().getName());
            entry.put("source", event.getSource() != null ? event.getSource().getClass().getSimpleName() : "null");
            entry.put("sourceToString", truncate(ReflectionHelper.safeToString(event.getSource()), 200));

            // For payload-based events, include the payload
            try {
                java.lang.reflect.Method getSource = event.getClass().getMethod("getSource");
                Object source = getSource.invoke(event);
                if (source != null && !source.getClass().getName().startsWith("org.springframework")) {
                    entry.put("payload", truncate(ReflectionHelper.safeToString(source), 300));
                }
            } catch (Exception ignored) {}

            recentEvents.addFirst(entry);

            // Trim to max
            while (recentEvents.size() > MAX_EVENTS) {
                recentEvents.removeLast();
            }
        } catch (Exception e) {
            log.debug("Failed to capture event: {}", e.getMessage());
        }
    }

    @DebugTool(description = "Get recently published Spring Application Events from memory. Shows event type, source, timestamp, and payload. Useful for debugging event-driven flows.")
    public Map<String, Object> getRecentEvents(
            @ToolParam(description = "Filter by event type name (case-insensitive partial match, e.g., 'Order', 'ContextRefreshed')") String typeFilter,
            @ToolParam(description = "Maximum number of events to return (default 20)") Integer limit
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        int max = limit != null && limit > 0 ? limit : 20;

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> event : recentEvents) {
            String type = (String) event.get("eventType");
            if (typeFilter != null && !typeFilter.isBlank()
                    && !type.toLowerCase().contains(typeFilter.toLowerCase())) {
                continue;
            }
            events.add(event);
            if (events.size() >= max) break;
        }

        result.put("events", events);
        result.put("returnedCount", events.size());
        result.put("totalCaptured", recentEvents.size());
        result.put("note", "Events are captured from an in-memory ring buffer (max " + MAX_EVENTS + ").");
        return result;
    }

    @DebugTool(description = "List all registered ApplicationEvent listeners with the event types they handle. Useful for understanding event-driven architecture and finding missing or duplicate handlers.")
    public List<Map<String, Object>> getEventListeners(
            @ToolParam(description = "Filter by event type name. Leave empty for all.") String typeFilter
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            // Access the application event multicaster
            Object multicaster = getEventMulticaster();
            if (multicaster == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "ApplicationEventMulticaster not found");
                result.add(error);
                return result;
            }

            // Retrieve the list of ApplicationListeners via reflection
            Object listeners = getListeners(multicaster);
            if (listeners instanceof Collection<?> collection) {
                for (Object listener : collection) {
                    Map<String, Object> info = describeListener(listener, typeFilter);
                    if (info != null) {
                        result.add(info);
                    }
                }
            }

            // Also scan for @EventListener methods
            scanEventListenerAnnotations(result, typeFilter);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            result.add(error);
        }
        return result;
    }

    // ==================== Helpers ====================

    private Object getEventMulticaster() {
        try {
            // Try internal bean name
            if (ctx.containsBean("applicationEventMulticaster")) {
                return ctx.getBean("applicationEventMulticaster");
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private Collection<?> getListeners(Object multicaster) {
        try {
            // AbstractApplicationEventMulticaster has retrieveApplicationListeners()
            Object listeners = ReflectionHelper.invokeMethod(multicaster, "getApplicationListeners");
            if (listeners instanceof Collection) return (Collection<?>) listeners;
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private Map<String, Object> describeListener(Object listener, String typeFilter) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();

            // Try to get the delegate/listener type
            String listenerType = listener.getClass().getSimpleName();
            String listenerClass = listener.getClass().getName();

            // Check if it's a GenericApplicationListener or ApplicationListener
            Object eventType = null;
            try {
                // GenericApplicationListener supportsEventType
                Class<?> genericInterface = Class.forName(
                        "org.springframework.context.event.GenericApplicationListener");
                if (genericInterface.isInstance(listener)) {
                    info.put("type", "GenericApplicationListener");
                }
            } catch (ClassNotFoundException ignored) {}

            info.put("listenerClass", listenerClass);
            info.put("listenerName", listenerType);

            // Try to get the resolved event type
            try {
                java.lang.reflect.Method[] methods = listener.getClass().getMethods();
                for (Method m : methods) {
                    if (m.getName().equals("onApplicationEvent") && m.getParameterCount() == 1) {
                        String eventTypeName = m.getParameterTypes()[0].getSimpleName();
                        info.put("listensFor", eventTypeName);
                        if (typeFilter != null && !typeFilter.isBlank()
                                && !eventTypeName.toLowerCase().contains(typeFilter.toLowerCase())) {
                            return null;
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}

            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private void scanEventListenerAnnotations(List<Map<String, Object>> result, String typeFilter) {
        try {
            String[] beanNames = ctx.getBeanDefinitionNames();
            for (String beanName : beanNames) {
                try {
                    Object bean = ctx.getBean(beanName);
                    Class<?> clazz = bean.getClass();
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.isAnnotationPresent(EventListener.class)) {
                            Class<?>[] paramTypes = m.getParameterTypes();
                            if (paramTypes.length > 0) {
                                String eventTypeName = paramTypes[0].getSimpleName();
                                if (typeFilter != null && !typeFilter.isBlank()
                                        && !eventTypeName.toLowerCase().contains(typeFilter.toLowerCase())) {
                                    continue;
                                }
                                Map<String, Object> info = new LinkedHashMap<>();
                                info.put("type", "@EventListener");
                                info.put("bean", beanName);
                                info.put("method", m.getName());
                                info.put("listensFor", eventTypeName);
                                result.add(info);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
