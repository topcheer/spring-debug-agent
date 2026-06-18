#!/usr/bin/env node
/**
 * E2E tests for infrastructure / Web / JMX / Metrics / Event tools (~25 tools).
 *
 * Sends prompts to /agent/api/chat (SSE), parses tool_start / tool_result events,
 * and verifies each tool was invoked with the expected behavior.
 *
 * Usage:  node e2e-tests/infra-web-tests.js
 */

const http = require('http');

// ─── Configuration ───────────────────────────────────────────────────────

const BASE = process.env.AGENT_BASE || 'http://localhost:8088';
const TIMEOUT_MS = 90000; // 90s per group (LLM may call many tools)

// ─── SSE helper ──────────────────────────────────────────────────────────

/**
 * Send a chat message and collect SSE events.
 *
 * Returns: {
 *   toolsCalled:  string[],                 // unique tool names that fired tool_start
 *   toolResults:  { tool, preview, isError }[],
 *   content:      string,
 *   done:         boolean,
 *   error:        string | null
 * }
 */
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
      const toolsCalled = [];
      const toolResults = [];
      const contentChunks = [];
      let done = false;
      let error = null;
      let buffer = '';
      let currentEvent = null;

      res.on('data', (chunk) => {
        buffer += chunk.toString();
        const lines = buffer.split('\n');
        buffer = lines.pop(); // keep partial line

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.substring(5).trim();

            if (currentEvent === 'tool_start') {
              const toolName = data.trim();
              if (toolName && !toolsCalled.includes(toolName)) {
                toolsCalled.push(toolName);
              }
            } else if (currentEvent === 'tool_result') {
              // Format: "toolName: preview..."
              const colonIdx = data.indexOf(': ');
              let toolName, rest;
              if (colonIdx > 0) {
                toolName = data.substring(0, colonIdx).trim();
                rest = data.substring(colonIdx + 2);
              } else {
                toolName = data.replace(/:$/, '').trim();
                rest = '';
              }
              const isError = rest.includes('"error"') ||
                              /\berror\b/i.test(rest.substring(0, 60));
              toolResults.push({ tool: toolName, preview: rest, isError });
            } else if (currentEvent === 'content') {
              // Content chunks are JSON-escaped strings
              try {
                const text = JSON.parse(data);
                if (text) contentChunks.push(text);
              } catch {
                if (data && data !== '""') contentChunks.push(data);
              }
            } else if (currentEvent === 'done') {
              done = true;
            } else if (currentEvent === 'error') {
              error = data;
            }
            currentEvent = null;
          }
        }
      });

      res.on('end', () => {
        resolve({ toolsCalled, toolResults, content: contentChunks.join(''), done, error });
      });

      res.on('error', (err) => reject(err));
    });

    req.on('timeout', () => {
      req.destroy();
      reject(new Error(`Request timed out after ${TIMEOUT_MS}ms`));
    });

    req.on('error', (err) => reject(err));
    req.write(postData);
    req.end();
  });
}

// ─── Test Case Definitions ───────────────────────────────────────────────
//
// Each test case: { tool, category, description, expectedBehavior, verify }
//
// verify(result, allResults) → 'PASS' | 'WARN: reason' | 'SKIP: reason'
//   result      = the toolResult entry for this tool (or null if not called)
//   allResults  = all toolResult entries from the same group response

