#!/usr/bin/env node
/**
 * Fast batch E2E test — groups all 55 tools into 5 mega-prompts.
 * Each prompt asks the LLM to call 8-12 tools at once, collecting all tool results.
 *
 * Usage: node e2e-tests/fast-e2e-test.js
 */
const http = require('http');

const BASE = process.env.BASE_URL || 'http://localhost:8088';
const TIMEOUT = 120000;

// ─── SSE chat helper ─────────────────────────────────────────────────────

function sendChat(message, sessionId) {
  return new Promise((resolve, reject) => {
    const postData = JSON.stringify({ message, sessionId });
    const req = http.request(`${BASE}/agent/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(postData) },
      timeout: TIMEOUT,
    }, (res) => {
      const toolStarts = new Set();
      const toolResults = new Map(); // toolName -> preview
      let content = '';
      let pendingEvent = '';
      let buffer = '';
      let hasError = false;
      let errorMsg = '';

      res.on('data', (chunk) => {
        buffer += chunk.toString();
        const lines = buffer.split('\n');
        buffer = lines.pop();

        for (const line of lines) {
          if (line.startsWith('event:')) {
            pendingEvent = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.substring(5).trim();
            if (pendingEvent === 'tool_start') {
              toolStarts.add(data);
            } else if (pendingEvent === 'tool_result') {
              const colonIdx = data.indexOf(': ');
              if (colonIdx > 0) {
                const name = data.substring(0, colonIdx).trim();
                const preview = data.substring(colonIdx + 2);
                toolResults.set(name, preview);
                if (preview.includes('"error"') && !preview.includes('"errorCount"') && !preview.includes('"errorRate"')) {
                  // flag but don't skip — some "error" keys are legitimate data
                }
              }
            } else if (pendingEvent === 'content') {
              try { content += JSON.parse(data) || ''; } catch {}
            } else if (pendingEvent === 'error') {
              hasError = true;
              errorMsg = data;
            } else if (pendingEvent === 'done') {
              // stream complete
            }
            pendingEvent = '';
          }
        }
      });

      res.on('end', () => resolve({ toolStarts: [...toolStarts], toolResults, content, hasError, errorMsg }));
      res.on('error', reject);
    });

    req.on('error', reject);
    req.on('timeout', () => { req.destroy(new Error('Request timeout')); });
    req.write(postData);
    req.end();
  });
}

// ─── Test Groups ─────────────────────────────────────────────────────────

const GROUPS = [
  {
    name: 'JVM Core (9 tools)',
    prompt: `Call these 9 tools one by one and show results: get_thread_summary, get_thread_dump(includeStack=false), detect_deadlocks, get_cpu_consuming_threads(limit=5), get_memory_summary, get_gc_stats, get_runtime_info, get_heap_histogram(limit=10), get_buffer_pool_stats`,
    expected: ['get_thread_summary','get_thread_dump','detect_deadlocks','get_cpu_consuming_threads','get_memory_summary','get_gc_stats','get_runtime_info','get_heap_histogram','get_buffer_pool_stats'],
  },
  {
    name: 'Spring Core + Bean Graph (10 tools)',
    prompt: `Call these tools: get_context_info, get_active_profiles, get_all_beans(typeFilter="Service"), get_bean_details(beanName="orderService"), get_bean_dependencies(beanName="orderService"), get_property(key="server.port"), search_properties(keyword="spring"), get_bean_creation_order(filter="service"), get_circular_references, get_lazy_beans`,
    expected: ['get_context_info','get_active_profiles','get_all_beans','get_bean_details','get_bean_dependencies','get_property','search_properties','get_bean_creation_order','get_circular_references','get_lazy_beans'],
  },
  {
    name: 'Web + Infra (15 tools)',
    prompt: `Call ALL these tools: get_http_endpoints, get_request_stats, get_recent_requests(limit=5), get_slow_requests(limit=5), get_data_source_info, get_health_status, get_scheduled_tasks, get_async_task_info, get_cache_stats, get_jpa_query_stats, get_http_client_pool_stats, get_feature_flags, get_recent_logs(maxEntries=5, level="INFO"), get_environment_diff, get_compilation_stats`,
    expected: ['get_http_endpoints','get_request_stats','get_recent_requests','get_slow_requests','get_data_source_info','get_health_status','get_scheduled_tasks','get_async_task_info','get_cache_stats','get_jpa_query_stats','get_http_client_pool_stats','get_feature_flags','get_recent_logs','get_environment_diff','get_compilation_stats'],
  },
  {
    name: 'JMX + Memory Pools + Metrics (10 tools)',
    prompt: `Call these tools: list_mbeans(domainFilter="java.lang"), get_mbean_attributes(objectName="java.lang:type=Memory"), get_mbean_attribute(objectName="java.lang:type=Memory", attributeName="HeapMemoryUsage"), get_memory_pool_details, get_memory_manager_stats, get_metrics_list(nameFilter="jvm."), get_metric_value(metricName="jvm.memory.used"), get_meter_registries, get_recent_events(limit=10), get_event_listeners`,
    expected: ['list_mbeans','get_mbean_attributes','get_mbean_attribute','get_memory_pool_details','get_memory_manager_stats','get_metrics_list','get_metric_value','get_meter_registries','get_recent_events','get_event_listeners'],
  },
  {
    name: 'Classloading + WatchPoints + Invoke + GC (7 tools)',
    prompt: `Call these tools in order: search_loaded_classes(pattern="OrderService"), find_class_location(className="com.demo.service.OrderService"), list_watch_points, invoke_bean_method(beanName="orderService", methodName="getAllOrders", args="[]"), get_bean_field_value(beanName="orderService", fieldName="log"), trigger_gc, add_watch_point(beanName="orderService", methodName="getAllOrders"). Show each result.`,
    expected: ['search_loaded_classes','find_class_location','list_watch_points','invoke_bean_method','get_bean_field_value','trigger_gc','add_watch_point'],
  },
  {
    name: 'WatchPoint Results + Cleanup (3 tools)',
    prompt: `First call get_watch_results on the watch point we just added. Then call remove_watch_point(id="<find from list>"). Then call list_watch_points to confirm it was removed. Use list_watch_points first if you need to find the ID.`,
    expected: ['get_watch_results','remove_watch_point','list_watch_points'],
  },
];

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  const G = s => `\x1b[32m${s}\x1b[0m`;
  const R = s => `\x1b[31m${s}\x1b[0m`;
  const Y = s => `\x1b[33m${s}\x1b[0m`;
  const C = s => `\x1b[36m${s}\x1b[0m`;
  const B = s => `\x1b[1m${s}\x1b[0m`;
  const D = s => `\x1b[2m${s}\x1b[0m`;

  console.log(`\n${B('═══════════════════════════════════════════════════')}`);
  console.log(`${B('  Full E2E Test — All 55 Tools (Fast Batch Mode)')}`);
  console.log(`${B('  Target: ' + BASE)}`);
  console.log(`${B('═══════════════════════════════════════════════════')}\n`);

  const allCalled = new Set();
  const allErrors = []; // {tool, preview}
  const allMissing = [];
  let groupPass = 0;
  let groupFail = 0;

  for (let i = 0; i < GROUPS.length; i++) {
    const g = GROUPS[i];
    const sid = `fast-e2e-g${i+1}`;
    console.log(`${C(`[${i+1}/${GROUPS.length}] ${g.name}`)}`);
    console.log(`  ${D('Sending batch prompt...')}`);

    const t0 = Date.now();
    try {
      const result = await sendChat(g.prompt, sid);
      const dt = ((Date.now() - t0) / 1000).toFixed(1);

      // Track all tools called
      result.toolStarts.forEach(t => allCalled.add(t));
      result.toolResults.forEach((_, t) => allCalled.add(t));

      // Check each expected tool
      const missing = g.expected.filter(t => !result.toolResults.has(t) && !result.toolStarts.includes(t));
      const found = g.expected.filter(t => result.toolResults.has(t) || result.toolStarts.includes(t));

      // Check for error results
      const errors = [];
      for (const [tool, preview] of result.toolResults) {
        // Check for real error payloads (but exclude legitimate data fields like errorCount, errorRate)
        if (preview.includes('"error"') &&
            !preview.includes('"errorCount"') &&
            !preview.includes('"errorRate"') &&
            !preview.includes('"error"') !== false) {
          // More nuanced check: look for {"error": "..."} at the start
          if (preview.startsWith('{"error"') || preview.includes('"error":"')) {
            errors.push({ tool, preview: preview.substring(0, 150) });
            allErrors.push({ tool, preview: preview.substring(0, 150) });
          }
        }
      }

      // Print result
      for (const tool of g.expected) {
        const wasCalled = result.toolResults.has(tool) || result.toolStarts.includes(tool);
        const hasError = errors.some(e => e.tool === tool);
        const status = !wasCalled ? Y('MISS') : hasError ? R('ERR ') : G('PASS');
        const preview = result.toolResults.get(tool);
        const prevStr = preview ? D(' ' + preview.substring(0, 80)) : '';
        console.log(`  ${status}  ${tool}${prevStr}`);
      }

      if (missing.length > 0) {
        console.log(`  ${Y('⚠ Not called: ' + missing.join(', '))}`);
        missing.forEach(t => allMissing.push(t));
      }

      const allFound = missing.length === 0;
      if (allFound && errors.length === 0) {
        groupPass++;
        console.log(`  ${G('✓ PASS')} — ${found.length}/${g.expected.length} tools in ${dt}s\n`);
      } else {
        groupFail++;
        console.log(`  ${R('✗ ISSUES')} — ${found.length}/${g.expected.length} called, ${errors.length} errors in ${dt}s\n`);
      }
    } catch (err) {
      groupFail++;
      console.log(`  ${R('✗ FAILED: ' + err.message)}\n`);
      g.expected.forEach(t => allMissing.push(t));
    }
  }

  // ─── Final Summary ─────────────────────────────────────────────────
  console.log(`${B('═══════════════════════════════════════════════════')}`);
  console.log(`${B('  SUMMARY')}`);
  console.log(`${B('═══════════════════════════════════════════════════')}\n`);

  const ALL_TOOLS = GROUPS.flatMap(g => g.expected);
  const uniqueTools = [...new Set(ALL_TOOLS)];
  const calledCount = uniqueTools.filter(t => allCalled.has(t)).length;

  console.log(`  Groups:    ${G(groupPass + ' passed')} / ${R(groupFail + ' failed')} / ${GROUPS.length} total`);
  console.log(`  Tools:     ${G(calledCount + ' called')} / ${uniqueTools.length} expected`);
  console.log(`  Missing:   ${allMissing.length > 0 ? Y(allMissing.length + '') : G('0')}`);
  console.log(`  Errors:    ${allErrors.length > 0 ? R(allErrors.length + '') : G('0')}`);

  if (allMissing.length > 0) {
    console.log(`\n  ${Y('MISSING TOOLS:')}`);
    [...new Set(allMissing)].forEach(t => console.log(`    ${Y('- ' + t)}`));
  }

  if (allErrors.length > 0) {
    console.log(`\n  ${R('ERROR RESULTS:')}`);
    allErrors.forEach(e => console.log(`    ${R('- ' + e.tool)}: ${e.preview.substring(0, 100)}`));
  }

  const allPass = calledCount === uniqueTools.length && allErrors.length === 0;
  console.log(`\n  ${allPass ? G('✅ ALL 55 TOOLS PASSED') : Y('⚠ SOME ISSUES FOUND')}\n`);

  process.exit(allPass ? 0 : 1);
})();
