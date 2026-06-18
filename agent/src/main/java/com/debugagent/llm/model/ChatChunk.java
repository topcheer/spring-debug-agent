package com.debugagent.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single SSE chunk in a streaming chat completion response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatChunk {

    private String id;
    private String model;
    private List<ChunkChoice> choices;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<ChunkChoice> getChoices() { return choices; }
    public void setChoices(List<ChunkChoice> choices) { this.choices = choices; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChunkChoice {
        private int index;
        private Delta delta;
        @JsonProperty("finish_reason")
        private String finishReason;

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public Delta getDelta() { return delta; }
        public void setDelta(Delta delta) { this.delta = delta; }

        public String getFinishReason() { return finishReason; }
        public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Delta {
        private String role;
        private String content;
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public List<ToolCall> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    }
}
