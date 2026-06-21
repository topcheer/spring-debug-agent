# Spring Debug Agent

An AI-powered debugging agent that **embeds directly into your Spring Boot application**. Add one dependency, configure an LLM key, and chat with your live application at `/agent` to inspect threads, memory, Spring beans, JMX MBeans, HTTP requests, metrics, and set runtime watch points — no external process, no agent attach, no IDE plugin required.

> **226+ diagnostic tools** across **64 inspectors** — the most comprehensive embedded debugging toolkit for the JVM.

## Why?

Traditional debugging tools (jstack, jmap, JConsole, Arthas) require separate processes, JVM attach permissions, or deep CLI knowledge. Spring Debug Agent puts an AI assistant *inside* your running app. It speaks natural language, understands Spring context, and can reason across multiple diagnostic signals to find root causes faster.

## Demo Videos

Automated recordings of real multi-turn AI debugging sessions (click to watch):

| Scenario | Description | Tools Showcased |
|----------|-------------|-----------------|
| Connection Pool Health | HikariCP stats + DB diagnostics | data_source, health, jpa_query_stats |
| REST API Discovery | Endpoint listing + runtime method invocation | http_endpoints, invoke_bean_method, cache_stats |
| Memory Leak Hunt | Heap histogram + GC + buffer analysis | memory, heap_histogram, trigger_gc, buffer_pool |
| Tasks & Logs | Scheduled tasks + live log capture | scheduled_tasks, recent_logs, search_logs, log_stats |
| Full System Audit | Deep dive across all subsystems | runtime_info, environment, metrics, CPU threads |
| Transaction Debug | Transaction status + rollback tracking | transaction_info, transaction_stats, rollback_history |
| API Smoke Test | Batch endpoint testing + coverage | test_endpoint_batch, endpoint_coverage, compare_endpoints |
| Security Audit | Security config + auth + sessions | security_config, authentication_info, session_info |
| SQL Deep Dive | Slow queries + connection leaks | active_sql_queries, slow_sql, detect_connection_leak |
| Redis Inspection | Server info + slowlog + key details | redis_info, redis_slowlog, redis_key_info |
| Resilience Health | Circuit breakers + retry + rate limits | circuit_breakers, retry_stats, rate_limiters |
| Profiling Hotspots | CPU + allocation sampling | cpu_hotspots, allocation_hotspots |
| DB Migration Status | Flyway history + pending migrations | db_migrations, pending_migrations |

> All recordings were captured automatically via Playwright. See `demo-record.js` to record your own.

## Quick Start

### 1. Add the dependency (Maven)

```xml
<dependency>
    <groupId>dev.ggcode</groupId>
    <artifactId>spring-debug-agent</artifactId>
    <version>0.8.0</version>
</dependency>
```

### 2. Configure your LLM

```yaml
debug-agent:
  enabled: true
  base-path: /agent
  llm:
    base-url: https://api.openai.com/v1    # Any OpenAI-compatible endpoint
    api-key: ${LLM_API_KEY}
    model: gpt-4o
    temperature: 0.3
    max-tokens: 4096
    max-tool-rounds: 10
    timeout-seconds: 120
    max-retries: 3              # Auto-retry on 429/5xx with exponential backoff
    retry-base-delay-ms: 1000
    retry-max-delay-ms: 30000
```

### 3. Run your app and open the chat UI

```
http://localhost:8080/agent
```

That's it. The agent auto-configures via Spring Boot Starter — no code changes needed.

### Gradle

```groovy
implementation 'dev.ggcode:spring-debug-agent:0.8.0'
```

## Supported LLM Providers

Any endpoint that implements the OpenAI `/v1/chat/completions` API:

| Provider | base-url | Models |
|----------|----------|--------|
| OpenAI | `https://api.openai.com/v1` | gpt-4o, gpt-4o-mini, etc. |
| ZhipuAI (GLM) | `https://open.bigmodel.cn/api/paas/v4` | glm-4, glm-4.6, glm-5.2 |
| DeepSeek | `https://api.deepseek.com/v1` | deepseek-chat, deepseek-coder |
| Moonshot | `https://api.moonshot.cn/v1` | moonshot-v1-128k |
| Ollama (local) | `http://localhost:11434/v1` | llama3, qwen2, mistral |
| vLLM | `http://localhost:8000/v1` | Any hosted model |

---

## Diagnostic Tools (226+ total)

### JVM Diagnostics (`JvmInspector` — 16 tools)

| Tool | Description |
|------|-------------|
| `get_thread_summary` | Thread state overview (RUNNABLE, WAITING, BLOCKED, etc.) |
| `get_thread_dump` | Full thread dump with optional stack traces |
| `detect_deadlocks` | Deadlock detection via `ThreadMXBean` |
| `get_cpu_consuming_threads` | Top CPU-consuming threads by time |
| `get_memory_summary` | Heap / non-heap / memory pool usage |
| `get_gc_stats` | GC collection count and time per algorithm |
| `get_runtime_info` | JVM version, uptime, class loading info |
| `get_heap_histogram` | Object count and size per class (HotSpot DiagnosticCommand) |
| `get_buffer_pool_stats` | DirectByteBuffer and MappedByteBuffer usage |
| `get_compilation_stats` | JIT compilation time and compiler info |
| `trigger_gc` | Trigger garbage collection and show before/after comparison |
| `get_system_properties` | JVM system properties (java.version, os.name, etc.) with prefix filtering |
| `get_process_info` | Process-level info: PID, CPU usage, memory limits, container detection |
| `get_thread_contention` | Thread lock contention analysis: blocked threads, lock owners, wait times |
| `get_lock_owners` | Lock ownership map: which threads hold locks, which threads are waiting |
| `get_deadlock_graph` | Deadlock dependency graph with full cycle and stack traces |

