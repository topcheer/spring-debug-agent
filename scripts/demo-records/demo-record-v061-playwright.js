const { chromium } = require('playwright');
const fs = require('fs');

/**
 * v0.6.1 Full demo recording — 141 tools / 43 inspectors.
 *
 * 12 sections using NATURAL LANGUAGE prompts (no explicit tool names).
 * The LLM must autonomously decide which tools to invoke — just like a real developer would ask.
 *
 * Usage: node demo-record-v061-playwright.js
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const OUTPUT_DIR = './demo-recordings';

// ─── Helpers ──────────────────────────────────────────────────────────────

async function typeMessage(page, text, charDelay = 8) {
  const input = page.locator('#input');
  await input.click();
  await input.pressSequentially(text, { delay: charDelay });
}

async function waitForAgentIdle(page, timeout = 300000) {
  await page.waitForSelector('.typing', { timeout: 15000 }).catch(() => {});

  await page.waitForFunction(() => {
    return document.querySelectorAll('.typing').length === 0;
  }, { timeout }).catch(() => {});

  try {
    await page.waitForFunction(() => {
      const btn = document.querySelector('#send');
      return btn && !btn.disabled && btn.textContent.trim() === 'Send';
    }, { timeout });
  } catch {
    console.log('  Warning: Agent still busy, waiting more...');
    await page.waitForFunction(() => {
      const btn = document.querySelector('#send');
      return btn && !btn.disabled && btn.textContent.trim() === 'Send';
    }, { timeout: 120000 }).catch(() => {
      console.log('  Warning: Force proceeding after extended wait');
    });
  }

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
  await page.waitForTimeout(1500);
}

async function sendAndWait(page, timeout = 300000) {
  await page.waitForSelector('#send:not([disabled])', { timeout: 300000 });
  await page.locator('#send').click();
  await waitForAgentIdle(page, timeout);
}

async function pause(page, ms = 3000) {
  await page.waitForTimeout(ms);
}

// ─── Section 1: JVM Deep Dive ────────────────────────────────────────────

async function section1_jvm(page) {
  console.log('  [1/12] JVM Deep Dive');

  await typeMessage(page, 'My app feels sluggish. Can you check the overall JVM health — memory usage, how long it\'s been running, and GC performance?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any threading issues? Check for blocked threads, deadlocks, and show me which threads are burning the most CPU.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Let\'s dig into memory. What are the top object types eating up heap space? Also check if there\'s excessive DirectByteBuffer usage.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Try forcing a garbage collection — I want to see how much memory can actually be reclaimed.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 2: Spring Core + Bean Graph ─────────────────────────────────

async function section2_spring(page) {
  console.log('  [2/12] Spring Core + Bean Graph');

  await typeMessage(page, 'How long did the app take to start? What Spring profiles are active, and how many beans are loaded?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me all the Service beans. Then pick the orderService and tell me what it depends on and what its current field values are.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'I\'m debugging a config issue. What port is the server running on? Also, find all properties related to datasource.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Could there be circular bean dependencies? Show me the bean creation order for service beans and flag any lazy-initialized ones.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 3: JMX MBean Browser ────────────────────────────────────────

async function section3_jmx(page) {
  console.log('  [3/12] JMX MBean Browser');

  await typeMessage(page, 'What MBeans are registered in JMX? Show me all the domains.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the memory attributes from the java.lang Memory MBean.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any Tomcat-related MBeans? Show me what Tomcat metrics are exposed.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 4: Web + HTTP Request Tracing ────────────────────────────────

async function section4_web(page) {
  console.log('  [4/12] Web + HTTP Request Tracing');

  await typeMessage(page, 'What REST API endpoints does this application expose?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'How are the HTTP requests performing? Show me latency percentiles, error rates, and status code distribution.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the most recent requests and the slowest ones — I want to see if anything is timing out.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 5: Metrics + Events ──────────────────────────────────────────

async function section5_metrics(page) {
  console.log('  [5/12] Metrics + Events');

  await typeMessage(page, 'What JVM metrics is Micrometer tracking? Show me the available metric names.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What\'s the actual memory usage value from the metrics right now?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What Spring events have been published recently, and who\'s listening to them?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 6: Database + SQL + Migrations ───────────────────────────────

async function section6_database(page) {
  console.log('  [6/12] Database + SQL + Migrations');

  await typeMessage(page, 'How\'s the database connection pool looking? Any connection leaks or pool exhaustion?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any slow SQL queries? Show me Hibernate query performance stats.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What\'s the transaction success rate? Also check if all Flyway migrations have been applied.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 7: Redis + Kafka Messaging ───────────────────────────────────

async function section7_messaging(page) {
  console.log('  [7/12] Redis + Kafka Messaging');

  await typeMessage(page, 'Is Redis healthy? Show me server info — memory, connected clients, and how many keys are stored.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check the Redis slow log for expensive commands. Also show me some of the cached keys.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What Kafka consumer groups exist and is there any consumer lag? What topics are available?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 8: MongoDB + Elasticsearch [NEW v0.6] ───────────────────────

async function section8_mongo_es(page) {
  console.log('  [8/12] MongoDB + Elasticsearch [NEW]');

  await typeMessage(page, 'My app uses MongoDB. Is the connection healthy? Show me server details and storage engine info.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What collections are in MongoDB and how many documents does each have? Show me the indexes on the audit_logs collection.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'How\'s the Elasticsearch cluster? Is it green? Show me node count and shard info.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'List all Elasticsearch indices with their document counts. Are there any slow ES queries?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 9: Batch + Quartz + WebSocket [NEW v0.6] ────────────────────

async function section9_batch_quartz_ws(page) {
  console.log('  [9/12] Batch + Quartz + WebSocket [NEW]');

  await typeMessage(page, 'What Spring Batch jobs are configured? Show me the execution history of the order import job — did it succeed?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the step-level statistics for the batch job. Have there been any batch failures?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What Quartz scheduled jobs are running and when are they next firing?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any active WebSocket connections? Show me the WebSocket session stats.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 10: GraphQL + OpenAPI + ThreadPools [NEW v0.6] ──────────────

async function section10_graphql_api(page) {
  console.log('  [10/12] GraphQL + OpenAPI + ThreadPools [NEW]');

  await typeMessage(page, 'What does the GraphQL schema look like? Show me all the queries and mutations available.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Generate the OpenAPI spec for this app. How many API paths and operations are there?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me all thread pool configurations. What are their queue sizes and how many tasks are active right now?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 11: Security + Cache + Transactions ─────────────────────────

async function section11_security(page) {
  console.log('  [11/12] Security + Cache + Transactions');

  await typeMessage(page, 'Walk me through the security setup. Which URLs require authentication and which are public?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'How effective is the Spring Cache? Show me hit/miss ratios. Also, what background scheduled tasks are running?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check the HttpClient connection pool stats. What auto-configurations were enabled or disabled at startup?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 12: Profiling + Logs + Classloading ─────────────────────────

async function section12_profiling(page) {
  console.log('  [12/12] Profiling + Logs + Classloading');

  await typeMessage(page, 'Are any circuit breakers tripped? Show me the resilience4j config — circuit breakers, rate limiters, retries.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me recent warning logs. How many classes has the app loaded?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the environment configuration — what property sources are active and are there any overridden values?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  console.log('\n======================================================');
  console.log('  Spring Debug Agent v0.6.1 — Full Demo Recording');
  console.log('  141 tools / 43 inspectors / 12 sections');
  console.log('  Natural language prompts (no tool names exposed)');
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

  await page.goto(`${BASE_URL}/agent`, { waitUntil: 'networkidle' });
  await pause(page, 2000);

  const sections = [
    { name: '1-jvm-deep-dive',      fn: section1_jvm },
    { name: '2-spring-core',        fn: section2_spring },
    { name: '3-jmx-mbean',          fn: section3_jmx },
    { name: '4-web-http',           fn: section4_web },
    { name: '5-metrics-events',     fn: section5_metrics },
    { name: '6-database-sql',       fn: section6_database },
    { name: '7-redis-kafka',        fn: section7_messaging },
    { name: '8-mongo-elasticsearch',fn: section8_mongo_es },
    { name: '9-batch-quartz-ws',    fn: section9_batch_quartz_ws },
    { name: '10-graphql-openapi',   fn: section10_graphql_api },
    { name: '11-security-cache',    fn: section11_security },
    { name: '12-profiling-system',  fn: section12_profiling },
  ];

  for (let i = 0; i < sections.length; i++) {
    const section = sections[i];
    console.log(`\n--- Recording Section ${i + 1}: ${section.name} ---`);
    await section.fn(page);
    await page.screenshot({ path: `${OUTPUT_DIR}/v061-demo-${section.name}.png` });
    console.log(`  Screenshot: v061-demo-${section.name}.png`);
  }

  await page.evaluate(() => window.scrollTo(0, 0));
  await pause(page, 1000);

  const video = page.video();
  const videoPath = await video.path();
  console.log(`\n  Video path: ${videoPath}`);

  await context.close();
  await browser.close();

  // Rename video
  console.log('\n--- Finalizing video ---');
  const finalWebm = `${OUTPUT_DIR}/v061-full-demo.webm`;
  const finalMp4 = `${OUTPUT_DIR}/v061-full-demo.mp4`;

  try { fs.unlinkSync(finalWebm); } catch {}
  try { fs.unlinkSync(finalMp4); } catch {}

  if (videoPath && fs.existsSync(videoPath)) {
    fs.copyFileSync(videoPath, finalWebm);
    const size = fs.statSync(finalWebm).size;
    console.log(`  Saved: v061-full-demo.webm (${(size / 1024 / 1024).toFixed(1)} MB)`);
  } else {
    const webmFiles = fs.readdirSync(OUTPUT_DIR)
      .filter(f => f.endsWith('.webm') && f !== 'v061-full-demo.webm')
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
    if (fs.existsSync(finalWebm)) {
      execSync(`ffmpeg -y -i "${finalWebm}" -c:v libx264 -preset fast -crf 23 -c:a aac "${finalMp4}"`, { stdio: 'pipe' });
      const size = fs.statSync(finalMp4).size;
      console.log(`  Done: v061-full-demo.mp4 (${(size / 1024 / 1024).toFixed(1)} MB)`);
    }
  } catch (e) {
    console.log('  (ffmpeg conversion failed, keeping .webm)');
  }

  console.log('\n======================================================');
  console.log('  Recording complete!');
  console.log(`  Output: ${OUTPUT_DIR}/v061-full-demo.mp4`);
  console.log('======================================================\n');
})();
