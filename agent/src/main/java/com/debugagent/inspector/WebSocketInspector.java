package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket session diagnostic tools.
 * Tracks active sessions, statistics, and recent messages in-memory.
 * Conditional on spring-websocket being on classpath.
 */
public class WebSocketInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    /** Active session registry: sessionId -> session descriptor. */
    private final Map<String, Map<String, Object>> sessionRegistry = new ConcurrentHashMap<>();

    /** Recent messages per session (capped). */
    private final Map<String, List<Map<String, Object>>> messageBuffer = new ConcurrentHashMap<>();
    private static final int MAX_MESSAGES_PER_SESSION = 100;

    /** Aggregate counters. */
    private final AtomicLong totalSessionsCreated = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Count and list active WebSocket sessions. Each entry shows session ID, URI, remote address, and creation time. Combines registry-maintained sessions with live sessions obtained from Spring handlers.")
    public List<Map<String, Object>> getWebsocketSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        try {
            sessions.addAll(discoverLiveSessions());
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Failed to discover live sessions: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            sessions.add(err);
        }

        // Merge with registry (registry may have entries that discovery misses)
        for (Map.Entry<String, Map<String, Object>> entry : sessionRegistry.entrySet()) {
            if (sessions.stream().noneMatch(s -> entry.getKey().equals(s.get("sessionId")))) {
                sessions.add(new LinkedHashMap<>(entry.getValue()));
            }
        }

        if (sessions.isEmpty()) {
            sessions.add(Map.of(
                    "note", "No WebSocket sessions tracked.",
                    "hint", "Sessions are tracked when WebSocketHandler registration is detected, " +
                            "or when applications call recordSession()/recordMessage()."
            ));
        }
        return sessions;
    }

    @DebugTool(description = "Get WebSocket statistics: total sessions created, currently active sessions, and messages sent/received counters. Useful for monitoring throughput and detecting leaks.")
    public Map<String, Object> getWebsocketStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeSessions", sessionRegistry.size());
        stats.put("totalSessionsCreated", totalSessionsCreated.get());
        stats.put("messagesSent", messagesSent.get());
        stats.put("messagesReceived", messagesReceived.get());
        stats.put("trackedSessionBuffer", messageBuffer.size());

        // Endpoint configuration
        try {
            String endpoint = ctx.getEnvironment()
                    .getProperty("spring.websocket.enabled");
            if (endpoint != null) stats.put("websocketEnabled", endpoint);
        } catch (Exception ignored) {}

        // Handler count via Spring WebSocketHandlerRegistry if available
        try {
            List<Object> registries = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry");
            if (!registries.isEmpty()) {
                stats.put("handlerRegistries", registries.size());
            }
        } catch (Exception ignored) {}

        return stats;
    }

    @DebugTool(description = "Return recent in-memory messages for a specific WebSocket session. If sessionId is empty, returns a per-session message count summary instead.")
    public Map<String, Object> getWebsocketMessages(
            @ToolParam(description = "Session ID (leave empty for summary across all sessions)", required = false) String sessionId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (sessionId == null || sessionId.isBlank()) {
            List<Map<String, Object>> summary = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : messageBuffer.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sessionId", entry.getKey());
                row.put("messageCount", entry.getValue().size());
                row.put("registeredAt", sessionRegistry.containsKey(entry.getKey())
                        ? sessionRegistry.get(entry.getKey()).get("createdAt") : null);
                summary.add(row);
            }
            result.put("sessions", summary);
            result.put("totalSessionsWithMessages", summary.size());
            return result;
        }

        List<Map<String, Object>> messages = messageBuffer.get(sessionId);
        if (messages == null) {
            result.put("error", "No messages tracked for session: " + sessionId);
            result.put("hint", "Use getWebsocketSessions to list active sessions with tracked messages.");
            return result;
        }

        synchronized (messages) {
            result.put("sessionId", sessionId);
            result.put("messageCount", messages.size());
            result.put("messages", new ArrayList<>(messages));
        }
        return result;
    }

    // ---- Public recording API (called by application interceptors/handlers) ----

    public void recordSession(WebSocketSession session) {
        if (session == null) return;
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("sessionId", session.getId());
        try {
            descriptor.put("uri", session.getUri() != null ? session.getUri().toString() : null);
        } catch (Exception ignored) { descriptor.put("uri", null); }
        try {
            descriptor.put("remoteAddress",
                    session.getRemoteAddress() != null ? session.getRemoteAddress().toString() : null);
        } catch (Exception ignored) { descriptor.put("remoteAddress", null); }
        try {
            descriptor.put("principal",
                    session.getPrincipal() != null ? session.getPrincipal().getName() : null);
        } catch (Exception ignored) { descriptor.put("principal", null); }
        try {
            descriptor.put("open", session.isOpen());
        } catch (Exception ignored) {}
        try {
            descriptor.put("acceptedProtocol", session.getAcceptedProtocol());
        } catch (Exception ignored) {}
        descriptor.put("createdAt", new Date());
        sessionRegistry.put(session.getId(), descriptor);
        totalSessionsCreated.incrementAndGet();
    }

    public void recordSessionClosed(String sessionId) {
        sessionRegistry.remove(sessionId);
    }

    public void recordMessage(String sessionId, String direction, Object payload) {
        if (sessionId == null) return;
        List<Map<String, Object>> messages = messageBuffer
                .computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", new Date());
        entry.put("direction", direction != null ? direction : "in");
        entry.put("type", payload != null ? payload.getClass().getSimpleName() : "null");
        String preview;
        if (payload == null) {
            preview = "null";
        } else {
            preview = String.valueOf(payload);
            if (preview.length() > 500) preview = preview.substring(0, 500) + "... (truncated)";
        }
        entry.put("preview", preview);

        synchronized (messages) {
            messages.add(entry);
            while (messages.size() > MAX_MESSAGES_PER_SESSION) {
                messages.remove(0);
            }
        }

        if ("out".equalsIgnoreCase(direction)) {
            messagesSent.incrementAndGet();
        } else {
            messagesReceived.incrementAndGet();
        }
    }

    // ---- Live session discovery from Spring handlers ----

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> discoverLiveSessions() {
        List<Map<String, Object>> discovered = new ArrayList<>();

        // Look for WebSocketHandler beans; some implementations expose active sessions
        List<Object> handlers = ReflectionHelper.getBeansOfType(ctx,
                "org.springframework.web.socket.handler.SessionTrackingWebSocketHandler");
        if (handlers.isEmpty()) {
            handlers = ReflectionHelper.getBeansOfType(ctx,
                    "org.springframework.web.socket.WebSocketHandler");
        }
        for (Object handler : handlers) {
            Object liveField = ReflectionHelper.invokeMethod(handler, "getSessions");
            if (liveField instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof WebSocketSession session) {
                        discovered.add(describe(session));
                    }
                }
            } else if (liveField instanceof Collection<?> coll) {
                for (Object value : coll) {
                    if (value instanceof WebSocketSession session) {
                        discovered.add(describe(session));
                    }
                }
            }
        }
        return discovered;
    }

    private Map<String, Object> describe(WebSocketSession session) {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            info.put("sessionId", session.getId());
            info.put("open", session.isOpen());
            info.put("uri", session.getUri() != null ? session.getUri().toString() : null);
            info.put("remoteAddress",
                    session.getRemoteAddress() != null ? session.getRemoteAddress().toString() : null);
            info.put("principal",
                    session.getPrincipal() != null ? session.getPrincipal().getName() : null);
            info.put("acceptedProtocol", session.getAcceptedProtocol());
        } catch (Exception e) {
            info.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return info;
    }
}
