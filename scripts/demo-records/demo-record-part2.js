const { chromium } = require('playwright');
const fs = require('fs');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const OUTPUT_DIR = './demo-recordings';

async function typeMessage(page, text, charDelay = 6) {
  const input = page.locator('#input');
  await input.click();
  await input.pressSequentially(text, { delay: charDelay });
}

async function waitForAgentIdle(page, timeout = 120000) {
  await page.waitForSelector('.typing', { timeout: 15000 }).catch(() => {});
  await page.waitForFunction(() => document.querySelectorAll('.typing').length === 0, { timeout }).catch(() => {});
  try {
    await page.waitForFunction(() => {
      const btn = document.querySelector('#send');
      return btn && !btn.disabled && btn.textContent.trim() === 'Send';
    }, { timeout });
  } catch {
    console.log('  ...extended wait');
    await page.waitForTimeout(30000);
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

async function sendAndWait(page, timeout = 120000) {
  await page.locator('#send').click();
  await waitForAgentIdle(page, timeout);
}

async function pause(page, ms = 3000) { await page.waitForTimeout(ms); }

(async () => {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  console.log('\n=== Part 2: Sections 13-16 ===\n');

  const browser = await chromium.launch({ headless: false, args: ['--window-size=1280,860'] });
  const context = await browser.newContext({
    viewport: { width: 1280, height: 860 },
    recordVideo: { dir: OUTPUT_DIR, size: { width: 1280, height: 860 } },
  });
  const page = await context.newPage();
  await page.goto(`${BASE_URL}/agent`, { waitUntil: 'networkidle' });
  await pause(page, 2000);

  // Section 13: Tracing + gRPC + Cloud Discovery
  console.log('  [13/16] Tracing + gRPC + Cloud Discovery');
  await typeMessage(page, "Is distributed tracing enabled? Show me recent traces and span information.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What gRPC channels are configured? Show me channel state and connection details.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What services are registered in service discovery? Show me all discovered service instances.");
  await sendAndWait(page); await pause(page, 4000);

  await page.screenshot({ path: `${OUTPUT_DIR}/full-demo-13-trace-grpc.png` });
  console.log('  Screenshot: 13');

  // Section 14: OAuth2 + Vault + Object Storage
  console.log('  [14/16] OAuth2 + Vault + Object Storage');
  await typeMessage(page, "What OAuth2 clients are registered? Show me client IDs, grant types, scopes, and token settings.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Is HashiCorp Vault connected? Show me the configured secret engines and list available secret paths.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What S3 or MinIO buckets exist? Show me the object storage buckets and their contents.");
  await sendAndWait(page); await pause(page, 4000);

  await page.screenshot({ path: `${OUTPUT_DIR}/full-demo-14-oauth-vault.png` });
  console.log('  Screenshot: 14');

  // Section 15: Profiling + Logs + Environment + Modulith
  console.log('  [15/16] Profiling + Logs + Environment');
  await typeMessage(page, "Run a quick CPU allocation profile — what are the top allocating classes?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me recent log entries. Are there any WARNING or ERROR level logs?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the Spring Modulith module structure — what modules exist and their dependencies.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "What Spring Boot auto-configurations were enabled or disabled?");
  await sendAndWait(page); await pause(page, 4000);

  await page.screenshot({ path: `${OUTPUT_DIR}/full-demo-15-profile-env.png` });
  console.log('  Screenshot: 15');

  // Section 16: Reactive + Resilience + HttpClient + Health
  console.log('  [16/16] Reactive + Resilience + HttpClient');
  await typeMessage(page, "Is Spring WebFlux reactive stack available? What reactive endpoints or schedulers are configured?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Show me the Resilience4j configuration — circuit breakers, rate limiters, retries. Any tripped?");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "How is the Apache HttpClient connection pool performing? Show me pool stats and connection reuse.");
  await sendAndWait(page); await pause(page, 3000);

  await typeMessage(page, "Give me a complete application health summary — all actuator health indicators.");
  await sendAndWait(page); await pause(page, 4000);

  await page.screenshot({ path: `${OUTPUT_DIR}/full-demo-16-reactive.png` });
  console.log('  Screenshot: 16');

  // Finalize
  const video = page.video();
  const videoPath = await video.path();
  await context.close();
  await browser.close();

  console.log('\n--- Converting ---');
  const { execSync } = require('child_process');
  const outMp4 = `${OUTPUT_DIR}/full-coverage-part2.mp4`;
  try { fs.unlinkSync(outMp4); } catch {}

  if (videoPath && fs.existsSync(videoPath)) {
    execSync(`ffmpeg -y -i "${videoPath}" -c:v libx264 -preset ultrafast -crf 28 "${outMp4}"`, { stdio: 'pipe' });
    console.log(`  Part 2: ${(fs.statSync(outMp4).size / 1024 / 1024).toFixed(1)} MB`);
  }

  console.log('\n=== Part 2 Complete ===\n');
})();
