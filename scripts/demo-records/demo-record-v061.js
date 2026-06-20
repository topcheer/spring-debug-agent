#!/usr/bin/env node
/**
 * Full demo recording script for v0.6.1 — covers all 141 tools across 43 inspectors.
 *
 * This script drives the agent chat UI at http://localhost:8080/agent by sending
 * sequential messages. Each "chapter" exercises a group of related diagnostic tools.
 *
 * Usage:
 *   node demo-record-v061.js
 *
 * Prerequisites:
 *   - Demo app running on localhost:8080
 *   - Containers: Redis, Kafka (Redpanda), MongoDB, Elasticsearch
 *
 * For screen recording, pair with:
 *   screencapture -v /tmp/demo-v061.mp4
 *   or use macOS Cmd+Shift+5 screen recording
 */

const http = require('http');
const BASE = 'http://localhost:8080';
const PAUSE_MS = 5000; // pause between messages for viewing

function sendChat(message) {
  return new Promise((resolve) => {
    const data = JSON.stringify({ message });
    const req = http.request(`${BASE}/agent/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) },
      timeout: 120000,
    }, (res) => {
      let buffer = '';
      let toolCount = 0;
      const toolsCalled = [];
      let contentChunks = 0;
      res.on('data', (chunk) => {
        buffer += chunk.toString();
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const evt = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          if (evt.includes('event:tool_start')) {
            toolCount++;
            const m = evt.match(/data:(.+)/);
            if (m) toolsCalled.push(m[1].trim());
          }
          if (evt.includes('event:content')) contentChunks++;
        }
      });
      res.on('end', () => resolve({ toolCount, toolsCalled, contentChunks, ok: res.statusCode === 200 }));
    });
    req.on('error', () => resolve({ toolCount: 0, toolsCalled: [], contentChunks: 0, ok: false }));
    req.on('timeout', () => { req.destroy(); resolve({ toolCount: 0, toolsCalled: [], contentChunks: 0, ok: false, timeout: true }); });
    req.write(data);
    req.end();
  });
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ═══════════════════════════════════════════════════════════════════
//  25 chapters covering all 141 tools across 43 inspectors
// ═══════════════════════════════════════════════════════════════════

const chapters = [

  // ── Part 1: Core Spring Boot (Ch 1-5) ──────────────────────────
  {
    title: 'Ch 1: Application Health',
    emoji: 'health',
    msg: 'Give me a full health check: application health status, all health components (database, disk, ping), and any issues.',
  },
  {
    title: 'Ch 2: Spring Beans',
    emoji: 'beans',
    msg: 'Show me all Spring beans. Then check for circular dependencies and show the bean creation order. How many beans are there?',
  },
  {
    title: 'Ch 3: Environment & Config',
    emoji: 'config',
    msg: 'Show me the active Spring profiles, environment properties, and any overridden config values. Search for properties containing "spring.data".',
  },
  {
    title: 'Ch 4: Auto-Configuration Report',
    emoji: 'autoconfig',
    msg: 'Show me the Spring Boot auto-configuration report: which conditional configurations passed and which failed.',
  },
  {
    title: 'Ch 5: Spring Security',
    emoji: 'security',
    msg: 'Show me the security configuration overview, filter chains, security rules (which paths require auth), and current authentication details.',
  },

  // ── Part 2: JVM & Diagnostics (Ch 6-9) ─────────────────────────
  {
    title: 'Ch 6: JVM Overview',
    emoji: 'jvm',
    msg: 'Give me a comprehensive JVM overview: thread state summary, memory usage (heap and non-heap), GC statistics, and runtime info (Java version, uptime, processors).',
  },
  {
    title: 'Ch 7: Thread Analysis',
    emoji: 'threads',
    msg: 'Analyze threads in detail: get a full thread dump with stack traces, check for deadlocks, and show CPU time per thread. Are there any blocked or waiting threads?',
  },
  {
    title: 'Ch 8: Lock Contention',
    emoji: 'contention',
    msg: 'Check thread lock contention: which threads are blocked, what locks they are waiting for, and who holds those locks. Show the lock ownership map.',
  },
  {
    title: 'Ch 9: Memory Deep Dive',
    emoji: 'memory',
    msg: 'Show me memory pool details, heap histogram (top object types by count and size), and DirectByteBuffer/MappedByteBuffer pool stats.',
  },

  // ── Part 3: Performance Profiling (Ch 10-12) ──────────────────
  {
    title: 'Ch 10: CPU Profiling',
    emoji: 'profiling',
    msg: 'Profile CPU hotspots: sample thread stack traces for 3 seconds and show me the top methods consuming CPU.',
  },
  {
    title: 'Ch 11: Memory Allocation',
    emoji: 'alloc',
    msg: 'Sample memory allocation hotspots for 3 seconds and show me which methods allocate the most objects.',
  },
  {
    title: 'Ch 12: Compilation & Classloading',
    emoji: 'compile',
    msg: 'Show me JIT compilation statistics and classloader information: loaded class count, total loaded, unloaded, and classloader hierarchy.',
  },

  // ── Part 4: Web Layer (Ch 13-15) ──────────────────────────────
  {
    title: 'Ch 13: HTTP Endpoints',
    emoji: 'endpoints',
    msg: 'List all registered HTTP endpoints (REST API routes) with their controller class and method names. Then batch-test all GET endpoints.',
  },
  {
    title: 'Ch 14: Request Analysis',
    emoji: 'requests',
    msg: 'Show me recent HTTP requests, the slowest requests, and request statistics (total requests, status code distribution, average/P95/P99 latency).',
  },
  {
    title: 'Ch 15: Filter Chains',
    emoji: 'filters',
    msg: 'List all registered servlet filter chains and show which filters apply to which URL patterns.',
  },

  // ── Part 5: Database & SQL (Ch 16-18) ─────────────────────────
  {
    title: 'Ch 16: Database Connections',
    emoji: 'database',
    msg: 'Show database connection pool statistics (active, idle, total connections, max pool size) and check for connection leaks.',
  },
  {
    title: 'Ch 17: SQL & JPA',
    emoji: 'sql',
    msg: 'Show active SQL queries, slow SQL queries from Hibernate statistics, and JPA query statistics including any potential N+1 issues.',
  },
  {
    title: 'Ch 18: DB Migrations',
    emoji: 'migration',
    msg: 'Show database migration status (applied migrations, schema version, history) and any pending migrations from Flyway.',
  },

  // ── Part 6: Data Stores (Ch 19-22) ────────────────────────────
  {
    title: 'Ch 19: Redis Inspection',
    emoji: 'redis',
    msg: 'Show me Redis server info (version, memory, connected clients, keyspace), check the Redis slowlog, and inspect a specific cache key.',
  },
  {
    title: 'Ch 20: MongoDB Inspection',
    emoji: 'mongo',
    msg: 'Show me MongoDB server info (version, connections, storage engine). Then list all collections with document counts, and show indexes for the audit_logs collection.',
  },
  {
    title: 'Ch 21: Elasticsearch',
    emoji: 'es',
    msg: 'Show me Elasticsearch cluster health (status, nodes, shards), list all indices with document counts and sizes, and check slow query settings.',
  },
  {
    title: 'Ch 22: Kafka Messaging',
    emoji: 'kafka',
    msg: 'Show Kafka consumer group info (topic assignments, lag, offsets), message queue info, and check for any dead letter queues.',
  },

  // ── Part 7: v0.6.0 New Features (Ch 23-25) ────────────────────
  {
    title: 'Ch 23: Batch, Quartz & WebSocket',
    emoji: 'batch',
    msg: 'Show me: (1) Spring Batch jobs and their execution history, (2) Quartz jobs and triggers, (3) WebSocket session stats.',
  },
  {
    title: 'Ch 24: GraphQL & OpenAPI',
    emoji: 'graphql',
    msg: 'Show me the GraphQL schema (query types, mutation types, all fields). Then show the OpenAPI spec (paths, operations, schemas) and validate it for issues.',
  },
  {
    title: 'Ch 25: Thread Pools, Cache & Transactions',
    emoji: 'summary',
    msg: 'Show me: (1) All thread pool configurations and their live stats, (2) Spring Cache statistics (hit/miss ratios), (3) Transaction statistics (commits, rollbacks, rollback rate).',
  },
];

async function main() {
  console.log(`\n${'='.repeat(75)}`);
  console.log('  v0.6.1 Full Demo Recording');
  console.log('  141 tools / 43 inspectors / 25 chapters');
  console.log(`${'='.repeat(75)}\n`);

  console.log('  Make sure your screen recorder is running!');
  console.log('  Press Enter to start...');
  console.log('');

  // Auto-start after 3 seconds if no input
  await sleep(3000);

  let totalTools = 0;
  const startTime = Date.now();

  for (let i = 0; i < chapters.length; i++) {
    const ch = chapters[i];
    console.log(`\n  [${i + 1}/${chapters.length}] ${ch.title}`);
    console.log(`       Q: ${ch.msg.slice(0, 80)}...`);
    process.stdout.write('       Sending... ');

    const result = await sendChat(ch.msg);

    if (result.timeout) {
      console.log('TIMEOUT');
    } else if (!result.ok) {
      console.log('FAILED');
    } else {
      console.log(`${result.toolCount} tools called, ${result.contentChunks} content chunks`);
      if (result.toolsCalled.length > 0) {
        console.log(`       Tools: ${result.toolsCalled.join(', ')}`);
      }
      totalTools += result.toolCount;
    }

    // Pause between chapters for viewing
    await sleep(PAUSE_MS);
  }

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

  console.log(`\n${'='.repeat(75)}`);
  console.log(`  Demo Complete!`);
  console.log(`  ${totalTools} total tool calls across ${chapters.length} chapters`);
  console.log(`  Total time: ${elapsed}s`);
  console.log(`${'='.repeat(75)}\n`);
}

main().catch(e => console.error('Fatal:', e));
