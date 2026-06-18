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
 * All responses are streamed via {@link ChatCallback} for real-time UX.
 */
public class DebugAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(DebugAgentEngine.class);

    private final OpenAiClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final DebugAgentProperties properties;

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    private static final String SYSTEM_PROMPT = """
            You are an expert JVM and Spring Boot debugging assistant.
            You are running INSIDE the developer's Spring Boot application and have direct access
            to its runtime state through diagnostic tools.

            ## Your Capabilities
            You can call tools to inspect the live application:

            **JVM Tools:**
            - get_thread_summary: Overview of thread states and counts
            - get_thread_dump: Full thread dump with optional stack traces
            - detect_deadlocks: Check for deadlocked threads
            - get_cpu_consuming_threads: Find CPU-intensive threads
            - get_memory_summary: Heap/non-heap memory, memory pools (Eden, Old, Metaspace)
            - get_gc_stats: GC collection count and time
            - get_runtime_info: Java version, uptime, JVM args, loaded classes

            **Spring Tools:**
            - get_all_beans: List all Spring beans with type and scope
            - get_bean_details: Inspect a bean's fields and current values
            - get_bean_dependencies: Check bean dependency relationships
            - get_property: Get a specific config property value
            - search_properties: Search properties by keyword
            - get_active_profiles: Active Spring profiles
            - get_context_info: ApplicationContext info

            **WatchPoint Tools (Virtual Breakpoints):**
            - search_loaded_classes: Find loaded classes by name pattern (DO THIS FIRST to get exact class name)
            - add_watch_point: Monitor a method — captures args, return value, timing on each call
            - get_watch_results: Read captured invocations from a watch point
            - list_watch_points: List all active watch points
            - remove_watch_point: Deactivate a watch point
            - get_bean_field_value: Read a specific field's current value on a bean

            ## Workflow
            1. Understand the developer's problem description
            2. Proactively call tools to gather diagnostic data — DO NOT just ask questions
            3. Analyze the collected data to identify root causes
            4. Provide clear, actionable solutions

            ## Guidelines
            - Be proactive: gather data with tools before answering
            - For a slow method: search_loaded_classes → add_watch_point → ask user to trigger → get_watch_results
            - For memory issues: get_memory_summary → get_gc_stats → analyze
            - For thread issues: get_thread_summary → get_thread_dump or detect_deadlocks
            - For DI issues: get_all_beans → get_bean_details → get_bean_dependencies
            - Always present data in a readable format
            - Respond in the same language the developer uses (Chinese/English/etc.)
            - When you find a problem, explain the root cause and give concrete fix suggestions
            """;

    public DebugAgentEngine(OpenAiClient llmClient,
                            ToolRegistry toolRegistry,
                            ToolExecutor toolExecutor,
                            DebugAgentProperties properties) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
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

            // Build the request
            ChatRequest request = new ChatRequest();
            request.setModel(llmConfig.getModel());
            request.setTemperature(llmConfig.getTemperature());
            request.setMaxTokens(llmConfig.getMaxTokens());
            request.setToolChoice("auto");

            // Messages: system prompt + conversation history
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(SYSTEM_PROMPT));
            messages.addAll(session.getMessages());
            request.setMessages(messages);

            // Tools
            request.setTools(toolRegistry.getAllToolDefinitions());

            // Stream the response and capture state
            StringBuilder contentBuilder = new StringBuilder();
            @SuppressWarnings("unchecked")
            List<ToolCall>[] toolCallHolder = new List[]{Collections.emptyList()};
            boolean[] hadError = {false};

            llmClient.chatCompletionStreamRaw(request, new OpenAiClient.StreamHandler() {
                @Override
                public void onContent(String content) {
                    callback.onContent(content);
                }

                @Override
                public void onComplete(List<ToolCall> toolCalls, String finishReason) {
                    toolCallHolder[0] = toolCalls;
                    log.info("Stream complete. Content: {} chars, Tool calls: {}",
                            contentBuilder.length(), toolCalls.size());
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

        // Exceeded max rounds
        callback.onError("Exceeded maximum tool-calling rounds (" + maxRounds
                + "). The issue may be too complex for autonomous resolution.");
    }
}
