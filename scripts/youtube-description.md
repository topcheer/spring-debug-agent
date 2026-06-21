# YouTube 视频描述

---

## 标题

Spring Debug Agent — AI-Powered In-Process Diagnostics for Spring Boot (66 Inspectors / 246 Tools)

---

## 简短描述

Add one dependency. Get an AI debugging assistant that lives INSIDE your Spring Boot app. It can inspect JVM memory, thread dumps, database pools, Redis, Kafka, MongoDB, Elasticsearch, GraphQL, OAuth2, Vault, Kerberos, RocketMQ, Nacos, Seata, Cassandra, Camel, Spring AOP, OpenFeign — and 240+ more tools — all through natural language chat.

---

## 完整描述

Spring Debug Agent is an open-source debugging companion for Spring Boot 3.x / Java 17+ apps. Add one Maven dependency, and it registers 66 diagnostic inspectors with 246 tools that an embedded LLM can call autonomously.

No external agents. No sidecar processes. No code changes. Just chat with your app at /agent and ask questions in plain English.

Maven Central: dev.ggcode : spring-debug-agent : 0.8.1

In this 65-minute demo, the LLM receives only natural-language questions and must autonomously decide which of 246 tools to call, analyze results, and provide actionable recommendations.

The demo runs against a live Spring Boot app connected to 13 Docker services: Redis, MongoDB, Elasticsearch, Redpanda (Kafka), Vault, MinIO, RocketMQ, Nacos, RabbitMQ, Cassandra, Seata, MIT Kerberos KDC, and Zookeeper.

Watch the AI detect memory leaks, diagnose thread deadlocks, inspect HikariCP pools, query MongoDB and Elasticsearch, browse JMX MBeans, check OAuth2 clients and Vault secrets, inspect Kerberos keytabs, analyze Camel routes, monitor Seata distributed transactions, profile CPU hotspots, and auto-compress conversation context when tokens exceed limits.

TIMESTAMPS

0:00 Intro
2:00 01 JVM Deep Dive — memory, GC, thread dump, deadlocks, heap histogram, DirectByteBuffer, JIT
6:00 02 Spring Core + Modulith — beans, startup time, profiles, properties, circular deps, bean graph, module boundaries
10:00 03 JMX MBean Browser — domains, Tomcat connectors, thread pools, memory attributes
13:00 04 Web + HTTP + Endpoint Testing — endpoints, filter chains, request stats, latency, batch endpoint testing, outbound HTTP
17:00 05 Database + SQL + MyBatis — HikariCP, Hibernate stats, slow queries, connection leaks, MyBatis config, Flyway migrations
21:00 06 Transactions + Thread Pools + Tasks — transaction stats, rollback rate, thread pool configs, @Scheduled, async pool
24:30 07 Redis + Kafka + RabbitMQ + RocketMQ — server info, slow log, consumer groups, queue topology, RocketMQ producer/consumer
28:30 08 MongoDB + Elasticsearch + Cassandra — collections, indexes, cluster health, shards, CQL keyspaces, session stats
32:30 09 Batch + Quartz + Flowable + WebSocket — job history, step stats, triggers, BPMN processes, WebSocket sessions
36:30 10 GraphQL + OpenAPI + gRPC — schema, queries, mutations, API spec, endpoint coverage, gRPC channels
40:00 11 Security + OAuth2 + Sessions — filter chain, password encoder, OAuth2 clients, grant types, HTTP sessions
43:00 12 Kerberos + TLS + LDAP — SPNEGO config, keytab inspection, krb5.conf, certificate expiry, TLS ciphers, LDAP context
47:00 13 Resilience + Sentinel + Profiling + Reactive — circuit breakers, rate limiters, Sentinel flow rules, CPU hotspots, WebFlux
50:30 14 Nacos + Cloud + Zookeeper + Dubbo — service discovery, Nacos config, ZK cluster, Dubbo services, registry status
54:00 15 Seata + Tracing + Camel + State Machine — distributed TX config, spans, Camel routes, state machine transitions
57:00 16 Vault + Object Storage + Distributed Cache — secret engines, MinIO/S3 buckets, Hazelcast cluster members
58:30 17 Metrics + Events + Health — Micrometer meters, event listeners, actuator health, cache hit/miss ratios
59:00 18 AOP + OpenFeign + System Control — @Aspect beans, advice types, proxy beans, Feign clients, thread dump, heap dump
1:02:00 19 Logs + Environment + Classloading + WatchPoints — log stats, property sources, @ConfigurationProperties, loaded classes, ByteBuddy watch points

KEY FEATURES

- Dynamic system prompt: all 246 tools described to the LLM automatically
- Real token tracking via stream_options.include_usage
- Auto context compression: LLM summarizes old tool results when exceeding 100K tokens (not hard truncation)
- Graceful degradation: forces final summary at max rounds instead of erroring
- Conditional registration: only inspectors whose deps are on classpath get loaded
- 66 inspectors covering JVM, Spring, databases, messaging, NoSQL, security, distributed systems, AOP, OpenFeign, and more
- 13 Docker-backed external services for full-stack debugging demos

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

#SpringBoot #Java #Debugging #AI #LLM #SpringFramework #DevOps #JVM #OpenSource #DeveloperTools #Diagnostics #ApplicationMonitoring #Kerberos #Microservices #ApacheCamel #RocketMQ #Nacos #Seata #Cassandra #SpringAOP #OpenFeign