### Memory & Compilation (`CompilationInspector` — 3 tools)

| Tool | Description |
|------|-------------|
| `get_compilation_stats` | JIT compilation statistics (compile count, total time) |
| `get_memory_pool_details` | Per-pool usage, thresholds, peak, collection snapshots |
| `get_memory_manager_stats` | GC algorithm names, managed pools, collection counts |

### JMX MBean Browser (`MBeanInspector` — 4 tools)

| Tool | Description |
|------|-------------|
| `list_mbeans` | List all registered MBeans grouped by domain |
| `get_mbean_attributes` | Read all attributes of a specific MBean |
| `get_mbean_attribute` | Read a single attribute value |
| `invoke_mbean_operation` | Invoke an operation on an MBean |

> Covers Tomcat, HikariCP, Hibernate, Logback, JVM internals — anything registered in the platform MBean server.

### Spring Core (`SpringInspector` — 11 tools)

| Tool | Description |
|------|-------------|
| `get_all_beans` | List all Spring beans (name, type, scope) |
| `get_bean_details` | Inspect a bean's fields, methods, and live values |
| `get_bean_dependencies` | Dependency graph for a specific bean |
| `get_bean_field_value` | Read a specific field value from a live bean |
| `get_property` | Query a configuration property |
| `search_properties` | Search properties by keyword |
| `get_active_profiles` | Active Spring profiles |
| `get_context_info` | ApplicationContext metadata (startup time, bean count) |
| `get_bean_methods` | List all public methods of a bean (params, return type, annotations) |
| `get_bean_annotations` | Get all class-level and field-level annotations on a bean |
| `get_environment_properties` | Full environment properties grouped by property source |

### Bean Dependency Graph (`BeanGraphInspector` — 3 tools)

| Tool | Description |
|------|-------------|
| `get_bean_creation_order` | Bean creation sequence (startup order) |
| `get_circular_references` | Detect circular dependencies in the container |
| `get_lazy_beans` | List @Lazy beans and uninitialized singletons |

### Runtime Watch Points (`WatchPointManager` — 6 tools)

| Tool | Description |
|------|-------------|
| `search_loaded_classes` | Find loaded classes by name pattern |
| `add_watch_point` | Set a method-level watch point (virtual breakpoint) |
| `get_watch_results` | Retrieve captured method calls (args, return value, timing) |
| `list_watch_points` | List all active watch points |
| `remove_watch_point` | Remove a watch point |
| `add_field_watch_point` | Monitor a specific field on a bean for value changes |

Watch points use **ByteBuddy** runtime bytecode instrumentation — no restart needed. Set a watch point on any loaded method, trigger the code path, and inspect what arguments were passed and what was returned.

### Web & HTTP (`WebInspector` + `RequestInspector` — 9 tools)

| Tool | Description |
|------|-------------|
| `get_http_endpoints` | List all `@RequestMapping` endpoints with controllers |
| `invoke_bean_method` | Invoke any bean method at runtime with arguments |
| `get_filter_chains` | List all registered servlet filter chains with URL patterns |
| `get_recent_requests` | Recent HTTP requests from in-memory ring buffer |
| `get_slow_requests` | Slowest requests sorted by duration |
| `get_request_stats` | Request stats: P50/P95/P99 latency, status distribution, error rate |
| `get_request_by_path` | Search HTTP request history by path (partial match) |
| `get_error_requests` | Get all error requests (4xx/5xx) with exception traces |
| `get_bean_field_value` | Read a specific field from a live bean |

### Metrics (`MetricsInspector` — 3 tools)  
*Requires Micrometer on classpath (included with Spring Boot Actuator)*

| Tool | Description |
|------|-------------|
| `get_metrics_list` | List all Micrometer meters (gauges, counters, timers) |
| `get_metric_value` | Read current value of a specific metric |
| `get_meter_registries` | List all MeterRegistry backends (Prometheus, JMX, etc.) |

### Spring Events (`EventInspector` — 2 tools)

| Tool | Description |
|------|-------------|
| `get_recent_events` | Recently published ApplicationEvents (in-memory ring buffer) |
| `get_event_listeners` | List all registered event listeners and their event types |

### Spring Transactions (`TransactionInspector` — 5 tools)
*Requires spring-tx on classpath (included with Spring Boot Data JPA, etc.)*

| Tool | Description |
|------|-------------|
| `get_transaction_info` | Current thread's transaction status (active, name, isolation, propagation) |
| `get_transaction_stats` | Transaction statistics (commits, rollbacks, avg duration, rollback rate) |
| `get_recent_transactions` | Recent transaction execution records (method, duration, result) |
| `get_rollback_history` | All rollback records with error details |
| `get_slow_transactions` | Slowest transactions sorted by duration |

### Outbound HTTP Tracking (`RestClientInspector` — 4 tools)
*Requires Spring RestTemplate on classpath*

| Tool | Description |
|------|-------------|
| `get_outbound_requests` | Recent outbound HTTP calls (URL, method, status, duration, host) |
| `get_slow_outbound_requests` | Slowest outbound calls sorted by duration |
| `get_outbound_request_stats` | Outbound call statistics (success rate, P95/P99 latency, by host) |
| `get_outbound_errors` | Outbound call errors (timeouts, 4xx/5xx, exceptions) |

### Active API Probing (`EndpointTestInspector` — 5 tools)
*Requires Spring MVC on classpath*

