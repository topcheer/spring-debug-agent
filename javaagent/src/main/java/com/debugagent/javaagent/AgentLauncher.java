package com.debugagent.javaagent;

import com.debugagent.engine.DebugAgentEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for agent initialization, loaded by the {@link AgentClassLoader}
 * (child of the app's ClassLoader). This class CAN reference Spring types
 * because they resolve through the parent ClassLoader chain.
 *
 * <p>Called by {@link JavaAgentBootstrap} via reflection after the Spring
 * ApplicationContext has been captured (or after timeout for JVM-only mode).
 *
 * <h3>Flow:</h3>
 * <ol>
 *   <li>Cast captured context to {@link ApplicationContext}</li>
 *   <li>Create {@link StandaloneInspectorFactory} and build engine</li>
 *   <li>Start {@link AgentHttpServer} on the configured port</li>
 * </ol>
 */
public class AgentLauncher {

    private static final Logger log = LoggerFactory.getLogger(AgentLauncher.class);

    /**
     * Initialize the standalone agent.
     *
     * @param context         The captured Spring ApplicationContext (may be null for JVM-only mode)
     * @param config          The agent configuration
     * @param instrumentation JVM Instrumentation (from premain)
     */
    @SuppressWarnings("unused") // called via reflection from JavaAgentBootstrap
    public static void initialize(Object context, AgentConfig config, Instrumentation instrumentation) {
        try {
            // Cast context to ApplicationContext (safe — we verified in SpringRunAdvice)
            ApplicationContext springContext = null;
            if (context instanceof ApplicationContext ac) {
                springContext = ac;
            } else if (context != null) {
                // Try to cast via reflection (handles classloader differences)
                try {
                    springContext = (ApplicationContext) context;
                } catch (ClassCastException e) {
                    log.warn("Captured context is not an ApplicationContext: {}", context.getClass());
                }
            }

            log.info("AgentLauncher initializing. Spring context: {}, LLM configured: {}",
                    springContext != null ? "available" : "null",
                    config.isLlmConfigured());

            // Create inspector factory and build engine
            StandaloneInspectorFactory factory =
                    new StandaloneInspectorFactory(springContext, instrumentation);
            Object[] result = factory.buildEngineWithToolCount(config);
            DebugAgentEngine engine = (DebugAgentEngine) result[0];
            int toolCount = (Integer) result[1];

            // Start HTTP server
            AgentHttpServer server = new AgentHttpServer(engine, config, toolCount);
            server.start();

            log.info("Spring Debug Agent javaagent ready on port {}", config.getPort());
            System.err.println("[spring-debug-agent] Ready! Chat UI: http://localhost:" + config.getPort());
            System.err.println("[spring-debug-agent] Health: http://localhost:" + config.getPort() + "/api/health");

        } catch (Throwable t) {
            // SWALLOW — must never affect the host application
            log.error("Agent initialization failed (suppressed)", t);
            System.err.println("[spring-debug-agent] Initialization failed (suppressed): " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }
}
