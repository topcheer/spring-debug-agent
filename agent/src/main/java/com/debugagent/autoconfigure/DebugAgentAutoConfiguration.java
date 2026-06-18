package com.debugagent.autoconfigure;

import com.debugagent.engine.DebugAgentEngine;
import com.debugagent.inspector.JvmInspector;
import com.debugagent.inspector.SpringInspector;
import com.debugagent.inspector.WatchPointManager;
import com.debugagent.llm.OpenAiClient;
import com.debugagent.tool.ToolExecutor;
import com.debugagent.tool.ToolRegistry;
import com.debugagent.web.AgentController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the Spring Debug Agent when the starter is on the classpath.
 *
 * Activation conditions:
 *   1. debug-agent.enabled is not false (default: enabled)
 *   2. Spring Web MVC is on the classpath
 *
 * The user only needs to add this dependency and configure an LLM API key.
 * Everything else is auto-wired.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "debug-agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = "org.springframework.web.servlet.mvc.method.annotation.SseEmitter")
@EnableConfigurationProperties(DebugAgentProperties.class)
public class DebugAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DebugAgentAutoConfiguration.class);

    // ==================== LLM Client ====================

    @Bean
    @ConditionalOnMissingBean
    public OpenAiClient openAiClient(DebugAgentProperties props) {
        DebugAgentProperties.Llm llm = props.getLlm();
        log.info("Spring Debug Agent starting with LLM: {} at {} (maxRetries={})",
                llm.getModel(), llm.getBaseUrl(), llm.getMaxRetries());
        return new OpenAiClient(
                llm.getBaseUrl(),
                llm.getApiKey(),
                llm.getTimeoutSeconds(),
                llm.getMaxRetries(),
                llm.getRetryBaseDelayMs(),
                llm.getRetryMaxDelayMs());
    }

    // ==================== Diagnostic Inspectors ====================

    @Bean
    @ConditionalOnMissingBean
    public JvmInspector jvmInspector() {
        return new JvmInspector();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringInspector springInspector() {
        return new SpringInspector();
    }

    @Bean
    @ConditionalOnMissingBean
    public WatchPointManager watchPointManager() {
        return new WatchPointManager();
    }

    // ==================== Tool Framework ====================

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(JvmInspector jvmInspector,
                                      SpringInspector springInspector,
                                      WatchPointManager watchPointManager) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(jvmInspector);
        registry.register(springInspector);
        registry.register(watchPointManager);
        log.info("ToolRegistry initialized with {} tools", registry.size());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutor toolExecutor(ToolRegistry registry) {
        return new ToolExecutor(registry);
    }

    // ==================== Agent Engine ====================

    @Bean
    @ConditionalOnMissingBean
    public DebugAgentEngine debugAgentEngine(OpenAiClient openAiClient,
                                              ToolRegistry toolRegistry,
                                              ToolExecutor toolExecutor,
                                              DebugAgentProperties properties) {
        return new DebugAgentEngine(openAiClient, toolRegistry, toolExecutor, properties);
    }

    // ==================== Web Controller ====================

    @Bean
    @ConditionalOnMissingBean
    public AgentController agentController() {
        return new AgentController();
    }
}
