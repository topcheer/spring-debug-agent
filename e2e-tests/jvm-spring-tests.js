#!/usr/bin/env node
/**
 * E2E test suite for all 23 JVM / Spring core tools.
 *
 * Tests each tool individually by sending a targeted prompt to the agent's
 * /agent/api/chat endpoint, parsing the SSE event stream, and verifying:
 *   1. The expected tool was actually invoked.
 *   2. The tool result does not contain an error payload.
 *
 * Tool groups covered:
 *   - JVM Core (9)
 *   - Spring Core (7)
 *   - New JVM Tools (3)
 *   - Bean Graph Tools (3)
 *   - System Control (1)
 *
 * Usage:
 *   node e2e-tests/jvm-spring-tests.js                 # default http://localhost:8088
 *   BASE_URL=http://localhost:9090 node e2e-tests/jvm-spring-tests.js
 */

const http = require('http');
const https = require('https');
const { URL } = require('url');

// ─── Configuration ───────────────────────────────────────────────────────

const BASE_URL = process.env.BASE_URL || 'http://localhost:8088';
const TIMEOUT_MS = parseInt(process.env.TIMEOUT_MS || '90000', 10);
const PARALLEL = process.env.PARALLEL === '1'; // default: sequential

const parsedBase = new URL(BASE_URL);
const httpClient = parsedBase.protocol === 'https:' ? https : http;

// ─── SSE Chat Client ─────────────────────────────────────────────────────

/**
 * Send a chat message and collect SSE events.
 *
 * Returns:
 *   {
 *     toolsCalled:   string[],           unique tool names invoked
 *     toolResults:   {tool, preview, isError}[]
 *     content:       string,             concatenated assistant text
 *     done:          boolean,
 *     error:         string | null,
 *   }
 */
function sendChat(message, sessionId) {
  return new Promise((resolve, reject) => {
    const postData = JSON.stringify({ message, sessionId });
    const options = {
      hostname: parsedBase.hostname,
      port: parsedBase.port || (parsedBase.protocol === 'https:' ? 443 : 80),
      path: `${parsedBase.pathname.replace(/\/$/, '')}/agent/api/chat`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData),
      },
      timeout: TIMEOUT_MS,
    };

    const toolsCalled = [];
    const toolResults = [];
    const contentChunks = [];
    let done = false;
    let streamError = null;
    let buffer = '';

    // Track the last `event:` line so we know how to interpret the next `data:` line.
    let pendingEvent = null;

    const req = httpClient.request(options, (res) => {
      if (res.statusCode !== 200) {
        reject(new Error(`HTTP ${res.statusCode} from /agent/api/chat`));
        return;
      }

      res.on('data', (chunk) => {
        buffer += chunk.toString();
        const lines = buffer.split('\n');
        buffer = lines.pop(); // keep incomplete trailing line

        for (const rawLine of lines) {
          const line = rawLine.trim();

          if (line.startsWith('event:')) {
            pendingEvent = line.substring(6).trim();
            continue;
          }

          if (line.startsWith('data:')) {
            const data = line.substring(5).trim();
            const eventType = pendingEvent;

            if (eventType === 'done') {
              done = true;
            } else if (eventType === 'error') {
              streamError = data;
            } else if (eventType === 'tool_start') {
              if (data && !toolsCalled.includes(data)) {
                toolsCalled.push(data);
              }
            } else if (eventType === 'tool_result') {
              // Format: "<toolName>: <preview>"
              const colonIdx = data.indexOf(': ');
              let toolName, preview;
              if (colonIdx > 0) {
                toolName = data.substring(0, colonIdx).trim();
                preview = data.substring(colonIdx + 2);
              } else {
                toolName = data;
                preview = '';
              }

              if (toolName && !toolsCalled.includes(toolName)) {
                toolsCalled.push(toolName);
              }

              // Determine error: tool result payloads use {"error": "..."} or "error" keys
              const isError =
                preview.includes('"error"') ||
                preview.includes('"error":') ||
                (preview.toLowerCase().includes('error') && preview.length < 300) ||
                preview.includes('"Bean not found"');

              toolResults.push({ tool: toolName, preview, isError });
            } else if (eventType === 'content' || eventType === null) {
              // Content chunk: a JSON-quoted string (e.g. "Hello ")
              if (data.startsWith('"') || data === '""') {
                try {
                  const text = JSON.parse(data);
                  if (text) contentChunks.push(text);
                } catch {
                  // Not valid JSON — skip
                }
              }
            }
          }
        }
      });

      res.on('end', () => {
        resolve({
          toolsCalled,
          toolResults,
          content: contentChunks.join(''),
          done,
          error: streamError,
        });
      });

      res.on('error', (err) => reject(err));
    });

    req.on('error', (err) => reject(err));
    req.on('timeout', () => {
      req.destroy(new Error(`Request timed out after ${TIMEOUT_MS}ms`));
    });
    req.write(postData);
    req.end();
  });
}

