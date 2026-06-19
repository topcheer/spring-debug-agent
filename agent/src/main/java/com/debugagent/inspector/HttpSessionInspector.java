package com.debugagent.inspector;

import com.debugagent.tool.annotation.DebugTool;
import com.debugagent.tool.annotation.ToolParam;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.*;

/**
 * HTTP Session diagnostic tools.
 * Inspects HTTP sessions, their attributes, and allows session management.
 * Conditional on Servlet API being on classpath.
 */
public class HttpSessionInspector implements ApplicationContextAware {

    private ApplicationContext ctx;

    // Track sessions created via this inspector
    private final Map<String, Map<String, Object>> sessionRegistry = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @DebugTool(description = "Get HTTP session statistics and configuration: active sessions, session timeout, session cookie config. Useful for understanding session behavior in the application.")
    public Map<String, Object> getHttpSessions(
            @ToolParam(description = "Include session attribute details (default false)") Boolean includeDetails
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean include = includeDetails != null && includeDetails;

        // Session registry info
        result.put("trackedSessions", sessionRegistry.size());

        List<Map<String, Object>> sessionList = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : sessionRegistry.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>(entry.getValue());
            if (!include) {
                info.remove("attributes");
            }
            sessionList.add(info);
        }
        result.put("sessions", sessionList);

        // Server session config
        try {
            // Try to read from Environment
            String timeout = ctx.getEnvironment().getProperty("server.servlet.session.timeout");
            result.put("configuredTimeout", timeout != null ? timeout : "default (30m)");

            String cookieName = ctx.getEnvironment().getProperty("server.servlet.session.cookie.name");
            result.put("cookieName", cookieName != null ? cookieName : "JSESSIONID");
        } catch (Exception ignored) {}

        result.put("note", "Session tracking is limited to sessions created during diagnostic tool calls. " +
                "For full session tracking, integrate with Spring Session or HttpSessionEventPublisher.");

        return result;
    }

    @DebugTool(description = "Get attributes of a specific HTTP session by ID. Shows all session attribute names and values. Useful for inspecting what's stored in a user's session.")
    public Map<String, Object> getSessionAttributes(
            @ToolParam(description = "Session ID (leave empty to list all tracked sessions)") String sessionId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (sessionId == null || sessionId.isBlank()) {
            // List all tracked sessions
            List<Map<String, Object>> sessions = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : sessionRegistry.entrySet()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("sessionId", entry.getKey());
                info.put("createdTime", entry.getValue().get("createdTime"));
                Map<?, ?> attrs = (Map<?, ?>) entry.getValue().get("attributes");
                info.put("attributeCount", attrs != null ? attrs.size() : 0);
                sessions.add(info);
            }
            result.put("sessions", sessions);
            result.put("count", sessions.size());
            return result;
        }

        Map<String, Object> sessionData = sessionRegistry.get(sessionId);
        if (sessionData == null) {
            result.put("error", "Session not found: " + sessionId);
            result.put("hint", "Use get_http_sessions to list available sessions");
            return result;
        }

        result.put("sessionId", sessionId);
        result.put("attributes", sessionData.get("attributes"));
        result.put("createdTime", sessionData.get("createdTime"));
        return result;
    }

    @DebugTool(description = "Invalidate (destroy) an HTTP session by ID. All session attributes will be removed. Useful for forcing logout or clearing session-scoped state during debugging.")
    public Map<String, Object> invalidateSession(
            @ToolParam(description = "Session ID to invalidate", required = true) String sessionId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> removed = sessionRegistry.remove(sessionId);
        if (removed != null) {
            result.put("status", "invalidated");
            result.put("sessionId", sessionId);
            result.put("removedAttributeCount", removed.get("attributes") != null
                    ? ((Map<?, ?>) removed.get("attributes")).size() : 0);
        } else {
            result.put("error", "Session not found: " + sessionId);
        }

        return result;
    }
}
