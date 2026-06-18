package com.debugagent.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.debugagent.engine.ChatCallback;
import com.debugagent.engine.DebugAgentEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * REST controller for the embedded debug agent chat UI.
 *
 * Serves a chat page at /agent and provides SSE streaming endpoints.
 */
@RestController
@RequestMapping("${debug-agent.base-path:/agent}")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final Executor executor = Executors.newCachedThreadPool();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DebugAgentEngine engine;

    @Value("${debug-agent.base-path:/agent}")
    private String basePath;

    /**
     * Serve the embedded chat UI.
     */
    @GetMapping
    public String chatPage(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String base = (contextPath + basePath).replaceAll("/+", "/");
        return ChatPageHtml.render(base);
    }

    /**
     * Streaming chat endpoint via SSE.
     *
     * Sends events: content, tool_start, tool_result, done, error
     */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequestDto request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();

        executor.execute(() -> {
            try {
                engine.chat(request.message(), sessionId, new ChatCallback() {
                    @Override
                    public void onContent(String chunk) {
                        try {
                            // JSON-encode so newlines survive SSE transport.
                            // Without this, \n in content breaks the SSE line protocol.
                            String json = objectMapper.writeValueAsString(chunk);
                            emitter.send(SseEmitter.event()
                                    .name("content")
                                    .data(json, MediaType.TEXT_PLAIN));
                        } catch (Exception e) {
                            log.debug("SSE send error (client likely disconnected)", e);
                        }
                    }

                    @Override
                    public void onToolStart(String toolName, String arguments) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("tool_start")
                                    .data(toolName));
                        } catch (Exception e) {
                            log.debug("SSE send error", e);
                        }
                    }

                    @Override
                    public void onToolResult(String toolName, String result) {
                        try {
                            String preview = result.length() > 200
                                    ? result.substring(0, 200) + "..."
                                    : result;
                            emitter.send(SseEmitter.event()
                                    .name("tool_result")
                                    .data(toolName + ": " + preview));
                        } catch (Exception e) {
                            log.debug("SSE send error", e);
                        }
                    }

                    @Override
                    public void onComplete() {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data(""));
                            emitter.complete();
                        } catch (Exception e) {
                            log.debug("SSE complete error", e);
                            emitter.complete();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(message));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    }
                });
            } catch (Exception e) {
                log.error("Agent chat error", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("Internal error: " + e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * Clear conversation history for a session.
     */
    @PostMapping("/api/clear")
    public String clear(@RequestBody ClearRequestDto request) {
        String sessionId = request.sessionId();
        if (sessionId != null) {
            engine.clearSession(sessionId);
        }
        return "{\"status\":\"cleared\"}";
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/api/health")
    public String health() {
        return "{\"status\":\"ok\",\"agent\":\"spring-debug-agent\"}";
    }

    // ==================== DTOs ====================

    public record ChatRequestDto(String message, String sessionId) {}

    public record ClearRequestDto(String sessionId) {}
}
