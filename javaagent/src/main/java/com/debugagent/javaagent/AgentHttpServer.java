package com.debugagent.javaagent;

import com.debugagent.engine.ChatCallback;
import com.debugagent.engine.DebugAgentEngine;
import com.debugagent.web.ChatPageHtml;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight standalone HTTP server using JDK's built-in {@link com.sun.net.httpserver.HttpServer}.
 *
 * <p>Zero Spring MVC dependency — serves the same chat UI and SSE streaming API
 * as the embedded {@code AgentController}, but on a separate port (default 9900).
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>{@code GET /} — Chat UI HTML page</li>
 *   <li>{@code POST /api/chat} — SSE streaming chat (same protocol as AgentController)</li>
 *   <li>{@code POST /api/clear} — Clear conversation session</li>
 *   <li>{@code GET /api/health} — Health check with tool count</li>
 * </ul>
 *
 * <p>All request handling runs on a daemon thread pool — won't block JVM shutdown.
 */
public class AgentHttpServer {

    private static final Logger log = LoggerFactory.getLogger(AgentHttpServer.class);

    private final DebugAgentEngine engine;
    private final AgentConfig config;
    private final int toolCount;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final com.sun.net.httpserver.HttpServer server;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public AgentHttpServer(DebugAgentEngine engine, AgentConfig config, int toolCount) throws IOException {
        this.engine = engine;
        this.config = config;
        this.toolCount = toolCount;
        this.server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(config.getPort()), 0);

        Executor executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "spring-debug-agent-http");
            t.setDaemon(true);
            return t;
        });

        // Routes
        server.createContext("/", this::handleRoot);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/clear", this::handleClear);
        server.createContext("/api/health", this::handleHealth);

        server.setExecutor(executor);
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            server.start();
            log.info("Agent HTTP server started on port {}", config.getPort());
        }
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            server.stop(0);
        }
    }

    // ==================== Handlers ====================

    /**
     * Serve the chat UI HTML.
     */
    private void handleRoot(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String html = ChatPageHtml.render("");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * SSE streaming chat — same event protocol as AgentController.
     *
     * Events:
     *   event: content       data: "text chunk" (JSON-escaped)
     *   event: tool_start    data: tool_name
     *   event: tool_result   data: tool_name:result_summary
     *   event: context_compressed  data: {originalTokens, compressedTokens, removedRounds}
     *   event: error         data: error message
     *   event: done          data:
     */
    private void handleChat(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        // Parse request body
        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String message;
        String sessionId;
        try {
            JsonNode node = objectMapper.readTree(requestBody);
            message = node.has("message") ? node.get("message").asText() : "";
            sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "default";
        } catch (Exception e) {
            sendResponse(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        if (message.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Message is empty\"}");
            return;
        }

        // Check LLM configuration
        if (!config.isLlmConfigured()) {
            // Start SSE response with error
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                writeSseEvent(os, "error", "LLM API key not configured. Set -Ddebug-agent.llm.api-key or LLM_API_KEY env var.");
                writeSseEvent(os, "done", "");
                os.flush();
            }
            return;
        }

        // Set up SSE streaming
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        String escapedMessage = message;

        try {
            // Create streaming callback that writes SSE events
            ChatCallback callback = new ChatCallback() {
                @Override
                public void onContent(String chunk) {
                    try {
                        // Send as JSON-escaped string (same as AgentController)
                        String jsonChunk = objectMapper.writeValueAsString(chunk);
                        writeSseEvent(os, "content", jsonChunk);
                        os.flush();
                    } catch (IOException e) {
                        log.debug("Stream write error (client disconnected?): {}", e.getMessage());
                    }
                }

                @Override
                public void onToolStart(String toolName, String arguments) {
                    try {
                        writeSseEvent(os, "tool_start", toolName);
                        os.flush();
                    } catch (IOException e) {
                        log.debug("Stream write error", e);
                    }
                }

                @Override
                public void onToolResult(String toolName, String result) {
                    try {
                        // Same format as AgentController: "toolName: result" (full result)
                        writeSseEvent(os, "tool_result", toolName + ": " + result);
                        os.flush();
                    } catch (IOException e) {
                        log.debug("Stream write error", e);
                    }
                }

                @Override
                public void onContextCompressed(int originalTokens, int compressedTokens, int removedRounds) {
                    try {
                        String data = objectMapper.writeValueAsString(Map.of(
                                "originalTokens", originalTokens,
                                "compressedTokens", compressedTokens,
                                "removedRounds", removedRounds));
                        writeSseEvent(os, "context_compressed", data);
                        os.flush();
                    } catch (IOException e) {
                        log.debug("Stream write error", e);
                    }
                }

                @Override
                public void onComplete() {
                    try {
                        writeSseEvent(os, "done", "");
                        os.flush();
                    } catch (IOException e) {
                        log.debug("Stream write error", e);
                    }
                }

                @Override
                public void onError(String error) {
                    try {
                        writeSseEvent(os, "error", error);
                        writeSseEvent(os, "done", "");
                        os.flush();
                    } catch (IOException e) {
                        log.debug("Stream write error", e);
                    }
                }
            };

            // Run the agent engine (blocking — streams via callback)
            engine.chat(escapedMessage, sessionId, callback);

        } catch (Exception e) {
            log.error("Chat error", e);
            try {
                writeSseEvent(os, "error", e.getMessage() != null ? e.getMessage() : "Internal error");
                writeSseEvent(os, "done", "");
                os.flush();
            } catch (IOException ignored) {
            }
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Clear a chat session.
     */
    private void handleClear(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        try {
            JsonNode node = objectMapper.readTree(requestBody);
            String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : "default";
            engine.clearSession(sessionId);
        } catch (Exception ignored) {
        }

        sendResponse(exchange, 200, "{\"status\":\"cleared\"}");
    }

    /**
     * Health check endpoint.
     */
    private void handleHealth(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String health = objectMapper.writeValueAsString(Map.of(
                "status", "UP",
                "mode", "javaagent",
                "port", config.getPort(),
                "llmConfigured", config.isLlmConfigured(),
                "model", config.getModel(),
                "toolCount", toolCount));
        sendResponse(exchange, 200, health);
    }

    // ==================== SSE Helpers ====================

    /**
     * Write a single SSE event to the output stream.
     *
     * @param os       The output stream
     * @param event    Event type (content, tool_start, tool_result, done, error)
     * @param data     Event data (raw string, NOT JSON-escaped — caller must escape as needed)
     */
    private static void writeSseEvent(OutputStream os, String event, String data) throws IOException {
        String sseEvent = "event: " + event + "\n" +
                "data: " + data + "\n\n";
        os.write(sseEvent.getBytes(StandardCharsets.UTF_8));
    }

    private void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
