package com.debugagent.engine;

/**
 * Manages conversation sessions in memory.
 */
public class ChatSession {

    private final String sessionId;
    private final long createdAt;
    private volatile long lastActiveAt;
    private volatile java.util.List<com.debugagent.llm.model.ChatMessage> messages;

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
        this.messages = new java.util.ArrayList<>();
    }

    public String getSessionId() { return sessionId; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }

    public synchronized java.util.List<com.debugagent.llm.model.ChatMessage> getMessages() {
        return new java.util.ArrayList<>(messages);
    }

    public synchronized void setMessages(java.util.List<com.debugagent.llm.model.ChatMessage> msgs) {
        this.messages = new java.util.ArrayList<>(msgs);
        this.lastActiveAt = System.currentTimeMillis();
    }

    public synchronized void addMessage(com.debugagent.llm.model.ChatMessage msg) {
        this.messages.add(msg);
        this.lastActiveAt = System.currentTimeMillis();
    }

    public synchronized void clear() {
        this.messages.clear();
        this.lastActiveAt = System.currentTimeMillis();
    }
}