| Tool | Description |
|------|-------------|
| `test_endpoint` | Actively call any application API endpoint (internal loopback HTTP) |
| `test_endpoint_batch` | Batch-test all GET endpoints in one call |
| `test_endpoint_auth` | Test an endpoint with custom authentication headers |
| `compare_endpoints` | Compare responses from the same endpoint on two different hosts/ports |
| `get_endpoint_coverage` | API endpoint coverage report (called vs. never-called endpoints) |

### Log Analysis (`LogInspector` — 3 tools)
*Requires Logback on classpath (included with Spring Boot)*

| Tool | Description |
|------|-------------|
| `get_recent_logs` | In-memory Logback log capture (ring buffer), filter by level |
| `search_logs` | Search log entries by keyword (message + exception text) |
| `get_log_stats` | Log statistics: count per level, error rate, most active loggers |

### Infrastructure Inspectors (9 tools)

| Tool | Inspector | Description |
|------|-----------|-------------|
| `get_data_source_info` | `DataSourceInspector` | HikariCP pool stats (active, idle, queued) |
| `get_health_status` | `HealthInspector` | Spring Boot Actuator health endpoint |
| `get_scheduled_tasks` | `TaskInspector` | `@Scheduled` tasks with cron/fix-delay info |
| `get_async_task_info` | `TaskInspector` | `@Async` thread pool stats |
| `get_cache_stats` | `CacheInspector` | Spring Cache hit/miss/eviction stats |
| `get_jpa_query_stats` | `JpaInspector` | Hibernate query stats (slow queries, N+1) |
| `get_http_client_pool_stats` | `HttpClientInspector` | Apache HttpClient pool stats |
| `get_feature_flags` | `FeatureFlagInspector` | Auto-configuration condition evaluation report |
| `get_environment_diff` | `EnvironmentInspector` | Active property sources and configuration |

### Spring Security (`SecurityInspector` — 4 tools)  *v0.5.0*
*Requires Spring Security on classpath*

| Tool | Description |
|------|-------------|
| `get_security_config` | Security filter chains, user details services, password encoder, auth managers |
| `get_authentication_info` | Current authentication: principal, roles, authorities, credentials |
| `get_session_info` | Active HTTP sessions: count, attributes, timeout config |
| `get_security_events` | Security event listeners and audit configuration |

### Deep SQL Diagnostics (`SqlInspector` — 3 tools)  *v0.5.0*
*Requires javax.sql.DataSource*

| Tool | Description |
|------|-------------|
| `get_active_sql_queries` | Database metadata, connection info, table list, pool stats |
| `get_slow_sql` | Slow SQL queries from Hibernate statistics (execution time, row count) |
| `detect_connection_leak` | Connection pool leak detection: exhaustion, utilization, warnings |

### HTTP Session Management (`HttpSessionInspector` — 3 tools)  *v0.5.0*
*Requires Servlet API*

| Tool | Description |
|------|-------------|
| `get_http_sessions` | Session statistics: active count, timeout, cookie config |
| `get_session_attributes` | Inspect specific session attributes by ID |
| `invalidate_session` | Destroy an HTTP session (force logout / clear state) |

### Redis Diagnostics (`RedisInspector` — 3 tools)  *v0.5.0*
*Requires Spring Data Redis*

| Tool | Description |
|------|-------------|
| `get_redis_info` | Server info: version, memory, clients, keyspace stats |
| `get_redis_slowlog` | Redis slow query log with command and duration |
| `get_redis_key_info` | Inspect a specific key: type, TTL, value preview |

### Messaging (`MessagingInspector` — 3 tools)  *v0.5.0*
*Requires Spring Kafka or Spring AMQP*

| Tool | Description |
|------|-------------|
| `get_kafka_consumers` | Kafka listener containers: topics, group IDs, pause status |
| `get_queue_info` | Queue/topic info for Kafka and RabbitMQ |
| `get_dead_letter_queues` | Dead letter queue handlers and error topic detection |

### Resilience (`ResilienceInspector` — 4 tools)  *v0.5.0*
*Requires Resilience4j*

| Tool | Description |
|------|-------------|
| `get_circuit_breakers` | Circuit breaker instances: state, failure rate, config |
| `get_retry_stats` | Retry config and stats: attempts, success/failure counts |
| `get_rate_limiters` | Rate limiter instances: available permissions, waiting threads |
| `get_rate_limit_stats` | Rate limiter utilization: throttling rate, used vs. limit |

### CPU & Memory Profiling (`ProfilingInspector` — 2 tools)  *v0.5.0*

| Tool | Description |
|------|-------------|
| `get_cpu_hotspots` | Sample CPU hotspots: top methods by CPU time via thread sampling |
| `get_allocation_hotspots` | Sample memory allocation: top allocating threads by bytes |

### Reactive / WebFlux (`ReactiveInspector` — 2 tools)  *v0.5.0*
*Requires Project Reactor*

| Tool | Description |
|------|-------------|
| `get_reactive_streams` | Reactive stream info: schedulers, WebClient, context API |
| `get_event_loop_status` | Netty event loop threads: active/blocked, blocking I/O detection |

### Database Migrations (`MigrationInspector` — 2 tools)  *v0.5.0*
*Requires Flyway or Liquibase*

| Tool | Description |
|------|-------------|
| `get_db_migrations` | Applied migrations, schema version, migration history |
| `get_pending_migrations` | Unapplied migration scripts: version, description, file path |

### Spring Cloud (`CloudInspector` — 3 tools)  *v0.5.0*
*Requires Spring Cloud*

| Tool | Description |
|------|-------------|
| `get_service_discovery` | Service discovery (Eureka/Consul/Nacos): registered services, instances |
| `get_config_server_status` | Spring Cloud Config: config server URI, refresh scope, property sources |
| `get_cloud_circuit_breakers` | Spring Cloud circuit breaker abstraction: instances and implementations |

