# Spring Debug Agent

An AI-powered debugging agent that **embeds directly into your Spring Boot application**. Add one dependency, configure an LLM key, and chat with your live application at `/agent` to inspect threads, memory, Spring beans, and set runtime watch points — no external process, no agent attach, no IDE plugin required.

## Why?

Traditional debugging tools (jstack, jmap, JConsole, Arthas) require separate processes, JVM attach permissions, or deep CLI knowledge. Spring Debug Agent puts an AI assistant *inside* your running app. It speaks natural language, understands Spring context, and can reason across multiple diagnostic signals to find root causes faster.

## Demo Videos

Automated recordings of real debugging sessions (click to download `.mp4`):

| Scenario | Description | File |
|----------|-------------|------|
| JVM Health Check | Memory usage + GC statistics inspection | [01-jvm-health-check.mp4](demo-recordings/01-jvm-health-check.mp4) |
| Thread Analysis | Thread overview + deadlock detection with table | [02-thread-analysis.mp4](demo-recordings/02-thread-analysis.mp4) |
| Spring Bean Inspection | List all Service-type beans with details | [03-spring-beans.mp4](demo-recordings/03-spring-beans.mp4) |
| Order Service Debugging | Inspect OrderService live bean fields | [04-order-debug.mp4](demo-recordings/04-order-debug.mp4) |
| Comprehensive Diagnosis | Full health check with markdown summary | [05-comprehensive.mp4](demo-recordings/05-comprehensive.mp4) |

> All recordings were captured automatically via Playwright. See `demo-record.js` to record your own.

## Quick Start

### 1. Add the dependency (Maven)

```xml
<dependency>
    <groupId>com.debugagent</groupId>
    <artifactId>spring-debug-agent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
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

## Supported LLM Providers

Any endpoint that implements the OpenAI `/v1/chat/completions` API:

| Provider | base-url | Models |
|----------|----------|--------|
| OpenAI | `https://api.openai.com/v1` | gpt-4o, gpt-4o-mini, etc. |
| ZhipuAI (GLM) | `https://open.bigmodel.cn/api/paas/v4` | glm-4, glm-5, glm-5.2 |
| DeepSeek | `https://api.deepseek.com/v1` | deepseek-chat, deepseek-coder |
| Moonshot | `https://api.moonshot.cn/v1` | moonshot-v1-128k |
| Ollama (local) | `http://localhost:11434/v1` | llama3, qwen2, mistral |
| vLLM | `http://localhost:8000/v1` | Any hosted model |

## Diagnostic Tools

The agent has 18 built-in tools it can call autonomously:

### JVM Diagnostics (`JvmInspector`)

| Tool | Description |
|------|-------------|
| `get_thread_summary` | Thread state overview (RUNNABLE, WAITING, BLOCKED, etc.) |
| `get_thread_dump` | Full thread dump with stack traces |
| `detect_deadlocks` | Deadlock detection via `ThreadMXBean` |
| `get_cpu_consuming_threads` | Top CPU-consuming threads by time |
| `get_memory_summary` | Heap / non-heap / memory pool usage |
| `get_gc_stats` | GC collection count and time per algorithm |
| `get_runtime_info` | JVM version, uptime, class loading info |

### Spring Diagnostics (`SpringInspector`)

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

### Runtime Watch Points (`WatchPointManager`)

| Tool | Description |
|------|-------------|
| `search_loaded_classes` | Find loaded classes by name pattern |
| `add_watch_point` | Set a method-level watch point (virtual breakpoint) |
| `get_watch_results` | Retrieve captured method calls (args, return value, timing) |
| `list_watch_points` | List all active watch points |
| `remove_watch_point` | Remove a watch point |

Watch points use **ByteBuddy** runtime bytecode instrumentation — no restart needed. Set a watch point on any loaded method, trigger the code path, and inspect what arguments were passed and what was returned.

## Architecture

