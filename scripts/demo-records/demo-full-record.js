const { chromium } = require('playwright');
const fs = require('fs');

/**
 * Comprehensive demo recording — all 55 tools in one long video.
 *
 * 8 sections, each with multi-turn conversation:
 *   1. JVM Deep Dive (11 tools)
 *   2. Spring Core + Bean Graph (11 tools)
 *   3. JMX MBean Browser (4 tools)
 *   4. Web + HTTP Request Tracing (6 tools)
 *   5. Metrics + Events (5 tools)
 *   6. Infrastructure Inspectors (10 tools)
 *   7. WatchPoints Lifecycle (5 tools)
 *   8. Classloading + System Control (3 tools)
 *
 * Usage: node demo-full-record.js
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:8088';
const OUTPUT_DIR = './demo-recordings';

// ─── Helpers ──────────────────────────────────────────────────────────────

async function typeMessage(page, text, charDelay = 8) {
  const input = page.locator('#input');
  await input.click();
  await input.pressSequentially(text, { delay: charDelay });
}

async function waitForAgentIdle(page, timeout = 300000) {
  // Phase 1: Wait for typing indicator to appear (agent started working)
  await page.waitForSelector('.typing', { timeout: 15000 }).catch(() => {});

  // Phase 2: Wait for typing indicator to disappear (agent stopped generating)
  await page.waitForFunction(() => {
    return document.querySelectorAll('.typing').length === 0;
  }, { timeout }).catch(() => {});

  // Phase 3: Wait for send button to be re-enabled and text back to "Send"
  // This is the definitive signal that the SSE stream has closed
  try {
    await page.waitForFunction(() => {
      const btn = document.querySelector('#send');
      return btn && !btn.disabled && btn.textContent.trim() === 'Send';
    }, { timeout });
  } catch {
    console.log('  ⚠ Agent still busy after timeout, waiting more...');
    await page.waitForFunction(() => {
      const btn = document.querySelector('#send');
      return btn && !btn.disabled && btn.textContent.trim() === 'Send';
    }, { timeout: 120000 }).catch(() => {
      console.log('  ⚠ Force proceeding after extended wait');
    });
  }

  // Phase 4: DOM stability check — wait until message count stops changing for 3s
  let lastCount = 0;
  let stableTime = 0;
  let maxWait = 20000;
  const interval = 1000;
  while (stableTime < 3000 && maxWait > 0) {
    const count = await page.evaluate(() => document.querySelectorAll('.message').length);
    if (count === lastCount) {
      stableTime += interval;
    } else {
      lastCount = count;
      stableTime = 0;
    }
    await page.waitForTimeout(interval);
    maxWait -= interval;
  }

  // Extra settle for markdown rendering
  await page.waitForTimeout(1500);
}

async function sendAndWait(page, timeout = 300000) {
  // Wait for send button to be enabled before clicking
  await page.waitForSelector('#send:not([disabled])', { timeout: 300000 });
  await page.locator('#send').click();
  await waitForAgentIdle(page, timeout);
}

async function pause(page, ms = 3000) {
  await page.waitForTimeout(ms);
}

async function clearChat(page) {
  // Click clear link to start fresh conversation
  const clearLink = page.locator('#clear-link');
  if (await clearLink.isVisible()) {
    await clearLink.click();
    await page.waitForTimeout(800);
  }
}

// ─── Section 1: JVM Deep Dive (11 tools) ────────────────────────────────

async function section1_jvm(page) {
  console.log('  [1/8] JVM Deep Dive — 11 tools');

  // Turn 1: Memory & GC overview
  await typeMessage(page,
    'Give me a complete JVM health check. Call get_runtime_info, get_memory_summary, and get_gc_stats — show me uptime, heap usage, and GC collection stats.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Threads & deadlocks
  await typeMessage(page,
    'Now check the thread health. Call get_thread_summary to see thread states, then get_cpu_consuming_threads (top 5), and detect_deadlocks to see if any threads are deadlocked.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 3: Heap histogram + buffer pools
  await typeMessage(page,
    'Let\'s dig into memory details. Call get_heap_histogram to see the top classes by instance count, get_buffer_pool_stats for DirectByteBuffer usage, and get_compilation_stats for JIT info.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 4: Trigger GC and compare
  await typeMessage(page,
    'Let\'s trigger a garbage collection with trigger_gc and see how much memory gets reclaimed.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 2: Spring Core + Bean Graph (11 tools) ─────────────────────

async function section2_spring(page) {
  console.log('  [2/8] Spring Core + Bean Graph — 11 tools');

  // Turn 1: Context overview
  await typeMessage(page,
    'Show me the Spring ApplicationContext details. Call get_context_info for startup time and bean count, and get_active_profiles.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Bean inspection
  await typeMessage(page,
    'Now call get_all_beans with typeFilter "Service" to list all service beans. Then call get_bean_details for "orderService" to see its fields and values, and get_bean_dependencies to see what it depends on.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 3: Properties
  await typeMessage(page,
    'Call get_property for key "server.port", then search_properties with keyword "datasource" to find all datasource-related config.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 4: Bean graph analysis
  await typeMessage(page,
    'Let\'s analyze the bean dependency graph. Call get_bean_creation_order (filter "service") to see the startup order, get_circular_references to detect any cycles, and get_lazy_beans to find lazy-initialized beans.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 3: JMX MBean Browser (4 tools) ─────────────────────────────

async function section3_jmx(page) {
  console.log('  [3/8] JMX MBean Browser — 4 tools');

  // Turn 1: List all MBeans
  await typeMessage(page,
    'Browse the JMX MBean server. Call list_mbeans to list all registered domains and their MBeans.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Inspect Memory MBean
  await typeMessage(page,
    'Call get_mbean_attributes for objectName "java.lang:type=Memory" to see all memory-related attributes.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 3: Read single attribute
  await typeMessage(page,
    'Call get_mbean_attribute for objectName "java.lang:type=Memory" and attributeName "HeapMemoryUsage" to get the current heap usage.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 4: Browse Tomcat MBeans
  await typeMessage(page,
    'Call list_mbeans again with domainFilter "Tomcat" to find Tomcat-related MBeans. Then pick one and call get_mbean_attributes on it.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 4: Web + HTTP Request Tracing (6 tools) ────────────────────

async function section4_web(page) {
  console.log('  [4/8] Web + HTTP Request Tracing — 6 tools');

  // Turn 1: HTTP endpoints
  await typeMessage(page,
    'Call get_http_endpoints to list all registered REST API endpoints in this application.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Request stats
  await typeMessage(page,
    'Call get_request_stats to see HTTP request statistics — total requests, status distribution, P50/P95/P99 latency, and error rate.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 3: Recent and slow requests
  await typeMessage(page,
    'Call get_recent_requests (limit 5) to show the latest HTTP requests, and get_slow_requests (limit 5) to show the slowest ones.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 4: Invoke a method at runtime
  await typeMessage(page,
    'Call invoke_bean_method on bean "orderService", method "getAllOrders", with no arguments (empty array []). Also call get_bean_field_value for bean "orderService" and field "log".');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 5: Metrics + Events (5 tools) ──────────────────────────────

async function section5_metrics(page) {
  console.log('  [5/8] Metrics + Events — 5 tools');

  // Turn 1: Micrometer metrics
  await typeMessage(page,
    'Call get_metrics_list with nameFilter "jvm." to list all JVM-related Micrometer metrics, and get_meter_registries to see which backends are registered.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Read specific metric
  await typeMessage(page,
    'Call get_metric_value for metricName "jvm.memory.used" to see the actual memory usage values.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 3: Spring Events
  await typeMessage(page,
    'Call get_recent_events (limit 10) to see recently published Spring Application Events, and get_event_listeners to list all registered event listeners.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 6: Infrastructure (10 tools) ───────────────────────────────

async function section6_infra(page) {
  console.log('  [6/8] Infrastructure — 10 tools');

  // Turn 1: Database & health
  await typeMessage(page,
    'Call get_data_source_info to check the HikariCP connection pool stats, get_health_status for the Actuator health check, and get_jpa_query_stats for Hibernate query performance.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Tasks & cache
  await typeMessage(page,
    'Call get_scheduled_tasks to see background scheduled tasks, get_async_task_info for async thread pool stats, and get_cache_stats to see cache hit/miss ratios.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 3: HttpClient pool + feature flags
  await typeMessage(page,
    'Call get_http_client_pool_stats for the Apache HttpClient connection pool, and get_feature_flags to see which Spring Boot auto-configurations were enabled or disabled.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 4: Logs + environment
  await typeMessage(page,
    'Call get_recent_logs with level "WARN" and maxEntries 5 to see recent warnings. Then call get_environment_diff to see all active property sources.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 7: WatchPoints Lifecycle (5 tools) ─────────────────────────

async function section7_watchpoints(page) {
  console.log('  [7/8] WatchPoints Lifecycle — 5 tools');

  // Turn 1: Find classes
  await typeMessage(page,
    'Call search_loaded_classes with pattern "OrderService" to find all loaded OrderService-related classes. Then call find_class_location for className "com.demo.service.OrderService" to find which JAR it came from.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Add watch point
  await typeMessage(page,
    'Call list_watch_points to check if any exist. Then call add_watch_point for bean "orderService" and method "getAllOrders" to set a runtime watch point.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 3: Trigger and inspect
  await typeMessage(page,
    'Now call get_watch_results to see if any method calls were captured. Then call remove_watch_point to clean up (use the ID from the watch point we just added).');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 8: Classloading + Memory Pools (3 tools) ───────────────────

async function section8_system(page) {
  console.log('  [8/8] Classloading + Memory Pools — 3 tools');

  // Turn 1: Classloading info
  await typeMessage(page,
    'Call get_classloading_info to see loaded/unloaded class counts, and get_compilation_stats for JIT compilation details.');
  await sendAndWait(page);
  await pause(page, 4000);

  // Turn 2: Memory pool details
  await typeMessage(page,
    'Call get_memory_pool_details to see each memory pool\'s used/committed/max, thresholds, and peak usage. Also call get_memory_manager_stats to see GC algorithm details.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  console.log('\n======================================================');
  console.log('  Spring Debug Agent — Full 55-Tool Demo Recording');
  console.log('  Target: ' + BASE_URL + '/agent');
  console.log('======================================================\n');

  const browser = await chromium.launch({
    headless: false,
    args: ['--window-size=1280,860', '--disable-blink-features=AutomationControlled'],
  });

  const context = await browser.newContext({
    viewport: { width: 1280, height: 860 },
    recordVideo: { dir: OUTPUT_DIR, size: { width: 1280, height: 860 } },
  });

  const page = await context.newPage();

  // Navigate to chat
  await page.goto(`${BASE_URL}/agent`, { waitUntil: 'networkidle' });
  await pause(page, 2000);

  const sections = [
    { name: '1-jvm-deep-dive', fn: section1_jvm },
    { name: '2-spring-core', fn: section2_spring },
    { name: '3-jmx-mbean', fn: section3_jmx },
    { name: '4-web-http', fn: section4_web },
    { name: '5-metrics-events', fn: section5_metrics },
    { name: '6-infrastructure', fn: section6_infra },
    { name: '7-watchpoints', fn: section7_watchpoints },
    { name: '8-classloading-system', fn: section8_system },
  ];

  for (let i = 0; i < sections.length; i++) {
    const section = sections[i];
    console.log(`\n--- Recording Section ${i + 1}: ${section.name} ---`);

    // No clearChat — keep all messages in one continuous conversation for video continuity

    await section.fn(page);

    // Screenshot after each section
    await page.screenshot({
      path: `${OUTPUT_DIR}/full-demo-${section.name}.png`,
    });
    console.log(`  Screenshot: full-demo-${section.name}.png`);
  }

  // Scroll to top for final screenshot
  await page.evaluate(() => window.scrollTo(0, 0));
  await pause(page, 1000);

  // Get the video path BEFORE closing
  const video = page.video();
  const videoPath = await video.path();
  console.log(`\n  Video path: ${videoPath}`);

  // Close context to finalize video
  await context.close();
  await browser.close();

  // Rename video
  console.log('\n--- Renaming video ---');
  const finalWebm = `${OUTPUT_DIR}/full-55-tools-demo.webm`;
  const finalMp4 = `${OUTPUT_DIR}/full-55-tools-demo.mp4`;

  // Remove old output if exists
  try { fs.unlinkSync(finalWebm); } catch {}
  try { fs.unlinkSync(finalMp4); } catch {}

  if (videoPath && fs.existsSync(videoPath)) {
    fs.copyFileSync(videoPath, finalWebm);
    const size = fs.statSync(finalWebm).size;
    console.log(`  Saved: full-55-tools-demo.webm (${(size / 1024 / 1024).toFixed(1)} MB)`);
  } else {
    // Fallback: find the most recent webm
    const webmFiles = fs.readdirSync(OUTPUT_DIR)
      .filter(f => f.endsWith('.webm') && f !== 'full-55-tools-demo.webm')
      .map(f => ({ name: f, path: `${OUTPUT_DIR}/${f}`, mtime: fs.statSync(`${OUTPUT_DIR}/${f}`).mtime }))
      .sort((a, b) => b.mtime - a.mtime);
    if (webmFiles.length > 0) {
      fs.copyFileSync(webmFiles[0].path, finalWebm);
      console.log(`  Copied from: ${webmFiles[0].name}`);
    }
  }

  // Convert to mp4
  try {
    console.log('\n--- Converting to mp4 ---');
    const { execSync } = require('child_process');
    const webm = `${OUTPUT_DIR}/full-55-tools-demo.webm`;
    const mp4 = `${OUTPUT_DIR}/full-55-tools-demo.mp4`;
    if (fs.existsSync(webm)) {
      execSync(`ffmpeg -y -i "${webm}" -c:v libx264 -preset fast -crf 23 -c:a aac "${mp4}"`, { stdio: 'pipe' });
      const size = fs.statSync(mp4).size;
      console.log(`  Done: full-55-tools-demo.mp4 (${(size / 1024 / 1024).toFixed(1)} MB)`);
    }
  } catch (e) {
    console.log('  (ffmpeg not available, keeping .webm)');
  }

  console.log('\n======================================================');
  console.log('  Recording complete!');
  console.log(`  Output: ${OUTPUT_DIR}/full-55-tools-demo.mp4`);
  console.log('======================================================\n');
})();
