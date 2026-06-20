package com.debugagent.engine;

import com.debugagent.llm.OpenAiClient;
import com.debugagent.llm.model.*;
import com.debugagent.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compresses conversation context by asking the LLM to summarize older history.
 * <p>
 * Strategy:
 * <ol>
 *   <li>Split history into [old rounds to summarize] + [recent rounds to keep]</li>
 *   <li>Send old rounds to the LLM with a summarization prompt</li>
 *   <li>Replace old rounds with the generated summary message</li>
 *   <li>The compressed context = summary + recent rounds</li>
 * </ol>
 * <p>
 * Token counts are based on actual {@code prompt_tokens} from the LLM API response.
 * The summarization call itself uses a non-streaming request for simplicity.
 */
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    private final OpenAiClient llmClient;
    private final String model;
    private final double temperature;
    private final int maxContextTokens;
    private final int targetTokens;
    private final int recentRoundsToKeep;

    public ContextCompressor(OpenAiClient llmClient, String model, double temperature,
                             int maxContextTokens, int recentRoundsToKeep) {
        this.llmClient = llmClient;
        this.model = model;
        this.temperature = temperature;
        this.maxContextTokens = maxContextTokens;
        this.targetTokens = (int) (maxContextTokens * 0.75);
        this.recentRoundsToKeep = recentRoundsToKeep;
    }

    /**
     * Check if compression is needed based on the current token count.
     */
    public boolean needsCompression(int currentTokens) {
        return currentTokens > maxContextTokens;
    }

    /**
     * Compress the conversation history by summarizing older rounds via LLM.
     *
     * @param session the chat session (will be modified in place)
     * @return compression result, or null if no compression was needed
     */
    public CompressionResult compress(ChatSession session) {
        int originalTokens = session.getCurrentContextTokens();
        if (!needsCompression(originalTokens)) {
            return null;
        }

        List<ChatMessage> allMessages = session.getMessages();
        List<Round> rounds = identifyRounds(allMessages);

        // Figure out how many recent rounds to keep
        // Start with configured default, but ensure we're actually removing enough
        int keepCount = Math.min(recentRoundsToKeep, rounds.size() - 1);
        if (keepCount < 1) {
            // Can't drop rounds — try compressing tool results within rounds instead
            log.info("Cannot drop rounds (only {}), trying intra-round tool result compression",
                    rounds.size());
            return compressToolResults(session, originalTokens);
        }

        int summarizeCount = rounds.size() - keepCount;
        log.info("Context compression: {} tokens, summarizing {} of {} rounds, keeping {} recent",
                originalTokens, summarizeCount, rounds.size(), keepCount);

        // Collect messages to summarize
        List<ChatMessage> toSummarize = new ArrayList<>();
        for (int i = 0; i < summarizeCount; i++) {
            toSummarize.addAll(rounds.get(i).messages);
        }

        // Collect recent messages to keep verbatim
        List<ChatMessage> toKeep = new ArrayList<>();
        for (int i = summarizeCount; i < rounds.size(); i++) {
            toKeep.addAll(rounds.get(i).messages);
        }

        // Ask LLM to summarize the old rounds
        String summary;
        try {
            summary = summarizeWithLlm(toSummarize);
            log.info("LLM summary generated: {} chars", summary.length());
        } catch (Exception e) {
            log.error("LLM summarization failed, falling back to truncation: {}", e.getMessage());
            summary = fallbackTruncate(toSummarize);
        }

        // Build compressed message list
        List<ChatMessage> compressed = new ArrayList<>();
        compressed.add(ChatMessage.system(
                "[Previous conversation summary — " + summarizeCount + " rounds compressed]\n\n" + summary));
        compressed.addAll(toKeep);

        // Estimate compressed token count
        int compressedTokens = estimateTokens(compressed);

        session.replaceMessages(compressed);

        log.info("Context compressed: {} → ~{} tokens (summary {} chars + {} recent rounds kept)",
                originalTokens, compressedTokens, summary.length(), keepCount);

        return new CompressionResult(originalTokens, compressedTokens, summarizeCount,
                "LLM summarized " + summarizeCount + " rounds");
    }

    // ==================== Intra-Round Tool Result Compression ====================

    /**
     * When we can't drop entire rounds (e.g., only 1 user message), compress by
     * summarizing entire tool-call blocks (assistant+tools) via LLM.
     * <p>
     * A "tool-call block" = [assistant message with tool_calls] + [all tool results for those calls]
     * We replace entire blocks to avoid orphaned tool_call_ids that violate the API contract.
     */
    private CompressionResult compressToolResults(ChatSession session, int originalTokens) {
        List<ChatMessage> messages = session.getMessages();

        // Identify tool-call blocks: each block starts with an assistant message that has tool_calls
        // and includes all subsequent tool messages until the next non-tool message
        List<ToolBlock> blocks = new ArrayList<>();
        ToolBlock currentBlock = null;

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                // Start a new tool-call block
                currentBlock = new ToolBlock();
                currentBlock.startIndex = i;
                currentBlock.messages.add(msg);
                blocks.add(currentBlock);
            } else if ("tool".equals(msg.getRole()) && currentBlock != null) {
                currentBlock.messages.add(msg);
                currentBlock.endIndex = i;
            } else {
                // Non-tool message breaks the block
                currentBlock = null;
            }
        }

        if (blocks.isEmpty()) {
            log.warn("No tool-call blocks found to compress");
            return null;
        }

        // Keep the last block intact, summarize the rest
        int keepRecent = Math.min(1, blocks.size() - 1);
        int summarizeCount = blocks.size() - keepRecent;

        if (summarizeCount < 1) {
            // Only 1 block — can we still compress it? 
            // Yes if it has multiple tool results — summarize all but the latest 2 results
            ToolBlock onlyBlock = blocks.get(0);
            if (onlyBlock.messages.size() <= 3) {
                log.warn("Single block too small to compress");
                return null;
            }
            // Summarize the entire block and replace with system message
            summarizeCount = 1;
            keepRecent = 0;
        }

        log.info("Tool-block compression: summarizing {} of {} tool-call blocks, keeping {}",
                summarizeCount, blocks.size(), keepRecent);

        // Collect blocks to summarize
        List<ChatMessage> toSummarize = new ArrayList<>();
        for (int i = 0; i < summarizeCount; i++) {
            toSummarize.addAll(blocks.get(i).messages);
        }

        // Ask LLM for summary
        String summary;
        try {
            summary = summarizeToolResultsWithLlm(toSummarize);
            log.info("Tool block summary: {} chars", summary.length());
        } catch (Exception e) {
            log.error("Tool block summarization failed: {}", e.getMessage());
            return null;
        }

        // Rebuild message list:
        // Replace summarized blocks with a single system summary message
        List<ChatMessage> compressed = new ArrayList<>();
        Set<Integer> skipIndices = new HashSet<>();

        // Mark indices of summarized blocks for removal
        for (int i = 0; i < summarizeCount; i++) {
            ToolBlock b = blocks.get(i);
            for (int j = b.startIndex; j <= b.endIndex; j++) {
                skipIndices.add(j);
            }
        }

        // Build compressed list, inserting summary where the first block was
        boolean summaryInserted = false;
        for (int i = 0; i < messages.size(); i++) {
            if (skipIndices.contains(i)) {
                if (!summaryInserted) {
                    compressed.add(ChatMessage.system(
                            "[Previous diagnostic results summary — "
                                    + summarizeCount + " tool-call round(s) compressed]\n\n" + summary));
                    summaryInserted = true;
                }
                continue; // skip this message
            }
            compressed.add(messages.get(i));
        }

        if (!summaryInserted) {
            compressed.add(ChatMessage.system("[Diagnostic summary]\n\n" + summary));
        }

        int compressedTokens = estimateTokens(compressed);
        session.replaceMessages(compressed);

        log.info("Tool blocks compressed: {} → ~{} tokens", originalTokens, compressedTokens);

        return new CompressionResult(originalTokens, compressedTokens, 0,
                "LLM summarized " + summarizeCount + " tool-call blocks");
    }

    /**
     * Ask LLM to summarize a set of tool results.
     */
    private String summarizeToolResultsWithLlm(List<ChatMessage> toolMessages) throws Exception {
        StringBuilder toolText = new StringBuilder();
        for (ChatMessage msg : toolMessages) {
            String content = msg.getContent();
            if (content != null && content.length() > 3000) {
                content = content.substring(0, 3000) + "...[truncated]";
            }
            toolText.append("[Tool Result] ").append(content).append("\n\n---\n\n");
        }

        String prompt = """
                You are summarizing diagnostic tool results from a Spring Boot debugging session.
                Below are tool results that need to be compressed to save context space.

                For each tool result, extract:
                - The tool name (if identifiable from the data)
                - The KEY metrics: actual numbers, statuses, error messages, configuration values
                - Any anomalies or issues detected

                Format as concise bullet points. Do NOT include full JSON — extract only meaningful values.
                Keep it under 400 words.
                """;

        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setTemperature(0.0);
        request.setMaxTokens(800);
        request.setStream(false);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(prompt));
        messages.add(ChatMessage.user("Tool results to summarize:\n\n" + toolText));
        request.setMessages(messages);

        ChatResponse response = llmClient.chatCompletion(request);

        if (response.getChoices() != null && !response.getChoices().isEmpty()) {
            String s = response.getChoices().get(0).getMessage().getContent();
            return s != null ? s : "(summary unavailable)";
        }
        return "(summary unavailable)";
    }

    private static class ToolBlock {
        int startIndex = -1;
        int endIndex = -1;
        final List<ChatMessage> messages = new ArrayList<>();
    }

    /**
     * Call the LLM to generate a summary of the given messages.
     * Uses a non-streaming request.
     */
    private String summarizeWithLlm(List<ChatMessage> oldMessages) throws Exception {
        // Serialize conversation into readable text for the summarizer
        StringBuilder conversationText = new StringBuilder();
        for (ChatMessage msg : oldMessages) {
            switch (msg.getRole()) {
                case "user" -> conversationText.append("[User] ").append(msg.getContent()).append("\n\n");
                case "assistant" -> {
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        conversationText.append("[Assistant] ").append(msg.getContent()).append("\n\n");
                    }
                    if (msg.getToolCalls() != null) {
                        for (ToolCall tc : msg.getToolCalls()) {
                            if (tc.getFunction() != null) {
                                conversationText.append("[Tool Call] ")
                                        .append(tc.getFunction().getName())
                                        .append("(").append(tc.getFunction().getArguments()).append(")\n\n");
                            }
                        }
                    }
                }
                case "tool" -> {
                    String content = msg.getContent();
                    // Cap individual tool results to avoid blowing up the summarization prompt
                    if (content != null && content.length() > 2000) {
                        content = content.substring(0, 2000) + "...[truncated]";
                    }
                    conversationText.append("[Tool Result] ").append(content).append("\n\n");
                }
                default -> {}
            }
        }

        String prompt = """
                You are a conversation summarizer for a Spring Boot debugging assistant.
                Summarize the KEY diagnostic findings from the conversation below concisely.

                Focus on preserving:
                - Problems investigated and their root causes (if found)
                - Key tool results: actual numbers, statuses, error messages, configuration values
                - Recommendations or fixes already suggested
                - Any unresolved issues or follow-up actions pending

                Rules:
                - Be concise but preserve ALL important data points (memory sizes, thread counts, error codes, etc.)
                - Use bullet points
                - Do NOT include full JSON dumps — extract only the meaningful values
                - Keep it under 600 words
                """;

        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setTemperature(0.0);
        request.setMaxTokens(1024);
        request.setStream(false);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(prompt));
        messages.add(ChatMessage.user("Conversation to summarize:\n\n" + conversationText));
        request.setMessages(messages);

        // Use non-streaming API
        ChatResponse response = llmClient.chatCompletion(request);

        if (response.getChoices() != null && !response.getChoices().isEmpty()) {
            String summary = response.getChoices().get(0).getMessage().getContent();
            return summary != null ? summary : "(summary unavailable)";
        }

        return "(summary unavailable)";
    }

    // ==================== Fallback ====================

    /**
     * If LLM summarization fails, fall back to simple truncation of tool results.
     */
    private String fallbackTruncate(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Previous conversation summary (fallback — LLM summarization failed):\n\n");

        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                String q = msg.getContent().length() > 100
                        ? msg.getContent().substring(0, 100) + "..."
                        : msg.getContent();
                sb.append("- User asked: ").append(q).append("\n");
            }
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null) {
                        sb.append("- Called tool: ").append(tc.getFunction().getName()).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    // ==================== Round Identification ====================

    /**
     * Group messages into compressible "rounds".
     * <p>
     * A round is: [optional user message] + [one assistant message] + [its tool results]
     * <p>
     * This granularity allows compression both:
     * - Between user messages (previous Q&A sessions)
     * - Within a single user message's tool-calling loop (multiple tool iterations)
     */
    private List<Round> identifyRounds(List<ChatMessage> messages) {
        List<Round> rounds = new ArrayList<>();
        Round current = new Round();

        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                // A new user message starts a new round
                if (!current.messages.isEmpty()) {
                    rounds.add(current);
                    current = new Round();
                }
                current.messages.add(msg);
            } else if ("assistant".equals(msg.getRole())) {
                // If current round already has an assistant response, start a new round
                if (current.hasAssistant) {
                    rounds.add(current);
                    current = new Round();
                }
                current.messages.add(msg);
                current.hasAssistant = true;
            } else {
                // tool/system messages go into the current round
                current.messages.add(msg);
            }
        }
        if (!current.messages.isEmpty()) {
            rounds.add(current);
        }

        return rounds;
    }

    // ==================== Token Estimation ====================

    private int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage msg : messages) {
            if (msg.getContent() != null) {
                chars += msg.getContent().length();
            }
            if (msg.getToolCalls() != null) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null) {
                        chars += (tc.getFunction().getName() != null ? tc.getFunction().getName().length() : 0);
                        chars += (tc.getFunction().getArguments() != null ? tc.getFunction().getArguments().length() : 0);
                    }
                }
            }
        }
        return chars / 4;
    }

    // ==================== Inner Types ====================

    private static class Round {
        final List<ChatMessage> messages = new ArrayList<>();
        boolean hasAssistant = false;
    }

    public static class CompressionResult {
        public final int originalTokens;
        public final int compressedTokens;
        public final int removedRounds;
        public final String strategy;

        CompressionResult(int originalTokens, int compressedTokens, int removedRounds, String strategy) {
            this.originalTokens = originalTokens;
            this.compressedTokens = compressedTokens;
            this.removedRounds = removedRounds;
            this.strategy = strategy;
        }
    }
}
