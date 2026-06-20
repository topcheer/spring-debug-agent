#!/usr/bin/env node
/**
 * Full end-to-end test for all 55 diagnostic tools.
 *
 * Strategy: send targeted prompts grouped by inspector, collect SSE events,
 * parse which tools were called and whether they returned errors.
 *
 * Usage: node e2e-tool-test.js
 */

const http = require('http');

const BASE = 'http://localhost:8088';
const TIMEOUT_MS = 60000;

// ─── SSE fetcher ─────────────────────────────────────────────────────────

function sendChat(message, sessionId) {
  return new Promise((resolve, reject) => {
    const postData = JSON.stringify({ message, sessionId });
    const req = http.request(`${BASE}/agent/api/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData),
      },
      timeout: TIMEOUT_MS,
    }, (res) => {
      let toolsCalled = [];
      let toolResults = [];
      let contentChunks = [];
      let done = false;
      let error = null;
      let buffer = '';

      res.on('data', (chunk) => {
        buffer += chunk.toString();
        const lines = buffer.split('\n');
        buffer = lines.pop(); // keep incomplete line

        for (const line of lines) {
          if (line.startsWith('event:tool_start')) {
            // next data: line has tool name
          } else if (line.startsWith('event:tool_result')) {
            // next data: line has toolName: preview
          } else if (line.startsWith('data:')) {
            const data = line.substring(5).trim();
            if (data.startsWith('"') || data === '""') {
              // content chunk
              try {
                const text = JSON.parse(data);
                if (text) contentChunks.push(text);
              } catch {}
            } else if (data.includes(': ') && !data.startsWith('{')) {
              // tool_start or tool_result with name prefix
              const colonIdx = data.indexOf(': ');
              const toolName = data.substring(0, colonIdx).trim();
              const rest = data.substring(colonIdx + 2);

              if (!toolsCalled.includes(toolName)) {
                toolsCalled.push(toolName);
              }

              // Check if this is a result (has preview data)
              if (rest.length > 0 || data.endsWith(':')) {
                const isError = rest.includes('"error"') || rest.includes('error');
                toolResults.push({ tool: toolName, preview: rest, isError });
              }
            }
          } else if (line.startsWith('event:done')) {
            done = true;
          } else if (line.startsWith('event:error')) {
            // next data: is the error message
          }
        }
      });

      res.on('end', () => {
        resolve({ toolsCalled, toolResults, content: contentChunks.join(''), done, error });
      });

      res.on('error', (err) => {
        reject(err);
      });
    });

    req.on('error', (err) => reject(err));
    req.write(postData);
    req.end();
  });
}

// ─── Test definitions ────────────────────────────────────────────────────

const ALL_TOOLS = [
  // JVM Core (9)
  'get_thread_summary', 'get_thread_dump', 'detect_deadlocks',
  'get_cpu_consuming_threads', 'get_memory_summary', 'get_gc_stats',
  'get_runtime_info', 'get_heap_histogram', 'get_buffer_pool_stats',

  // Spring Core (7)
  'get_all_beans', 'get_bean_details', 'get_bean_dependencies',
  'get_property', 'search_properties', 'get_active_profiles', 'get_context_info',

  // WatchPoints (7)
  'search_loaded_classes', 'add_watch_point', 'get_watch_results',
  'list_watch_points', 'remove_watch_point', 'get_bean_field_value',

  // Web/HTTP (5)
  'get_http_endpoints', 'invoke_bean_method',
  'get_recent_requests', 'get_slow_requests', 'get_request_stats',

  // JMX (4)
  'list_mbeans', 'get_mbean_attributes', 'get_mbean_attribute', 'invoke_mbean_operation',

  // Infrastructure (10)
  'get_data_source_info', 'get_health_status', 'get_scheduled_tasks',
  'get_async_task_info', 'get_cache_stats', 'get_jpa_query_stats',
  'get_http_client_pool_stats', 'get_feature_flags', 'get_recent_logs',
  'get_environment_diff',

  // New JVM/Spring (8)
  'get_compilation_stats', 'get_memory_pool_details', 'get_memory_manager_stats',
  'get_metrics_list', 'get_metric_value', 'get_meter_registries',
  'get_recent_events', 'get_event_listeners',

  // Bean Graph (3)
  'get_bean_creation_order', 'get_circular_references', 'get_lazy_beans',

  // System Control (1)
  'trigger_gc',
];

// Group prompts to efficiently trigger tools
const TEST_GROUPS = [
  {
    name: 'JVM Core',
    prompt: `Run ALL of these tools in one go and report results: get_thread_summary, get_thread_dump (without stack traces), detect_deadlocks, get_cpu_consuming_threads (top 5), get_memory_summary, get_gc_stats, get_runtime_info, get_heap_histogram, get_buffer_pool_stats. Call all 9 tools.`,
    expectedTools: ['get_thread_summary', 'get_thread_dump', 'detect_deadlocks', 'get_cpu_consuming_threads', 'get_memory_summary', 'get_gc_stats', 'get_runtime_info', 'get_heap_histogram', 'get_buffer_pool_stats'],
  },
  {
    name: 'Spring Core',
    prompt: `Run these tools: get_context_info, get_active_profiles, get_all_beans with typeFilter "Service", then get_bean_details for "orderService", get_bean_dependencies for "orderService", get_property for "server.port", search_properties with keyword "spring". Call all these tools.`,
    expectedTools: ['get_context_info', 'get_active_profiles', 'get_all_beans', 'get_bean_details', 'get_bean_dependencies', 'get_property', 'search_properties'],
  },
  {
    name: 'WatchPoints',
    prompt: `First call search_loaded_classes with pattern "OrderService". Then call list_watch_points to see existing watch points. Then call get_bean_field_value for bean "orderService" and field "logger". Call all 3 tools.`,
    expectedTools: ['search_loaded_classes', 'list_watch_points', 'get_bean_field_value'],
  },
  {
    name: 'Web/HTTP',
    prompt: `Call get_http_endpoints to list all REST endpoints. Then call get_request_stats to see HTTP request statistics. Then call get_recent_requests (limit 5). Then call get_slow_requests. Call all 4 tools.`,
    expectedTools: ['get_http_endpoints', 'get_request_stats', 'get_recent_requests', 'get_slow_requests'],
  },
  {
    name: 'JMX/MBean',
    prompt: `Call list_mbeans to see all JMX domains. Then pick the "java.lang" domain and call get_mbean_attributes for "java.lang:type=Memory". Then call get_mbean_attribute for objectName "java.lang:type=Memory" and attributeName "HeapMemoryUsage". Call all 3 tools.`,
    expectedTools: ['list_mbeans', 'get_mbean_attributes', 'get_mbean_attribute'],
  },
  {
    name: 'Infrastructure',
    prompt: `Call these tools: get_data_source_info, get_health_status, get_scheduled_tasks, get_async_task_info, get_cache_stats, get_jpa_query_stats, get_http_client_pool_stats, get_feature_flags, get_recent_logs (last 5), get_environment_diff. Call ALL 10 tools.`,
    expectedTools: ['get_data_source_info', 'get_health_status', 'get_scheduled_tasks', 'get_async_task_info', 'get_cache_stats', 'get_jpa_query_stats', 'get_http_client_pool_stats', 'get_feature_flags', 'get_recent_logs', 'get_environment_diff'],
  },
  {
    name: 'Metrics & Compilation',
    prompt: `Call these JVM/Metrics tools: get_compilation_stats, get_memory_pool_details, get_memory_manager_stats, get_metrics_list, get_meter_registries. Then get_metric_value for metric name "jvm.memory.used". Call all 6 tools.`,
    expectedTools: ['get_compilation_stats', 'get_memory_pool_details', 'get_memory_manager_stats', 'get_metrics_list', 'get_meter_registries', 'get_metric_value'],
  },
  {
    name: 'Events & Bean Graph',
    prompt: `Call these tools: get_recent_events (last 10), get_event_listeners, get_bean_creation_order, get_circular_references, get_lazy_beans. Call all 5 tools.`,
    expectedTools: ['get_recent_events', 'get_event_listeners', 'get_bean_creation_order', 'get_circular_references', 'get_lazy_beans'],
  },
  {
    name: 'System Control & Invoke',
    prompt: `Call trigger_gc to trigger garbage collection and show before/after memory. Then call invoke_bean_method on bean "orderService" method "findAll" with no arguments. Call both tools.`,
    expectedTools: ['trigger_gc', 'invoke_bean_method'],
  },
];

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  console.log('═══════════════════════════════════════════════');
  console.log('  Spring Debug Agent — Full E2E Tool Test');
  console.log('  Target: 55 tools across 21 inspectors');
  console.log('═══════════════════════════════════════════════\n');

  const allCalledTools = new Set();
  const allToolErrors = [];
  const groupResults = [];

  for (let i = 0; i < TEST_GROUPS.length; i++) {
    const group = TEST_GROUPS[i];
    const sid = `e2e-g${i + 1}`;
    console.log(`[Group ${i + 1}/${TEST_GROUPS.length}] ${group.name}`);
    console.log(`  Prompt: ${group.prompt.substring(0, 80)}...`);

    try {
      const result = await sendChat(group.prompt, sid);
      const called = result.toolsCalled;

      console.log(`  Tools called: ${called.join(', ') || '(none)'}`);

      // Check errors
      const errors = result.toolResults.filter(r => r.isError);
      if (errors.length > 0) {
        console.log(`  ⚠ ERROR results: ${errors.map(e => e.tool).join(', ')}`);
        allToolErrors.push(...errors);
      }

      // Track called tools
      called.forEach(t => allCalledTools.add(t));

      // Check missing
      const missing = group.expectedTools.filter(t => !called.includes(t));
      if (missing.length > 0) {
        console.log(`  ⚠ NOT CALLED: ${missing.join(', ')}`);
      }

      groupResults.push({ name: group.name, called, missing, errors: errors.length });
      console.log('');
    } catch (err) {
      console.log(`  ✗ FAILED: ${err.message}\n`);
      groupResults.push({ name: group.name, called: [], missing: group.expectedTools, errors: 1 });
    }
  }

  // ─── Summary ──────────────────────────────────────────────────────────
  console.log('═══════════════════════════════════════════════');
  console.log('  SUMMARY');
  console.log('═══════════════════════════════════════════════\n');

  const totalExpected = ALL_TOOLS.length;
  const totalCalled = allCalledTools.size;
  const notCalled = ALL_TOOLS.filter(t => !allCalledTools.has(t));

  console.log(`Tools tested:      ${totalCalled}/${totalExpected}`);
  console.log(`Tools with errors: ${allToolErrors.length}`);
  console.log(`Tools NOT called:  ${notCalled.length}`);

  if (notCalled.length > 0) {
    console.log(`\n⚠ NOT CALLED (${notCalled.length}):`);
    notCalled.forEach(t => console.log(`   - ${t}`));
  }

  if (allToolErrors.length > 0) {
    console.log(`\n⚠ ERROR RESULTS (${allToolErrors.length}):`);
    allToolErrors.forEach(e => console.log(`   - ${e.tool}: ${e.preview.substring(0, 100)}`));
  }

  // Group breakdown
  console.log('\n─── Group Breakdown ───');
  for (const g of groupResults) {
    const status = g.missing.length === 0 && g.errors === 0 ? '✅' :
                   g.missing.length === 0 ? '⚠ ' : '✗ ';
    console.log(`  ${status} ${g.name}: ${g.called.length}/${g.called.length + g.missing.length} called, ${g.errors} errors`);
  }

  const allPass = notCalled.length === 0 && allToolErrors.length === 0;
  console.log(`\n${allPass ? '✅ ALL 55 TOOLS PASSED' : '⚠ SOME ISSUES FOUND — see above'}`);
  console.log('═══════════════════════════════════════════════\n');

  process.exit(allPass ? 0 : 1);
})();
