# YouTube Video Description

---

## Title

Spring Debug Agent — AI-Powered In-Process Diagnostics for Spring Boot (66 Inspectors / 246 Tools)

---

## Short Description

Add one dependency. Get an AI debugging assistant that lives INSIDE your Spring Boot app. It can inspect JVM memory, thread dumps, database pools, Redis, Kafka, MongoDB, Elasticsearch, GraphQL, OAuth2, Vault, Kerberos, RocketMQ, Nacos, Seata, Cassandra, Camel, Spring AOP, OpenFeign — and 240+ more tools — all through natural language chat.

---

## Full Description

Spring Debug Agent is an open-source debugging companion for Spring Boot 3.x / Java 17+ apps. Add one Maven dependency, and it registers 66 diagnostic inspectors with 246 tools that an embedded LLM can call autonomously.

No external agents. No sidecar processes. No code changes. Just chat with your app at /agent and ask questions in plain English.

Maven Central: dev.ggcode : spring-debug-agent : 0.8.1

In this 65-minute demo, the LLM receives only natural-language questions and must autonomously decide which of 246 tools to call, analyze results, and provide actionable recommendations.

The demo runs against a live Spring Boot app connected to 13 Docker services: Redis, MongoDB, Elasticsearch, Redpanda (Kafka), Vault, MinIO, RocketMQ, Nacos, RabbitMQ, Cassandra, Seata, MIT Kerberos KDC, and Zookeeper.

Watch the AI detect memory leaks, diagnose thread deadlocks, inspect HikariCP pools, query MongoDB and Elasticsearch, browse JMX MBeans, check OAuth2 clients and Vault secrets, inspect Kerberos keytabs, analyze Camel routes, monitor Seata distributed transactions, profile CPU hotspots, and auto-compress conversation context when tokens exceed limits.

TIMESTAMPS

0:00 Intro
2:00 01 JVM Deep Dive — memory, GC, thread dump, deadlocks, heap histogram, DirectByteBuffer, JIT
6:00 02 Spring Core + Modulith — beans, startup time, profiles, properties, circular deps, bean graph
10:00 03 JMX MBean Browser — domains, Tomcat connectors, thread pools, memory attributes
13:00 04 Web + HTTP + Endpoint Testing — endpoints, filter chains, request stats, latency
17:00 05 Database + SQL + MyBatis — HikariCP, Hibernate stats, slow queries, Flyway migrations
21:00 06 Transactions + Thread Pools + Tasks — transaction stats, rollback rate, async pool
24:30 07 Redis + Kafka + RabbitMQ + RocketMQ — server info, slow log, consumer groups
28:30 08 MongoDB + Elasticsearch + Cassandra — collections, indexes, cluster health, CQL keyspaces
32:30 09 Batch + Quartz + Flowable + WebSocket — job history, step stats, triggers, BPMN processes
36:30 10 GraphQL + OpenAPI + gRPC — schema, queries, mutations, API spec, gRPC channels
40:00 11 Security + OAuth2 + Sessions — filter chain, password encoder, OAuth2 clients
43:00 12 Kerberos + TLS + LDAP — SPNEGO config, keytab inspection, certificate expiry, TLS ciphers
47:00 13 Resilience + Sentinel + Profiling + Reactive — circuit breakers, CPU hotspots, WebFlux
50:30 14 Nacos + Cloud + Zookeeper + Dubbo — service discovery, Nacos config, ZK cluster
54:00 15 Seata + Tracing + Camel + State Machine — distributed TX, spans, Camel routes
57:00 16 Vault + Object Storage + Distributed Cache — secret engines, MinIO/S3, Hazelcast
58:30 17 Metrics + Events + Health — Micrometer meters, event listeners, actuator health
59:00 18 AOP + OpenFeign + System Control — @Aspect beans, Feign clients, thread dump, heap dump
1:02:00 19 Logs + Environment + Classloading + WatchPoints — log stats, loaded classes, ByteBuddy

KEY FEATURES

- Dynamic system prompt: all 246 tools described to the LLM automatically
- Real token tracking via stream_options.include_usage
- Auto context compression: LLM summarizes old tool results when exceeding 100K tokens
- Graceful degradation: forces final summary at max rounds instead of erroring
- Conditional registration: only inspectors whose deps are on classpath get loaded
- 66 inspectors covering JVM, Spring, databases, messaging, NoSQL, security, distributed systems

QUICK START

Add to your project: dev.ggcode : spring-debug-agent : 0.8.1

Configure LLM in application.yml:
  debug-agent.llm.base-url = api.openai.com/v1
  debug-agent.llm.api-key = your key
  debug-agent.llm.model = gpt-4o

Start app, open localhost:8080/agent, start chatting.

LINKS

GitHub: github.com/topcheer/spring-debug-agent
Maven Central: dev.ggcode : spring-debug-agent : 0.8.1

#SpringBoot #Java #Debugging #AI #LLM #SpringFramework #DevOps #JVM #OpenSource #DeveloperTools #Diagnostics #ApplicationMonitoring #Microservices #ApacheCamel #RocketMQ #Nacos #Seata #Cassandra #SpringAOP #OpenFeign
