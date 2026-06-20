const { chromium } = require('playwright');

/**
 * Multi-turn demo recordings for Spring Debug Agent.
 *
 * Each scenario is a realistic debugging conversation with multiple back-and-forth
 * messages, showcasing the 35 available diagnostic tools.
 *
 * Usage:
 *   node demo-record.js
 *
 * Prerequisites:
 *   - Demo app running on http://localhost:8088
 *   - Playwright installed: npm install playwright
 *   - ffmpeg for mp4 conversion
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:8088';
const OUTPUT_DIR = './demo-recordings';

// ─── Helpers ──────────────────────────────────────────────────────────────

/** Type text into the chat input with human-like delay */
async function typeMessage(page, text, charDelay = 15) {
  const input = page.locator('#input');
  await input.click();
  for (const ch of text) {
    await input.type(ch, { delay: charDelay });
  }
}

/** Send the current message and wait for the response to complete */
async function sendAndWait(page, timeout = 60000) {
  // Click send button
  await page.locator('#send').click();

  // Wait for AI response to start (agent message appears)
  await page.waitForSelector('.message.agent .message-content:not(:empty)', { timeout: 30000 });

  // Wait for streaming to complete — the send button becomes enabled again
  // and the loading indicator disappears
  await page.waitForFunction(() => {
    const btn = document.querySelector('#send');
    return btn && !btn.disabled;
  }, { timeout }).catch(() => {});

  // Extra settle time for final rendering
  await page.waitForTimeout(1500);
}

/** Wait for the user to read, then continue */
async function pause(page, ms = 2000) {
  await page.waitForTimeout(ms);
}

// ─── Scenario 1: Diagnose Connection Pool Issues ────────────────────────