// ─── Test Case Definitions ───────────────────────────────────────────────
//
// Each entry: {
//   id:          short identifier
//   tool:        snake_case tool name to verify
//   description: human-readable description of the tool
//   prompt:      chat message that should trigger the tool
//   expectedBehavior:  what a successful invocation looks like
//   verify:      function(result) => {pass: boolean, detail: string}
// }

const TEST_CASES = [
  // ═══════════════════════════════════════════════════════════════════════
  // 1. JVM Core Tools (9)
  // ═══════════════════════════════════════════════════════════════════════

  {
    id: 'jvm-01',
    group: 'JVM Core',
    tool: 'get_thread_summary',
    description: 'Returns total/daemon/peak thread counts and per-state breakdown',
    prompt: 'Call get_thread_summary and tell me the thread state breakdown.',
    expectedBehavior: 'Returns totalThreadCount, daemonThreadCount, peakThreadCount, threadStates, deadlockedThreads',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_thread_summary');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-02',
    group: 'JVM Core',
    tool: 'get_thread_dump',
    description: 'Detailed thread dump with optional stack traces and state filter',
    prompt: 'Call get_thread_dump with includeStack set to false to get a lightweight thread dump.',
    expectedBehavior: 'Returns list of threads with name, state, blockedCount, waitedCount',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_thread_dump');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-03',
    group: 'JVM Core',
    tool: 'detect_deadlocks',
    description: 'Detects deadlocked threads, returns cycle details or "no deadlocks"',
    prompt: 'Call detect_deadlocks to check for any deadlocked threads.',
    expectedBehavior: 'Returns deadlockDetected (boolean), and message or deadlockedThreads list',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'detect_deadlocks');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-04',
    group: 'JVM Core',
    tool: 'get_cpu_consuming_threads',
    description: 'Threads ranked by CPU time, shows top consumers',
    prompt: 'Call get_cpu_consuming_threads with limit 5 to show the top CPU-consuming threads.',
    expectedBehavior: 'Returns sorted list of threads with cpuTimeMs and userTimeMs',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_cpu_consuming_threads');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-05',
    group: 'JVM Core',
    tool: 'get_memory_summary',
    description: 'Heap and non-heap memory usage with per-pool breakdown',
    prompt: 'Call get_memory_summary and report the heap and non-heap memory usage.',
    expectedBehavior: 'Returns heap, nonHeap, objectsPendingFinalization, memoryPools',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_memory_summary');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-06',
    group: 'JVM Core',
    tool: 'get_gc_stats',
    description: 'Garbage collector statistics: collection count, time, affected pools',
    prompt: 'Call get_gc_stats to show garbage collection statistics.',
    expectedBehavior: 'Returns list of GCs with name, collectionCount, collectionTimeMs, memoryPools',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_gc_stats');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-07',
    group: 'JVM Core',
    tool: 'get_runtime_info',
    description: 'JVM runtime: version, vendor, uptime, class loading, processors',
    prompt: 'Call get_runtime_info to show the Java version, uptime, and loaded class count.',
    expectedBehavior: 'Returns javaVersion, javaVendor, jvmName, uptimeMs, availableProcessors, loadedClassCount',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_runtime_info');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-08',
    group: 'JVM Core',
    tool: 'get_heap_histogram',
    description: 'Object count and estimated size per class (heap histogram)',
    prompt: 'Call get_heap_histogram with limit 10 to show the top 10 classes by heap size.',
    expectedBehavior: 'Returns ranked list with rank, instanceCount, sizeBytes, className (or fallback note)',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_heap_histogram');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      // Heap histogram may return a fallback note — that is not a hard error
      if (res.isError && !res.preview.includes('note')) {
        return { pass: false, detail: `Result contains error: ${res.preview}` };
      }
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-09',
    group: 'JVM Core',
    tool: 'get_buffer_pool_stats',
    description: 'Direct/Mapped buffer pool statistics (count, memory used, capacity)',
    prompt: 'Call get_buffer_pool_stats to show direct buffer pool statistics.',
    expectedBehavior: 'Returns bufferPools list (name, count, memoryUsed, totalCapacity) and poolCount',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_buffer_pool_stats');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },

  // ═══════════════════════════════════════════════════════════════════════
  // 2. Spring Core Tools (7)
  // ═══════════════════════════════════════════════════════════════════════

  {
    id: 'spring-01',
    group: 'Spring Core',
    tool: 'get_all_beans',
    description: 'List all Spring beans with name, type, scope; optional type filter',
    prompt: 'Call get_all_beans to list all registered Spring beans.',
    expectedBehavior: 'Returns list of beans with name, type, package, scope, aliases',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_all_beans');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'spring-02',
    group: 'Spring Core',
    tool: 'get_bean_details',
    description: 'Detailed info about a specific bean including fields and values',
    prompt: 'Call get_bean_details with beanName "orderService" to inspect the orderService bean.',
    expectedBehavior: 'Returns className, scope, and fields list with name/type/value for orderService',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_bean_details');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'spring-03',
    group: 'Spring Core',
    tool: 'get_bean_dependencies',
    description: 'Shows beans that depend on / are depended on by the target bean',
    prompt: 'Call get_bean_dependencies with beanName "orderService" to see its dependency graph.',
    expectedBehavior: 'Returns dependedOnBy and dependsOn arrays for orderService',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_bean_dependencies');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'spring-04',
    group: 'Spring Core',
    tool: 'get_property',
    description: 'Read a specific config property value (sensitive values masked)',
    prompt: 'Call get_property with key "server.port" to check the configured server port.',
    expectedBehavior: 'Returns {"value": "<port number>"} for server.port',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_property');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'spring-05',
    group: 'Spring Core',
    tool: 'search_properties',
    description: 'Search properties by keyword (case-insensitive, sensitive masked)',
    prompt: 'Call search_properties with keyword "spring" to find all Spring-related properties.',
    expectedBehavior: 'Returns list of {key, value} pairs matching "spring"',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'search_properties');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'spring-06',
    group: 'Spring Core',
    tool: 'get_active_profiles',
    description: 'List active and default Spring profiles',
    prompt: 'Call get_active_profiles to show the active Spring profiles.',
    expectedBehavior: 'Returns activeProfiles and defaultProfiles arrays',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_active_profiles');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'spring-07',
    group: 'Spring Core',
    tool: 'get_context_info',
    description: 'ApplicationContext metadata: startup date, bean count, parent context',
    prompt: 'Call get_context_info to show the Spring ApplicationContext info.',
    expectedBehavior: 'Returns applicationName, displayName, startupDate, beanDefinitionCount, uptimeMs',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_context_info');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },

  // ═══════════════════════════════════════════════════════════════════════
  // 3. New JVM Tools (3)
  // ═══════════════════════════════════════════════════════════════════════

  {
    id: 'jvm-new-01',
    group: 'New JVM',
    tool: 'get_compilation_stats',
    description: 'JIT compilation statistics: total compilation time, compiler name',
    prompt: 'Call get_compilation_stats to show JIT compilation statistics.',
    expectedBehavior: 'Returns name, totalCompilationTimeMs, isCompilationTimeMonitoringSupported',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_compilation_stats');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-new-02',
    group: 'New JVM',
    tool: 'get_memory_pool_details',
    description: 'Detailed memory pool breakdown with thresholds and peak usage',
    prompt: 'Call get_memory_pool_details to show detailed memory pool information.',
    expectedBehavior: 'Returns pools list with name, type, usage, peakUsage, usageThreshold info; poolCount',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_memory_pool_details');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'jvm-new-03',
    group: 'New JVM',
    tool: 'get_memory_manager_stats',
    description: 'Memory manager (GC algorithm) details with managed pools and GC stats',
    prompt: 'Call get_memory_manager_stats to show memory manager and GC algorithm details.',
    expectedBehavior: 'Returns managers list with name, managedMemoryPools, gcCount, gcTimeMs; managerCount',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_memory_manager_stats');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },

  // ═══════════════════════════════════════════════════════════════════════
  // 4. Bean Graph Tools (3)
  // ═══════════════════════════════════════════════════════════════════════

  {
    id: 'bean-graph-01',
    group: 'Bean Graph',
    tool: 'get_bean_creation_order',
    description: 'Bean creation/initialization order with scope and lazy flags',
    prompt: 'Call get_bean_creation_order to show the order in which beans were created.',
    expectedBehavior: 'Returns beans list (order, name, type, scope, lazy) and totalAnalyzed',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_bean_creation_order');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'bean-graph-02',
    group: 'Bean Graph',
    tool: 'get_circular_references',
    description: 'Detects circular dependencies by traversing the bean dependency graph',
    prompt: 'Call get_circular_references to detect any circular bean dependencies.',
    expectedBehavior: 'Returns cycleCount (0 or more) with cycles list or "no cycles" message',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_circular_references');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'bean-graph-03',
    group: 'Bean Graph',
    tool: 'get_lazy_beans',
    description: 'Lists lazy-initialized beans and not-yet-instantiated singletons',
    prompt: 'Call get_lazy_beans to list all lazy-initialized Spring beans.',
    expectedBehavior: 'Returns lazyBeans list and lazyCount; optionally notInstantiated',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'get_lazy_beans');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },

  // ═══════════════════════════════════════════════════════════════════════
  // 5. System Control (1)
  // ═══════════════════════════════════════════════════════════════════════

  {
    id: 'sys-01',
    group: 'System Control',
    tool: 'trigger_gc',
    description: 'Triggers GC and shows before/after memory comparison',
    prompt: 'Call trigger_gc to trigger garbage collection and show the before/after memory comparison.',
    expectedBehavior: 'Returns beforeUsedMB, afterUsedMB, freedMB, freedPercent, note',
    verify: (r) => {
      const res = r.toolResults.find(t => t.tool === 'trigger_gc');
      if (!res) return { pass: false, detail: 'Tool was not called' };
      if (res.isError) return { pass: false, detail: `Result contains error: ${res.preview}` };
      return { pass: true, detail: `Called successfully, preview: ${res.preview.substring(0, 120)}` };
    },
  },
];

