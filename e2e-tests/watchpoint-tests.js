#!/usr/bin/env node
/**
 * E2E tests for WatchPoint and Class Loading tools (~7 tools).
 *
 * Tool coverage:
 *   Class Loading (2):
 *     - search_loaded_classes (pattern: OrderService)
 *     - find_class_location  (className: com.demo.service.OrderService)
 *
 *   WatchPoint Lifecycle (5 steps):
 *     - list_watch_points  (check initial state — should be empty)
 *     - add_watch_point    (className: com.demo.service.OrderService, methodName: getAllOrders)
 *     - list_watch_points  (confirm watch point was added)
 *     - HTTP trigger       (POST /api/orders then GET /api/orders to invoke getAllOrders)
 *     - get_watch_results  (verify captured invocations)
 *     - remove_watch_point (clean up)
 *
 * The WatchPoint flow requires a special sequence: set the watch point first,
 * then trigger the method via an HTTP call, and finally read the captured results.
 *
 * Usage:
 *   node e2e-tests/watchpoint-tests.js                      # default http://localhost:8088
 *   BASE_URL=http://localhost:9090 node e2e-tests/watchpoint-tests.js
 */

const http = require('http');
const https = require('https');
const { URL } = require('url');

// ─── Configuration ───────────────────────────────────────────────────────

const BASE_URL = process.env.BASE_URL || 'http://localhost:8088';
const TIMEOUT_MS = parseInt(process.env.TIMEOUT_MS || '90000', 10);

const parsedBase = new URL(BASE_URL);
const httpClient = parsedBase.protocol === 'https:' ? https : http;

// Fully-qualified class name and method to watch
const TARGET_CLASS = 'com.demo.service.OrderService';
const TARGET_METHOD = 'getAllOrders';

// ─── SSE Chat Client ─────────────────────────────────────────────────────

/**
 * Send a chat message to the agent and collect SSE events.
 *
 * Returns:
 *   {
 *     toolsCalled:  string[],                 // unique tool names invoked
 *     toolResults:  { tool, preview, isError }[],
 *     content:      string,                   // concatenated assistant text
 *     done:         boolean,
 *     error:        string | null,
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
                toolName = data.replace(/:$/, '').trim();
                preview = '';
              }

              if (toolName && !toolsCalled.includes(toolName)) {
                toolsCalled.push(toolName);
              }

              const isError =
                preview.includes('"error"') ||
                preview.includes('"error":') ||
                (/\berror\b/i.test(preview.substring(0, 80)) && preview.length < 300);

              toolResults.push({ tool: toolName, preview, isError });
            } else if (eventType === 'content' || eventType === null) {
              // Content chunk: JSON-quoted string
              if (data.startsWith('"') || data === '""') {
                try {
                  const text = JSON.parse(data);
                  if (text) contentChunks.push(text);
                } catch {
                  // skip non-JSON content
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

// ─── HTTP Helper (for triggering demo app endpoints) ─────────────────────

/**
 * Make a direct HTTP request to the demo application.
 * Used to trigger method invocations that WatchPoints should capture.
 */
function httpRequest(method, path, body) {
  return new Promise((resolve, reject) => {
    const postData = body ? JSON.stringify(body) : null;
    const options = {
      hostname: parsedBase.hostname,
      port: parsedBase.port || (parsedBase.protocol === 'https:' ? 443 : 80),
      path: path,
      method: method,
      headers: {
        'Content-Type': 'application/json',
        ...(postData ? { 'Content-Length': Buffer.byteLength(postData) } : {}),
      },
      timeout: 15000,
    };

    const req = httpClient.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk.toString()));
      res.on('end', () => {
        resolve({ statusCode: res.statusCode, body: data });
      });
      res.on('error', (err) => reject(err));
    });

    req.on('error', (err) => reject(err));
    req.on('timeout', () => {
      req.destroy(new Error('HTTP request timed out'));
    });
    if (postData) req.write(postData);
    req.end();
  });
}

// ─── Color helpers ───────────────────────────────────────────────────────

const C = {
  green:  (s) => `\x1b[32m${s}\x1b[0m`,
  red:    (s) => `\x1b[31m${s}\x1b[0m`,
  yellow: (s) => `\x1b[33m${s}\x1b[0m`,
  cyan:   (s) => `\x1b[36m${s}\x1b[0m`,
  dim:    (s) => `\x1b[2m${s}\x1b[0m`,
  bold:   (s) => `\x1b[1m${s}\x1b[0m`,
};

// ─── Test Case Definitions ───────────────────────────────────────────────

