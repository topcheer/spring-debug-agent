package com.debugagent.javaagent;

import com.debugagent.autoconfigure.DebugAgentProperties;
import com.debugagent.engine.DebugAgentEngine;
import com.debugagent.inspector.*;
import com.debugagent.llm.OpenAiClient;
import com.debugagent.tool.ToolExecutor;
import com.debugagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates all diagnostic inspectors, the tool registry, and the debug agent engine
 * WITHOUT Spring dependency injection.
 *
 * <p>Mirrors the logic of {@code DebugAgentAutoConfiguration} but instantiates
 * everything manually. This class IS loaded by the {@link AgentClassLoader}
 * (child of the app's ClassLoader), so it CAN reference Spring types.
 */
public class StandaloneInspectorFactory {

    private static final Logger log = LoggerFactory.getLogger(StandaloneInspectorFactory.class);

    private final ApplicationContext springContext;
    private final Instrumentation instrumentation;

    public StandaloneInspectorFactory(ApplicationContext springContext, Instrumentation instrumentation) {
        this.springContext = springContext;
        this.instrumentation = instrumentation;
    }

    /**
     * Build the complete agent engine with all available inspectors.
     *
     * @return array of [DebugAgentEngine, Integer toolCount]
     */
    public Object[] buildEngineWithToolCount(AgentConfig config) {
        DebugAgentEngine engine = buildEngine(config);
        // Get tool count from the registry we created
        // We need to return the count since DebugAgentEngine doesn't expose it
        int count = lastToolCount;
        return new Object[]{engine, count};
    }

    private int lastToolCount = 0;

    /**
     * Build the complete agent engine with all available inspectors.
     */
    public DebugAgentEngine buildEngine(AgentConfig config) {
        // 1. Create properties from AgentConfig
        DebugAgentProperties props = new DebugAgentProperties();
        props.setEnabled(true);
        props.getLlm().setBaseUrl(config.getBaseUrl());
        props.getLlm().setApiKey(config.getApiKey());
        props.getLlm().setModel(config.getModel());
        props.getLlm().setTemperature(config.getTemperature());
        props.getLlm().setMaxTokens(config.getMaxTokens());
        props.getLlm().setMaxToolRounds(config.getMaxToolRounds());
        props.getLlm().setTimeoutSeconds(config.getTimeoutSeconds());
        props.getLlm().setContextWindowTokens(config.getContextWindowTokens());

        // 2. Create LLM client
        OpenAiClient llmClient = new OpenAiClient(
                config.getBaseUrl(),
                config.getApiKey(),
                config.getTimeoutSeconds(),
                3, 1000, 30000);

        // 3. Create all inspectors
        List<Object> inspectors = createAllInspectors();

        // 4. Register in ToolRegistry
        ToolRegistry registry = new ToolRegistry();
        for (Object inspector : inspectors) {
            try {
                registry.register(inspector);
                log.debug("Registered inspector: {}", inspector.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("Failed to register inspector {}: {}",
                        inspector.getClass().getSimpleName(), e.getMessage());
            }
        }

        // 5. Create executor
        ToolExecutor executor = new ToolExecutor(registry);

        // 6. Create engine
        DebugAgentEngine engine = new DebugAgentEngine(llmClient, registry, executor, props);

        log.info("Standalone agent initialized: {} inspectors, {} tools",
                inspectors.size(), registry.toolCount());
        lastToolCount = registry.toolCount();

        return engine;
    }

    /**
     * Create all inspectors, injecting Spring context and checking classpath conditions.
     */
    private List<Object> createAllInspectors() {
        List<Object> inspectors = new ArrayList<>();

        // === Always-available inspectors (no classpath requirements) ===
        addInspector(inspectors, new JvmInspector());
        addInspector(inspectors, new CompilationInspector());
        addInspector(inspectors, new ClassloadingInspector());
        addInspector(inspectors, new SystemControlInspector());
        addInspector(inspectors, new MBeanInspector());
        addInspector(inspectors, new EventInspector());
        addInspector(inspectors, new BeanGraphInspector());
        addInspector(inspectors, new ProfilingInspector());
        addInspector(inspectors, new ThreadPoolInspector());
        addInspector(inspectors, new TlsInspector());
        addInspector(inspectors, new OpenApiInspector());

        // WatchPointManager — inject pre-existing Instrumentation
        WatchPointManager wpm = new WatchPointManager();
        injectInstrumentation(wpm);
        addInspector(inspectors, wpm);

        // === Spring-context-dependent inspectors ===
        if (springContext != null) {
            // These need ApplicationContext to be useful
            addInspector(inspectors, createContextAware(new SpringInspector()));
            addInspector(inspectors, createContextAware(new EnvironmentInspector()));
            addInspector(inspectors, createContextAware(new LogInspector()));

            // === Conditional inspectors (check classpath) ===
            addIfClassPresent(inspectors, "javax.sql.DataSource", () -> {
                DataSourceInspector dsi = createContextAware(new DataSourceInspector());
                return dsi;
            });
            addIfClassPresent(inspectors, "org.springframework.web.servlet.DispatcherServlet",
                    () -> createContextAware(new WebInspector()));
            addIfClassPresent(inspectors, "jakarta.servlet.http.HttpServletRequest",
                    () -> createContextAware(new RequestInspector()));
            addIfClassPresent(inspectors, "org.springframework.boot.actuate.health.HealthEndpoint",
                    () -> createContextAware(new HealthInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor",
                    () -> createContextAware(new TaskInspector()));
            addIfClassPresent(inspectors, "org.springframework.cache.CacheManager",
                    () -> createContextAware(new CacheInspector()));
            addIfClassPresent(inspectors, "org.hibernate.SessionFactory",
                    () -> createContextAware(new JpaInspector()));
            addIfClassPresent(inspectors,
                    "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager",
                    () -> createContextAware(new HttpClientInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport",
                    () -> createContextAware(new FeatureFlagInspector()));
            addIfClassPresent(inspectors, "io.micrometer.core.instrument.MeterRegistry",
                    () -> createContextAware(new MetricsInspector()));
            addIfClassPresent(inspectors, "org.springframework.transaction.PlatformTransactionManager",
                    () -> createContextAware(new TransactionInspector()));
            addIfClassPresent(inspectors, "org.springframework.web.client.RestTemplate",
                    () -> createContextAware(new RestClientInspector()));
            addIfClassPresent(inspectors, "org.springframework.web.servlet.DispatcherServlet",
                    () -> createContextAware(new EndpointTestInspector()));

            // v0.5.0 inspectors
            addIfClassPresent(inspectors,
                    "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",
                    () -> createContextAware(new SecurityInspector()));
            addIfClassPresent(inspectors, "javax.sql.DataSource",
                    () -> createContextAware(new SqlInspector()));
            addIfClassPresent(inspectors, "jakarta.servlet.http.HttpSession",
                    () -> createContextAware(new HttpSessionInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.data.redis.connection.RedisConnectionFactory",
                    () -> createContextAware(new RedisInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.kafka.config.KafkaListenerEndpointRegistry",
                    () -> createContextAware(new MessagingInspector()));
            addIfClassPresent(inspectors, "io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry",
                    () -> createContextAware(new ResilienceInspector()));
            addIfClassPresent(inspectors, "reactor.core.publisher.Flux",
                    () -> createContextAware(new ReactiveInspector()));
            addIfClassPresent(inspectors, "org.flywaydb.core.Flyway",
                    () -> createContextAware(new MigrationInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.cloud.client.discovery.DiscoveryClient",
                    () -> createContextAware(new CloudInspector()));

            // v0.6.0 Tier S
            addIfClassPresent(inspectors, "io.micrometer.tracing.Tracer",
                    () -> createContextAware(new TracingInspector()));
            addIfClassPresent(inspectors, "org.springframework.data.mongodb.core.MongoTemplate",
                    () -> createContextAware(new MongoDbInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.data.elasticsearch.core.ElasticsearchOperations",
                    () -> createContextAware(new ElasticsearchInspector()));
            addIfClassPresent(inspectors, "io.grpc.ManagedChannel",
                    () -> createContextAware(new GrpcInspector()));
            addIfClassPresent(inspectors, "org.springframework.web.socket.WebSocketHandler",
                    () -> createContextAware(new WebSocketInspector()));

            // v0.6.0 Tier A
            addIfClassPresent(inspectors, "org.springframework.batch.core.repository.JobRepository",
                    () -> createContextAware(new BatchInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings",
                    () -> createContextAware(new OAuth2Inspector()));
            addIfClassPresent(inspectors, "org.springframework.modulith.core.ApplicationModules",
                    () -> createContextAware(new ModulithInspector()));
            addIfClassPresent(inspectors, "org.quartz.Scheduler",
                    () -> createContextAware(new QuartzInspector()));

            // v0.6.0 Tier B
            addIfClassPresent(inspectors, "io.minio.MinioClient",
                    () -> createContextAware(new ObjectStorageInspector()));
            addIfClassPresent(inspectors, "org.springframework.vault.core.VaultTemplate",
                    () -> createContextAware(new VaultInspector()));
            addIfClassPresent(inspectors, "com.hazelcast.core.HazelcastInstance",
                    () -> createContextAware(new DistributedCacheInspector()));
            addIfClassPresent(inspectors, "org.springframework.statemachine.StateMachine",
                    () -> createContextAware(new StateMachineInspector()));
            addIfClassPresent(inspectors, "org.springframework.graphql.execution.GraphQlSource",
                    () -> createContextAware(new GraphQLInspector()));

            // v0.6.2 Enterprise Security
            addIfClassPresent(inspectors, "org.springframework.ldap.core.ContextSource",
                    () -> createContextAware(new LdapInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.security.kerberos.authentication.KerberosServiceAuthenticationProvider",
                    () -> createContextAware(new KerberosInspector()));

            // v0.8.0 Enterprise Integration
            addIfClassPresent(inspectors, "org.mybatis.spring.SqlSessionFactoryBean",
                    () -> createContextAware(new MyBatisInspector()));
            addIfClassPresent(inspectors, "org.apache.camel.CamelContext",
                    () -> createContextAware(new CamelInspector()));
            addIfClassPresent(inspectors, "org.springframework.amqp.rabbit.core.RabbitAdmin",
                    () -> createContextAware(new AmqpInspector()));
            addIfClassPresent(inspectors, "org.apache.dubbo.config.spring.ServiceBean",
                    () -> createContextAware(new DubboInspector()));
            addIfClassPresent(inspectors, "org.apache.rocketmq.spring.core.RocketMQTemplate",
                    () -> createContextAware(new RocketMqInspector()));
            addIfClassPresent(inspectors, "com.alibaba.nacos.api.naming.NamingService",
                    () -> createContextAware(new NacosInspector()));
            addIfClassPresent(inspectors, "com.alibaba.csp.sentinel.Sph",
                    () -> createContextAware(new SentinelInspector()));
            addIfClassPresent(inspectors, "io.seata.spring.annotation.GlobalTransactionScanner",
                    () -> createContextAware(new SeataInspector()));
            addIfClassPresent(inspectors, "org.flowable.engine.ProcessEngine",
                    () -> createContextAware(new FlowableInspector()));
            addIfClassPresent(inspectors, "com.datastax.oss.driver.api.core.CqlSession",
                    () -> createContextAware(new CassandraInspector()));
            addIfClassPresent(inspectors, "org.apache.curator.framework.CuratorFramework",
                    () -> createContextAware(new ZookeeperInspector()));
            addIfClassPresent(inspectors,
                    "org.springframework.cloud.gateway.route.RouteDefinitionLocator",
                    () -> createContextAware(new GatewayInspector()));

            // v0.8.1
            addIfClassPresent(inspectors, "org.aspectj.lang.annotation.Aspect",
                    () -> createContextAware(new AopInspector()));
            addIfClassPresent(inspectors, "org.springframework.cloud.openfeign.FeignClient",
                    () -> createContextAware(new OpenFeignInspector()));
        }

        return inspectors;
    }

    // ==================== Helpers ====================

    @FunctionalInterface
    private interface InspectorSupplier {
        Object create() throws Exception;
    }

    private void addInspector(List<Object> inspectors, Object inspector) {
        if (inspector != null) {
            inspectors.add(inspector);
        }
    }

    /**
     * Create an inspector only if the given class is on the classpath.
     */
    private void addIfClassPresent(List<Object> inspectors, String className, InspectorSupplier supplier) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            Object inspector = supplier.create();
            if (inspector != null) {
                inspectors.add(inspector);
                log.debug("Conditional inspector added: {} (class {} present)",
                        inspector.getClass().getSimpleName(), className);
            }
        } catch (ClassNotFoundException e) {
            // Class not on classpath — skip silently
        } catch (Throwable e) {
            log.warn("Failed to create inspector for {}: {}", className, e.getMessage());
        }
    }

    /**
     * Inject Spring ApplicationContext into an ApplicationContextAware inspector.
     */
    @SuppressWarnings("unchecked")
    private <T> T createContextAware(T inspector) {
        if (springContext != null && inspector instanceof ApplicationContextAware aware) {
            try {
                aware.setApplicationContext(springContext);
            } catch (Exception e) {
                log.warn("Failed to inject context into {}: {}",
                        inspector.getClass().getSimpleName(), e.getMessage());
            }
        }
        return inspector;
    }

    /**
     * Inject pre-existing Instrumentation into WatchPointManager via reflection.
     * This avoids ByteBuddyAgent.install() self-attach since we already have it from premain.
     */
    private void injectInstrumentation(WatchPointManager wpm) {
        if (instrumentation != null) {
            try {
                Field field = WatchPointManager.class.getDeclaredField("instrumentation");
                field.setAccessible(true);
                field.set(wpm, instrumentation);
            } catch (Exception e) {
                // If injection fails, WatchPointManager will self-attach via ByteBuddyAgent.install()
                log.debug("Could not pre-inject Instrumentation into WatchPointManager: {}", e.getMessage());
            }
        }
    }
}
