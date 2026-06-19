#!/usr/bin/env node
/**
 * E2E test for v0.5.0 new tools (32 tools across 10 inspectors).
 * Tests in 8 batches via SSE chat endpoint.
 */

const http = require('http');

const BASE = 'http://localhost:8080';
const TIMEOUT = 90000;

function sendChat(message) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify({ message });
    const req = http.request(`${BASE}/agent/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) },
      timeout: TIMEOUT,
    }, (res) => {
      let buffer = '';
      const toolCalls = [];

      res.on('data', (chunk) => {
        buffer += chunk.toString();
        // Process complete SSE events (separated by \n\n)
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const rawEvent = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);

          const lines = rawEvent.split('\n');
          let eventType = '';
          let eventData = '';
          for (const line of lines) {
            if (line.startsWith('event:')) eventType = line.slice(6).trim();
            else if (line.startsWith('data:')) eventData = line.slice(5).trim();
          }

          if (eventType === 'tool_start') {
            // eventData is a JSON string like "get_redis_info"
            let toolName = eventData.replace(/^"|"$/g, '');
            if (toolName && !toolCalls.includes(toolName)) {
              toolCalls.push(toolName);
            }
          }
        }
      });

      res.on('end', () => {
        resolve({ toolCalls, ok: res.statusCode === 200 });
      });
      res.on('error', (e) => {
        resolve({ toolCalls, ok: false, error: e.message });
      });
    });

    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); resolve({ toolCalls: [], ok: false, timeout: true }); });
    req.write(data);
    req.end();
  });
}

const batches = [
  {
    name: 'Security Inspector (4 tools)',
    message: 'Use these 4 tools and tell me the results: get_security_config, get_authentication_info, get_session_info with includeAttributes=false, get_security_events',
    expect: ['get_security_config', 'get_authentication_info', 'get_session_info', 'get_security_events'],
  },
  {
    name: 'Thread Contention (3 tools)',
    message: 'Use these 3 tools and tell me the results: get_thread_contention, get_lock_owners, get_deadlock_graph',
    expect: ['get_thread_contention', 'get_lock_owners', 'get_deadlock_graph'],
  },
  {
    name: 'SQL Inspector (3 tools)',
    message: 'Use these 3 tools: get_active_sql_queries, get_slow_sql with minAvgMs=0, detect_connection_leak',
    expect: ['get_active_sql_queries', 'get_slow_sql', 'detect_connection_leak'],
  },
  {
    name: 'HTTP Session Inspector (3 tools)',
    message: 'Use these 3 tools: get_http_sessions with includeDetails=false, get_session_attributes with sessionId empty, invalidate_session with sessionId nonexistent',
    expect: ['get_http_sessions', 'get_session_attributes', 'invalidate_session'],
  },
  {
    name: 'Redis Inspector (3 tools)',
    message: 'Use these 3 tools: get_redis_info, get_redis_slowlog with count=5, get_redis_key_info with key test',
    expect: ['get_redis_info', 'get_redis_slowlog', 'get_redis_key_info'],
  },
  {
    name: 'Messaging Inspector (3 tools)',
    message: 'Use these 3 tools: get_kafka_consumers with groupId empty, get_queue_info with queueName empty, get_dead_letter_queues',
    expect: ['get_kafka_consumers', 'get_queue_info', 'get_dead_letter_queues'],
  },
  {
    name: 'Resilience Inspector (4 tools)',
    message: 'Use these 4 tools: get_circuit_breakers with name empty, get_retry_stats, get_rate_limiters, get_rate_limit_stats',
    expect: ['get_circuit_breakers', 'get_retry_stats', 'get_rate_limiters', 'get_rate_limit_stats'],
  },
  {
    name: 'Profiling + Reactive + Migration + Cloud (9 tools)',
    message: 'Use these 9 tools one by one: get_cpu_hotspots with durationSeconds=2, get_allocation_hotspots with durationSeconds=2, get_reactive_streams, get_event_loop_status, get_db_migrations, get_pending_migrations, get_service_discovery, get_config_server_status, get_cloud_circuit_breakers',
    expect: ['get_cpu_hotspots', 'get_allocation_hotspots', 'get_reactive_streams', 'get_event_loop_status', 'get_db_migrations', 'get_pending_migrations', 'get_service_discovery', 'get_config_server_status', 'get_cloud_circuit_breakers'],
  },
];

async function main() {
  console.log(`\n${'='.repeat(70)}`);
  console.log('  v0.5.0 New Tools E2E Test — 32 tools, 8 batches');
  console.log(`${'='.repeat(70)}\n`);

  let passed = 0, failed = 0;
  const failedTools = [];

  for (let i = 0; i < batches.length; i++) {
    const batch = batches[i];
    console.log(`[${i + 1}/${batches.length}] ${batch.name}`);
    process.stdout.write('  Sending chat... ');

    const result = await sendChat(batch.message);

    if (result.timeout) {
      console.log('TIMEOUT');
      for (const t of batch.expect) { failed++; failedTools.push(t); }
      continue;
    }

    const found = result.toolCalls;
    const missing = batch.expect.filter(t => !found.includes(t));

    if (missing.length === 0) {
      console.log(`PASS (${batch.expect.length}/${batch.expect.length}) — [${found.join(', ')}]`);
      passed += batch.expect.length;
    } else {
      console.log(`PARTIAL (${batch.expect.length - missing.length}/${batch.expect.length})`);
      console.log(`  Found:   [${found.join(', ')}]`);
      console.log(`  Missing: [${missing.join(', ')}]`);
      passed += batch.expect.length - missing.length;
      failed += missing.length;
      failedTools.push(...missing);
    }

    await new Promise(r => setTimeout(r, 3000));
  }

  console.log(`\n${'='.repeat(70)}`);
  console.log(`  Result: ${passed}/${passed + failed} tools verified`);
  if (failed > 0) {
    console.log(`  Failed: ${failedTools.join(', ')}`);
  }
  console.log(`${'='.repeat(70)}\n`);

  process.exit(failed > 0 ? 1 : 0);
}

main().catch(e => { console.error(e); process.exit(1); });
