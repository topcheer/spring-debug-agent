# Spring Debug Agent

An AI-powered debugging agent that **embeds directly into your Spring Boot application**. Add one dependency, configure an LLM key, and chat with your live application at `/agent` to inspect threads, memory, Spring beans, JMX MBeans, HTTP requests, metrics, and set runtime watch points — no external process, no agent attach, no IDE plugin required.

> **55 diagnostic tools** across **21 inspectors** — the most comprehensive embedded debugging toolkit for the JVM.

## Why?

Traditional debugging tools (jstack, jmap, JConsole, Arthas) require separate processes, JVM attach permissions, or deep CLI knowledge. Spring Debug Agent puts an AI assistant *inside* your running app. It speaks natural language, understands Spring context, and can reason across multiple diagnostic signals to find root causes faster.

## Demo Videos

Automated recordings of real multi-turn AI debugging sessions (click to watch):

| Scenario | Description | Tools Showcased |
|----------|-------------|-----------------|
| Connection Pool Health | HikariCP stats + DB diagnostics | data_source, health, jpa_query_stats |
| REST API Discovery | Endpoint listing + runtime method invocation | http_endpoints, invoke_bean_method, cache_stats |
| Memory Leak Hunt | Heap histogram + GC + buffer analysis | memory, heap_histogram, trigger_gc, buffer_pool |
| Tasks & Logs | Scheduled tasks + live log capture | scheduled_tasks, recent_logs, feature_flags |
| Full System Audit | Deep dive across all subsystems | runtime_info, environment, metrics, CPU threads |

> All recordings were captured automatically via Playwright. See `demo-record.js` to record your own.

## Quick Start

### 1. Add the dependency (Maven)