### Distributed Tracing (`TracingInspector` — 4 tools)  *v0.6.0*
*Requires Micrometer Tracing or OpenTelemetry*

| Tool | Description |
|------|-------------|
| `get_trace_info` | Tracer type, exporter config, sampling strategy |
| `get_recent_spans` | Recent spans from in-memory ring buffer: traceId, spanId, name, duration |
| `get_trace_dependencies` | Service dependency graph from trace data |
| `get_slow_spans` | Filter spans by duration threshold for bottleneck analysis |

### MongoDB (`MongoDbInspector` — 4 tools)  *v0.6.0*
*Requires Spring Data MongoDB*

| Tool | Description |
|------|-------------|
| `get_mongo_info` | Server status: version, connections, storage engine, uptime |
| `get_mongo_collections` | Collections with document count and average size |
| `get_mongo_slow_queries` | Slow operations from `$currentOp` or in-memory tracker |
| `get_mongo_indexes` | Index details per collection: keys, size, uniqueness |

### Elasticsearch (`ElasticsearchInspector` — 3 tools)  *v0.6.0*
*Requires Spring Data Elasticsearch*

| Tool | Description |
|------|-------------|
| `get_es_cluster_health` | Cluster name, status (green/yellow/red), nodes, shards, pending tasks |
| `get_es_indices` | All indices: doc count, store size, health, shards/replicas |
| `get_es_slow_queries` | Slow log threshold configuration |

### gRPC (`GrpcInspector` — 3 tools)  *v0.6.0*
*Requires gRPC*

| Tool | Description |
|------|-------------|
| `get_grpc_channels` | Registered ManagedChannel beans: target, state, pending RPCs |
| `get_grpc_services` | Registered gRPC service definitions and method list |
| `get_grpc_call_stats` | Call statistics per method: count, latency, error rate |

### WebSocket (`WebSocketInspector` — 3 tools)  *v0.6.0*
*Requires Spring WebSocket*

| Tool | Description |
|------|-------------|
| `get_websocket_sessions` | Active sessions: ID, URI, remote address, creation time |
| `get_websocket_stats` | Stats: total created, active count, messages sent/received |
| `get_websocket_messages` | Recent messages per session from in-memory buffer |

### Spring Batch (`BatchInspector` — 4 tools)  *v0.6.0*
*Requires Spring Batch*

| Tool | Description |
|------|-------------|
| `get_batch_jobs` | Registered jobs: name, instance count, latest status |
| `get_batch_job_executions` | Recent executions: start/end, status, exit code, step count |
| `get_batch_step_stats` | Step-level stats: reads, writes, commits, rollbacks |
| `get_batch_failures` | Failed executions with exception details |

### OAuth2 Authorization Server (`OAuth2Inspector` — 4 tools)  *v0.6.0*
*Requires Spring Authorization Server*

| Tool | Description |
|------|-------------|
| `get_authorization_server_config` | Issuer URL, token formats, grant types, JWK set |
| `get_oauth2_clients` | Registered clients: ID, grant types, scopes, redirect URIs |
| `get_oauth2_tokens` | Active access/refresh token counts |
| `get_oauth2_consents` | User consents: which users consented to which clients |

### Thread Pool Monitoring (`ThreadPoolInspector` — 3 tools)  *v0.6.0*

| Tool | Description |
|------|-------------|
| `get_thread_pools` | All ThreadPoolTaskExecutor beans: core/max size, queue, rejection policy |
| `get_thread_pool_stats` | Live stats: active threads, pool size, queue depth, completed tasks |
| `get_rejected_tasks` | Aggregated rejected task counts across all pools |

### Spring Modulith (`ModulithInspector` — 3 tools)  *v0.6.0*
*Requires Spring Modulith*

| Tool | Description |
|------|-------------|
| `get_modules` | Application modules: name, base package, contained types, openness |
| `verify_module_boundaries` | Module boundary verification with violation details |
| `get_module_dependencies` | Module dependency graph: direct and transitive |

### Quartz Scheduler (`QuartzInspector` — 3 tools)  *v0.6.0*
*Requires Quartz*

| Tool | Description |
|------|-------------|
| `get_quartz_jobs` | All JobDetails: name, group, job class, durability |
| `get_quartz_triggers` | All triggers: type (Simple/Cron), next/prev fire time, priority |
| `get_quartz_job_history` | Recent job executions: start/end, duration, result, exceptions |

### Object Storage (`ObjectStorageInspector` — 2 tools)  *v0.6.0*
*Requires AWS S3 SDK or MinIO*

| Tool | Description |
|------|-------------|
| `get_s3_buckets` | List buckets: name, creation date, region, object count |
| `get_s3_object_info` | Object metadata: size, content type, ETag, storage class |

### Vault Secrets (`VaultInspector` — 2 tools)  *v0.6.0*
*Requires Spring Vault*

| Tool | Description |
|------|-------------|
| `get_secret_engines` | Enabled secret engines: kv, transit, database |
| `get_secret_metadata` | Secret metadata: version count, timestamps, deletion status |

### Distributed Cache (`DistributedCacheInspector` — 2 tools)  *v0.6.0*
*Requires Hazelcast or Infinispan*

| Tool | Description |
|------|-------------|
| `get_distributed_cache_members` | Cluster members: address, UUID, local flag |
| `get_distributed_cache_stats` | Cache stats: entries, hit/miss ratio, backup entries |

### Spring State Machine (`StateMachineInspector` — 3 tools)  *v0.6.0*
*Requires Spring State Machine*

| Tool | Description |
|------|-------------|
| `get_state_machines` | Registered machines: ID, states, transitions |
| `get_current_states` | Active machine instances and their current state |
| `get_state_transitions` | Transitions: source, target, event, guard, actions |

