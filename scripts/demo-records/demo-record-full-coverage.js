const { chromium } = require('playwright');
const fs = require('fs');

/**
 * Full Coverage Demo — 50 inspectors / 161 tools
 *
 * 16 sections, natural language prompts, covers EVERY inspector category.
 * New in this version: State Machine, Hazelcast, Tracing, gRPC, OAuth2,
 * Vault, Object Storage (MinIO), Cloud Discovery, Reactive.
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const OUTPUT_DIR = './demo-recordings';

// ─── Helpers ──────────────────────────────────────────────────────────────

async function typeMessage(page, text, charDelay = 6) {
  const input = page.locator('#input');
  await input.click();
  await input.pressSequentially(text, { delay: charDelay });
}

async function waitForAgentIdle(page, timeout = 180000) {
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
    console.log('  ...still busy, extended wait');
    await page.waitForFunction(() => {
      const btn = document.querySelector('#send');
      return btn && !btn.disabled && btn.textContent.trim() === 'Send';
    }, { timeout: 120000 }).catch(() => {});
  }
  let lastCount = 0, stable = 0, maxWait = 15000;
  while (stable < 3000 && maxWait > 0) {
    const c = await page.evaluate(() => document.querySelectorAll('.message').length);
    if (c === lastCount) stable += 1000; else { lastCount = c; stable = 0; }
    await page.waitForTimeout(1000);
    maxWait -= 1000;
  }
  await page.waitForTimeout(1500);
}

async function sendAndWait(page, timeout = 180000) {
  await page.waitForSelector('#send:not([disabled])', { timeout: 300000 });
  await page.locator('#send').click();
  await waitForAgentIdle(page, timeout);
}

async function pause(page, ms = 3000) { await page.waitForTimeout(ms); }

// ─── Section 1: JVM Deep Dive ────────────────────────────────────────────

async function section1(page) {
  console.log('  [1/16] JVM Deep Dive');
  await typeMessage(page, "My app feels slow. Check the overall JVM health: memory usage, uptime, loaded classes, and GC statistics.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me a thread dump. Check for blocked threads, deadlocks, and which threads are consuming the most CPU.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What are the top object types in the heap? Also show me JIT compilation statistics and loaded class information.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Force a garbage collection so I can see how much memory can be reclaimed.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 2: Spring Core + Bean Graph ─────────────────────────────────

async function section2(page) {
  console.log('  [2/16] Spring Core + Bean Graph');
  await typeMessage(page, "How long did the app take to start? What profiles are active? Show me the application context hierarchy.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "List all Service beans and their dependencies. Then inspect the orderService — what are its field values?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What properties are configured? Show me all datasource-related and server-related properties.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Are there any circular bean dependencies? Show me the full bean dependency graph for service-tier beans.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 3: JMX MBean Browser ────────────────────────────────────────

async function section3(page) {
  console.log('  [3/16] JMX MBean Browser');
  await typeMessage(page, "What MBean domains are registered in JMX? Show me an overview.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the Tomcat-related MBeans and their attributes — thread pools, connectors, request stats.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 4: Web + HTTP + Endpoints ──────────────────────────────────

async function section4(page) {
  console.log('  [4/16] Web + HTTP Request Tracing');
  await typeMessage(page, "What REST endpoints does this application expose? Show me all mapped URL patterns.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "How are HTTP requests performing? Show me request statistics, latency percentiles, and status code distribution.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the registered filters and interceptors. What's in the filter chain?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Test the /api/orders endpoint — send a GET request and show me the response. Also check any active HTTP sessions.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 5: Metrics + Events ─────────────────────────────────────────

async function section5(page) {
  console.log('  [5/16] Metrics + Events');
  await typeMessage(page, "What metrics is Micrometer tracking? List the available meter names.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the JVM memory usage metric value and the HTTP server request metrics.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What Spring application events have been published? Show me the event listeners registered.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 6: Database + SQL + Migrations ──────────────────────────────

async function section6(page) {
  console.log('  [6/16] Database + SQL');
  await typeMessage(page, "How is the database connection pool? Show me HikariCP stats — active connections, idle, pending.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me JPA/Hibernate query statistics. Are there any slow queries? What entities have the most operations?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What's the transaction success rate? Also check the Flyway migration history — all applied?");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 7: Redis + Kafka ────────────────────────────────────────────

async function section7(page) {
  console.log('  [7/16] Redis + Kafka Messaging');
  await typeMessage(page, "Is Redis healthy? Show me server info — memory usage, connected clients, keyspace stats.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the Redis slow log. Also list some cached keys and their TTL values.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What Kafka topics exist? Show me consumer groups and any consumer lag.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 8: MongoDB + Elasticsearch ──────────────────────────────────

async function section8(page) {
  console.log('  [8/16] MongoDB + Elasticsearch');
  await typeMessage(page, "Is MongoDB connected? Show me server info, storage engine, and connection details.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What MongoDB collections exist? Show me document counts and indexes for audit_logs.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "How is the Elasticsearch cluster? Show me health, node info, shard allocation, and all indices.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Check for slow Elasticsearch queries. What's in the ES slow log?");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 9: Batch + Quartz + WebSocket ───────────────────────────────

async function section9(page) {
  console.log('  [9/16] Batch + Quartz + WebSocket');
  await typeMessage(page, "What Spring Batch jobs are configured? Show me the execution history of the order import job — did it succeed?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the step-level execution statistics for the latest batch job run.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What Quartz scheduled jobs are running and when are they next firing?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Are there any active WebSocket connections? Show me session details.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 10: GraphQL + OpenAPI + ThreadPools ────────────────────────

async function section10(page) {
  console.log('  [10/16] GraphQL + OpenAPI + ThreadPools');
  await typeMessage(page, "What does the GraphQL schema look like? Show me all queries, mutations, and types available.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Are there any GraphQL errors logged? Show me recent GraphQL query statistics.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Generate the OpenAPI specification. How many paths and operations are documented?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me all thread pool configurations — core size, max, queue size, active tasks, rejection counts.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 11: Security + Cache + Scheduled ───────────────────────────

async function section11(page) {
  console.log('  [11/16] Security + Cache + Scheduled');
  await typeMessage(page, "Walk me through the security configuration. Which URL patterns require auth, which are public?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What users are defined in the security configuration? Show me their roles and authorities.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "How effective is the Spring Cache? Show me hit/miss ratios for all cache names.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What @Scheduled tasks are running? Show me their cron expressions and next execution times.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 12: State Machine + Distributed Cache [NEW] ────────────────

async function section12(page) {
  console.log('  [12/16] State Machine + Distributed Cache [NEW]');
  await typeMessage(page, "What state machines are configured? Show me the order lifecycle states, events, and transitions.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What is the current state of the order state machine? Has it fired any transitions?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "How is the Hazelcast distributed cache performing? Show me cluster members, partition counts, and map stats.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What entries are in the Hazelcast distributed map? Show me the order cache entries and their distribution.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 13: Tracing + gRPC + Cloud Discovery [NEW] ─────────────────

async function section13(page) {
  console.log('  [13/16] Tracing + gRPC + Cloud Discovery [NEW]');
  await typeMessage(page, "Is distributed tracing enabled? Show me recent traces and span information.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What gRPC channels are configured? Show me channel state and connection details.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What services are registered in service discovery? Show me all discovered service instances.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 14: OAuth2 + Vault + Object Storage [NEW] ──────────────────

async function section14(page) {
  console.log('  [14/16] OAuth2 + Vault + Object Storage [NEW]');
  await typeMessage(page, "What OAuth2 clients are registered? Show me client IDs, grant types, scopes, and token settings.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Is HashiCorp Vault connected? Show me the configured secret engines and list available secret paths.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the metadata for the secret stored at 'order-db' in Vault — versions, creation time.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What S3/MinIO buckets exist? Show me the object storage buckets and their contents.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 15: Profiling + Logs + Environment + Modulith ──────────────

async function section15(page) {
  console.log('  [15/16] Profiling + Logs + Environment');
  await typeMessage(page, "Run a quick CPU allocation profile — what are the top allocating classes?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me recent log entries. Are there any WARNING or ERROR level logs?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the full environment configuration — all property sources and their values.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What Spring Boot auto-configurations were enabled or disabled? Also check if there are feature flags or toggles.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the Spring Modulith module structure — what modules exist and their dependencies.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Section 16: Reactive + Resilience + HttpClient ─────────────────────

async function section16(page) {
  console.log('  [16/16] Reactive + Resilience + HttpClient');
  await typeMessage(page, "Is Spring WebFlux reactive stack available? What reactive endpoints or schedulers are configured?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the Resilience4j configuration — circuit breakers, rate limiters, retries, bulkheads. Any tripped?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "How is the Apache HttpClient connection pool performing? Show me pool stats, connection reuse, and timeouts.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Finally, give me an overall application health summary — actuator health endpoints for all components.");
  await sendAndWait(page); await pause(page, 4000);
}

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  console.log('\n======================================================');
  console.log('  Spring Debug Agent — Full Coverage Demo');
  console.log('  50 inspectors / 161 tools / 16 sections');
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
    { name: '01-jvm',         fn: section1 },
    { name: '02-spring-core', fn: section2 },
    { name: '03-jmx',         fn: section3 },
    { name: '04-web-http',    fn: section4 },
    { name: '05-metrics',     fn: section5 },
    { name: '06-database',    fn: section6 },
    { name: '07-messaging',   fn: section7 },
    { name: '08-nosql',       fn: section8 },
    { name: '09-batch-ws',    fn: section9 },
    { name: '10-api',         fn: section10 },
    { name: '11-security',    fn: section11 },
    { name: '12-state-dc',    fn: section12 },
    { name: '13-trace-grpc',  fn: section13 },
    { name: '14-oauth-vault', fn: section14 },
    { name: '15-profile-env', fn: section15 },
    { name: '16-reactive',    fn: section16 },
  ];

  for (let i = 0; i < sections.length; i++) {
    const s = sections[i];
    console.log(`\n--- Section ${i + 1}/16: ${s.name} ---`);
    await s.fn(page);
    await page.screenshot({ path: `${OUTPUT_DIR}/full-demo-${s.name}.png` });
    console.log(`  Screenshot: full-demo-${s.name}.png`);
  }

  await page.evaluate(() => window.scrollTo(0, 0));
  await pause(page, 1000);

  const video = page.video();
  const videoPath = await video.path();
  console.log(`\n  Video: ${videoPath}`);

  await context.close();
  await browser.close();

  // Rename + convert
  console.log('\n--- Finalizing ---');
  const finalWebm = `${OUTPUT_DIR}/full-coverage-demo.webm`;
  const finalMp4 = `${OUTPUT_DIR}/full-coverage-demo.mp4`;
  try { fs.unlinkSync(finalWebm); } catch {}
  try { fs.unlinkSync(finalMp4); } catch {}

  if (videoPath && fs.existsSync(videoPath)) {
    fs.copyFileSync(videoPath, finalWebm);
    const size = fs.statSync(finalWebm).size;
    console.log(`  WebM: ${(size / 1024 / 1024).toFixed(1)} MB`);
  }

  try {
    const { execSync } = require('child_process');
    if (fs.existsSync(finalWebm)) {
      execSync(`ffmpeg -y -i "${finalWebm}" -c:v libx264 -preset fast -crf 23 -c:a aac "${finalMp4}"`, { stdio: 'pipe' });
      const size = fs.statSync(finalMp4).size;
      console.log(`  MP4: ${(size / 1024 / 1024).toFixed(1)} MB`);
    }
  } catch {
    console.log('  (ffmpeg conversion skipped)');
  }

  console.log('\n======================================================');
  console.log('  Recording complete!');
  console.log('======================================================\n');
})();
