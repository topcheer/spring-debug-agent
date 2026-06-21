const { chromium } = require('playwright');
const fs = require('fs');
const { execSync } = require('child_process');

/**
 * v0.8.0 Full demo recording — 226 tools / 64 inspectors.
 *
 * 18 sections using NATURAL LANGUAGE prompts (no explicit tool names).
 * The LLM must autonomously decide which tools to invoke — just like a real developer would ask.
 *
 * Coverage: ALL 64 inspectors, ALL 226 tools.
 *
 * Usage:
 *   1. Start Docker:  docker compose up -d
 *   2. Start demo app with Kerberos JVM flags
 *   3. Run:  node scripts/demo-records/demo-record-v08-full.js
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const OUTPUT_DIR = './demo-recordings';
const VERSION = 'v08';

// ─── Helpers ──────────────────────────────────────────────────────────────

async function typeMessage(page, text, charDelay = 5) {
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

  // Wait for DOM to stabilize (no new messages for 3s)
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

async function clearChat(page) {
  // Click "Clear" button if it exists to start fresh per section
  try {
    const clearBtn = page.locator('button:has-text("Clear")');
    if (await clearBtn.isVisible({ timeout: 2000 })) {
      await clearBtn.click();
      await pause(page, 1000);
    }
  } catch {
    // No clear button — continue with existing context
  }
}

// ─── Section 1: JVM Deep Dive ────────────────────────────────────────────

async function section1_jvm(page) {
  console.log('  [1/18] JVM Deep Dive');

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

// ─── Section 2: Spring Core + Bean Graph + Modulith ──────────────────────

async function section2_spring(page) {
  console.log('  [2/18] Spring Core + Bean Graph + Modulith');

  await typeMessage(page, 'How long did the app take to start? What Spring profiles are active, and how many beans are loaded?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me all the Service beans. Then pick the orderService and tell me what it depends on and what its current field values are.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Could there be circular bean dependencies? Show me the bean creation order for service beans and flag any lazy-initialized ones.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'This app uses Spring Modulith. Can you show me the application modules and verify that module boundaries are respected?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 3: JMX MBean Browser ────────────────────────────────────────

async function section3_jmx(page) {
  console.log('  [3/18] JMX MBean Browser');

  await typeMessage(page, 'What MBeans are registered in JMX? Show me all the domains.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the memory attributes from the java.lang Memory MBean. Also check what Tomcat connectors are exposed.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 4: Web + HTTP + Endpoint Testing ────────────────────────────

async function section4_web(page) {
  console.log('  [4/18] Web + HTTP + Endpoint Testing');

  await typeMessage(page, 'What REST API endpoints does this application expose? Also show me the servlet filter chains.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'How are the HTTP requests performing? Show me latency percentiles, error rates, and status code distribution.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Batch-test all GET endpoints and show me the endpoint coverage report. Also check outbound HTTP calls — any slow ones or errors?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 5: Database + SQL + MyBatis + Migrations ────────────────────

async function section5_database(page) {
  console.log('  [5/18] Database + SQL + MyBatis + Migrations');

  await typeMessage(page, 'How\'s the database connection pool looking? Any connection leaks or pool exhaustion?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any slow SQL queries? Show me Hibernate query performance stats and check for N+1 issues.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the MyBatis configuration — mapped statements, interceptors, and second-level cache settings.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check if all Flyway database migrations have been applied. Are there any pending ones?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 6: Transactions + Thread Pools + Tasks ──────────────────────

async function section6_txn(page) {
  console.log('  [6/18] Transactions + Thread Pools + Tasks');

  await typeMessage(page, 'What\'s the current transaction status? Show me transaction stats — commit rate, rollback rate, and any slow transactions.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me all thread pool configurations. What are their queue sizes and how many tasks are active right now? Any rejected tasks?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What scheduled tasks are running in the background? Show me the cron expressions and async thread pool stats.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 7: Redis + Kafka + RabbitMQ + RocketMQ ──────────────────────

async function section7_messaging(page) {
  console.log('  [7/18] Redis + Kafka + RabbitMQ + RocketMQ');

  await typeMessage(page, 'Is Redis healthy? Show me server info — memory, connected clients, and check the slow log for expensive commands.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What Kafka consumer groups exist and is there any consumer lag? Also check RabbitMQ — show me the queues, consumers, and exchange topology.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the RocketMQ producer and consumer info — groups, topics, and subscription details.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 8: MongoDB + Elasticsearch + Cassandra ──────────────────────

async function section8_nosql(page) {
  console.log('  [8/18] MongoDB + Elasticsearch + Cassandra');

  await typeMessage(page, 'My app uses MongoDB. Is the connection healthy? Show me server details, collections, and indexes.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'How\'s the Elasticsearch cluster? Is it green? Show me all indices with their document counts and shard info.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the Cassandra cluster topology — nodes, datacenter, keyspaces, and session stats.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 9: Batch + Quartz + Flowable + WebSocket ────────────────────

async function section9_workflows(page) {
  console.log('  [9/18] Batch + Quartz + Flowable + WebSocket');

  await typeMessage(page, 'What Spring Batch jobs are configured? Show me the execution history and step-level statistics.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What Quartz scheduled jobs are running and when are they next firing?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'This app uses Flowable BPM. Show me the deployed process definitions, active instances, and current user tasks.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any active WebSocket connections? Show me the WebSocket session stats and recent messages.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 10: GraphQL + OpenAPI + gRPC ────────────────────────────────

async function section10_api(page) {
  console.log('  [10/18] GraphQL + OpenAPI + gRPC');

  await typeMessage(page, 'What does the GraphQL schema look like? Show me all the queries and mutations available.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Generate the OpenAPI spec for this app. How many API paths and operations are there? Any undocumented endpoints?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any gRPC channels or services registered? Show me the gRPC call statistics if available.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 11: Security + OAuth2 + Sessions ────────────────────────────

async function section11_security(page) {
  console.log('  [11/18] Security + OAuth2 + Sessions');

  await typeMessage(page, 'Walk me through the security setup. Which URLs require authentication and which are public? What password encoder is used?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'This app has an OAuth2 Authorization Server. Show me the registered clients, grant types, scopes, and token configuration.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'How many active HTTP sessions are there? Show me session attributes and timeout configuration.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 12: Kerberos + TLS + LDAP [v0.7+] ───────────────────────────

async function section12_kerberos_tls(page) {
  console.log('  [12/18] Kerberos + TLS + LDAP');

  await typeMessage(page, 'Show me the Kerberos security configuration — ticket validator, SPNEGO filter, and authentication provider.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Inspect the keytab file at docker/kdc/keytabs/demo.keytab. List all principals and encryption types. Also show the Kerberos environment and krb5.conf settings.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check the TLS configuration — keystore info, certificate expiry, supported protocols and cipher suites.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Is there any LDAP configuration? Show me the context source and connection pool stats.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 13: Resilience + Sentinel + Profiling + Reactive ────────────

async function section13_resilience(page) {
  console.log('  [13/18] Resilience + Sentinel + Profiling + Reactive');

  await typeMessage(page, 'Are any circuit breakers tripped? Show me the resilience4j config — circuit breakers, rate limiters, and retry stats.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check Alibaba Sentinel — flow control rules, degrade rules, and real-time metrics.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Profile CPU hotspots for a few seconds and show me memory allocation hotspots. Also check if there\'s any reactive/WebFlux blocking I/O.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 14: Nacos + Cloud + Zookeeper + Dubbo ───────────────────────

async function section14_microservices(page) {
  console.log('  [14/18] Nacos + Cloud + Zookeeper + Dubbo');

  await typeMessage(page, 'Show me all services registered in Nacos. What config is being served by the Nacos config server?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check Spring Cloud service discovery and any cloud circuit breakers. Also check the Zookeeper cluster status and registered watchers.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Are there any Apache Dubbo services or references? Show me the Dubbo application config, thread pool, and registry status.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 15: Seata + Tracing + Camel + State Machine ─────────────────

async function section15_distributed(page) {
  console.log('  [15/18] Seata + Tracing + Camel + State Machine');

  await typeMessage(page, 'Show me the Seata distributed transaction configuration — global config, RM, TM, and TC server connectivity.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Is distributed tracing enabled? Show me recent spans and trace dependencies. Also check for slow spans.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'This app uses Apache Camel. Show me the Camel routes, their performance stats, and the context info.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check the Spring State Machine — what states and transitions are configured? What\'s the current state?');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 16: Vault + Object Storage + Distributed Cache ──────────────

async function section16_infra(page) {
  console.log('  [16/18] Vault + Object Storage + Distributed Cache');

  await typeMessage(page, 'Is HashiCorp Vault integrated? Show me the enabled secret engines and any secret metadata.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the MinIO/S3 object storage buckets — names, creation dates, and object counts. Also inspect a specific object.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Is there a distributed cache like Hazelcast or Infinispan? Show me the cluster members and cache statistics.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 17: Metrics + Events + Health + Cache ───────────────────────

async function section17_monitoring(page) {
  console.log('  [17/18] Metrics + Events + Health + Cache');

  await typeMessage(page, 'What JVM metrics is Micrometer tracking? Show me available metric names and meter registries.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'What Spring events have been published recently, and who\'s listening to them?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Check the Spring Boot Actuator health status. Also, how effective is the Spring Cache? Show me hit/miss ratios.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 18: AOP + OpenFeign + System Control [v0.8.1] ──────────────

async function section18_aop_feign(page) {
  console.log('  [18/19] AOP + OpenFeign + System Control');

  await typeMessage(page, 'Show me all AOP aspects in this app. What advice types are configured and what are their pointcut expressions?');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Which beans are AOP proxies? Also show me the detailed AOP advice info grouped by type — @Before, @Around, @AfterReturning.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'List all Feign clients with their target URLs and fallback classes. Also show the Feign client configuration — timeouts, logger level.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Capture a thread dump of all running threads. Also trigger a heap dump to a temp file and show me the file path.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Section 19: Logs + Environment + Classloading + WatchPoints ─────────

async function section19_system(page) {
  console.log('  [19/19] Logs + Environment + Classloading + WatchPoints');

  await typeMessage(page, 'Show me recent warning and error logs. How many classes has the app loaded? Also show the HttpClient connection pool stats.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Show me the environment configuration — what property sources are active? Also check what auto-configurations were enabled or disabled at startup.');
  await sendAndWait(page);
  await pause(page, 4000);

  await typeMessage(page, 'Set a watch point on the OrderService.getAllOrders method. Then search for loaded classes matching "OrderService" and find where the class is loaded from.');
  await sendAndWait(page);
  await pause(page, 5000);
}

// ─── Main ────────────────────────────────────────────────────────────────

(async () => {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  console.log('\n======================================================');
  console.log('  Spring Debug Agent v0.8.1 — Full Demo Recording');
  console.log('  246 tools / 66 inspectors / 19 sections');
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
    { name: '01-jvm-deep-dive',           fn: section1_jvm },
    { name: '02-spring-core-modulith',     fn: section2_spring },
    { name: '03-jmx-mbean',               fn: section3_jmx },
    { name: '04-web-http-endpoint',        fn: section4_web },
    { name: '05-database-sql-mybatis',     fn: section5_database },
    { name: '06-txn-threadpool-tasks',     fn: section6_txn },
    { name: '07-redis-kafka-rabbit-rocket',fn: section7_messaging },
    { name: '08-mongo-es-cassandra',       fn: section8_nosql },
    { name: '09-batch-quartz-flowable-ws', fn: section9_workflows },
    { name: '10-graphql-openapi-grpc',     fn: section10_api },
    { name: '11-security-oauth2-session',  fn: section11_security },
    { name: '12-kerberos-tls-ldap',        fn: section12_kerberos_tls },
    { name: '13-resilience-sentinel-prof', fn: section13_resilience },
    { name: '14-nacos-cloud-zk-dubbo',     fn: section14_microservices },
    { name: '15-seata-tracing-camel-sm',   fn: section15_distributed },
    { name: '16-vault-storage-distcache',   fn: section16_infra },
    { name: '17-metrics-events-health',     fn: section17_monitoring },
    { name: '18-aop-feign-sysctl',           fn: section18_aop_feign },
    { name: '19-logs-env-classload-wp',       fn: section19_system },
  ];

  const startTime = Date.now();

  for (let i = 0; i < sections.length; i++) {
    const section = sections[i];
    const elapsed = ((Date.now() - startTime) / 60000).toFixed(1);
    console.log(`\n--- [${i + 1}/${sections.length}] ${section.name} (elapsed: ${elapsed} min) ---`);
    await section.fn(page);
    await page.screenshot({ path: `${OUTPUT_DIR}/${VERSION}-demo-${section.name}.png` });
    console.log(`  Screenshot: ${VERSION}-demo-${section.name}.png`);
  }

  await page.evaluate(() => window.scrollTo(0, 0));
  await pause(page, 2000);

  const video = page.video();
  const videoPath = await video.path();
  console.log(`\n  Video path: ${videoPath}`);

  await context.close();
  await browser.close();

  // Rename video
  console.log('\n--- Finalizing video ---');
  const finalWebm = `${OUTPUT_DIR}/${VERSION}-full-demo.webm`;
  const finalMp4 = `${OUTPUT_DIR}/${VERSION}-full-demo.mp4`;

  try { fs.unlinkSync(finalWebm); } catch {}
  try { fs.unlinkSync(finalMp4); } catch {}

  if (videoPath && fs.existsSync(videoPath)) {
    fs.copyFileSync(videoPath, finalWebm);
    const size = fs.statSync(finalWebm).size;
    console.log(`  Saved: ${VERSION}-full-demo.webm (${(size / 1024 / 1024).toFixed(1)} MB)`);
  } else {
    const webmFiles = fs.readdirSync(OUTPUT_DIR)
      .filter(f => f.endsWith('.webm') && f !== `${VERSION}-full-demo.webm`)
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
    if (fs.existsSync(finalWebm)) {
      execSync(`ffmpeg -y -i "${finalWebm}" -c:v libx264 -preset fast -crf 23 -c:a aac "${finalMp4}"`, { stdio: 'pipe' });
      const size = fs.statSync(finalMp4).size;
      console.log(`  Done: ${VERSION}-full-demo.mp4 (${(size / 1024 / 1024).toFixed(1)} MB)`);
    }
  } catch (e) {
    console.log('  (ffmpeg conversion failed, keeping .webm)');
  }

  const totalMin = ((Date.now() - startTime) / 60000).toFixed(1);
  console.log('\n======================================================');
  console.log('  Recording complete!');
  console.log(`  Total time: ${totalMin} minutes`);
  console.log(`  Output: ${OUTPUT_DIR}/${VERSION}-full-demo.mp4`);
  console.log('======================================================\n');
})();
