package com.debugagent.engine;

import com.debugagent.llm.model.ToolDefinition;
import com.debugagent.tool.ToolRegistry;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the system prompt dynamically from registered tools.
 * <p>
 * Instead of hardcoding tool descriptions, this class reads all registered tools
 * from {@link ToolRegistry} and groups them by category for the LLM.
 */
public class SystemPromptBuilder {

    private static final Pattern CATEGORY_PATTERN = Pattern.compile("^([a-z]+)_");

    private final ToolRegistry toolRegistry;

    public SystemPromptBuilder(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Build a system prompt that includes all registered tools grouped by category.
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are an expert JVM and Spring Boot debugging assistant.
                You are running INSIDE the developer's Spring Boot application and have direct access
                to its runtime state through diagnostic tools.

                ## Your Capabilities
                You can call tools to inspect the live application. Here are ALL available tools,
                grouped by category:

                """);

        // Group tools by category prefix (e.g., "mongo_" → MongoDB)
        Map<String, List<ToolEntry>> categories = categorizeTools();

        for (Map.Entry<String, List<ToolEntry>> entry : categories.entrySet()) {
            sb.append("**").append(entry.getKey()).append("**\n");
            for (ToolEntry tool : entry.getValue()) {
                sb.append("- `").append(tool.name).append("`: ")
                  .append(truncateDescription(tool.description)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("""
                ## Workflow
                1. Understand the developer's problem description
                2. Proactively call the most relevant tools to gather diagnostic data — DO NOT just ask questions
                3. Analyze the collected data to identify root causes
                4. Provide clear, actionable solutions with data evidence

                ## Guidelines
                - Be proactive: gather data with tools before answering
                - Always present data in a readable format (tables, bullet points)
                - Respond in the same language the developer uses (Chinese/English/etc.)
                - When you find a problem, explain the root cause and give concrete fix suggestions
                - You can call multiple tools in parallel if they are independent
                """);

        return sb.toString();
    }

    private Map<String, List<ToolEntry>> categorizeTools() {
        Map<String, List<ToolEntry>> categories = new LinkedHashMap<>();

        for (ToolDefinition def : toolRegistry.getAllToolDefinitions()) {
            String name = def.getFunction().getName();
            String desc = def.getFunction().getDescription();

            String category = extractCategory(name);
            categories.computeIfAbsent(category, k -> new ArrayList<>())
                    .add(new ToolEntry(name, desc));
        }

        return categories;
    }

    private String extractCategory(String toolName) {
        Matcher m = CATEGORY_PATTERN.matcher(toolName);
        if (m.find()) {
            String prefix = m.group(1);
            return switch (prefix) {
                case "jvm", "get", "trigger", "detect" -> {
                    if (toolName.contains("thread") || toolName.contains("deadlock") || toolName.contains("cpu"))
                        yield "Thread & CPU";
                    if (toolName.contains("memory") || toolName.contains("heap") || toolName.contains("gc"))
                        yield "Memory & GC";
                    if (toolName.contains("runtime") || toolName.contains("compilation") || toolName.contains("class"))
                        yield "JVM Runtime";
                    yield "JVM Diagnostics";
                }
                case "bean", "context", "property", "profile", "search", "circular", "lazy" -> "Spring Core";
                case "watch", "add", "remove", "list" -> "WatchPoint (Virtual Breakpoints)";
                case "mbean" -> "JMX MBean";
                case "http", "request", "filter", "endpoint" -> "Web & HTTP";
                case "metric", "meter", "event" -> "Metrics & Events";
                case "datasource", "data", "jpa", "sql", "transaction", "migration" -> "Database & SQL";
                case "redis" -> "Redis";
                case "kafka" -> "Kafka";
                case "mongo" -> "MongoDB";
                case "es" -> "Elasticsearch";
                case "batch" -> "Spring Batch";
                case "quartz" -> "Quartz Scheduler";
                case "graphql" -> "GraphQL";
                case "openapi" -> "OpenAPI";
                case "websocket" -> "WebSocket";
                case "threadpool", "thread_pool", "rejected" -> "Thread Pool";
                case "cache" -> "Cache";
                case "security" -> "Security";
                case "scheduled" -> "Scheduled Tasks";
                case "resilience", "circuit", "rate" -> "Resilience";
                case "log" -> "Logs";
                case "environment", "feature" -> "Environment & Config";
                case "session" -> "HTTP Session";
                case "profiling", "alloc", "cpu" -> "Profiling";
                default -> "Other Tools";
            };
        }
        return "Other Tools";
    }

    private String truncateDescription(String desc) {
        if (desc == null) return "";
        // Keep first sentence or first 120 chars
        int period = desc.indexOf('.');
        if (period > 0 && period < 150) {
            return desc.substring(0, period + 1);
        }
        return desc.length() > 120 ? desc.substring(0, 117) + "..." : desc;
    }

    private record ToolEntry(String name, String description) {}
}