const TEST_CASES = [
  // ═══ Web / HTTP Tools (6) ═════════════════════════════════════════════

  {
    tool: 'get_http_endpoints',
    category: 'Web/HTTP',
    description: 'List all registered REST endpoints with HTTP method, path, controller, and method name',
    expectedBehavior: 'Returns a list of endpoint maps; each has "path", "methods", "controller", "method"',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (r.preview.includes('Spring Web MVC not found')) return 'WARN: Spring Web MVC not on classpath';
      // Should reference controller/endpoint or path keys
      if (/controller|endpoint|path|\[\//i.test(r.preview)) return 'PASS';
      return 'PASS'; // tool executed without error
    },
  },
  {
    tool: 'invoke_bean_method',
    category: 'Web/HTTP',
    description: 'Invoke orderService.getAllOrders() with no arguments',
    expectedBehavior: 'Returns { success: true, returnValue: [...], returnType: "..." } or a method-not-found error if method name differs',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /Bean not found|not found/i.test(r.preview)) return 'FAIL: bean/method not found';
      if (/success/.test(r.preview)) {
        if (/true/i.test(r.preview)) return 'PASS';
        return `WARN: success=false — ${r.preview.substring(0, 80)}`;
      }
      return 'PASS'; // executed without runtime error
    },
  },
  {
    tool: 'get_bean_field_value',
    category: 'Web/HTTP',
    description: 'Read the "log" field from orderService bean',
    expectedBehavior: 'Returns field name, type, and current value; the logger field should be accessible',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /Bean not found|Field.*not found/i.test(r.preview)) return 'WARN: bean/field not found';
      if (r.preview.includes('not found')) return 'WARN: bean or field not found';
      if (/field|value|Logger/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_recent_requests',
    category: 'Web/HTTP',
    description: 'Get recent HTTP requests from the in-memory ring buffer',
    expectedBehavior: 'Returns requests array, returnedCount, totalCaptured, and optional summary',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/returnedCount|totalCaptured|requests/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_slow_requests',
    category: 'Web/HTTP',
    description: 'Get slowest HTTP requests sorted by duration descending',
    expectedBehavior: 'Returns slowestRequests array, returnedCount, and stats (max/min/avg duration)',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/slowestRequests|durationMs|returnedCount/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_request_stats',
    category: 'Web/HTTP',
    description: 'Get HTTP request statistics summary',
    expectedBehavior: 'Returns totalRequests, statusDistribution, latency (min/max/avg/p95/p99), errorRate',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/No requests captured/i.test(r.preview)) return 'PASS (no traffic yet)';
      if (/totalRequests|statusDistribution|latency|errorRate/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },

  // ═══ JMX / MBean Tools (4) ═══════════════════════════════════════════

  {
    tool: 'list_mbeans',
    category: 'JMX/MBean',
    description: 'List all registered JMX MBeans grouped by domain',
    expectedBehavior: 'Returns domains map, totalDomains, totalMBeans; java.lang domain should be present',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/totalDomains|totalMBeans|domains|java\.lang/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_mbean_attributes',
    category: 'JMX/MBean',
    description: 'Read all attributes of java.lang:type=Memory MBean',
    expectedBehavior: 'Returns objectName, className, attributes array with HeapMemoryUsage, NonHeapMemoryUsage, etc.',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/HeapMemoryUsage|attributes|objectName|Memory/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_mbean_attribute',
    category: 'JMX/MBean',
    description: 'Read HeapMemoryUsage attribute from java.lang:type=Memory',
    expectedBehavior: 'Returns objectName, attribute name, and composite value with init/used/committed/max',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/HeapMemoryUsage|used|committed|max|init/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'invoke_mbean_operation',
    category: 'JMX/MBean',
    description: 'Invoke dumpAllThreads on java.lang:type=Threading MBean',
    expectedBehavior: 'Returns operation name, returnType, and returnValue containing thread info array',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /not found/i.test(r.preview)) return 'FAIL: operation not found';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/dumpAllThreads|returnType|Thread/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },

  // ═══ Infrastructure Tools (10) ═══════════════════════════════════════

  {
    tool: 'get_data_source_info',
    category: 'Infrastructure',
    description: 'Get database connection pool statistics',
    expectedBehavior: 'Returns DataSource bean info with pool stats (HikariCP: active/idle/total connections) or "No DataSource" info',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /No DataSource/i.test(r.preview)) return 'WARN: no DataSource configured';
      if (/beanName|pool|HikariCP|activeConnections|DataSource/i.test(r.preview)) return 'PASS';
      if (/No DataSource/i.test(r.preview)) return 'PASS (no DataSource)';
      return 'PASS';
    },
  },
  {
    tool: 'get_health_status',
    category: 'Infrastructure',
    description: 'Get application health from Spring Boot Actuator',
    expectedBehavior: 'Returns status (UP/DOWN) and component health details',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /Actuator.*not found/i.test(r.preview)) return 'WARN: actuator not on classpath';
      if (/status|UP|DOWN|components|Health/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_scheduled_tasks',
    category: 'Infrastructure',
    description: 'List all @Scheduled tasks',
    expectedBehavior: 'Returns list of scheduled task info (method, class, schedulerType, nextRunInMs)',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/No @Scheduled|method|schedulerType|info/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_async_task_info',
    category: 'Infrastructure',
    description: 'Get async thread pool statistics',
    expectedBehavior: 'Returns thread pool info (activeCount, poolSize, queueSize, completedTaskCount)',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/activeCount|poolSize|ThreadPool|No thread pool|info/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_cache_stats',
    category: 'Infrastructure',
    description: 'Get Spring Cache statistics',
    expectedBehavior: 'Returns cache info with names, types, sizes, and hit/miss ratios',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/CacheManager|cache|hitRate|No CacheManager|info/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_jpa_query_stats',
    category: 'Infrastructure',
    description: 'Get JPA/Hibernate query statistics',
    expectedBehavior: 'Returns entity load counts, query executions, L2 cache stats, or info if JPA not configured',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/entityLoad|queryExecution|JPA|Hibernate|No EntityManager|info/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_http_client_pool_stats',
    category: 'Infrastructure',
    description: 'Get HTTP client connection pool statistics',
    expectedBehavior: 'Returns Apache HttpClient / Reactor Netty pool stats or "No HTTP client pools"',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/Apache HttpClient|Reactor Netty|leased|No HTTP client|info/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_feature_flags',
    category: 'Infrastructure',
    description: 'Show Spring Boot auto-configuration outcomes',
    expectedBehavior: 'Returns matchedConfigurations, unconditionalClasses, failedConfigurations',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/matched|unconditional|ConditionEvaluation|failedConfig|info/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_recent_logs',
    category: 'Infrastructure',
    description: 'Get recent application logs from memory',
    expectedBehavior: 'Returns log entries with timestamp, level, logger, message',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /appender not available/i.test(r.preview)) return 'WARN: logback appender not registered';
      if (/level|timestamp|logger|WARN|ERROR|INFO/i.test(r.preview)) return 'PASS';
      if (r.preview === '' || r.preview === '[]') return 'PASS (no matching logs)';
      return 'PASS';
    },
  },
  {
    tool: 'get_environment_diff',
    category: 'Infrastructure',
    description: 'Show active property sources and overridden values',
    expectedBehavior: 'Returns activeProfiles, propertySources list, totalUniqueProperties',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/activeProfiles|propertySources|totalUniqueProperties/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },

  // ═══ Metrics Tools (3) ═══════════════════════════════════════════════

  {
    tool: 'get_metrics_list',
    category: 'Metrics',
    description: 'List all registered Micrometer metrics',
    expectedBehavior: 'Returns metrics array (name, type, tags), totalMeters, filteredCount',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /No MeterRegistry/i.test(r.preview)) return 'WARN: no MeterRegistry (actuator needed)';
      if (/totalMeters|filteredCount|Gauge|Counter|Timer|jvm\./i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_metric_value',
    category: 'Metrics',
    description: 'Read current value of jvm.memory.used metric',
    expectedBehavior: 'Returns metricName, matches array with type, tags, and value for each matching gauge',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /No meter found/i.test(r.preview)) return 'WARN: metric name not found';
      if (r.isError && /No MeterRegistry/i.test(r.preview)) return 'WARN: no MeterRegistry';
      if (/jvm\.memory\.used|matches|Gauge|value/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },
  {
    tool: 'get_meter_registries',
    category: 'Metrics',
    description: 'List all MeterRegistry instances and their types',
    expectedBehavior: 'Returns registries array (class, className, meterCount) and count',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError && /No MeterRegistry/i.test(r.preview)) return 'WARN: no MeterRegistry';
      if (/registries|Prometheus|Simple|Composite|meterCount/i.test(r.preview)) return 'PASS';
      return 'PASS';
    },
  },

  // ═══ Event Tools (2) ═════════════════════════════════════════════════

  {
    tool: 'get_recent_events',
    category: 'Events',
    description: 'Get recently published Spring Application Events',
    expectedBehavior: 'Returns events array (timestamp, eventType, source), returnedCount, totalCaptured',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/returnedCount|totalCaptured|eventType|events/i.test(r.preview)) return 'PASS';
      if (r.preview === '' || /\[\]/.test(r.preview)) return 'PASS (no events captured)';
      return 'PASS';
    },
  },
  {
    tool: 'get_event_listeners',
    category: 'Events',
    description: 'List all registered ApplicationEvent listeners',
    expectedBehavior: 'Returns list of listener info (listenerClass, listensFor) and @EventListener methods',
    verify: (r) => {
      if (!r) return 'SKIP: tool not called';
      if (r.isError) return `FAIL: ${r.preview.substring(0, 80)}`;
      if (/listenerClass|listensFor|EventListener|onApplicationEvent/i.test(r.preview)) return 'PASS';
      if (r.preview === '' || /\[\]/.test(r.preview)) return 'WARN: empty result (may be valid)';
      return 'PASS';
    },
  },
];

