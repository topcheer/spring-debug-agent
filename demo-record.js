const { chromium } = require('playwright');

/**
 * Automated demo recording for Spring Debug Agent.
 *
 * Runs through several realistic debugging scenarios via the chat UI,
 * recording each as a separate .webm video.
 *
 * Usage:
 *   node demo-record.js
 *
 * Prerequisites:
 *   - Demo app running at http://localhost:8080
 *   - npm install playwright  (already done via npx)
 */

const BASE_URL = 'http://localhost:8080/agent';
const OUTPUT_DIR = '/Users/zhanju/springagent/demo-recordings';

// Helper: wait for a condition with generous timeout for LLM responses
const LLM_TIMEOUT = 120_000; // 2 minutes per scenario

async function typeSlowly(page, selector, text, delay = 30) {
  await page.click(selector);
  for (const char of text) {
    await page.keyboard.type(char, { delay });
  }
}

async function sendMessageAndWait(page, message) {
  // Type the message
  await typeSlowly(page, 'textarea#input', message, 15);
  await page.waitForTimeout(500);
  // Click send
  await page.click('button#send');

  // Wait for streaming to start (send button disabled)
  await page.waitForFunction(() => {
    const btn = document.querySelector('button#send');
    return btn && btn.disabled;
  }, { timeout: 10_000 }).catch(() => {});

  // Wait for streaming to complete (send button re-enabled with 'Send' text)
  await page.waitForFunction(() => {
    const btn = document.querySelector('button#send');
    return btn && !btn.disabled && btn.textContent.trim() === 'Send';
  }, { timeout: LLM_TIMEOUT }).catch((e) => {
    console.log('  ! LLM response timed out, continuing...');
  });

  // Extra pause to let final markdown render settle
  await page.waitForTimeout(2000);
}

async function waitForTypingStart(page) {
  // Wait for either typing indicator or tool badge to appear
  await page.waitForFunction(() => {
    return document.querySelector('.typing, .tool-badge') !== null;
  }, { timeout: 10_000 }).catch(() => {});
}

async function clearConversation(page) {
  await page.click('#clear-link');
  await page.click('text=Clear conversation history' ).catch(async () => {
    // Handle confirm dialog
  });
  await page.waitForTimeout(500);
}

// ==================== Recordings ====================

const scenarios = [
  {
    name: '01-jvm-health-check',
    title: 'JVM Health Check',
    messages: [
      'Check the current JVM memory usage and GC statistics for me',
    ],
  },
  {
    name: '02-thread-analysis',
    title: 'Thread Analysis',
    messages: [
      'How many threads are currently running? Are there any deadlock risks? Show the key threads in a table',
    ],
  },
  {
    name: '03-spring-beans',
    title: 'Spring Bean Inspection',
    messages: [
      'List all Service-type beans with their class names and package paths',
    ],
  },
  {
    name: '04-order-debug',
    title: 'Order Service Debugging',
    messages: [
      'Inspect the OrderService bean fields and internal state, I want to understand its configuration',
    ],
  },
  {
    name: '05-comprehensive',
    title: 'Comprehensive Diagnosis',
    messages: [
      'Run a full health check: JVM memory, thread status, and Spring context info. Summarize in markdown',
    ],
  },
];

(async () => {
  const browser = await chromium.launch({
    headless: false,
    args: ['--window-size=1280,860'],
  });

  for (const scenario of scenarios) {
    console.log(`\n🎬 Recording: ${scenario.name} — "${scenario.title}"`);

    const context = await browser.newContext({
      viewport: { width: 1280, height: 860 },
      recordVideo: {
        dir: OUTPUT_DIR,
        size: { width: 1280, height: 860 },
      },
    });

    const page = await context.newPage();

    // Handle any dialogs
    page.on('dialog', dialog => dialog.accept());

    try {
      // Navigate
      await page.goto(BASE_URL, { waitUntil: 'networkidle' });
      await page.waitForTimeout(2000);

      // Run messages
      for (const msg of scenario.messages) {
        console.log(`  → Sending: ${msg.substring(0, 50)}...`);
        await sendMessageAndWait(page, msg);
        console.log(`  ✓ Response complete`);
      }

      // Hold the final frame for a few seconds
      await page.waitForTimeout(3000);
    } catch (err) {
      console.log(`  ! Error in scenario: ${err.message}`);
    }

    // Close context to finalize the video
    await context.close();
    console.log(`  🎥 Saved to ${OUTPUT_DIR}/${scenario.name}.webm`);
  }

  await browser.close();
  console.log('\n✅ All recordings complete!');
  console.log(`📁 Videos saved in: ${OUTPUT_DIR}`);
})();
