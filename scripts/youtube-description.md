# YouTube 视频描述

---

## 标题

Spring Debug Agent — AI-Powered In-Process Diagnostics for Spring Boot (50 Inspectors / 161 Tools)

---

## 简短描述

Add one dependency. Get an AI debugging assistant that lives INSIDE your Spring Boot app. It can inspect JVM memory, thread dumps, database pools, Redis, Kafka, MongoDB, Elasticsearch, GraphQL, OAuth2, Vault, gRPC — and 140+ more tools — all through natural language chat.

---

## 完整描述

Spring Debug Agent is an open-source debugging companion for Spring Boot 3.x / Java 17+ apps. Add one Maven dependency, and it registers 50 diagnostic inspectors with 161 tools that an embedded LLM can call autonomously.

No external agents. No sidecar processes. No code changes. Just chat with your app at /agent and ask questions in plain English.

Maven Central: dev.ggcode : spring-debug-agent : 0.6.0

In this 61-minute demo, the LLM receives only natural-language questions and must autonomously decide which of 161 tools to call, analyze results, and provide actionable recommendations.

Watch the AI detect memory leaks, diagnose thread deadlocks, inspect HikariCP pools, query MongoDB and Elasticsearch, browse JMX MBeans, check OAuth2 clients and Vault secrets — and auto-compress conversation context when tokens exceed limits.

TIMESTAMPS

0:00 Intro
0:30 01 JVM Deep Dive — memory, GC, thread dump, deadlocks, heap, JIT, classloading
5:00 02 Spring Core — beans, startup time, profiles, properties, circular deps, bean graph
10:00 03 JMX MBean Browser — domains, Tomcat connectors, thread pools
13:00 04 Web & HTTP — endpoints, request stats, latency, filter chain, endpoint testing
17:30 05 Metrics & Events — Micrometer meters, events, listeners
21:00 06 Database & SQL — HikariCP, Hibernate stats, slow queries, transactions, Flyway
26:00 07 Redis & Kafka — server info, slow log, cached keys, topics, consumer groups, lag
31:00 08 MongoDB & Elasticsearch — collections, indexes, cluster health, shards, slow queries
36:00 09 Batch, Quartz & WebSocket — job history, step stats, triggers, WebSocket sessions
40:00 10 GraphQL, OpenAPI & ThreadPools — schema, API spec, pool configs
44:00 11 Security, Cache & Scheduled — filter chain, users/roles, cache ratios, @Scheduled
48:00 12 State Machine & Distributed Cache — Statemachine transitions, Hazelcast cluster
53:00 13 Tracing, gRPC & Cloud Discovery — spans, channel state, service instances
55:30 14 OAuth2, Vault & Object Storage — clients, secret engines, MinIO/S3 buckets
58:00 15 Profiling, Logs & Environment — CPU profile, logs, properties, auto-config
60:00 16 Reactive, Resilience & Health — WebFlux, circuit breakers, actuator health

KEY FEATURES

- Dynamic system prompt: all 161 tools described to the LLM automatically
- Real token tracking via stream_options.include_usage
- Auto context compression: LLM summarizes old tool results when exceeding 100K tokens (not hard truncation)
- Graceful degradation: forces final summary at max rounds instead of erroring
- Conditional registration: only inspectors whose deps are on classpath get loaded

QUICK START

Add to your project: dev.ggcode : spring-debug-agent : 0.6.0

Configure LLM in application.yml:
  debug-agent.llm.base-url = api.openai.com/v1
  debug-agent.llm.api-key = your key
  debug-agent.llm.model = gpt-4o

Start app, open localhost:8080/agent, start chatting.

LINKS

GitHub: github.com/topcheer/spring-debug-agent
Maven Central: dev.ggcode : spring-debug-agent : 0.6.0

#SpringBoot #Java #Debugging #AI #LLM #SpringFramework #DevOps #JVM #OpenSource #DeveloperTools #Diagnostics #ApplicationMonitoring
