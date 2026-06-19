#!/usr/bin/env node
/**
 * Full demo recording script for v0.5.0 — covers all 112 tools.
 * Sends sequential chat messages to the agent UI at /agent.
 * Each message exercises a group of related tools.
 *
 * Usage:
 *   node demo-record-v05.js
 *
 * Prerequisites:
 *   - Demo app running on localhost:8080
 *   - Redis + Kafka (Redpanda) containers running
 */

const http = require('http');
const BASE = 'http://localhost:8080';
const PAUSE_MS = 4000; // pause between messages for viewing

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
      res.on('data', (chunk) => {
        buffer += chunk.toString();
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const evt = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          if (evt.includes('event:tool_start')) toolCount++;
        }
      });
      res.on('end', () => resolve({ toolCount, ok: res.statusCode === 200 }));
    });
    req.on('error', () => resolve({ toolCount: 0, ok: false }));
    req.on('timeout', () => { req.destroy(); resolve({ toolCount: 0, ok: false, timeout: true }); });
    req.write(data);
    req.end();
  });
}

const chapters = [
  { title: 'Chapter 1: JVM Overview', msg: 'Give me a comprehensive JVM overview: thread summary, memory, GC stats, and process info' },
  { title: 'Chapter 2: Thread Contention', msg: 'Check thread contention, lock owners, and deadlock graph. Are there any blocked threads?' },
  { title: 'Chapter 3: Spring Beans', msg: 'List all beans, check for circular references, and show bean creation order' },
  { title: 'Chapter 4: Spring Security', msg: 'Show me the security configuration, filter chains, and authentication setup' },
  { title: 'Chapter 5: SQL Deep Dive', msg: 'Show database info, check for slow SQL queries, and detect any connection leaks' },
  { title: 'Chapter 6: HTTP Requests', msg: 'Show recent HTTP requests, error requests, and request statistics' },
  { title: 'Chapter 7: Redis Inspection', msg: 'Show Redis server info, slowlog, and check a specific key' },
  { title: 'Chapter 8: Kafka Messaging', msg: 'Show Kafka consumers, queue info, and dead letter queues' },
  { title: 'Chapter 9: Resilience', msg: 'Check all circuit breakers, retry stats, and rate limiters' },
  { title: 'Chapter 10: CPU Profiling', msg: 'Profile CPU hotspots for 3 seconds and show me the top methods' },
  { title: 'Chapter 11: Memory Allocation', msg: 'Sample memory allocation hotspots for 3 seconds' },
  { title: 'Chapter 12: DB Migrations', msg: 'Show database migration history and any pending migrations' },
  { title: 'Chapter 13: Reactive/Event Loop', msg: 'Check reactive streams status and event loop thread health' },
  { title: 'Chapter 14: Logs', msg: 'Search logs for error and show log statistics' },
  { title: 'Chapter 15: Cache & Transactions', msg: 'Show cache stats and transaction information' },
  { title: 'Chapter 16: API Coverage', msg: 'Batch test all GET endpoints and show endpoint coverage report' },
  { title: 'Chapter 17: Spring Cloud', msg: 'Check service discovery and config server status' },
  { title: 'Chapter 18: Full System Audit', msg: 'Give me a full system health summary: runtime info, metrics, environment, health status' },
];

async function main() {
  console.log(`\n${'='.repeat(70)}`);
  console.log('  v0.5.0 Full Demo Recording — 112 tools across 18 chapters');
  console.log(`${'='.repeat(70)}\n`);

  let totalTools = 0;
  for (let i = 0; i < chapters.length; i++) {
    const ch = chapters[i];
    console.log(`\n${ch.title}`);
    console.log(`  Q: ${ch.msg}`);
    process.stdout.write('  Waiting for response... ');

    const result = await sendChat(ch.msg);
    console.log(`done (${result.toolCount} tool calls)`);
    totalTools += result.toolCount;

    await new Promise(r => setTimeout(r, PAUSE_MS));
  }

  console.log(`\n${'='.repeat(70)}`);
  console.log(`  Complete! ${totalTools} total tool calls across ${chapters.length} chapters`);
  console.log(`${'='.repeat(70)}\n`);
}

main().catch(e => console.error(e));
