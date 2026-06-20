package com.debugagent.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A chat message in the OpenAI-compatible conversation format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage {

    private String role;
    private String content;
    private String name;           // for tool messages
    private String toolCallId;     // for tool response messages
    private List<ToolCall> toolCalls;

    public ChatMessage() {}

    public static ChatMessage system(String content) {
        ChatMessage m = new ChatMessage();
        m.role = "system";
        m.content = content;
        return m;
    }

    public static ChatMessage user(String content) {
        ChatMessage m = new ChatMessage();
        m.role = "user";
        m.content = content;
        return m;
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        ChatMessage m = new ChatMessage();
        m.role = "assistant";
        m.content = content;
        m.toolCalls = toolCalls;
        return m;
    }

    public static ChatMessage tool(String toolCallId, String content) {
        ChatMessage m = new ChatMessage();
        m.role = "tool";
        m.toolCallId = toolCallId;
        m.content = content;
        return m;
    }

    // --- getters / setters ---

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
}