```
┌──────────────────────────────────────────────────┐
│                  Your Spring Boot App             │
│                                                   │
│  ┌─────────────────────────────────────────────┐ │
│  │           spring-debug-agent                 │ │
│  │                                              │ │
│  │  ┌──────────┐   ┌──────────┐   ┌─────────┐ │ │
│  │  │ Web UI   │──▶│  Engine  │──▶│   LLM   │ │ │
│  │  │ /agent   │   │ (reason) │   │ Client  │ │ │
│  │  └──────────┘   └────┬─────┘   └─────────┘ │ │
│  │                      │                       │ │
│  │    ┌─────────────────┼─────────────────┐    │ │
│  │    │                 │                 │    │ │
│  │  ┌──▼──┐      ┌──────▼─────┐   ┌──────▼──┐ │ │
│  │  │ JVM │      │  Spring    │   │ Watch   │ │ │
│  │  │ Insp│      │  Inspector │   │ Points  │ │ │
│  │  └─────┘      └────────────┘   └─────────┘ │ │
│  └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

### How it works

1. **User** types a question in the chat UI (`/agent`)
2. **Engine** sends the conversation + tool schemas to the LLM
3. **LLM** decides which tools to call (function calling)
4. **ToolExecutor** invokes the tool against the live JVM/Spring context
5. Results are fed back to the LLM for analysis
6. LLM responds with findings — repeat until the question is answered
7. Response streams to the UI via SSE with markdown rendering

## Project Structure

```
spring-debug-agent/
├── pom.xml                          # Parent POM (multi-module)
├── agent/                           # Core library (the dependency users add)
│   ├── pom.xml
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
│       ├── inspector/               # Diagnostic inspectors
│       │   ├── JvmInspector.java    # Threads, memory, GC, deadlocks
│       │   ├── SpringInspector.java # Beans, config, dependencies
│       │   └── WatchPoint*.java     # ByteBuddy runtime instrumentation
│       ├── engine/                  # Reasoning loop
│       │   └── DebugAgentEngine.java
│       └── web/                     # Embedded UI + REST
│           ├── AgentController.java # SSE streaming endpoints
│           └── ChatPageHtml.java    # Single-file chat UI
│
├── demo/                            # Demo app (Order Management System)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/demo/
│       │   ├── OrderManagementApp.java
│       │   ├── entity/              # Order, OrderItem, Customer (JPA)
│       │   ├── repository/          # Spring Data JPA repositories
│       │   ├── service/             # OrderService, CustomerService, PricingService
│       │   ├── controller/          # OrderController (REST API)
│       │   └── config/              # Async config, data initializer
│       └── resources/application.yml
│
├── demo-record.js                   # Playwright automated demo recorder
└── demo-recordings/                 # Recorded demo videos (.webm + .mp4)
```

## Demo Application

The `demo` module is an **Order Management System** with realistic complexity:

- **3 JPA entities** (Customer, Order, OrderItem) with relationships
- **Multi-step transactional service** (`OrderService.createOrder`) with pricing, credit check, async deduction
- **Simulated microservice** (`PricingService`) with network latency
- **Intentional bug** in `CustomerService.upgradeTier` (NPE on null tags)
- **REST API** for order CRUD operations
- **H2 in-memory database** with auto-initialized test data

### Run the demo

```bash
cd spring-debug-agent

# Build
mvn clean package -DskipTests

# Run with an LLM provider (example: ZhipuAI GLM-5.2)
java \
  -Ddebug-agent.llm.base-url=https://open.bigmodel.cn/api/paas/v4 \
  -Ddebug-agent.llm.api-key=YOUR_API_KEY \
  -Ddebug-agent.llm.model=glm-5.2 \
  -jar demo/target/spring-debug-agent-demo-0.1.0-SNAPSHOT.jar
```

Then open `http://localhost:8080/agent` and try asking:

- "Check the current JVM memory usage and GC stats"
- "How many threads are running? Any deadlock risks?"
- "List all Service-type beans"
- "Inspect the OrderService bean fields"
- "Set a watch point on OrderService.createOrder"
- "Run a full health check and summarize in markdown"

### Record your own demo videos

```bash
# Requires: npm install playwright && npx playwright install chromium
node demo-record.js
```

Videos are saved to `demo-recordings/` in `.webm` and can be converted to `.mp4`:

```bash
ffmpeg -i demo-recordings/01-jvm-health-check.webm -c:v libx264 demo-recordings/01-jvm-health-check.mp4
```

## Requirements

- **Java 17+**
- **Spring Boot 3.x**
- An OpenAI-compatible LLM API key

## Build

```bash
mvn clean package -DskipTests
# agent/target/spring-debug-agent-0.1.0-SNAPSHOT.jar        (core library, 79KB)
# demo/target/spring-debug-agent-demo-0.1.0-SNAPSHOT.jar     (executable fat JAR)
```

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Embedded** (not external attach) | Zero friction — works in any IDE, no JVM attach permissions |
| **Custom HTTP client** (not Spring AI) | Minimal dependencies, works with any OpenAI-compatible endpoint |
| **ByteBuddy** (not JDI) | Runtime bytecode enhancement, no separate agent process |
| **Self-contained UI** (no CDN) | Works in enterprise environments with no internet access |
| **Spring Boot Starter** | Auto-configures everything — just add the dependency |

## License

MIT