### GraphQL (`GraphQLInspector` — 3 tools)  *v0.6.0*
*Requires Spring for GraphQL*

| Tool | Description |
|------|-------------|
| `get_graphql_schema` | Schema overview: query, mutation, subscription, entity types |
| `get_graphql_queries` | Recent queries: operation, variables, duration, errors |
| `get_graphql_errors` | Recent errors: message, path, locations, extensions |

### OpenAPI / Swagger (`OpenApiInspector` — 3 tools)  *v0.6.0*

| Tool | Description |
|------|-------------|
| `get_openapi_spec` | OpenAPI spec overview: paths, schemas, security schemes |
| `validate_openapi` | Validate spec: missing responses, circular refs, deprecated ops |
| `get_api_changelog` | API drift: undocumented endpoints vs documented-but-missing |

### TLS / mTLS (`TlsInspector` — 5 tools)  *v0.7.0*

| Tool | Description |
|------|-------------|
| `get_tls_keystore_info` | Keystore/truststore config: paths, types, entries, certificates with subject/issuer/expiry |
| `check_certificate_expiry` | Check all X.509 certificates for expiration — warns if expiring within 30 days |
| `test_tls_handshake` | Test TLS handshake against a remote HTTPS endpoint — protocol, cipher, cert chain, duration |
| `get_tls_protocols_and_ciphers` | List supported/enabled TLS protocols and cipher suites on this JVM |
| `get_ssl_context_info` | Inspect default SSLContext, TrustManager chain, and javax.net.ssl system properties |

### LDAP (`LdapInspector` — 5 tools)  *v0.7.0*

| Tool | Description |
|------|-------------|
| `get_ldap_context_source_info` | ContextSource config: URLs, base DN, bind DN, auth strategy, pooling |
| `test_ldap_bind` | Test LDAP bind authentication — success/failure, response time, error diagnostics |
| `get_ldap_search_config` | User/group search config: search bases, filters, DN patterns, group role attributes |
| `search_ldap_directory` | Search LDAP directory for entries — returns DN, attributes, object classes |
| `get_ldap_connection_pool_stats` | Connection pool statistics and configuration (active, idle, max size, timeout) |

### Kerberos / SPNEGO (`KerberosInspector` — 5 tools)  *v0.7.0*

| Tool | Description |
|------|-------------|
| `get_jaas_configuration` | JAAS config: login modules, control flags, options — detect misconfigured Kerberos entries |
| `inspect_keytab` | Inspect keytab file: principals, encryption types, file integrity |
| `test_kerberos_login` | Test Kerberos login — attempt TGT acquisition, report credentials and detailed errors |
| `get_kerberos_security_config` | Spring Security Kerberos/SPNEGO beans: ticket validator, auth provider, SPNEGO filter |
| `get_kerberos_environment` | Kerberos environment: krb5.conf, realm/KDC settings, system properties, ticket cache |

### MyBatis (`MyBatisInspector` — 5 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_mybatis_configuration` | MyBatis global config: cacheEnabled, lazyLoading, mapUnderscoreToCamelCase, executor type |
| `get_mybatis_mappers` | All mapped statements: SQL ID, command type, XML resource, key generator |
| `get_mybatis_interceptors` | Interceptor (plugin) chain: class, @Intercepts signatures, ordering |
| `get_mybatis_sql_session_info` | SqlSessionFactory/Template config: data source, transaction factory, mapper count |
| `get_mybatis_cache_config` | Second-level cache: namespace, cache impl, eviction strategy, size |

### Apache Camel (`CamelInspector` — 5 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_camel_routes` | All Camel routes: ID, status, endpoint URI, uptime |
| `get_camel_route_stats` | Route performance: exchanges completed/failed, min/mean/max RT, inflight |
| `get_camel_endpoints` | All registered endpoints: URI, singleton status |
| `get_camel_consumers` | Consumer status: inflight exchanges, suspended/stopped state |
| `get_camel_context_info` | Camel context summary: name, version, uptime, components, shutdown strategy |

### RabbitMQ / AMQP (`AmqpInspector` — 5 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_amqp_queues` | Queue names from RabbitAdmin |
| `get_amqp_consumers` | Listener containers: queues, concurrent consumers, prefetch, acknowledge mode |
| `get_amqp_connection_info` | CachingConnectionFactory: host, port, vhost, channel cache, cache properties |
| `get_amqp_exchanges` | Exchange topology: name, type (direct/fanout/topic/headers), durable, auto-delete |
| `get_amqp_template_info` | RabbitTemplate: default exchange, routing key, confirm/returns callback, converter |

### Apache Dubbo (`DubboInspector` — 5 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_dubbo_services` | Service providers: interface, group, version, protocol, port, registries, timeout |
| `get_dubbo_references` | Consumer references: interface, load balance, timeout, retries, cluster, URL |
| `get_dubbo_application_config` | App config: name, registry addresses, protocol, QoS port, serialization |
| `get_dubbo_thread_pool` | Thread pool: max/core/io threads, queue capacity, active count, completed tasks |
| `get_dubbo_registry_status` | Registry: address, protocol, register/subscribe enabled, check, timeout |

### RocketMQ (`RocketMqInspector` — 5 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_rocket_mq_producer_info` | Producer: group, name server, send timeout, retry, max message size |
| `get_rocket_mq_consumers` | Consumers: group, topic, consume mode (concurrently/orderly), message model, threads |
| `get_rocket_mq_subscriptions` | Subscription table: topic to tag/expression mapping |
| `get_rocket_mq_server_info` | Name server connectivity, client ID, broker discovery |
| `get_rocket_mq_transaction_info` | Transaction producer, transaction listeners, check config |