async function scenario1_poolDiagnosis(page) {
  console.log('🎬 Scenario 1: Connection Pool & Database Health Check');

  // Turn 1: Initial overview
  await typeMessage(page,
    'Our order management app feels sluggish. Can you check the overall health and database connection pool status?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 2: Drill into specific pool stats
  await typeMessage(page,
    'Interesting. Now show me the HikariCP pool details — how many active vs idle connections do we have?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 3: Check JPA query performance
  await typeMessage(page,
    'Let\'s also check if there are any slow Hibernate queries. Are there N+1 query issues?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 4: Check async thread pool
  await typeMessage(page,
    'Good to know. One more thing — check the async thread pool stats. We use @Async for credit deduction, is the pool healthy?');
  await sendAndWait(page);
  await pause(page, 4000);
}

// ─── Scenario 2: Explore REST API & Invoke Methods ──────────────────────

async function scenario2_apiExploration(page) {
  console.log('🎬 Scenario 2: REST API Discovery & Runtime Method Invocation');

  // Turn 1: List all endpoints
  await typeMessage(page,
    'I need to understand the API surface of this application. List all HTTP endpoints with their controller and method names.');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 2: Inspect a specific bean
  await typeMessage(page,
    'Can you show me the OrderService bean — what fields does it have and what are their current values?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 3: Invoke a method at runtime
  await typeMessage(page,
    'Great! Now try calling the getOrder method on the orderService bean with argument [1] to check the first order.');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 4: Check the pricing cache
  await typeMessage(page,
    'The PricingService uses @Cacheable. Can you check the cache stats — how many hits and misses do we have for the "prices" cache?');
  await sendAndWait(page);
  await pause(page, 4000);
}

// ─── Scenario 3: Memory Leak Investigation ──────────────────────────────

async function scenario3_memoryInvestigation(page) {
  console.log('🎬 Scenario 3: Memory Leak Investigation');

  // Turn 1: Check memory overview
  await typeMessage(page,
    'I suspect a memory leak. Can you give me a full memory breakdown — heap, non-heap, and each memory pool?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 2: Check class loading
  await typeMessage(page,
    'Check the class loading stats too. How many classes are loaded? Is there anything unusual?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 3: Trigger GC and compare
  await typeMessage(page,
    'Let\'s trigger a garbage collection and see how much memory gets freed.');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 4: Check thread dump for leaks
  await typeMessage(page,
    'Now show me the thread summary — are there any threads stuck in WAITING or BLOCKED state?');
  await sendAndWait(page);
  await pause(page, 4000);
}

// ─── Scenario 4: Scheduled Tasks & Logs Investigation ───────────────────

async function scenario4_tasksAndLogs(page) {
  console.log('🎬 Scenario 4: Scheduled Tasks & Log Analysis');

  // Turn 1: List scheduled tasks
  await typeMessage(page,
    'What scheduled background tasks are running in this app? Show me their cron expressions and next run times.');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 2: Check recent logs
  await typeMessage(page,
    'Show me the recent application logs — any errors or warnings in the last few minutes?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 3: Check health endpoint
  await typeMessage(page,
    'What does the Actuator health endpoint say? Are all components healthy?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 4: Check feature flags / auto-configuration
  await typeMessage(page,
    'Finally, show me which Spring Boot auto-configurations were enabled or disabled, and why some features didn\'t load.');
  await sendAndWait(page);
  await pause(page, 4000);
}

// ─── Scenario 5: Full System Audit (multi-turn deep dive) ───────────────

async function scenario5_systemAudit(page) {
  console.log('🎬 Scenario 5: Full System Audit (Deep Dive)');

  // Turn 1: System overview
  await typeMessage(page,
    'Give me a complete system overview: JVM version, uptime, active profiles, and total bean count.');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 2: Environment diff
  await typeMessage(page,
    'Show me the environment configuration — which property sources are active and how many unique properties?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 3: HTTP client pool
  await typeMessage(page,
    'We configured an Apache HttpClient connection pool for our RestTemplate. Show me the pool statistics.');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 4: GC stats
  await typeMessage(page,
    'What are the current GC statistics? How much time have we spent in garbage collection?');
  await sendAndWait(page);
  await pause(page, 3000);

  // Turn 5: CPU consuming threads
  await typeMessage(page,
    'Which threads are consuming the most CPU right now? Show me the top 5.');
  await sendAndWait(page);
  await pause(page, 4000);
}

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  const fs = require('fs');
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  console.log('🚀 Starting Spring Debug Agent demo recordings...');
  console.log(`   Target: ${BASE_URL}/agent\n`);

  const browser = await chromium.launch({
    headless: false,
    args: ['--window-size=1280,860', '--disable-blink-features=AutomationControlled'],
  });

  const scenarios = [
    { name: '1-pool-diagnosis', fn: scenario1_poolDiagnosis, desc: 'Connection Pool & Database Health' },
    { name: '2-api-exploration', fn: scenario2_apiExploration, desc: 'REST API & Method Invocation' },
    { name: '3-memory-investigation', fn: scenario3_memoryInvestigation, desc: 'Memory Leak Investigation' },
    { name: '4-tasks-and-logs', fn: scenario4_tasksAndLogs, desc: 'Scheduled Tasks & Log Analysis' },
    { name: '5-system-audit', fn: scenario5_systemAudit, desc: 'Full System Audit' },
  ];

  for (const scenario of scenarios) {
    console.log(`\n────────────────────────────────────────────`);
    console.log(`Recording: ${scenario.desc}`);
    console.log(`────────────────────────────────────────────`);

    const context = await browser.newContext({
      viewport: { width: 1280, height: 860 },
      recordVideo: { dir: OUTPUT_DIR, size: { width: 1280, height: 860 } },
    });

    const page = await context.newPage();

    // Navigate to chat
    await page.goto(`${BASE_URL}/agent`, { waitUntil: 'networkidle' });
    await pause(page, 2000);

    // Run the scenario
    await scenario.fn(page);

    // Save a screenshot
    await page.screenshot({
      path: `${OUTPUT_DIR}/${scenario.name}-final.png`,
      fullPage: false,
    });

    // Close context to finalize video
    await context.close();
    console.log(`✅ ${scenario.name} recorded`);
  }

  await browser.close();

  // Rename videos
  console.log('\n📁 Renaming videos...');
  const files = fs.readdirSync(OUTPUT_DIR).filter(f => f.endsWith('.webm'));
  for (let i = 0; i < files.length && i < scenarios.length; i++) {
    const oldPath = `${OUTPUT_DIR}/${files[i]}`;
    const newPath = `${OUTPUT_DIR}/demo-${scenarios[i].name}.webm`;
    if (oldPath !== newPath) {
      fs.renameSync(oldPath, newPath);
      console.log(`   ${files[i]} → demo-${scenarios[i].name}.webm`);
    }
  }

  // Convert to mp4 if ffmpeg is available
  try {
    console.log('\n🎬 Converting to mp4...');
    for (const scenario of scenarios) {
      const webm = `${OUTPUT_DIR}/demo-${scenario.name}.webm`;
      const mp4 = `${OUTPUT_DIR}/demo-${scenario.name}.mp4`;
      if (fs.existsSync(webm)) {
        require('child_process').execSync(
          `ffmpeg -y -i "${webm}" -c:v libx264 -preset fast -crf 23 -c:a aac "${mp4}"`,
          { stdio: 'pipe' }
        );
        console.log(`   ✅ demo-${scenario.name}.mp4`);
      }
    }
  } catch (e) {
    console.log('   (ffmpeg not available, keeping .webm files)');
  }

  console.log('\n✅ All recordings complete!');
  console.log(`📁 Videos saved in: ${OUTPUT_DIR}`);
  console.log('\n   Scenarios:');
  scenarios.forEach((s, i) => {
    console.log(`   ${i + 1}. ${s.desc}`);
  });
})();
