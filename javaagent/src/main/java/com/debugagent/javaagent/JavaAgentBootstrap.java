package com.debugagent.javaagent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import net.bytebuddy.asm.Advice;

/**
 * The javaagent entry point — called by the JVM when the agent is loaded via
 * {@code -javaagent:agent.jar} (premain) or via dynamic attach (agentmain).
 *
 * <p><b>CRITICAL: This class runs in the System ClassLoader and must NEVER
 * reference Spring types.</b> All Spring interaction is deferred to
 * {@link AgentLauncher} which is loaded in an isolated child ClassLoader.
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>{@code premain()} or {@code agentmain()} is called</li>
 *   <li>Saves {@link Instrumentation} and {@link AgentConfig}</li>
 *   <li>Installs ByteBuddy {@link SpringRunAdvice} on {@code SpringApplication.run()}</li>
 *   <li>Starts a daemon watcher thread</li>
 * </ol>
 *
 * <p>When Spring's {@code run()} returns, the advice stores the context in
 * {@link #capturedContext}. The watcher thread detects this, creates an
 * {@link AgentClassLoader}, loads {@link AgentLauncher}, and delegates
 * initialization.
 *
 * <h3>Safety:</h3>
 * Everything is wrapped in try-catch. Any failure is logged to stderr and
 * swallowed. The host application is NEVER affected.
 */
public class JavaAgentBootstrap {

    /** Captured Spring ApplicationContext (stored as Object — no Spring type reference). */
    static volatile Object capturedContext;

    /** The ClassLoader that loaded the Spring app (used as parent for AgentClassLoader). */
    static volatile ClassLoader appClassLoader;

    /** Instrumentation from the JVM (premain or agentmain). */
    private static volatile Instrumentation instrumentation;

    /** Parsed agent configuration. */
    private static volatile AgentConfig config;

    /** The agent JAR URL (for creating AgentClassLoader). */
    private static volatile URL agentJarUrl;

    // ==================== JVM Entry Points ====================

    /**
     * Called by JVM when agent is loaded via -javaagent before main().
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        bootstrap(agentArgs, inst);
    }

    /**
     * Called by JVM when agent is attached dynamically at runtime.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        bootstrap(agentArgs, inst);
    }

    // ==================== Bootstrap Logic ====================

    private static void bootstrap(String agentArgs, Instrumentation inst) {
        // Everything in try-catch — NEVER affect the host app
        try {
            System.err.println("[spring-debug-agent] Javaagent starting...");

            instrumentation = inst;
            config = AgentConfig.parse(agentArgs);
            agentJarUrl = JavaAgentBootstrap.class.getProtectionDomain()
                    .getCodeSource().getLocation();

            // Install ByteBuddy transformer to capture Spring context
            installSpringContextInterceptor();

            // Start watcher thread (daemon, won't block JVM shutdown)
            startWatcherThread();

            System.err.println("[spring-debug-agent] Javaagent loaded. "
                    + "Port: " + config.getPort()
                    + ", LLM configured: " + config.isLlmConfigured()
                    + ", Waiting for Spring context...");

        } catch (Throwable t) {
            // SWALLOW — must never affect the host application
            System.err.println("[spring-debug-agent] FATAL: Bootstrap failed (suppressed): " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    /**
     * Install ByteBuddy AgentBuilder to intercept SpringApplication.run().
     */
    private static void installSpringContextInterceptor() {
        new net.bytebuddy.agent.builder.AgentBuilder.Default()
                .with(net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy.Default.REDEFINE)
                // Match SpringApplication class
                .type(net.bytebuddy.matcher.ElementMatchers.named(
                        "org.springframework.boot.SpringApplication"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(SpringRunAdvice.class)
                                .on(net.bytebuddy.matcher.ElementMatchers.named("run")
                                        .and(net.bytebuddy.matcher.ElementMatchers.returns(
                                                net.bytebuddy.matcher.ElementMatchers.named(
                                                        "org.springframework.context.ConfigurableApplicationContext")))))
                )
                .installOn(instrumentation);
    }

    /**
     * Watcher thread: waits for Spring context, then initializes the agent.
     */
    private static void startWatcherThread() {
        Thread watcher = new Thread(() -> {
            try {
                // Wait up to 120s for Spring context
                // Even if Spring never starts, we start JVM-only tools
                boolean contextFound = false;
                long deadline = System.currentTimeMillis() + 120_000;

                while (System.currentTimeMillis() < deadline) {
                    if (capturedContext != null) {
                        contextFound = true;
                        break;
                    }
                    TimeUnit.MILLISECONDS.sleep(500);
                }

                if (contextFound) {
                    System.err.println("[spring-debug-agent] Spring context captured, initializing agent...");
                    initializeAgentWithSpring();
                } else {
                    System.err.println("[spring-debug-agent] No Spring context detected within 120s. "
                            + "Starting in JVM-only mode.");
                    initializeAgentJvmOnly();
                }

            } catch (Throwable t) {
                System.err.println("[spring-debug-agent] Watcher thread failed (suppressed): " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }, "spring-debug-agent-watcher");

        watcher.setDaemon(true);
        watcher.setContextClassLoader(ClassLoader.getSystemClassLoader());
        watcher.start();
    }

    // ==================== Agent Initialization ====================

    /**
     * Initialize agent with Spring context available.
     * Creates child ClassLoader, loads AgentLauncher, delegates.
     */
    private static void initializeAgentWithSpring() throws Exception {
        // Determine the app's ClassLoader
        ClassLoader appCL = appClassLoader;
        if (appCL == null) {
            appCL = Thread.currentThread().getContextClassLoader();
        }
        if (appCL == null) {
            appCL = ClassLoader.getSystemClassLoader();
        }

        // Create child-first AgentClassLoader (parent = app CL so Spring resolves)
        URL[] urls = {agentJarUrl};
        AgentClassLoader agentCL = new AgentClassLoader(urls, appCL);

        // Load AgentLauncher via child CL and invoke initialize()
        Class<?> launcherClass = Class.forName(
                "com.debugagent.javaagent.AgentLauncher", true, agentCL);

        Method initMethod = launcherClass.getMethod("initialize",
                Object.class,           // captured Spring context
                AgentConfig.class,      // config
                Instrumentation.class); // instrumentation

        // Use the child CL as TCCL during initialization
        ClassLoader savedTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(agentCL);
        try {
            initMethod.invoke(null, capturedContext, config, instrumentation);
        } finally {
            Thread.currentThread().setContextClassLoader(savedTCCL);
        }
    }

    /**
     * Initialize agent in JVM-only mode (no Spring context).
     * Same delegation to AgentLauncher, but with null context.
     */
    private static void initializeAgentJvmOnly() throws Exception {
        ClassLoader sysCL = ClassLoader.getSystemClassLoader();
        URL[] urls = {agentJarUrl};
        AgentClassLoader agentCL = new AgentClassLoader(urls, sysCL);

        Class<?> launcherClass = Class.forName(
                "com.debugagent.javaagent.AgentLauncher", true, agentCL);

        Method initMethod = launcherClass.getMethod("initialize",
                Object.class,
                AgentConfig.class,
                Instrumentation.class);

        ClassLoader savedTCCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(agentCL);
        try {
            initMethod.invoke(null, null, config, instrumentation);
        } finally {
            Thread.currentThread().setContextClassLoader(savedTCCL);
        }
    }
}