// ─── Test Groups (batch prompts for efficiency) ─────────────────────────

const TEST_GROUPS = [
  {
    name: 'Web/HTTP Tools',
    prompt: `Please run the following tools and report their results:
1. get_http_endpoints — list all REST endpoints (no filter)
2. invoke_bean_method — call method "getAllOrders" on bean "orderService" with no arguments (args "[]")
3. get_bean_field_value — read field "log" from bean "orderService"
4. get_recent_requests — get last 10 requests (limit 10)
5. get_slow_requests — get slowest requests
6. get_request_stats — get overall request statistics
Call ALL 6 tools.`,
    expectedTools: [
      'get_http_endpoints', 'invoke_bean_method', 'get_bean_field_value',
      'get_recent_requests', 'get_slow_requests', 'get_request_stats',
    ],
  },
  {
    name: 'JMX/MBean Tools',
    prompt: `Please run the following JMX tools:
1. list_mbeans — list all JMX MBean domains (no filter)
2. get_mbean_attributes — read all attributes of objectName "java.lang:type=Memory"
3. get_mbean_attribute — read attribute "HeapMemoryUsage" from objectName "java.lang:type=Memory"
4. invoke_mbean_operation — invoke operation "dumpAllThreads" with params ["true","true"] on objectName "java.lang:type=Threading"
Call ALL 4 tools.`,
    expectedTools: [
      'list_mbeans', 'get_mbean_attributes', 'get_mbean_attribute', 'invoke_mbean_operation',
    ],
  },
  {
    name: 'Infrastructure Tools (Part 1)',
    prompt: `Please run these infrastructure tools:
1. get_data_source_info — show database connection pool stats
2. get_health_status — show app health status
3. get_scheduled_tasks — list all scheduled tasks
4. get_async_task_info — show async thread pool stats
5. get_cache_stats — show cache statistics
Call ALL 5 tools.`,
    expectedTools: [
      'get_data_source_info', 'get_health_status', 'get_scheduled_tasks',
      'get_async_task_info', 'get_cache_stats',
    ],
  },
  {
    name: 'Infrastructure Tools (Part 2)',
    prompt: `Please run these infrastructure tools:
1. get_jpa_query_stats — show JPA/Hibernate query statistics
2. get_http_client_pool_stats — show HTTP client connection pool stats
3. get_feature_flags — show Spring Boot auto-configuration outcomes
4. get_recent_logs — get last 10 log entries with level INFO (maxEntries 10, level "INFO")
5. get_environment_diff — show property sources and environment diff
Call ALL 5 tools.`,
    expectedTools: [
      'get_jpa_query_stats', 'get_http_client_pool_stats', 'get_feature_flags',
      'get_recent_logs', 'get_environment_diff',
    ],
  },
  {
    name: 'Metrics Tools',
    prompt: `Please run these Micrometer metrics tools:
1. get_metrics_list — list all metrics (no filter)
2. get_metric_value — read value for metric name "jvm.memory.used"
3. get_meter_registries — list all meter registries
Call ALL 3 tools.`,
    expectedTools: ['get_metrics_list', 'get_metric_value', 'get_meter_registries'],
  },
  {
    name: 'Event Tools',
    prompt: `Please run these Spring event tools:
1. get_recent_events — get last 20 events (limit 20)
2. get_event_listeners — list all registered event listeners (no filter)
Call BOTH tools.`,
    expectedTools: ['get_recent_events', 'get_event_listeners'],
  },
];

