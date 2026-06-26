package com.debugagent.javaagent;

/**
 * Configuration for the standalone javaagent.
 *
 * Resolved from multiple sources (priority order):
 * 1. Agent args: -javaagent:agent.jar=port=9900,api-key=sk-xxx
 * 2. System properties: -Ddebug-agent.llm.api-key=sk-xxx
 * 3. Environment variables: LLM_API_KEY=sk-xxx
 *
 * This class has NO Spring references — safe to load in System ClassLoader.
 */
public class AgentConfig {

    private int port = 9900;
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o";
    private double temperature = 0.3;
    private int maxTokens = 4096;
    private int maxToolRounds = 25;
    private int timeoutSeconds = 120;
    private int contextWindowTokens = 100000;

    /**
     * Parse agent args string (key=value pairs separated by commas).
     * Also falls back to -D system properties and env vars.
     */
    public static AgentConfig parse(String agentArgs) {
        AgentConfig config = new AgentConfig();

        // 1. Parse agent args: key=value,key=value
        if (agentArgs != null && !agentArgs.isBlank()) {
            for (String pair : agentArgs.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().toLowerCase();
                    String value = kv[1].trim();
                    config.applyArg(key, value);
                }
            }
        }

        // 2. System properties override / supplement (only if not already set by agent args)
        config.loadFromSystemProperties();

        // 3. Environment variables as final fallback
        config.loadFromEnv();

        return config;
    }

    private void applyArg(String key, String value) {
        switch (key) {
            case "port" -> port = parseInt(value, port);
            case "base-url", "baseurl", "base_url" -> baseUrl = value;
            case "api-key", "apikey", "api_key" -> apiKey = value;
            case "model" -> model = value;
            case "temperature" -> temperature = parseDouble(value, temperature);
            case "max-tokens", "maxtokens" -> maxTokens = parseInt(value, maxTokens);
            case "max-tool-rounds", "maxtoolrounds" -> maxToolRounds = parseInt(value, maxToolRounds);
            case "timeout-seconds", "timeout" -> timeoutSeconds = parseInt(value, timeoutSeconds);
            case "context-window-tokens", "contextwindow" -> contextWindowTokens = parseInt(value, contextWindowTokens);
        }
    }

    private void loadFromSystemProperties() {
        port = getIntProp("debug-agent.port", port);
        baseUrl = getStrProp("debug-agent.llm.base-url",
                getStrProp("debug-agent.llm.baseurl", baseUrl));
        apiKey = getStrProp("debug-agent.llm.api-key",
                getStrProp("debug-agent.llm.apikey", apiKey));
        model = getStrProp("debug-agent.llm.model", model);
        temperature = getDoubleProp("debug-agent.llm.temperature", temperature);
        maxTokens = getIntProp("debug-agent.llm.max-tokens", maxTokens);
        maxToolRounds = getIntProp("debug-agent.llm.max-tool-rounds", maxToolRounds);
        timeoutSeconds = getIntProp("debug-agent.llm.timeout-seconds", timeoutSeconds);
        contextWindowTokens = getIntProp("debug-agent.llm.context-window-tokens", contextWindowTokens);
    }

    private void loadFromEnv() {
        if (apiKey.isEmpty()) {
            apiKey = getEnv("LLM_API_KEY", getEnv("OPENAI_API_KEY", ""));
        }
        if (baseUrl.equals("https://api.openai.com/v1")) {
            String envBaseUrl = getEnv("LLM_BASE_URL", "");
            if (!envBaseUrl.isEmpty()) baseUrl = envBaseUrl;
        }
        if (model.equals("gpt-4o")) {
            String envModel = getEnv("LLM_MODEL", "");
            if (!envModel.isEmpty()) model = envModel;
        }
    }

    // ==================== Getters ====================

    public int getPort() { return port; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
    public int getMaxToolRounds() { return maxToolRounds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getContextWindowTokens() { return contextWindowTokens; }

    public boolean isLlmConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("your-api-key-here");
    }

    // ==================== Helpers ====================

    private static String getStrProp(String key, String def) {
        String val = System.getProperty(key);
        return val != null && !val.isBlank() ? val : def;
    }

    private static int getIntProp(String key, int def) {
        String val = System.getProperty(key);
        return val != null && !val.isBlank() ? parseInt(val, def) : def;
    }

    private static double getDoubleProp(String key, double def) {
        String val = System.getProperty(key);
        return val != null && !val.isBlank() ? parseDouble(val, def) : def;
    }

    private static String getEnv(String key, String def) {
        String val = System.getenv(key);
        return val != null && !val.isBlank() ? val : def;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }
}
