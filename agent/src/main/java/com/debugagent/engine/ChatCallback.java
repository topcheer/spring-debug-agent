package com.debugagent.engine;

/**
 * Callback interface for streaming agent responses to the UI.
 */
public interface ChatCallback {

    /**
     * A text chunk from the LLM's response.
     */
    void onContent(String chunk);

    /**
     * The LLM is calling a diagnostic tool.
     */
    void onToolStart(String toolName, String arguments);

    /**
     * A tool has completed with a result.
     */
    void onToolResult(String toolName, String result);

    /**
     * The entire response is complete.
     */
    void onComplete();

    /**
     * An error occurred.
     */
    void onError(String message);

    /**
     * Context was compressed to fit within the token limit.
     * Called before the next LLM round so the user is aware.
     *
     * @param originalTokens  token count before compression
     * @param compressedTokens token count after compression
     * @param removedRounds   number of conversation rounds removed
     */
    default void onContextCompressed(int originalTokens, int compressedTokens, int removedRounds) {}

    /**
     * A no-op implementation for synchronous calls.
     */
    ChatCallback NO_OP = new ChatCallback() {
        @Override public void onContent(String chunk) {}
        @Override public void onToolStart(String toolName, String args) {}
        @Override public void onToolResult(String toolName, String result) {}
        @Override public void onComplete() {}
        @Override public void onError(String message) {}
    };
}