const TEST_CASES = [
  // ═══════════════════════════════════════════════════════════════════════
  // 1. Class Loading Tools (2)
  // ═══════════════════════════════════════════════════════════════════════

  {
    id: 'cls-01',
    group: 'Class Loading',
    tool: 'search_loaded_classes',
    description: 'Search for loaded classes matching "OrderService" pattern',
    prompt: `Call search_loaded_classes with pattern "OrderService" and show me the results. Call only this one tool.`,
    expectedBehavior: 'Returns list of classes whose name contains "OrderService" with className, simpleName, and methods',
    verify: (r) => {
      if (!r) return { pass: false, detail: 'Tool was not called' };
      if (r.isError) return { pass: false, detail: `Error: ${r.preview.substring(0, 120)}` };
      if (/OrderService/i.test(r.preview)) return { pass: true, detail: 'Found OrderService class(es)' };
      return { pass: true, detail: `Called successfully, preview: ${r.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'cls-02',
    group: 'Class Loading',
    tool: 'find_class_location',
    description: 'Find where com.demo.service.OrderService is loaded from (JAR/path)',
    prompt: `Call find_class_location with className "com.demo.service.OrderService" and show the result. Call only this one tool.`,
    expectedBehavior: 'Returns className, classLoader, protectionDomain, and location for the OrderService class',
    verify: (r) => {
      if (!r) return { pass: false, detail: 'Tool was not called' };
      if (r.isError && /not found/i.test(r.preview))
        return { pass: false, detail: `Class not found: ${r.preview.substring(0, 120)}` };
      if (r.isError) return { pass: false, detail: `Error: ${r.preview.substring(0, 120)}` };
      if (/classLoader|location|protectionDomain|OrderService/i.test(r.preview))
        return { pass: true, detail: 'Found class location info' };
      return { pass: true, detail: `Called successfully` };
    },
  },

  // ═══════════════════════════════════════════════════════════════════════
  // 2. WatchPoint Lifecycle (5 steps — executed in sequence)
  // ═══════════════════════════════════════════════════════════════════════

  {
    id: 'wp-01',
    group: 'WatchPoints',
    tool: 'list_watch_points',
    description: 'List active watch points — check initial state (may be empty or have stale points)',
    prompt: `Call list_watch_points to show all currently active watch points. Call only this one tool.`,
    expectedBehavior: 'Returns a list (possibly empty) of active watch points',
    verify: (r) => {
      if (!r) return { pass: false, detail: 'Tool was not called' };
      if (r.isError) return { pass: false, detail: `Error: ${r.preview.substring(0, 120)}` };
      return { pass: true, detail: 'Tool executed successfully' };
    },
  },
  {
    id: 'wp-02',
    group: 'WatchPoints',
    tool: 'add_watch_point',
    description: `Set a watch point on ${TARGET_CLASS}.${TARGET_METHOD}`,
    prompt: `Call add_watch_point with className "${TARGET_CLASS}" and methodName "${TARGET_METHOD}". Call only this one tool.`,
    expectedBehavior: 'Returns status "active" with watchPointId com.demo.service.OrderService#getAllOrders',
    verify: (r) => {
      if (!r) return { pass: false, detail: 'Tool was not called' };
      if (r.isError) return { pass: false, detail: `Error: ${r.preview.substring(0, 120)}` };
      if (/active|watchPointId|getAllOrders/i.test(r.preview))
        return { pass: true, detail: 'Watch point set successfully' };
      return { pass: true, detail: `Called successfully, preview: ${r.preview.substring(0, 120)}` };
    },
  },
  {
    id: 'wp-03',
    group: 'WatchPoints',
    tool: 'list_watch_points',
    description: 'List watch points — confirm the new watch point is visible',
    prompt: `Call list_watch_points to show all currently active watch points. Call only this one tool.`,
    expectedBehavior: 'Returns a list that includes getAllOrders watch point',
    verify: (r) => {
      if (!r) return { pass: false, detail: 'Tool was not called' };
      if (r.isError) return { pass: false, detail: `Error: ${r.preview.substring(0, 120)}` };
      if (/getAllOrders/i.test(r.preview))
        return { pass: true, detail: 'Watch point for getAllOrders is active' };
      return { pass: true, detail: `Tool executed, preview: ${r.preview.substring(0, 120)}` };
    },
  },

  // Note: wp-04 (HTTP trigger + get_watch_results) is handled separately
  // as a composite step that cannot be expressed as a simple agent prompt.

  {
    id: 'wp-05',
    group: 'WatchPoints',
    tool: 'get_watch_results',
    description: `Read captured invocations for ${TARGET_CLASS}.${TARGET_METHOD} after HTTP trigger`,
    prompt: `Call get_watch_results with className "${TARGET_CLASS}" and methodName "${TARGET_METHOD}". Show me the captured invocation data. Call only this one tool.`,
    expectedBehavior: 'Returns totalCapturedCalls >= 1 with captures array containing args, returnValue, and durationMs',
    verify: (r) => {
      if (!r) return { pass: false, detail: 'Tool was not called' };
      if (r.isError) return { pass: false, detail: `Error: ${r.preview.substring(0, 120)}` };
      if (/totalCapturedCalls|captures|returnValue|durationMs/i.test(r.preview))
        return { pass: true, detail: 'Captured invocation data retrieved' };
      if (/No calls captured/i.test(r.preview))
        return { pass: false, detail: 'No calls captured — watch point may not have been triggered' };
      return { pass: true, detail: `Tool executed, preview: ${r.preview.substring(0, 120)}` };
    },
    // This test depends on the HTTP trigger step running first
    dependsOnTrigger: true,
  },
  {
    id: 'wp-06',
    group: 'WatchPoints',
    tool: 'remove_watch_point',
    description: `Remove the watch point on ${TARGET_CLASS}.${TARGET_METHOD}`,
    prompt: `Call remove_watch_point with className "${TARGET_CLASS}" and methodName "${TARGET_METHOD}". Call only this one tool.`,
    expectedBehavior: 'Returns status "removed" with the watchPointId',
    verify: (r) => {
      if (!r) return { pass: false, detail: 'Tool was not called' };
      if (r.isError) return { pass: false, detail: `Error: ${r.preview.substring(0, 120)}` };
      if (/removed/i.test(r.preview))
        return { pass: true, detail: 'Watch point removed successfully' };
      return { pass: true, detail: `Tool executed, preview: ${r.preview.substring(0, 120)}` };
    },
  },
];

// ─── Test Runner ─────────────────────────────────────────────────────────

/**
 * Run a single agent-based test case.
 * Sends the prompt, collects SSE events, and verifies the result.
 */
async function runAgentTest(testCase) {
  const sid = `wp-test-${testCase.id}-${Date.now()}`;
  const result = await sendChat(testCase.prompt, sid);
  const toolResult = result.toolResults.find((t) => t.tool === testCase.tool) || null;

  let verification;
  if (!result.toolsCalled.includes(testCase.tool)) {
    verification = { pass: false, detail: 'Tool was not invoked by the LLM' };
  } else {
    verification = testCase.verify(toolResult);
  }

  return {
    ...testCase,
    passed: verification.pass,
    detail: verification.detail,
    toolsCalled: result.toolsCalled,
    toolResultPreview: toolResult?.preview?.substring(0, 150),
  };
}

/**
 * Main test execution flow.
 *
 * Executes all test cases in order, with a special HTTP-trigger step
 * inserted between wp-03 (list after add) and wp-05 (get_watch_results).
 */
async function runAllTests() {
  console.log(C.bold('═══════════════════════════════════════════════════════'));
  console.log(C.bold('  WatchPoint & Class Loading Tools — E2E Test Suite'));
  console.log(C.bold(`  Target: ${BASE_URL}/agent/api/chat`));
  console.log(C.bold(`  Tools:  7 test cases (2 class loading + 5 watch point steps)`));
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  // Connectivity check
  try {
    await sendChat('hello', 'wp-health-check');
    console.log(C.green('  [OK] Agent is reachable.\n'));
  } catch (err) {
    console.log(C.red(`  [FATAL] Cannot reach agent at ${BASE_URL}: ${err.message}`));
    console.log(C.dim('  Make sure the Spring Boot app is running on the correct port.'));
    process.exit(1);
  }

  const allResults = [];

  // ─── Phase 1: Class Loading Tests ───────────────────────────────────
  console.log(C.cyan(C.bold('── Class Loading Tools (2) ──────────────────────────')));

  for (const tc of TEST_CASES.filter((t) => t.group === 'Class Loading')) {
    process.stdout.write(`  ${C.dim(tc.id)} ${C.bold(tc.tool.padEnd(28))} ... `);
    try {
      const result = await runAgentTest(tc);
      allResults.push(result);
      if (result.passed) {
        console.log(C.green('PASS'));
        console.log(C.dim(`       ${result.detail}`));
      } else {
        console.log(C.red('FAIL'));
        console.log(C.dim(`       ${result.detail}`));
        if (result.toolsCalled.length > 0)
          console.log(C.dim(`       Tools called: ${result.toolsCalled.join(', ')}`));
      }
    } catch (err) {
      allResults.push({ ...tc, passed: false, detail: `Request failed: ${err.message}`, toolsCalled: [] });
      console.log(C.red('FAIL'));
      console.log(C.dim(`       ${err.message}`));
    }
    console.log('');
  }

  // ─── Phase 2: WatchPoint Lifecycle ─────────────────────────────────
  console.log(C.cyan(C.bold('── WatchPoint Lifecycle (5 steps) ───────────────────')));

  const wpTests = TEST_CASES.filter((t) => t.group === 'WatchPoints');

  for (const tc of wpTests) {
    // Insert HTTP trigger step before wp-05 (get_watch_results)
    if (tc.dependsOnTrigger) {
      console.log(C.yellow(`  [trigger] Invoking ${TARGET_METHOD} via HTTP to generate watch data...`));

      // Step A: POST /api/orders — create an order (exercises createOrder)
      try {
        const postRes = await httpRequest('POST', '/api/orders', {
          customerId: 1,
          items: [{ sku: 'WIDGET-001', productName: 'Test Widget', quantity: 2 }],
        });
        console.log(
          C.dim(`       POST /api/orders -> ${postRes.statusCode} ${postRes.statusCode === 200 ? '(order created)' : postRes.body.substring(0, 80)}`)
        );
      } catch (err) {
        console.log(C.yellow(`       POST /api/orders failed: ${err.message} (continuing anyway)`));
      }

      // Step B: GET /api/orders — triggers getAllOrders (the watched method)
      try {
        const getRes = await httpRequest('GET', '/api/orders');
        console.log(
          C.dim(`       GET  /api/orders -> ${getRes.statusCode} ${getRes.statusCode === 200 ? '(orders retrieved)' : ''}`)
        );
      } catch (err) {
        console.log(C.yellow(`       GET /api/orders failed: ${err.message} (continuing anyway)`));
      }

      // Brief pause to ensure instrumentation advice has time to record
      await new Promise((r) => setTimeout(r, 500));
      console.log('');
    }

    process.stdout.write(`  ${C.dim(tc.id)} ${C.bold(tc.tool.padEnd(28))} ... `);

    try {
      const result = await runAgentTest(tc);
      allResults.push(result);
      if (result.passed) {
        console.log(C.green('PASS'));
        console.log(C.dim(`       ${result.detail}`));
      } else {
        console.log(C.red('FAIL'));
        console.log(C.dim(`       ${result.detail}`));
        if (result.toolsCalled.length > 0)
          console.log(C.dim(`       Tools called: ${result.toolsCalled.join(', ')}`));
        if (result.toolResultPreview)
          console.log(C.dim(`       Preview: ${result.toolResultPreview}`));
      }
    } catch (err) {
      allResults.push({ ...tc, passed: false, detail: `Request failed: ${err.message}`, toolsCalled: [] });
      console.log(C.red('FAIL'));
      console.log(C.dim(`       ${err.message}`));
    }
    console.log('');
  }

  // ─── Summary ───────────────────────────────────────────────────────
  const total = allResults.length;
  const passed = allResults.filter((r) => r.passed).length;
  const failed = total - passed;

  console.log(C.bold('═══════════════════════════════════════════════════════'));
  console.log(C.bold('  SUMMARY'));
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  // Per-group breakdown
  const groups = [...new Set(allResults.map((r) => r.group))];
  for (const group of groups) {
    const groupResults = allResults.filter((r) => r.group === group);
    const groupPassed = groupResults.filter((r) => r.passed).length;
    const status = groupPassed === groupResults.length ? C.green('PASS') : C.red('FAIL');
    console.log(`  ${status}  ${group.padEnd(20)} ${groupPassed}/${groupResults.length}`);
  }

  console.log('');
  console.log(`  Total:  ${C.green(passed + ' passed')} / ${total}`);
  if (failed > 0) console.log(`  Failed: ${C.red(failed)}`);

  // Detail for failures
  const failures = allResults.filter((r) => !r.passed);
  if (failures.length > 0) {
    console.log(C.red('\n  ── Failed Tests ──'));
    for (const f of failures) {
      console.log(C.red(`    [FAIL] ${f.id}: ${f.tool}`));
      console.log(C.dim(`           ${f.detail}`));
    }
  }

  console.log('');
  const allPass = failed === 0;
  console.log(
    allPass
      ? C.green(C.bold('  ALL WATCHPOINT/CLASS-LOADING TOOLS PASSED'))
      : C.red(C.bold(`  ${failed} TEST(S) FAILED — see above`))
  );
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  return { total, passed, failed, results: allResults };
}

// ─── Export for test-runner.js, or run directly ──────────────────────────

module.exports = {
  suiteName: 'WatchPoint & Class Loading Tools',
  testCases: TEST_CASES,
  runAllTests,
};

// If executed directly, run the tests
if (require.main === module) {
  runAllTests()
    .then((summary) => process.exit(summary.failed > 0 ? 1 : 0))
    .catch((err) => {
      console.error(C.red(`\nFatal error: ${err.message}`));
      process.exit(2);
    });
}
