package com.debugagent.engine;

import com.debugagent.llm.model.ChatMessage;
import com.debugagent.llm.model.TokenUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages conversation sessions in memory.
 * Tracks cumulative token usage for context compression decisions.
 */
public class ChatSession {

    private final String sessionId;
    private final long createdAt;
    private final List<ChatMessage> messages = new ArrayList<>();
    private volatile long lastActiveAt;

    /** Token usage from the most recent LLM API response. */
    private volatile TokenUsage lastTokenUsage;

    /** Cumulative prompt tokens across all rounds in this session. */
    private volatile int cumulativePromptTokens = 0;

    /** Total completion tokens across all rounds. */
    private volatile int cumulativeCompletionTokens = 0;

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
    }

    public String getSessionId() { return sessionId; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }

    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        lastActiveAt = System.currentTimeMillis();
    }

    /**
     * Replace the entire message list (used by context compression).
     */
    public void replaceMessages(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        lastActiveAt = System.currentTimeMillis();
    }

    /**
     * Record token usage from an LLM API response.
     */
    public void recordTokenUsage(TokenUsage usage) {
        if (usage == null) return;
        this.lastTokenUsage = usage;
        this.cumulativePromptTokens = usage.getPromptTokens();
        this.cumulativeCompletionTokens += usage.getCompletionTokens();
    }

    public TokenUsage getLastTokenUsage() { return lastTokenUsage; }
    public int getCumulativePromptTokens() { return cumulativePromptTokens; }
    public int getCumulativeCompletionTokens() { return cumulativeCompletionTokens; }

    /**
     * The prompt_tokens from the last API call represents the current
     * context window size (all messages sent to the LLM).
     */
    public int getCurrentContextTokens() {
        return cumulativePromptTokens;
    }

    public void clear() {
        this.messages.clear();
        this.lastTokenUsage = null;
        this.cumulativePromptTokens = 0;
        this.cumulativeCompletionTokens = 0;
        this.lastActiveAt = System.currentTimeMillis();
    }
}