### Alibaba Nacos (`NacosInspector` — 5 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_nacos_services` | Registered services with instances: IP, port, healthy, weight, cluster, metadata |
| `get_nacos_config` | Configuration by dataId/group: raw content from Nacos config server |
| `get_nacos_namespaces` | Namespace list: ID, name, config count |
| `get_nacos_health` | Server connectivity: naming/config server status, Spring properties |
| `get_nacos_config_listeners` | Config change listeners: watched dataIds, cached content, MD5 |

### Alibaba Sentinel (`SentinelInspector` — 4 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_sentinel_flow_rules` | Flow control rules: resource, grade (QPS/thread), threshold, control behavior |
| `get_sentinel_degrade_rules` | Circuit breaker rules: strategy (RT/exception), threshold, time window, min requests |
| `get_sentinel_metrics` | Real-time metrics: pass/block/success/exception QPS, RT, thread count |
| `get_sentinel_datasources` | Datasource config: rules loaded from Nacos/Apollo/ZK/file |

### Seata (`SeataInspector` — 4 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_seata_global_config` | Global TX config: app ID, tx-service-group, data source proxy mode, XID |
| `get_seata_rm_config` | RM config: async commit buffer, retry, undo log table, serialization |
| `get_seata_tm_config` | TM config: commit/rollback retry, default timeout, interceptor |
| `get_seata_tc_status` | TC connectivity: TM/RM clients, registry type, server address, transport |

### Flowable BPM (`FlowableInspector` — 4 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_flowable_process_definitions` | Deployed process definitions: key, name, version, suspended, resource name |
| `get_flowable_active_instances` | Active process instances: ID, definition, business key, start time |
| `get_flowable_tasks` | Active user tasks: name, assignee, creation time, priority, due date |
| `get_flowable_engine_config` | Engine config: DB type, schema update, history level, async executor, counts |

### Cassandra (`CassandraInspector` — 4 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_cassandra_cluster_info` | Cluster topology: nodes, datacenter, rack, state, open connections |
| `get_cassandra_keyspaces` | Keyspaces: replication strategy, durable writes, table/type count |
| `get_cassandra_session_stats` | Session metrics: prepared statements, consistency, page size, pool config |
| `test_cassandra_query` | Execute CQL query (SELECT/DESCRIBE) — returns columns and rows |

### Zookeeper (`ZookeeperInspector` — 4 tools)  *v0.8.0*

| Tool | Description |
|------|-------------|
| `get_zk_children` | List ZNode children at path: names, full paths |
| `get_zk_node` | ZNode details: data (string/hex), stat (version, ctime, mtime, ephemeral owner) |
| `get_zk_watchers` | Registered watchers: data/child/exist watcher paths, session info, Curator listeners |
| `get_zk_cluster_status` | Cluster status: connection state, session ID/timeout, server address, ensemble info |

### Classloading & System (3 tools)

| Tool | Description |
|------|-------------|
| `search_loaded_classes` | Search loaded classes by pattern |
| `find_class_location` | Find JAR/file location of a loaded class |
| `get_classloading_info` | Class loading/unloading statistics |

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Your Spring Boot App                      │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │                  spring-debug-agent                      │ │
│  │                                                          │ │
│  │  ┌──────────┐     ┌──────────┐     ┌─────────────┐     │ │
│  │  │  Web UI  │────▶│  Engine  │────▶│     LLM     │     │ │
│  │  │  /agent  │     │ (reason) │     │   Client    │     │ │
│  │  └──────────┘     └────┬─────┘     └─────────────┘     │ │
│  │                         │                                │ │
│  │    ┌────────────────────┼────────────────────────┐      │ │
│  │    │        64 Inspectors (226+ tools)           │      │ │
│  │    │                    │                        │      │ │
│  │  ┌─▼──┐ ┌────┐ ┌──────┐ ┌─────┐ ┌─────┐ ┌─────┐ │      │ │
│  │  │JVM │ │Spng│ │Watch │ │ JMX │ │Reqs │ │Metrc│ │      │ │
│  │  │ 13 │ │ 11 │ │  6   │ │  4  │ │  5  │ │  3  │ │      │ │
│  │  └────┘ └────┘ └──────┘ └─────┘ └─────┘ └─────┘ │      │ │
│  │  ┌────┐ ┌────┐ ┌──────┐ ┌─────┐ ┌─────┐ ┌─────┐ │      │ │
│  │  │Evt │ │Bean│ │Cache │ │ JPA │ │Tasks│ │Logs │ │      │ │
│  │  │ 2  │ │ 3  │ │  1   │ │  1  │ │  2  │ │  3  │ │      │ │
│  │  └────┘ └────┘ └──────┘ └─────┘ └─────┘ └─────┘ │      │ │
│  │  ┌──────┐ ┌────────┐ ┌──────────┐ ┌──────────┐  │      │ │
│  │  │ Txn  │ │RestCli │ │EndpointT │ │   Web    │  │      │ │
│  │  │  5   │ │   4    │ │    5     │ │    2     │  │      │ │
│  │  └──────┘ └────────┘ └──────────┘ └──────────┘  │      │ │
│  └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### How it works

1. **User** types a question in the chat UI (`/agent`)
2. **Engine** sends the conversation + tool schemas to the LLM
3. **LLM** decides which tools to call (function calling)
4. **ToolExecutor** invokes the tool against the live JVM/Spring context
5. Results are fed back to the LLM for analysis
6. LLM responds with findings — repeat until the question is answered
7. Response streams to the UI via SSE with markdown rendering

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Embedded** (not external attach) | Zero friction — works in any IDE, no JVM attach permissions |
| **226+ tools, zero hard dependencies** | All optional inspectors use `@ConditionalOnClass` — agent JAR never forces a dependency |
| **Custom HTTP client** (not Spring AI) | Minimal dependencies, works with any OpenAI-compatible endpoint |
| **ByteBuddy** (not JDI) | Runtime bytecode enhancement, no separate agent process |
| **Self-contained UI** (no CDN) | Works in enterprise environments with no internet access |
| **Spring Boot Starter** | Auto-configures everything — just add the dependency |

