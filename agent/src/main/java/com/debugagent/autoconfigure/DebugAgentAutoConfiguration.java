package com.debugagent.autoconfigure;

import com.debugagent.engine.DebugAgentEngine;
import com.debugagent.inspector.*;
import com.debugagent.llm.OpenAiClient;
import com.debugagent.tool.ToolExecutor;
import com.debugagent.tool.ToolRegistry;
import com.debugagent.web.AgentController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration for the Spring Debug Agent.
 *
 * All new inspectors are conditionally created based on what the host app has on its
 * classpath. If a library is missing, the inspector (and its tools) simply won't be
 * registered — the host app is never affected.
 */
@Configuration
@EnableConfigurationProperties(DebugAgentProperties.class)
@ConditionalOnProperty(prefix = "debug-agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DebugAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DebugAgentAutoConfiguration.class);

    // ================================================================
    //  Inspectors — always available (only need Spring core)
    // ================================================================

    @Bean
    public JvmInspector jvmInspector() {
        return new JvmInspector();
    }

    @Bean
    public SpringInspector springInspector() {
        return new SpringInspector();
    }

    @Bean
    public WatchPointManager watchPointManager() {
        return new WatchPointManager();
    }

    @Bean
    public LogInspector logInspector() {
        return new LogInspector();
    }

    @Bean
    public EnvironmentInspector environmentInspector() {
        return new EnvironmentInspector();
    }

    @Bean
    public ClassloadingInspector classloadingInspector() {
        return new ClassloadingInspector();
    }

    @Bean
    public SystemControlInspector systemControlInspector() {
        return new SystemControlInspector();
    }

    @Bean
    public MBeanInspector mbeanInspector() {
        return new MBeanInspector();
    }

    @Bean
    public CompilationInspector compilationInspector() {
        return new CompilationInspector();
    }

    @Bean
    public EventInspector eventInspector() {
        return new EventInspector();
    }

    @Bean
    public BeanGraphInspector beanGraphInspector() {
        return new BeanGraphInspector();
    }

    // ================================================================
    //  Inspectors — conditional on Spring Web MVC
    // ================================================================

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    public WebInspector webInspector() {
        return new WebInspector();
    }

    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    public RequestInspector requestInspector() {
        return new RequestInspector();
    }

    // ================================================================
    //  Inspectors — conditional on specific libraries
    // ================================================================

    @Bean
    @ConditionalOnClass(name = "javax.sql.DataSource")
    public DataSourceInspector dataSourceInspector() {
        return new DataSourceInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthEndpoint")
    public HealthInspector healthInspector() {
        return new HealthInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor")
    public TaskInspector taskInspector() {
        return new TaskInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.cache.CacheManager")
    public CacheInspector cacheInspector() {
        return new CacheInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.hibernate.SessionFactory")
    public JpaInspector jpaInspector() {
        return new JpaInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager")
    public HttpClientInspector httpClientInspector() {
        return new HttpClientInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport")
    public FeatureFlagInspector featureFlagInspector() {
        return new FeatureFlagInspector();
    }

    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    public MetricsInspector metricsInspector() {
        return new MetricsInspector();
    }

    // ================================================================
    //  Core engine wiring
    // ================================================================

    @Bean
    public OpenAiClient openAiClient(DebugAgentProperties props) {
        return new OpenAiClient(
                props.getLlm().getBaseUrl(),
                props.getLlm().getApiKey(),
                props.getLlm().getTimeoutSeconds());
    }

    @Bean
    public ToolRegistry toolRegistry(ApplicationContext ctx,
                                     JvmInspector jvmInspector,
                                     SpringInspector springInspector,
                                     WatchPointManager watchPointManager,
                                     LogInspector logInspector,
                                     EnvironmentInspector environmentInspector,
                                     ClassloadingInspector classloadingInspector,
                                     SystemControlInspector systemControlInspector,
                                     MBeanInspector mbeanInspector,
                                     CompilationInspector compilationInspector,
                                     EventInspector eventInspector,
                                     BeanGraphInspector beanGraphInspector) {

        List<Object> inspectors = new ArrayList<>();
        inspectors.add(jvmInspector);
        inspectors.add(springInspector);
        inspectors.add(watchPointManager);
        inspectors.add(logInspector);
        inspectors.add(environmentInspector);
        inspectors.add(classloadingInspector);
        inspectors.add(systemControlInspector);
        inspectors.add(mbeanInspector);
        inspectors.add(compilationInspector);
        inspectors.add(eventInspector);
        inspectors.add(beanGraphInspector);

        // Conditionally add optional inspectors (only if beans exist)
        addIfExists(ctx, DataSourceInspector.class, inspectors);
        addIfExists(ctx, WebInspector.class, inspectors);
        addIfExists(ctx, RequestInspector.class, inspectors);
        addIfExists(ctx, HealthInspector.class, inspectors);
        addIfExists(ctx, TaskInspector.class, inspectors);
        addIfExists(ctx, CacheInspector.class, inspectors);
        addIfExists(ctx, JpaInspector.class, inspectors);
        addIfExists(ctx, HttpClientInspector.class, inspectors);
        addIfExists(ctx, FeatureFlagInspector.class, inspectors);
        addIfExists(ctx, MetricsInspector.class, inspectors);

        ToolRegistry registry = new ToolRegistry();
        for (Object inspector : inspectors) {
            try {
                registry.register(inspector);
                log.debug("Registered inspector: {}", inspector.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("Failed to register inspector {}: {}", inspector.getClass().getSimpleName(), e.getMessage());
            }
        }

        log.info("Spring Debug Agent: {} inspectors registered, {} tools available",
                inspectors.size(), registry.toolCount());

        return registry;
    }

    private void addIfExists(ApplicationContext ctx, Class<?> inspectorClass, List<Object> inspectors) {
        try {
            Object bean = ctx.getBean(inspectorClass);
            inspectors.add(bean);
        } catch (Exception e) {
            // Bean not present — skip silently
        }
    }

    @Bean
    public ToolExecutor toolExecutor(ToolRegistry registry) {
        return new ToolExecutor(registry);
    }

    @Bean
    public DebugAgentEngine debugAgentEngine(OpenAiClient client,
                                              ToolRegistry registry,
                                              ToolExecutor executor,
                                              DebugAgentProperties props) {
        return new DebugAgentEngine(client, registry, executor, props);
    }

    @Bean
    public AgentController agentController() {
        return new AgentController();
    }
}
