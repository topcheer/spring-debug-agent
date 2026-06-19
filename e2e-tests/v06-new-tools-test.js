#!/usr/bin/env node
/**
 * E2E test for v0.6.0 new tools (49 tools across 16 inspectors).
 * Only tests inspectors with classpath deps active in demo.
 *
 * Active: MongoDB, ES, WebSocket, Batch, ThreadPool, Modulith, Quartz, GraphQL, OpenAPI
 * Skipped (no classpath): Tracing, gRPC, OAuth2, ObjectStorage, Vault, DistributedCache, StateMachine
 */

const http = require('http');
const BASE = 'http://localhost:8080';
const TIMEOUT = 120000;

function sendChat(message) {
  return new Promise((resolve) => {
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
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const rawEvent = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          const lines = rawEvent.split('\n');
          let eventType = '', eventData = '';
          for (const line of lines) {
            if (line.startsWith('event:')) eventType = line.slice(6).trim();
            else if (line.startsWith('data:')) eventData = line.slice(5).trim();
          }
          if (eventType === 'tool_start') {
            let toolName = eventData.replace(/^"|"$/g, '');
            if (toolName && !toolCalls.includes(toolName)) toolCalls.push(toolName);
          }
        }
      });
      res.on('end', () => resolve({ toolCalls, ok: res.statusCode === 200 }));
    });
    req.on('error', () => resolve({ toolCalls: [], ok: false }));
    req.on('timeout', () => { req.destroy(); resolve({ toolCalls: [], ok: false, timeout: true }); });
    req.write(data);
    req.end();
  });
}

const batches = [
  {
    name: 'MongoDB Inspector (4 tools)',
    message: 'Use these 4 tools and report results: get_mongo_info, get_mongo_collections, get_mongo_slow_queries, get_mongo_indexes with collectionName users',
    expect: ['get_mongo_info', 'get_mongo_collections', 'get_mongo_slow_queries', 'get_mongo_indexes'],
  },
  {
    name: 'Elasticsearch Inspector (3 tools)',
    message: 'Use these 3 tools and report results: get_es_cluster_health, get_es_indices, get_es_slow_queries',
    expect: ['get_es_cluster_health', 'get_es_indices', 'get_es_slow_queries'],
  },
  {
    name: 'WebSocket Inspector (3 tools)',
    message: 'Use these 3 tools and report results: get_websocket_sessions, get_websocket_stats, get_websocket_messages with sessionId test',
    expect: ['get_websocket_sessions', 'get_websocket_stats', 'get_websocket_messages'],
  },
  {
    name: 'Batch Inspector (4 tools)',
    message: 'Use these 4 tools and report results: get_batch_jobs, get_batch_job_executions with jobName importOrder, get_batch_step_stats with jobName importOrder, get_batch_failures',
    expect: ['get_batch_jobs', 'get_batch_job_executions', 'get_batch_step_stats', 'get_batch_failures'],
  },
  {
    name: 'ThreadPool Inspector (3 tools)',
    message: 'Use these 3 tools and report results: get_thread_pools, get_thread_pool_stats with poolName applicationTaskExecutor, get_rejected_tasks',
    expect: ['get_thread_pools', 'get_thread_pool_stats', 'get_rejected_tasks'],
  },
  {
    name: 'Modulith Inspector (3 tools)',
    message: 'Use these 3 tools and report results: get_modules, verify_module_boundaries, get_module_dependencies',
    expect: ['get_modules', 'verify_module_boundaries', 'get_module_dependencies'],
  },
  {
    name: 'Quartz Inspector (3 tools)',
    message: 'Use these 3 tools and report results: get_quartz_jobs, get_quartz_triggers, get_quartz_job_history',
    expect: ['get_quartz_jobs', 'get_quartz_triggers', 'get_quartz_job_history'],
  },
  {
    name: 'GraphQL Inspector (3 tools)',
    message: 'Use these 3 tools and report results: get_graphql_schema, get_graphql_queries, get_graphql_errors',
    expect: ['get_graphql_schema', 'get_graphql_queries', 'get_graphql_errors'],
  },
  {
    name: 'OpenAPI Inspector (3 tools)',
    message: 'Use these 3 tools and report results: get_openapi_spec, validate_openapi, get_api_changelog',
    expect: ['get_openapi_spec', 'validate_openapi', 'get_api_changelog'],
  },
];

async function main() {
  console.log(`\n${'='.repeat(70)}`);
  console.log('  v0.6.0 New Tools E2E Test — 29 active tools, 9 batches');
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
      console.log(`PASS (${batch.expect.length}/${batch.expect.length})`);
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
  if (failed > 0) console.log(`  Failed: ${failedTools.join(', ')}`);
  console.log(`${'='.repeat(70)}\n`);

  process.exit(failed > 0 ? 1 : 0);
}

main().catch(e => { console.error(e); process.exit(1); });