// ─── Test Runner ─────────────────────────────────────────────────────────

const COLORS = {
  reset: '\x1b[0m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  cyan: '\x1b[36m',
  dim: '\x1b[2m',
  bold: '\x1b[1m',
};

function color(text, c) {
  return `${COLORS[c] || ''}${text}${COLORS.reset}`;
}

async function runSingleTest(testCase, index) {
  const sid = `jvm-spring-test-${testCase.id}-${Date.now()}`;
  const label = `[${testCase.id}] ${testCase.tool}`;

  try {
    const result = await sendChat(testCase.prompt, sid);
    const verification = testCase.verify(result);

    return {
      ...testCase,
      passed: verification.pass,
      detail: verification.detail,
      toolsCalled: result.toolsCalled,
      result,
    };
  } catch (err) {
    return {
      ...testCase,
      passed: false,
      detail: `Request failed: ${err.message}`,
      toolsCalled: [],
      result: null,
    };
  }
}

async function runAllTests() {
  console.log(color('═══════════════════════════════════════════════════════', 'bold'));
  console.log(color('  JVM / Spring Core Tools — E2E Test Suite', 'bold'));
  console.log(color(`  Target: ${BASE_URL}/agent/api/chat`, 'dim'));
  console.log(color(`  Tools:  ${TEST_CASES.length} test cases`, 'dim'));
  console.log(color(`  Mode:   ${PARALLEL ? 'parallel' : 'sequential'}`, 'dim'));
  console.log(color('═══════════════════════════════════════════════════════\n', 'bold'));

  // Group tests by group name for organized output
  const groups = [...new Set(TEST_CASES.map(t => t.group))];
  const allResults = [];

  for (const group of groups) {
    const groupTests = TEST_CASES.filter(t => t.group === group);
    console.log(color(`── ${group} (${groupTests.length} tools) ──────────────────────────`, 'cyan'));

    for (const tc of groupTests) {
      const idx = TEST_CASES.indexOf(tc);
      process.stdout.write(`  ${color(tc.id, 'dim')} ${color(tc.tool.padEnd(32), 'bold')} ... `);

      const result = await runSingleTest(tc, idx);
      allResults.push(result);

      if (result.passed) {
        console.log(color('PASS', 'green'));
        console.log(color(`       ${result.detail}`, 'dim'));
      } else {
        console.log(color('FAIL', 'red'));
        console.log(color(`       ${result.detail}`, 'dim'));
        console.log(color(`       Expected: ${tc.expectedBehavior}`, 'dim'));
        if (result.toolsCalled.length > 0) {
          console.log(color(`       Tools called: ${result.toolsCalled.join(', ')}`, 'dim'));
        }
      }
      console.log('');
    }
  }

  // ─── Summary ──────────────────────────────────────────────────────────
  const total = allResults.length;
  const passed = allResults.filter(r => r.passed).length;
  const failed = total - passed;
  const allPass = failed === 0;

  console.log(color('═══════════════════════════════════════════════════════', 'bold'));
  console.log(color('  SUMMARY', 'bold'));
  console.log(color('═══════════════════════════════════════════════════════\n', 'bold'));

  // Per-group breakdown
  for (const group of groups) {
    const groupResults = allResults.filter(r => r.group === group);
    const groupPassed = groupResults.filter(r => r.passed).length;
    const groupTotal = groupResults.length;
    const status = groupPassed === groupTotal ? color('PASS', 'green') : color('FAIL', 'red');
    console.log(`  ${status}  ${group.padEnd(20)} ${groupPassed}/${groupTotal}`);
  }

  console.log('');
  console.log(`  Total:   ${passed}/${total} passed`);
  console.log(`  Failed:  ${failed}`);

  // List failures
  const failures = allResults.filter(r => !r.passed);
  if (failures.length > 0) {
    console.log(color('\n─── Failed Tests ───', 'red'));
    for (const f of failures) {
      console.log(color(`  ✗ ${f.id}: ${f.tool}`, 'red'));
      console.log(color(`    ${f.detail}`, 'dim'));
    }
  }

  console.log('');
  console.log(color(
    allPass
      ? '  ALL 23 TOOLS PASSED'
      : `  ${failed} TOOL(S) FAILED — see above`,
    allPass ? 'green' : 'red'
  ));
  console.log(color('═══════════════════════════════════════════════════════\n', 'bold'));

  process.exit(allPass ? 0 : 1);
}

// ─── Entry Point ─────────────────────────────────────────────────────────

runAllTests().catch((err) => {
  console.error(color(`\nFatal error: ${err.message}`, 'red'));
  process.exit(2);
});
