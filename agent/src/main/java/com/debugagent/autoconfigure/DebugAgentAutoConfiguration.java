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
    //  Inspectors — new in v0.4.0
    // ================================================================

    @Bean
    @ConditionalOnClass(name = "org.springframework.transaction.PlatformTransactionManager")
    public TransactionInspector transactionInspector() {
        return new TransactionInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    public RestClientInspector restClientInspector() {
        return new RestClientInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    public EndpointTestInspector endpointTestInspector() {
        return new EndpointTestInspector();
    }

    // ================================================================
    //  Inspectors — new in v0.5.0
    // ================================================================

    @Bean
    @ConditionalOnClass(name = "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity")
    public SecurityInspector securityInspector() {
        return new SecurityInspector();
    }

    @Bean
    @ConditionalOnClass(name = "javax.sql.DataSource")
    public SqlInspector sqlInspector() {
        return new SqlInspector();
    }

    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpSession")
    public HttpSessionInspector httpSessionInspector() {
        return new HttpSessionInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
    public RedisInspector redisInspector() {
        return new RedisInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.kafka.config.KafkaListenerEndpointRegistry")
    public MessagingInspector messagingInspector() {
        return new MessagingInspector();
    }

    @Bean
    @ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry")
    public ResilienceInspector resilienceInspector() {
        return new ResilienceInspector();
    }

    @Bean
    public ProfilingInspector profilingInspector() {
        return new ProfilingInspector();
    }

    @Bean
    @ConditionalOnClass(name = "reactor.core.publisher.Flux")
    public ReactiveInspector reactiveInspector() {
        return new ReactiveInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.flywaydb.core.Flyway")
    public MigrationInspector migrationInspectorFlyway() {
        return new MigrationInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.cloud.client.discovery.DiscoveryClient")
    public CloudInspector cloudInspector() {
        return new CloudInspector();
    }

    // ================================================================
    //  Inspectors — new in v0.6.0 (Tier S)
    // ================================================================

    @Bean
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    public TracingInspector tracingInspector() {
        return new TracingInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.data.mongodb.core.MongoTemplate")
    public MongoDbInspector mongoDbInspector() {
        return new MongoDbInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.data.elasticsearch.core.ElasticsearchOperations")
    public ElasticsearchInspector elasticsearchInspector() {
        return new ElasticsearchInspector();
    }

    @Bean
    @ConditionalOnClass(name = "io.grpc.ManagedChannel")
    public GrpcInspector grpcInspector() {
        return new GrpcInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.socket.WebSocketHandler")
    public WebSocketInspector webSocketInspector() {
        return new WebSocketInspector();
    }

    // ================================================================
    //  Inspectors — new in v0.6.0 (Tier A)
    // ================================================================

    @Bean
    @ConditionalOnClass(name = "org.springframework.batch.core.repository.JobRepository")
    public BatchInspector batchInspector() {
        return new BatchInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings")
    public OAuth2Inspector oAuth2Inspector() {
        return new OAuth2Inspector();
    }

    @Bean
    public ThreadPoolInspector threadPoolInspector() {
        return new ThreadPoolInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.modulith.core.ApplicationModules")
    public ModulithInspector modulithInspector() {
        return new ModulithInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.quartz.Scheduler")
    public QuartzInspector quartzInspector() {
        return new QuartzInspector();
    }

    // ================================================================
    //  Inspectors — new in v0.6.0 (Tier B)
    // ================================================================

    @Bean
    @ConditionalOnClass(name = "io.minio.MinioClient")
    public ObjectStorageInspector objectStorageInspector() {
        return new ObjectStorageInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.vault.core.VaultTemplate")
    public VaultInspector vaultInspector() {
        return new VaultInspector();
    }

    @Bean
    @ConditionalOnClass(name = "com.hazelcast.core.HazelcastInstance")
    public DistributedCacheInspector distributedCacheInspector() {
        return new DistributedCacheInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.statemachine.StateMachine")
    public StateMachineInspector stateMachineInspector() {
        return new StateMachineInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.graphql.execution.GraphQlSource")
    public GraphQLInspector graphQLInspector() {
        return new GraphQLInspector();
    }

    // ================================================================
    //  Enterprise Security inspectors (v0.6.2)
    // ================================================================

    @Bean
    public TlsInspector tlsInspector() {
        return new TlsInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.ldap.core.ContextSource")
    public LdapInspector ldapInspector() {
        return new LdapInspector();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.security.kerberos.authentication.KerberosServiceAuthenticationProvider")
    public KerberosInspector kerberosInspector() {
        return new KerberosInspector();
    }

    @Bean
    public OpenApiInspector openApiInspector() {
        return new OpenApiInspector();
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
        addIfExists(ctx, TransactionInspector.class, inspectors);
        addIfExists(ctx, RestClientInspector.class, inspectors);
        addIfExists(ctx, EndpointTestInspector.class, inspectors);
        // v0.5.0 inspectors
        addIfExists(ctx, SecurityInspector.class, inspectors);
        addIfExists(ctx, SqlInspector.class, inspectors);
        addIfExists(ctx, HttpSessionInspector.class, inspectors);
        addIfExists(ctx, RedisInspector.class, inspectors);
        addIfExists(ctx, MessagingInspector.class, inspectors);
        addIfExists(ctx, ResilienceInspector.class, inspectors);
        addIfExists(ctx, ProfilingInspector.class, inspectors);
        addIfExists(ctx, ReactiveInspector.class, inspectors);
        addIfExists(ctx, MigrationInspector.class, inspectors);
        addIfExists(ctx, CloudInspector.class, inspectors);
        // v0.6.0 Tier S
        addIfExists(ctx, TracingInspector.class, inspectors);
        addIfExists(ctx, MongoDbInspector.class, inspectors);
        addIfExists(ctx, ElasticsearchInspector.class, inspectors);
        addIfExists(ctx, GrpcInspector.class, inspectors);
        addIfExists(ctx, WebSocketInspector.class, inspectors);
        // v0.6.0 Tier A
        addIfExists(ctx, BatchInspector.class, inspectors);
        addIfExists(ctx, OAuth2Inspector.class, inspectors);
        addIfExists(ctx, ThreadPoolInspector.class, inspectors);
        addIfExists(ctx, ModulithInspector.class, inspectors);
        addIfExists(ctx, QuartzInspector.class, inspectors);
        // v0.6.0 Tier B
        addIfExists(ctx, ObjectStorageInspector.class, inspectors);
        addIfExists(ctx, VaultInspector.class, inspectors);
        addIfExists(ctx, DistributedCacheInspector.class, inspectors);
        addIfExists(ctx, StateMachineInspector.class, inspectors);
        addIfExists(ctx, GraphQLInspector.class, inspectors);
        addIfExists(ctx, OpenApiInspector.class, inspectors);
        // v0.6.2 Enterprise Security
        addIfExists(ctx, TlsInspector.class, inspectors);
        addIfExists(ctx, LdapInspector.class, inspectors);
        addIfExists(ctx, KerberosInspector.class, inspectors);

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