```xml
<dependency>
    <groupId>dev.ggcode</groupId>
    <artifactId>spring-debug-agent</artifactId>
    <version>0.3.0</version>
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
implementation 'dev.ggcode:spring-debug-agent:0.3.0'
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

## Diagnostic Tools (55 total)

### JVM Diagnostics (`JvmInspector` — 11 tools)

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

### Spring Core (`SpringInspector` — 8 tools)

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

### Bean Dependency Graph (`BeanGraphInspector` — 3 tools)

| Tool | Description |
|------|-------------|
| `get_bean_creation_order` | Bean creation sequence (startup order) |
| `get_circular_references` | Detect circular dependencies in the container |
| `get_lazy_beans` | List @Lazy beans and uninitialized singletons |

### Runtime Watch Points (`WatchPointManager` — 5 tools)

| Tool | Description |
|------|-------------|
| `search_loaded_classes` | Find loaded classes by name pattern |
| `add_watch_point` | Set a method-level watch point (virtual breakpoint) |
| `get_watch_results` | Retrieve captured method calls (args, return value, timing) |
| `list_watch_points` | List all active watch points |
| `remove_watch_point` | Remove a watch point |

Watch points use **ByteBuddy** runtime bytecode instrumentation — no restart needed. Set a watch point on any loaded method, trigger the code path, and inspect what arguments were passed and what was returned.

### Web & HTTP (`WebInspector` + `RequestInspector` — 6 tools)

| Tool | Description |
|------|-------------|
| `get_http_endpoints` | List all `@RequestMapping` endpoints with controllers |
| `invoke_bean_method` | Invoke any bean method at runtime with arguments |
| `get_recent_requests` | Recent HTTP requests from in-memory ring buffer |
| `get_slow_requests` | Slowest requests sorted by duration |
| `get_request_stats` | Request stats: P50/P95/P99 latency, status distribution, error rate |
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

### Infrastructure Inspectors (10 tools)

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
| `get_recent_logs` | `LogInspector` | In-memory Logback log capture (ring buffer) |
| `get_environment_diff` | `EnvironmentInspector` | Active property sources and configuration |

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
│  │    │        21 Inspectors (55 tools)             │      │ │
│  │    │                    │                        │      │ │
│  │  ┌─▼──┐ ┌────┐ ┌──────┐ ┌─────┐ ┌─────┐ ┌─────┐ │      │ │
│  │  │JVM │ │Spng│ │Watch │ │ JMX │ │Reqs │ │Metrc│ │      │ │
│  │  │ 11 │ │ 11 │ │  5   │ │  4  │ │  4  │ │  3  │ │      │ │
│  │  └────┘ └────┘ └──────┘ └─────┘ └─────┘ └─────┘ │      │ │
│  │  ┌────┐ ┌────┐ ┌──────┐ ┌─────┐ ┌─────┐ ┌─────┐ │      │ │
│  │  │Evt │ │Bean│ │Cache │ │ JPA │ │Tasks│ │Logs │ │      │ │
│  │  │ 2  │ │ 3  │ │  1   │ │  1  │ │  2  │ │  1  │ │      │ │
│  │  └────┘ └────┘ └──────┘ └─────┘ └─────┘ └─────┘ │      │ │
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
| **55 tools, zero hard dependencies** | All optional inspectors use `@ConditionalOnClass` — agent JAR never forces a dependency |
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
│       ├── inspector/               # 21 diagnostic inspectors (55 tools)
│       │   ├── JvmInspector.java        # Threads, memory, GC, heap histogram
│       │   ├── SpringInspector.java      # Beans, config, dependencies
│       │   ├── MBeanInspector.java       # JMX MBean browsing
│       │   ├── RequestInspector.java     # HTTP request tracing
│       │   ├── MetricsInspector.java     # Micrometer metrics
│       │   ├── EventInspector.java       # Spring Application Events
│       │   ├── BeanGraphInspector.java   # Dependency graph analysis
│       │   ├── CompilationInspector.java # JIT + memory pool details
│       │   ├── WatchPoint*.java          # ByteBuddy runtime instrumentation
│       │   └── ...                       # DataSource, Cache, JPA, Tasks, etc.
│       ├── engine/                  # Reasoning loop
│       │   └── DebugAgentEngine.java
│       └── web/                     # Embedded UI + REST
│           ├── AgentController.java # SSE streaming endpoints
│           └── ChatPageHtml.java    # Single-file chat UI
│
├── demo/                            # Demo app (Order Management System)
│   └── ...
│
├── e2e-tests/                       # End-to-end test suite (55 tools)
│   ├── fast-e2e-test.js             # Fast batch test runner
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

### Run the demo

```bash
cd spring-debug-agent

# Build
mvn clean install -pl agent -DskipTests
mvn package -pl demo -DskipTests

# Run with an LLM provider (example: ZhipuAI GLM-4.6)
java \
  -Ddebug-agent.llm.base-url=https://open.bigmodel.cn/api/paas/v4 \
  -Ddebug-agent.llm.api-key=YOUR_API_KEY \
  -Ddebug-agent.llm.model=glm-4.6 \
  -jar demo/target/spring-debug-agent-demo-0.1.0-SNAPSHOT.jar
```

Then open `http://localhost:8080/agent` and try asking:

- "Check the JVM memory and GC stats"
- "List all JMX MBean domains and show HikariCP pool attributes"
- "Show me recent HTTP requests with their status codes and latency"
- "Inspect the OrderService bean — what are its dependencies?"
- "Set a watch point on OrderService.getAllOrders"
- "Show the P95 latency for HTTP requests"
- "List all Micrometer metrics with 'jvm' prefix"
- "Check for circular bean dependencies"
- "Trigger GC and show before/after memory comparison"

### E2E Test Suite

All 55 tools are covered by automated E2E tests:

```bash
# 1. Generate test data (orders, HTTP traffic, logs, cache)
bash e2e-data-setup.sh

# 2. Run the fast E2E test (6 groups, ~5 min)
node e2e-tests/fast-e2e-test.js

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
git tag v0.3.0
git push origin v0.3.0
```

The CI pipeline will:
1. Build and GPG-sign the artifact
2. Deploy to Maven Central
3. Create a GitHub Release with auto-generated notes
4. Bump the version to the next SNAPSHOT

## License

MIT
