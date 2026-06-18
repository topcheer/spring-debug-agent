package com.debugagent.llm;

import com.debugagent.llm.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A lightweight OpenAI-compatible chat client with built-in retry.
 *
 * Works with any endpoint that implements the /v1/chat/completions API:
 *   - OpenAI (api.openai.com)
 *   - Ollama (localhost:11434)
 *   - vLLM, LM Studio, Together AI, DeepSeek, Moonshot, ZhipuAI, etc.
 *
 * Uses Java's built-in HttpClient — zero extra HTTP dependencies.
 */
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Maximum retry attempts for transient errors (429/5xx/IO). */
    private final int maxRetries;

    /** Base delay in milliseconds for exponential backoff. */
    private final long retryBaseDelayMs;

    /** Maximum delay cap in milliseconds. */
    private final long retryMaxDelayMs;

    /**
     * Callback for streaming responses.
     */
    public interface StreamHandler {
        void onContent(String content);
        void onComplete(List<ToolCall> accumulatedToolCalls, String finishReason);
        void onError(Throwable error);
    }

    // ==================== Constructors ====================

    public OpenAiClient(String baseUrl, String apiKey, int timeoutSeconds) {
        this(baseUrl, apiKey, timeoutSeconds, 3, 1000, 30000);
    }

    public OpenAiClient(String baseUrl, String apiKey, int timeoutSeconds,
                        int maxRetries, long retryBaseDelayMs, long retryMaxDelayMs) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.maxRetries = maxRetries;
        this.retryBaseDelayMs = retryBaseDelayMs;
        this.retryMaxDelayMs = retryMaxDelayMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ==================== Non-Streaming ====================

    /**
     * Non-streaming chat completion with automatic retry.
     */
    public ChatResponse chatCompletion(ChatRequest request) throws Exception {
        request.setStream(false);
        String body = objectMapper.writeValueAsString(request);

        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest httpRequest = buildRequest(body);
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                int code = response.statusCode();
                if (code >= 400 && isRetriable(code)) {
                    lastError = new RuntimeException(
                            "LLM API error " + code + ": " + response.body());
                    long delay = calculateDelay(code, response, attempt);
                    log.warn("Retriable error {} (attempt {}/{}), retrying in {}ms",
                            code, attempt + 1, maxRetries, delay);
                    sleep(delay);
                    continue;
                }
                if (code >= 400) {
                    throw new RuntimeException("LLM API error " + code + ": "
                            + response.body());
                }

                return objectMapper.readValue(response.body(), ChatResponse.class);

            } catch (java.io.IOException | InterruptedException e) {
                lastError = e;
                if (attempt < maxRetries) {
                    long delay = calculateDelay(0, null, attempt);
                    log.warn("Network error (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, maxRetries, delay, e.getMessage());
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    sleep(delay);
                }
            }
        }
        throw new RuntimeException("Exhausted retries after " + maxRetries
                + " attempts", lastError);
    }

    // ==================== Streaming (legacy) ====================

    /**
     * Streaming chat completion with index-aware tool call accumulation.
     * Retries only if no content has been streamed yet.
     */
    public void chatCompletionStream(ChatRequest request, StreamHandler handler) {
        request.setStream(true);
        try {
            String body = objectMapper.writeValueAsString(request);

            Exception lastError = null;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                HttpRequest httpRequest = buildRequest(body);
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream());

                int code = response.statusCode();
                if (code >= 400) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    if (isRetriable(code) && attempt < maxRetries) {
                        long delay = calculateDelay(code, response, attempt);
                        log.warn("Retriable error {} (attempt {}/{}), retrying in {}ms",
                                code, attempt + 1, maxRetries, delay);
                        sleep(delay);
                        continue;
                    }
                    handler.onError(new RuntimeException(
                            "LLM API error " + code + ": " + errorBody));
                    return;
                }

                // Stream OK — process normally (no retry possible after this point)
                processStreamRaw(response.body(), handler);
                return;
            }

            // Exhausted retries
            handler.onError(new RuntimeException(
                    "Exhausted retries after " + maxRetries + " attempts", lastError));

        } catch (Exception e) {
            handler.onError(e);
        }
    }

    // ==================== Streaming (raw JSON, primary) ====================

    /**
     * Primary streaming method used by the agent engine.
     * Parses raw JSON for correct tool_call index handling.
     * Retries on 429/5xx/network errors before streaming starts.
     */
    public void chatCompletionStreamRaw(ChatRequest request, StreamHandler handler) {
        request.setStream(true);
        try {
            String body = objectMapper.writeValueAsString(request);
            Exception lastError = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                HttpRequest httpRequest = buildRequest(body);

                HttpResponse<java.io.InputStream> response;
                try {
                    response = httpClient.send(httpRequest,
                            HttpResponse.BodyHandlers.ofInputStream());
                } catch (java.io.IOException e) {
                    if (attempt < maxRetries) {
                        long delay = calculateDelay(0, null, attempt);
                        log.warn("Network error (attempt {}/{}), retrying in {}ms: {}",
                                attempt + 1, maxRetries, delay, e.getMessage());
                        sleep(delay);
                        lastError = e;
                        continue;
                    }
                    handler.onError(e);
                    return;
                }

                int code = response.statusCode();
                if (code >= 400) {
                    String errorBody = readStreamSafely(response.body());
                    if (isRetriable(code) && attempt < maxRetries) {
                        long delay = calculateDelay(code, response, attempt);
                        log.warn("Retriable HTTP {} (attempt {}/{}), retrying in {}ms: {}",
                                code, attempt + 1, maxRetries, delay, errorBody);
                        sleep(delay);
                        lastError = new RuntimeException(
                                "LLM API error " + code + ": " + errorBody);
                        continue;
                    }
                    handler.onError(new RuntimeException(
                            "LLM API error " + code + ": " + errorBody));
                    return;
                }

                // Stream OK — process and return (no retry mid-stream)
                try {
                    processStreamRaw(response.body(), handler);
                    return;
                } catch (java.io.IOException e) {
                    // Mid-stream IO error — can't safely retry
                    log.warn("Stream interrupted (partial content may have been sent): {}",
                            e.getMessage());
                    handler.onError(e);
                    return;
                }
            }

            // Exhausted retries
            if (lastError != null) {
                handler.onError(new RuntimeException(
                        "Exhausted retries after " + maxRetries + " attempts", lastError));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handler.onError(e);
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    // ==================== Stream Processing ====================

    /**
     * Process the SSE stream, accumulate tool calls, and forward content.
     *
     * @throws java.io.IOException on mid-stream connection errors
     */
    private void processStreamRaw(java.io.InputStream input, StreamHandler handler) throws java.io.IOException {
        StringBuilder contentBuilder = new StringBuilder();
        Map<Integer, ToolCall> toolCallMap = new TreeMap<>();
        String[] finishReason = {null};

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;

                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) continue;

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");

                // Content → forward immediately
                String content = delta.path("content").asText("");
                if (!content.isEmpty() && !delta.path("content").isMissingNode()) {
                    contentBuilder.append(content);
                    handler.onContent(content);
                }

                // Tool calls with proper index
                JsonNode toolCallsNode = delta.path("tool_calls");
                if (toolCallsNode.isArray()) {
                    for (JsonNode tcNode : toolCallsNode) {
                        int idx = tcNode.path("index").asInt(0);
                        ToolCall existing = toolCallMap.computeIfAbsent(idx, k -> new ToolCall());

                        if (tcNode.has("id")) {
                            existing.setId(tcNode.path("id").asText());
                            existing.setType(tcNode.path("type").asText("function"));
                        }
                        JsonNode fnNode = tcNode.path("function");
                        if (existing.getFunction() == null) {
                            existing.setFunction(new ToolCall.FunctionCall());
                        }
                        if (fnNode.has("name")) {
                            existing.getFunction().setName(fnNode.path("name").asText());
                        }
                        if (fnNode.has("arguments")) {
                            String args = fnNode.path("arguments").asText("");
                            existing.getFunction().setArguments(
                                    existing.getFunction().getArguments() == null
                                            ? args
                                            : existing.getFunction().getArguments() + args);
                        }
                    }
                }

                String fr = choice.path("finish_reason").asText(null);
                if (fr != null) finishReason[0] = fr;
            }
        }

        List<ToolCall> toolCalls = new ArrayList<>(toolCallMap.values());
        toolCalls.removeIf(tc -> tc.getFunction() == null || tc.getFunction().getName() == null);
        handler.onComplete(toolCalls, finishReason[0]);
    }

    // ==================== Retry Helpers ====================

    /**
     * Determine if an HTTP status code is worth retrying.
     */
    private static boolean isRetriable(int statusCode) {
        return statusCode == 429   // Rate limited
                || statusCode == 500  // Internal server error
                || statusCode == 502  // Bad gateway
                || statusCode == 503  // Service unavailable
                || statusCode == 504; // Gateway timeout
    }

    /**
     * Calculate the retry delay for a given attempt.
     * Uses exponential backoff with jitter.
     * Respects Retry-After header for 429 responses if present.
     *
     * @param statusCode HTTP status code (0 for network errors)
     * @param response   HTTP response (may be null)
     * @param attempt    0-based attempt number
     * @return delay in milliseconds
     */
    private long calculateDelay(int statusCode, HttpResponse<?> response, int attempt) {
        // Check Retry-After header first
        if (response != null) {
            Optional<String> retryAfter = response.headers().firstValue("Retry-After");
            if (retryAfter.isPresent()) {
                try {
                    // Could be seconds or HTTP-date; we handle seconds
                    long seconds = Long.parseLong(retryAfter.get());
                    return Math.min(seconds * 1000, retryMaxDelayMs);
                } catch (NumberFormatException ignored) {
                    // Not a number, fall through to backoff
                }
            }
        }

        // Exponential backoff: base * 2^attempt + jitter
        long base = retryBaseDelayMs * (1L << attempt);
        long jitter = ThreadLocalRandom.current().nextLong(0, base / 2 + 1);
        return Math.min(base + jitter, retryMaxDelayMs);
    }

    /**
     * Sleep without throwing, handling interrupts gracefully.
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Read an input stream fully, swallowing errors. Used for error response bodies.
     */
    private String readStreamSafely(java.io.InputStream is) {
        try {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(unreadable)";
        }
    }

    // ==================== Request Building ====================

    private HttpRequest buildRequest(String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