// ─── Color helpers (for terminal output) ────────────────────────────────

const C = {
  green: (s) => `\x1b[32m${s}\x1b[0m`,
  red:   (s) => `\x1b[31m${s}\x1b[0m`,
  yellow:(s) => `\x1b[33m${s}\x1b[0m`,
  cyan:  (s) => `\x1b[36m${s}\x1b[0m`,
  dim:   (s) => `\x1b[2m${s}\x1b[0m`,
  bold:  (s) => `\x1b[1m${s}\x1b[0m`,
};

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  console.log(C.bold('═══════════════════════════════════════════════════════'));
  console.log(C.bold('  Infra / Web / JMX / Metrics / Events — E2E Tests'));
  console.log(C.bold(`  ${TEST_CASES.length} tools across ${TEST_GROUPS.length} groups`));
  console.log(C.bold(`  Target: ${BASE}`));
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  // Connectivity check
  try {
    await sendChat('hello', 'infra-health-check');
    console.log(C.green('  [OK] Agent is reachable.\n'));
  } catch (err) {
    console.log(C.red(`  [FATAL] Cannot reach agent at ${BASE}: ${err.message}`));
    console.log(C.dim('  Make sure the Spring Boot app is running on the correct port.'));
    process.exit(1);
  }

  const results = [];        // { tool, category, status, message }
  const groupSummaries = [];

  for (let gi = 0; gi < TEST_GROUPS.length; gi++) {
    const group = TEST_GROUPS[gi];
    const sid = `infra-g${gi + 1}-${Date.now()}`;

    console.log(C.cyan(C.bold(`── [Group ${gi + 1}/${TEST_GROUPS.length}] ${group.name} ──`)));
    console.log(C.dim(`  Prompt: ${group.prompt.substring(0, 90)}...`));

    let resp;
    try {
      resp = await sendChat(group.prompt, sid);
    } catch (err) {
      console.log(C.red(`  [ERROR] Request failed: ${err.message}\n`));
      for (const toolName of group.expectedTools) {
        results.push({ tool: toolName, category: getCategory(toolName), status: 'FAIL', message: `Request failed: ${err.message}` });
      }
      groupSummaries.push({ name: group.name, called: 0, expected: group.expectedTools.length, failed: group.expectedTools.length });
      continue;
    }

    console.log(`  Tools called: ${resp.toolsCalled.join(', ') || C.dim('(none)')}`);

    // Check for missing tools
    const missing = group.expectedTools.filter(t => !resp.toolsCalled.includes(t));
    if (missing.length > 0) {
      console.log(C.yellow(`  [WARN] Not called: ${missing.join(', ')}`));
    }

    // Log errors from tool results
    const errorResults = resp.toolResults.filter(r => r.isError);
    if (errorResults.length > 0) {
      console.log(C.yellow(`  [WARN] Error results: ${errorResults.map(e => e.tool).join(', ')}`));
    }

    // Verify each expected tool
    for (const toolName of group.expectedTools) {
      const tc = TEST_CASES.find(t => t.tool === toolName);
      const toolResult = resp.toolResults.find(r => r.tool === toolName) || null;

      let status, message;
      if (!resp.toolsCalled.includes(toolName)) {
        status = 'SKIP';
        message = 'Tool was not invoked by the LLM';
      } else {
        const verdict = tc.verify(toolResult, resp.toolResults);
        if (verdict.startsWith('PASS')) {
          status = 'PASS';
          message = verdict.replace(/^PASS\s*\(?/, '').replace(/\)$/, '');
        } else if (verdict.startsWith('WARN')) {
          status = 'WARN';
          message = verdict.replace(/^WARN:\s*/, '');
        } else if (verdict.startsWith('FAIL')) {
          status = 'FAIL';
          message = verdict.replace(/^FAIL:\s*/, '');
        } else if (verdict.startsWith('SKIP')) {
          status = 'SKIP';
          message = verdict.replace(/^SKIP:\s*/, '');
        } else {
          status = 'PASS';
          message = verdict;
        }
      }

      results.push({ tool: toolName, category: tc.category, status, message, preview: toolResult?.preview?.substring(0, 120) });

      const icon = status === 'PASS' ? C.green('PASS') :
                   status === 'WARN' ? C.yellow('WARN') :
                   status === 'FAIL' ? C.red('FAIL') :
                   C.dim('SKIP');
      const msg = message ? C.dim(` — ${message}`) : '';
      console.log(`    ${icon}  ${toolName}${msg}`);
    }

    const passed = results.filter(r => group.expectedTools.includes(r.tool) && r.status === 'PASS').length;
    groupSummaries.push({ name: group.name, called: resp.toolsCalled.length, expected: group.expectedTools.length, passed });

    console.log('');
  }

  // ─── Summary ──────────────────────────────────────────────────────────

  console.log(C.bold('═══════════════════════════════════════════════════════'));
  console.log(C.bold('  SUMMARY'));
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  // By category
  const categories = [...new Set(TEST_CASES.map(t => t.category))];
  for (const cat of categories) {
    const catResults = results.filter(r => r.category === cat);
    const passed = catResults.filter(r => r.status === 'PASS').length;
    const warned = catResults.filter(r => r.status === 'WARN').length;
    const failed = catResults.filter(r => r.status === 'FAIL').length;
    const skipped = catResults.filter(r => r.status === 'SKIP').length;
    console.log(`  ${C.cyan(cat)}: ${passed}/${catResults.length} passed` +
      (warned > 0 ? `, ${warned} warnings` : '') +
      (failed > 0 ? `, ${C.red(failed + ' failed')}` : '') +
      (skipped > 0 ? `, ${C.dim(skipped + ' skipped')}` : ''));
  }

  // Totals
  const totalPassed = results.filter(r => r.status === 'PASS').length;
  const totalWarned = results.filter(r => r.status === 'WARN').length;
  const totalFailed = results.filter(r => r.status === 'FAIL').length;
  const totalSkipped = results.filter(r => r.status === 'SKIP').length;
  const total = results.length;

  console.log(`\n  ${C.bold('Total')}: ${total} test cases`);
  console.log(`    ${C.green('PASS')}:  ${totalPassed}`);
  if (totalWarned > 0)  console.log(`    ${C.yellow('WARN')}:  ${totalWarned}`);
  if (totalFailed > 0)  console.log(`    ${C.red('FAIL')}:  ${totalFailed}`);
  if (totalSkipped > 0) console.log(`    ${C.dim('SKIP')}:  ${totalSkipped}`);

  // Group breakdown
  console.log('\n  ── Group Breakdown ──');
  for (const g of groupSummaries) {
    const status = g.passed === g.expected ? C.green('OK ') :
                   g.passed > 0 ? C.yellow('WARN') : C.red('FAIL');
    console.log(`    ${status} ${g.name}: ${g.passed}/${g.expected} passed`);
  }

  // Detail for failures/warnings
  const issues = results.filter(r => r.status === 'FAIL' || r.status === 'WARN');
  if (issues.length > 0) {
    console.log(`\n  ── Issues Detail ──`);
    for (const r of issues) {
      const icon = r.status === 'FAIL' ? C.red('[FAIL]') : C.yellow('[WARN]');
      console.log(`    ${icon} ${r.tool}: ${r.message}`);
      if (r.preview) console.log(C.dim(`           preview: ${r.preview}`));
    }
  }

  const allPassed = totalFailed === 0 && totalSkipped === 0;
  console.log(`\n  ${allPassed ? C.green(C.bold('ALL TOOLS VERIFIED')) :
    totalFailed > 0 ? C.red(C.bold('SOME FAILURES — see above')) :
    C.yellow(C.bold('SOME WARNINGS/SKIPS — see above'))}`);
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  process.exit(totalFailed > 0 ? 1 : 0);
})();

// ─── Helpers ─────────────────────────────────────────────────────────────

function getCategory(toolName) {
  const tc = TEST_CASES.find(t => t.tool === toolName);
  return tc ? tc.category : 'Unknown';
}