---

## Project Structure

```
spring-debug-agent/
├── pom.xml                          # Parent POM (multi-module)
├── agent/                           # Core library (the dependency users add)
│   ├── pom.xml                      # Standalone POM (no parent)
│   └── src/main/java/com/debugagent/
│       ├── autoconfigure/           # Spring Boot auto-configuration
│       │   ├── DebugAgentAutoConfiguration.java
│       │   └── DebugAgentProperties.java
│       ├── llm/                     # LLM client layer
│       │   ├── OpenAiClient.java    # HTTP client with retry + streaming
│       │   └── model/               # ChatMessage, ToolCall, ChatRequest...
│       ├── tool/                    # Tool framework
│       │   ├── annotation/          # @DebugTool, @ToolParam
│       │   ├── ToolRegistry.java    # Discovers and registers tools
│       │   └── ToolExecutor.java    # Invokes tools, marshals args
│       ├── inspector/               # 64 diagnostic inspectors (226+ tools)
│       │   ├── JvmInspector.java        # Threads, memory, GC, heap, process info
│       │   ├── SpringInspector.java      # Beans, config, annotations, methods
│       │   ├── MBeanInspector.java       # JMX MBean browsing
│       │   ├── RequestInspector.java     # HTTP request tracing + error search
│       │   ├── MetricsInspector.java     # Micrometer metrics
│       │   ├── TransactionInspector.java # Spring transaction monitoring
│       │   ├── RestClientInspector.java  # Outbound HTTP call tracking
│       │   ├── EndpointTestInspector.java # Active API probing + batch testing
│       │   ├── WatchPoint*.java          # ByteBuddy runtime instrumentation
│       │   └── ...                       # DataSource, Cache, JPA, Tasks, Logs, etc.
│       ├── engine/                  # Reasoning loop
│       │   └── DebugAgentEngine.java
│       └── web/                     # Embedded UI + REST
│           ├── AgentController.java # SSE streaming endpoints
│           └── ChatPageHtml.java    # Single-file chat UI
│
├── demo/                            # Demo app (Order Management System)
│   └── ...
│
├── docker/                           # Docker infrastructure for demo
│   └── kdc/                          # MIT Kerberos KDC (Dockerfile, init script, keytab output)
│       ├── Dockerfile                # Ubuntu 22.04 + krb5-kdc
│       ├── init-kdc.sh               # Auto-creates realm, principals, exports keytab
│       └── keytabs/                  # Generated keytab (demo.keytab) and krb5.conf
│
├── docker-compose.yml                # All 13 external dependencies (single file)
├── e2e-tests/                       # End-to-end test suite (80 tools)
│   ├── fast-e2e-test.js             # Fast batch test runner (original 55 tools)
│   ├── new-tools-test.js            # v0.4.0 new tools test (25 tools)
│   ├── v05-new-tools-test.js        # v0.5.0 new tools test (32 tools)
│   ├── v06-new-tools-test.js        # v0.6.0 new tools test (29 active tools)
│   ├── jvm-spring-tests.js          # JVM/Spring tool tests
│   ├── infra-web-tests.js           # Infrastructure/Web tool tests
│   ├── watchpoint-tests.js          # WatchPoint lifecycle tests
│   └── test-runner.js               # Orchestrates all test files
├── e2e-data-setup.sh                # Generates test data before E2E run
└── demo-record.js                   # Playwright automated demo recorder
```

---

## Demo Application

The `demo` module is an **Order Management System** with realistic complexity:

- **3 JPA entities** (Customer, Order, OrderItem) with relationships
- **Multi-step transactional service** (`OrderService.createOrder`) with pricing, credit check, async deduction
- **H2 in-memory database** with auto-initialized test data
- **HikariCP connection pool** with full statistics
- **Spring Cache** (`@Cacheable` on PricingService)
- **Scheduled tasks** (`@Scheduled` revenue reports + stock checking)
- **Async thread pool** (`@Async` for credit deduction)
- **Apache HttpClient5** connection pool for outbound calls
- **Hibernate statistics** enabled for query analysis
- **Actuator endpoints** for health checks
- **REST API** for order CRUD operations

#### External Infrastructure (Docker)

The demo integrates with **13 Docker services** covering all inspectors. All services are defined in a single `docker-compose.yml`:

| Service | Port(s) | Inspector Coverage |
|---------|---------|-------------------|
| Redis | 6379 | `RedisInspector` |
| MongoDB | 27017 | `MongoDbInspector` |
| Elasticsearch | 9200 | `ElasticsearchInspector` |
| Redpanda (Kafka) | 9092 | `MessagingInspector` |
| Vault | 8200 | `VaultInspector` |
| MinIO | 9000, 9001 | `ObjectStorageInspector` |
| RocketMQ NameServer + Broker | 9876, 10911 | `RocketMqInspector` |
| Nacos | 8848, 9848 | `NacosInspector`, `CloudInspector` |
| RabbitMQ | 5672, 15672 | `AmqpInspector` |
| Cassandra | 9042 | `CassandraInspector` |
| Seata TC Server | 8091, 7091 | `SeataInspector` |
| MIT Kerberos KDC | 88, 749 | `KerberosInspector` |
| Zookeeper | 2181 | `ZookeeperInspector` |

