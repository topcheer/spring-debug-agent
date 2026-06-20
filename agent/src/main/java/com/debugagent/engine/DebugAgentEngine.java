package com.debugagent.engine;

import com.debugagent.autoconfigure.DebugAgentProperties;
import com.debugagent.llm.OpenAiClient;
import com.debugagent.llm.model.*;
import com.debugagent.tool.ToolExecutor;
import com.debugagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The core agent reasoning engine.
 *
 * Implements the tool-calling loop:
 * 1. Send user message + tools to LLM
 * 2. If LLM returns tool calls → execute them → feed results back → repeat
 * 3. If LLM returns content → stream to caller → done
 *
 * Features:
 * - Dynamic system prompt generated from registered tools
 * - Real token usage tracking from LLM API responses
 * - Automatic context compression when token count exceeds threshold
 *
 * All responses are streamed via {@link ChatCallback} for real-time UX.
 */
public class DebugAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(DebugAgentEngine.class);

    private final OpenAiClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final DebugAgentProperties properties;

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /** Builds system prompt dynamically from registered tools. */
    private final SystemPromptBuilder promptBuilder;

    /** Compresses context when token count exceeds the limit. */
    private final ContextCompressor contextCompressor;

    /** Cached system prompt (rebuilt only when tool set changes, which is never at runtime). */
    private final String systemPrompt;

    public DebugAgentEngine(OpenAiClient llmClient,
                            ToolRegistry toolRegistry,
                            ToolExecutor toolExecutor,
                            DebugAgentProperties properties) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
        this.promptBuilder = new SystemPromptBuilder(toolRegistry);
        this.systemPrompt = promptBuilder.build();
        this.contextCompressor = new ContextCompressor(
                llmClient, properties.getLlm().getModel(), properties.getLlm().getTemperature(),
                properties.getLlm().getContextWindowTokens(), 3);
        log.info("DebugAgentEngine initialized: {} tools, context window {} tokens",
                toolRegistry.toolCount(), properties.getLlm().getContextWindowTokens());
    }

    /**
     * Process a user message with streaming output.
     */
    public void chat(String userMessage, String sessionId, ChatCallback callback) {
        try {
            ChatSession session = getOrCreateSession(sessionId);
            session.addMessage(ChatMessage.user(userMessage));

            runToolLoop(session, callback);
        } catch (Exception e) {
            log.error("Error during agent chat", e);
            callback.onError("Internal error: " + e.getMessage());
        }
    }

    /**
     * Clear a session's conversation history.
     */
    public void clearSession(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.clear();
        }
    }

    /**
     * Get or create a session.
     */
    public ChatSession getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ChatSession::new);
    }

    // ==================== Core Tool-Calling Loop ====================

    private void runToolLoop(ChatSession session, ChatCallback callback) {
        int maxRounds = properties.getLlm().getMaxToolRounds();
        DebugAgentProperties.Llm llmConfig = properties.getLlm();

        for (int round = 0; round < maxRounds; round++) {
            log.info("Tool-calling round {} for session {}", round + 1, session.getSessionId());

            // ── Check if context compression is needed ──
            if (round > 0 && contextCompressor.needsCompression(session.getCurrentContextTokens())) {
                log.info("Context compression triggered: {} tokens > {} limit",
                        session.getCurrentContextTokens(), llmConfig.getContextWindowTokens());

                ContextCompressor.CompressionResult result = contextCompressor.compress(session);
                if (result != null) {
                    // Notify the user that compression happened
                    callback.onContent("\n\n> [Context auto-compressed: " +
                            result.originalTokens + " → ~" + result.compressedTokens + " tokens" +
                            " (" + result.strategy + ")]\n\n");
                    callback.onContextCompressed(
                            result.originalTokens, result.compressedTokens, result.removedRounds);

                    log.info("Context compressed: {} → ~{} tokens ({})",
                            result.originalTokens, result.compressedTokens, result.strategy);
                }
            }

            // ── Build the request ──
            ChatRequest request = new ChatRequest();
            request.setModel(llmConfig.getModel());
            request.setTemperature(llmConfig.getTemperature());
            request.setMaxTokens(llmConfig.getMaxTokens());
            request.setToolChoice("auto");
            request.setStreamOptions(Map.of("include_usage", true));

            // Messages: dynamic system prompt + conversation history
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(systemPrompt));
            messages.addAll(session.getMessages());
            request.setMessages(messages);

            // Tools
            request.setTools(toolRegistry.getAllToolDefinitions());

            // ── Stream the response ──
            StringBuilder contentBuilder = new StringBuilder();
            @SuppressWarnings("unchecked")
            List<ToolCall>[] toolCallHolder = new List[]{Collections.emptyList()};
            boolean[] hadError = {false};
            TokenUsage[] usageHolder = {null};

            llmClient.chatCompletionStreamRaw(request, new OpenAiClient.StreamHandler() {
                @Override
                public void onContent(String content) {
                    contentBuilder.append(content);
                    callback.onContent(content);
                }

                @Override
                public void onComplete(List<ToolCall> toolCalls, String finishReason, TokenUsage usage) {
                    toolCallHolder[0] = toolCalls;
                    usageHolder[0] = usage;
                    log.info("Stream complete. Content: {} chars, Tool calls: {}, Usage: {}",
                            contentBuilder.length(), toolCalls.size(), usage);
                }

                @Override
                public void onError(Throwable error) {
                    hadError[0] = true;
                    log.error("Stream error", error);
                    callback.onError("LLM API error: " + error.getMessage());
                }
            });

            if (hadError[0]) {
                return; // Error already reported via callback
            }

            // ── Record token usage for compression decisions ──
            if (usageHolder[0] != null) {
                session.recordTokenUsage(usageHolder[0]);
                log.debug("Session {} token usage: {} (cumulative prompt: {})",
                        session.getSessionId(), usageHolder[0], session.getCurrentContextTokens());
            }

            List<ToolCall> toolCalls = toolCallHolder[0];

            if (toolCalls == null || toolCalls.isEmpty()) {
                // No tool calls → final answer is done (content was already streamed)
                session.addMessage(ChatMessage.assistant(contentBuilder.toString(), null));
                callback.onComplete();
                return;
            }

            // LLM wants to call tools → add the assistant message with tool calls
            session.addMessage(ChatMessage.assistant(contentBuilder.toString(), toolCalls));

            // Execute each tool call
            for (ToolCall tc : toolCalls) {
                String toolName = tc.getFunction().getName();
                String arguments = tc.getFunction().getArguments();

                callback.onToolStart(toolName, arguments);

                try {
                    String result = toolExecutor.execute(toolName, arguments);
                    callback.onToolResult(toolName, result);

                    // Add tool result to conversation
                    session.addMessage(ChatMessage.tool(tc.getId(), result));
                } catch (Exception e) {
                    String errorResult = "{\"error\": \"" + e.getMessage() + "\"}";
                    callback.onToolResult(toolName, errorResult);
                    session.addMessage(ChatMessage.tool(tc.getId(), errorResult));
                }
            }

            // Loop continues → LLM will analyze tool results in the next round
        }

        // Reached max rounds — force a final summary instead of erroring out.
        // Send one more request with tool_choice="none" so the LLM MUST produce
        // a text answer based on everything it has gathered so far.
        log.info("Reached max rounds ({}) for session {}, forcing final summary",
                maxRounds, session.getSessionId());

        ChatRequest finalRequest = new ChatRequest();
        finalRequest.setModel(llmConfig.getModel());
        finalRequest.setTemperature(llmConfig.getTemperature());
        finalRequest.setMaxTokens(llmConfig.getMaxTokens());
        finalRequest.setToolChoice("none");
        finalRequest.setStreamOptions(Map.of("include_usage", true));

        List<ChatMessage> finalMessages = new ArrayList<>();
        finalMessages.add(ChatMessage.system(systemPrompt));
        finalMessages.addAll(session.getMessages());
        // Add a nudge for the LLM to summarize
        finalMessages.add(ChatMessage.system(
                "You have reached the maximum number of tool-calling rounds. "
                + "Based on all the diagnostic data you have gathered so far, "
                + "provide a comprehensive analysis and actionable recommendations NOW. "
                + "Do not attempt to call more tools."));
        finalRequest.setMessages(finalMessages);
        // No tools — forces text-only response
        finalRequest.setTools(Collections.emptyList());

        llmClient.chatCompletionStreamRaw(finalRequest, new OpenAiClient.StreamHandler() {
            @Override
            public void onContent(String content) {
                callback.onContent(content);
            }

            @Override
            public void onComplete(List<ToolCall> toolCalls, String finishReason, TokenUsage usage) {
                if (usage != null) {
                    session.recordTokenUsage(usage);
                }
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                // If even the summary fails, at least give a graceful message
                log.error("Final summary also failed", error);
                callback.onContent("\n\n*I've gathered diagnostic data from multiple tools "
                        + "but reached the analysis limit. Please ask a more specific question "
                        + "about a particular component for deeper analysis.*");
                callback.onComplete();
            }
        });
    }
}
