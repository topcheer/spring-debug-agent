package com.debugagent.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Debug Agent configuration properties.
 *
 * Example application.yml:
 *   debug-agent:
 *     enabled: true
 *     llm:
 *       base-url: https://api.openai.com/v1
 *       api-key: sk-xxx
 *       model: gpt-4o
 *       temperature: 0.3
 */
@ConfigurationProperties(prefix = "debug-agent")
public class DebugAgentProperties {

    private boolean enabled = true;

    /**
     * Path for the embedded chat UI.
     */
    private String basePath = "/agent";

    private Llm llm = new Llm();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public static class Llm {

        /**
         * Any OpenAI-compatible base URL.
         * Examples:
         *   https://api.openai.com/v1
         *   http://localhost:11434/v1   (Ollama)
         *   http://localhost:8000/v1     (vLLM)
         */
        private String baseUrl = "https://api.openai.com/v1";

        private String apiKey = "";

        private String model = "gpt-4o";

        private double temperature = 0.3;

        /**
         * Max tokens for the final response.
         */
        private int maxTokens = 4096;

        /**
         * Maximum number of tool-calling rounds before giving up.
         */
        private int maxToolRounds = 25;

        /**
         * Request timeout in seconds.
         */
        private int timeoutSeconds = 120;

        /**
         * Maximum retry attempts for transient errors (429/5xx/network). Default 3.
         */
        private int maxRetries = 3;

        /**
         * Base delay in ms for exponential backoff. Default 1000ms.
         */
        private long retryBaseDelayMs = 1000;

        /**
         * Maximum delay cap in ms for retries. Default 30000ms.
         */
        private long retryMaxDelayMs = 30000;

        /**
         * Context window token limit for auto-compression.
         * When the conversation exceeds this many tokens, older messages
         * are automatically summarized/truncated. Default 100000 (100K).
         * Set to 0 to disable auto-compression.
         */
        private int contextWindowTokens = 100000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public int getMaxToolRounds() { return maxToolRounds; }
        public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
        public void setRetryBaseDelayMs(long retryBaseDelayMs) { this.retryBaseDelayMs = retryBaseDelayMs; }

        public long getRetryMaxDelayMs() { return retryMaxDelayMs; }
        public void setRetryMaxDelayMs(long retryMaxDelayMs) { this.retryMaxDelayMs = retryMaxDelayMs; }

        public int getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(int contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }
    }
}