```bash
# Start all external dependencies
docker compose up -d

# Verify all services are running
docker compose ps
```

> All services are **fail-safe** — the demo starts successfully even if some containers are down. Inspectors simply report `not_configured` or `unavailable` for missing services.

#### Kerberos KDC Setup

The Kerberos inspector requires a running KDC and keytab file. The Docker KDC container (`demo-kdc`) auto-provisions everything:

- **Realm**: `DEMO.LOCAL`
- **Principals**: `HTTP/localhost@DEMO.LOCAL`, `demo-user@DEMO.LOCAL`, `demo-admin@DEMO.LOCAL`
- **Keytab output**: `docker/kdc/keytabs/demo.keytab`

```bash
# Start the KDC (also started by `docker compose up -d`)
docker compose up -d kdc

# Verify the keytab was generated
klist -kt docker/kdc/keytabs/demo.keytab
```

When starting the demo with Kerberos enabled, you must pass JVM flags to open the `java.security.jgss` module for keytab inspection:

```bash
java \
  --add-opens java.security.jgss/sun.security.krb5.internal.ktab=ALL-UNNAMED \
  --add-opens java.security.jgss/sun.security.krb5=ALL-UNNAMED \
  -Djava.security.krb5.conf=demo/src/main/resources/krb5.conf \
  -jar demo/target/spring-debug-agent-demo-0.1.0-SNAPSHOT.jar
```

Without these flags, `inspect_keytab` will fail with `IllegalAccessException` (module access restriction in Java 17+). All other Kerberos tools work without the flags.

### Run the demo

```bash
cd spring-debug-agent

# 1. Start external dependencies (Redis, MongoDB, Kafka, Vault, MinIO, etc.)
docker compose up -d

# 2. Build
mvn clean install -pl agent -DskipTests
mvn package -pl demo -DskipTests

# 3. Run with an LLM provider (example: ZhipuAI GLM-4.6)
#    Include Kerberos JVM flags for full inspector coverage
java \
  -Ddebug-agent.llm.base-url=https://open.bigmodel.cn/api/paas/v4 \
  -Ddebug-agent.llm.api-key=YOUR_API_KEY \
  -Ddebug-agent.llm.model=glm-4.6 \
  --add-opens java.security.jgss/sun.security.krb5.internal.ktab=ALL-UNNAMED \
  --add-opens java.security.jgss/sun.security.krb5=ALL-UNNAMED \
  -Djava.security.krb5.conf=demo/src/main/resources/krb5.conf \
  -jar demo/target/spring-debug-agent-demo-0.1.0-SNAPSHOT.jar
```

Then open `http://localhost:8080/agent` and try asking:

- "Check the JVM memory, GC stats, and process info"
- "List all JMX MBean domains and show HikariCP pool attributes"
- "Show me recent HTTP requests with their status codes and latency"
- "Show me all error requests (4xx and 5xx)"
- "Inspect the OrderService bean — what are its dependencies and annotations?"
- "Set a watch point on OrderService.getAllOrders"
- "Show the P95 latency for HTTP requests"
- "List all Micrometer metrics with 'jvm' prefix"
- "Check for circular bean dependencies"
- "Trigger GC and show before/after memory comparison"
- "What's the current transaction status? Show transaction stats"
- "Search logs for 'order' and show log statistics"
- "Test the /api/orders endpoint and batch-test all GET endpoints"
- "Show me the servlet filter chains and endpoint coverage"
- "Show me the Spring Security configuration and authentication details"
- "Are there any slow SQL queries? Check for connection leaks"
- "What's the Redis server info? Show me the slowlog"
- "Check all circuit breakers and retry stats"
- "Profile CPU hotspots for 3 seconds"
- "Show me the database migration history and pending migrations"
- "Show me the Kerberos security config — ticket validator, SPNEGO filter, authentication provider"
- "Inspect the keytab file and list all principals and encryption types"
- "Check the RabbitMQ queues, consumers, and exchange topology"
- "Show me the RocketMQ producer and consumer info"
- "List all Nacos registered services and configuration"
- "Check the Cassandra cluster topology and keyspaces"
- "Show me the Seata global transaction config and TC server status"
- "Check the Zookeeper cluster status and registered watchers"
- "Show me the Camel routes and their performance stats"

### E2E Test Suite

All 226+ tools are covered by automated E2E tests:

```bash
# 1. Generate test data (orders, HTTP traffic, logs, cache)
bash e2e-data-setup.sh

# 2. Run the fast E2E test (original 55 tools, 6 groups, ~5 min)
node e2e-tests/fast-e2e-test.js

# 4. Run the v0.6.0 new tools test (29 active tools, 9 batches)
node e2e-tests/v06-new-tools-test.js

# Or run individual test suites:
node e2e-tests/jvm-spring-tests.js      # 23 JVM/Spring tools
node e2e-tests/infra-web-tests.js        # 25 infrastructure/Web tools
node e2e-tests/watchpoint-tests.js       # 7 WatchPoint/classloading tools
```

---

## Requirements

- **Java 17+**
- **Spring Boot 3.x**
- An OpenAI-compatible LLM API key

## Build

```bash
mvn clean package -DskipTests
# agent/target/spring-debug-agent-*.jar   (core library)
# demo/target/spring-debug-agent-demo-*.jar (executable fat JAR)
```

## Publishing

Releases are fully automated via GitHub Actions:

```bash
git tag v0.6.0
git push origin v0.6.0
```

The CI pipeline will:
1. Build and GPG-sign the artifact
2. Deploy to Maven Central
3. Create a GitHub Release with auto-generated notes
4. Bump the version to the next SNAPSHOT

## License

MIT
