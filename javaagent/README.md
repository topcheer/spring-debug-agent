# Spring Debug Agent — Javaagent Mode

Run the AI debugging agent on **any Spring Boot application** with zero code changes — just add a JVM flag:

```bash
java -javaagent:spring-debug-agent-javaagent.jar -jar your-app.jar
```

The agent starts on a **separate port** (default 9900) and never interferes with the host application.

## Quick Start

### 1. Build the javaagent JAR

```bash
cd spring-debug-agent
mvn install -pl agent -Dgpg.skip=true -DskipTests -q
mvn package -pl javaagent -Dgpg.skip=true -DskipTests -q
# Output: javaagent/target/spring-debug-agent-javaagent.jar (~7 MB)
```

### 2. Attach to any Spring Boot app

```bash
# Minimal: just pass the LLM API key
java -javaagent:spring-debug-agent-javaagent.jar \
  -Ddebug-agent.llm.api-key=sk-your-key \
  -jar your-app.jar

# With a non-OpenAI provider (e.g., DeepSeek)
java -javaagent:spring-debug-agent-javaagent.jar \
  -Ddebug-agent.llm.base-url=https://api.deepseek.com/v1 \
  -Ddebug-agent.llm.api-key=sk-your-key \
  -Ddebug-agent.llm.model=deepseek-chat \
  -jar your-app.jar
```

### 3. Open the chat UI

```
http://localhost:9900
```

## Configuration

All settings can be provided via agent args, system properties (`-D`), or environment variables. Priority: agent args > system properties > environment variables.

| Setting | System Property | Env Var | Default |
|---------|----------------|---------|---------|
| Agent HTTP port | `debug-agent.port` | `DEBUG_AGENT_PORT` | `9900` |
| LLM API key | `debug-agent.llm.api-key` | `LLM_API_KEY` or `OPENAI_API_KEY` | — |
| LLM base URL | `debug-agent.llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` |
| LLM model | `debug-agent.llm.model` | `LLM_MODEL` | `gpt-4o` |
| LLM temperature | `debug-agent.llm.temperature` | `LLM_TEMPERATURE` | `0.3` |
| Max tokens | `debug-agent.llm.max-tokens` | `LLM_MAX_TOKENS` | `4096` |
| Max tool rounds | `debug-agent.llm.max-tool-rounds` | `LLM_MAX_TOOL_ROUNDS` | `25` |

### Agent args format

Compact form for `-javaagent:`:

```bash
java -javaagent:agent.jar=port=9900,api-key=sk-xxx,model=gpt-4o -jar app.jar
```

## How It Works

### Architecture

```
JVM Startup
  │
  ├─ premain()                          ByteBuddy intercepts SpringApplication.run()
  │   ├─ Install SpringRunAdvice         Capture ApplicationContext return value
  │   └─ Start watcher thread (daemon)   Poll for context availability
  │
  └─ Watcher thread (once context ready)
      ├─ Create AgentClassLoader          Child-first CL for isolation
      ├─ AgentLauncher.init()             Load agent classes via child CL
      │   ├─ StandaloneInspectorFactory   Create all 246+ tools from context
      │   └─ AgentHttpServer              Start JDK HttpServer on port 9900
      └─ Log "Ready!" message
```

### Isolation Guarantees

1. **Package relocation** — All agent classes relocated to `com.debugagent.internal.*` in the fat JAR. Zero classpath pollution.
2. **Child-first ClassLoader** — Agent classes loaded from isolated CL; Spring/app types resolve through the parent (app) CL.
3. **No logging implementation** — Fat JAR excludes SLF4J implementations. Agent logs go through the host app's Logback/Log4j.
4. **Daemon threads** — All agent threads are daemon; JVM shutdown is never blocked.
5. **Silent failure** — Any agent initialization error is swallowed. The host app is **never** affected.

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /` | GET | Chat UI (single-page app) |
| `GET /api/health` | GET | Health status (mode, toolCount, llmConfigured) |
| `POST /api/chat` | POST | Chat with SSE streaming (`{message, sessionId}`) |
| `POST /api/clear` | POST | Clear conversation (`{sessionId}`) |

### SSE Events

| Event | Data | Description |
|-------|------|-------------|
| `content` | Text chunk | LLM response token |
| `tool_start` | Tool name | Tool invocation started |
| `tool_result` | `toolName: result` | Tool execution result |
| `error` | Error message | Error occurred |
| `done` | (empty) | Stream complete (always sent, even after error) |

## Embedded vs Javaagent

| Feature | Embedded (Maven dep) | Javaagent (-javaagent) |
|---------|---------------------|----------------------|
| Setup | Add dependency to pom.xml | Add JVM flag |
| Code changes | Add dependency | None |
| Port | App's port (e.g., :8080/agent) | Separate port (:9900) |
| UI path | `/agent` | `/` |
| Spring context | Full DI (auto-config) | Captured at runtime |
| Tools | All via auto-config | Same, manual init |
| Use case | Development / debugging | Production debug / zero-code |

## Building from Source

```bash
# Build agent library first, then javaagent fat JAR
cd spring-debug-agent
mvn install -pl agent -Dgpg.skip=true -DskipTests -q
mvn package -pl javaagent -Dgpg.skip=true -DskipTests -q

# Verify the fat JAR
ls -lh javaagent/target/spring-debug-agent-javaagent.jar
unzip -p javaagent/target/spring-debug-agent-javaagent.jar META-INF/MANIFEST.MF
```

### Expected MANIFEST.MF

```
Premain-Class: com.debugagent.javaagent.JavaAgentBootstrap
Agent-Class: com.debugagent.javaagent.JavaAgentBootstrap
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

## Tested Configurations

- **Spring Boot 3.3.x** with 5+ starters (Web, Actuator, JDBC, Cache, H2)
- **Spring Boot 3.3.x** demo app with 28+ starters + 13 Docker services (ES, Redis, Mongo, Kafka, etc.)
- **DeepSeek API** (`deepseek-chat` model) — full chat + tool execution
- **OpenAI API** (`gpt-4o` model) — full chat + tool execution
- **No API key** — app starts normally, agent reports `llmConfigured: false`, chat returns graceful error

## License

MIT
