#!/usr/bin/env node
/**
 * E2E Test Runner
 *
 * Discovers all test files (*-tests.js) in the e2e-tests/ directory, executes
 * them serially as child processes, captures exit codes and output, and
 * produces an aggregated colored report.
 *
 * Usage:
 *   node e2e-tests/test-runner.js                  # run all test files
 *   BASE_URL=http://localhost:9090 node e2e-tests/test-runner.js
 *   node e2e-tests/test-runner.js jvm-spring       # run specific file(s) by name match
 *
 * Features:
 *   - Serial execution (each suite runs one at a time)
 *   - Real-time output streaming from each test file
 *   - Exit code tracking (0 = pass, non-zero = fail)
 *   - Duration measurement per suite
 *   - Aggregated summary with colored output
 *   - Exit code reflects overall result (0 if all pass, 1 if any fail)
 */

const { execSync } = require('child_process');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

// ─── Color helpers ───────────────────────────────────────────────────────

const C = {
  green:  (s) => `\x1b[32m${s}\x1b[0m`,
  red:    (s) => `\x1b[31m${s}\x1b[0m`,
  yellow: (s) => `\x1b[33m${s}\x1b[0m`,
  cyan:   (s) => `\x1b[36m${s}\x1b[0m`,
  magenta:(s) => `\x1b[35m${s}\x1b[0m`,
  dim:    (s) => `\x1b[2m${s}\x1b[0m`,
  bold:   (s) => `\x1b[1m${s}\x1b[0m`,
};

// ─── Configuration ───────────────────────────────────────────────────────

const TEST_DIR = __dirname;
const BASE_URL = process.env.BASE_URL || 'http://localhost:8088';

// ─── Test File Discovery ─────────────────────────────────────────────────

/**
 * Discover all test files in the e2e-tests directory.
 * Excludes this file (test-runner.js) and non-test JS files.
 * Pattern: *-tests.js
 */
function discoverTestFiles() {
  const filterPattern = process.argv.slice(2); // optional name filters

  let files = fs
    .readdirSync(TEST_DIR)
    .filter((f) => f.endsWith('-tests.js'))
    .filter((f) => f !== path.basename(__filename))
    .sort();

  // Apply optional name filter (e.g., "jvm-spring" matches "jvm-spring-tests.js")
  if (filterPattern.length > 0) {
    files = files.filter((f) =>
      filterPattern.some((p) => f.toLowerCase().includes(p.toLowerCase()))
    );
  }

  return files.map((f) => path.join(TEST_DIR, f));
}

// ─── Test File Execution ─────────────────────────────────────────────────

/**
 * Run a single test file as a child process.
 * Streams stdout/stderr in real-time and captures exit code.
 *
 * Returns: {
 *   file:       string,     // file path
 *   name:       string,     // file name without extension
 *   passed:     boolean,    // true if exit code was 0
 *   exitCode:   number,     // process exit code
 *   durationMs: number,     // execution time in ms
 *   timedOut:   boolean,    // true if killed by timeout
 * }
 */
function runTestFile(filePath, index, total) {
  return new Promise((resolve) => {
    const fileName = path.basename(filePath, '.js');
    const startTime = Date.now();
    const TIMEOUT_MS = 600000; // 10 minutes per suite (LLM calls can be slow)

    console.log(C.bold(C.cyan(`\n${'='.repeat(60)}`)));
    console.log(C.bold(C.cyan(`  Suite ${index + 1}/${total}: ${fileName}`)));
    console.log(C.bold(C.cyan(`  File: ${filePath}`)));
    console.log(C.bold(C.cyan(`${'='.repeat(60)}`)));

    const child = spawn('node', [filePath], {
      cwd: path.dirname(filePath),
      env: { ...process.env, BASE_URL },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let timedOut = false;
    const timeoutHandle = setTimeout(() => {
      timedOut = true;
      child.kill('SIGTERM');
      setTimeout(() => {
        if (!child.killed) child.kill('SIGKILL');
      }, 5000);
    }, TIMEOUT_MS);

    // Stream child stdout to our stdout in real-time
    child.stdout.on('data', (data) => {
      process.stdout.write(data);
    });

    child.stderr.on('data', (data) => {
      process.stderr.write(data);
    });

    child.on('close', (code) => {
      clearTimeout(timeoutHandle);
      const durationMs = Date.now() - startTime;
      const passed = code === 0 && !timedOut;

      resolve({
        file: filePath,
        name: fileName,
        passed,
        exitCode: code,
        durationMs,
        timedOut,
      });
    });

    child.on('error', (err) => {
      clearTimeout(timeoutHandle);
      const durationMs = Date.now() - startTime;
      console.error(C.red(`  [ERROR] Failed to spawn process: ${err.message}`));
      resolve({
        file: filePath,
        name: fileName,
        passed: false,
        exitCode: -1,
        durationMs,
        timedOut: false,
      });
    });
  });
}

// ─── Pre-flight: Connectivity Check ──────────────────────────────────────

/**
 * Quick health check to verify the agent is reachable before running tests.
 */
function checkConnectivity() {
  return new Promise((resolve) => {
    const { host, port } = new URL(BASE_URL);
    const net = require('net');
    const socket = new net.Socket();
    socket.setTimeout(5000);

    socket.on('connect', () => {
      socket.destroy();
      resolve(true);
    });

    socket.on('timeout', () => {
      socket.destroy();
      resolve(false);
    });

    socket.on('error', () => {
      socket.destroy();
      resolve(false);
    });

    socket.connect(parseInt(port) || 80, host);
  });
}

// ─── Report Helpers ──────────────────────────────────────────────────────

function formatDuration(ms) {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  const mins = Math.floor(ms / 60000);
  const secs = Math.floor((ms % 60000) / 1000);
  return `${mins}m ${secs}s`;
}

// ─── Main ────────────────────────────────────────────────────────────────

async function main() {
  const testFiles = discoverTestFiles();

  if (testFiles.length === 0) {
    console.log(C.yellow('No test files found matching *-tests.js in e2e-tests/.'));
    console.log(C.dim('  Expected files like: jvm-spring-tests.js, infra-web-tests.js, etc.'));
    process.exit(0);
  }

  console.log(C.bold('═══════════════════════════════════════════════════════'));
  console.log(C.bold('  E2E Test Runner — Spring Debug Agent'));
  console.log(C.bold(`  Target: ${BASE_URL}`));
  console.log(C.bold(`  Suites: ${testFiles.length}`));
  if (process.argv.length > 2) {
    console.log(C.dim(`  Filter: ${process.argv.slice(2).join(', ')}`));
  }
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  // Pre-flight connectivity check
  const reachable = await checkConnectivity();
  if (!reachable) {
    console.log(C.red(`  [FATAL] Cannot reach ${BASE_URL}`));
    console.log(C.dim('  Make sure the Spring Boot application is running.'));
    console.log(C.dim(`  Start it with: mvn spring-boot:run -pl demo (or set BASE_URL to the correct host:port)`));
    console.log('');
    process.exit(1);
  }
  console.log(C.green(`  [OK] Agent reachable at ${BASE_URL}\n`));

  // Execute test suites serially
  const results = [];
  for (let i = 0; i < testFiles.length; i++) {
    const result = await runTestFile(testFiles[i], i, testFiles.length);
    results.push(result);

    // Print per-suite result line
    const status = result.timedOut
      ? C.yellow('TIMEOUT')
      : result.passed
        ? C.green('PASS')
        : C.red('FAIL');
    console.log(C.bold(C.cyan(`\n${'─'.repeat(60)}`)));
    console.log(`  ${status}  ${result.name}  ${C.dim(`(${formatDuration(result.durationMs)})`)}`);
    console.log(C.cyan(`${'─'.repeat(60)}\n`));
  }

  // ─── Aggregated Summary ────────────────────────────────────────────
  const totalSuites = results.length;
  const passedSuites = results.filter((r) => r.passed).length;
  const failedSuites = results.filter((r) => !r.passed && !r.timedOut).length;
  const timedOutSuites = results.filter((r) => r.timedOut).length;
  const totalDuration = results.reduce((sum, r) => sum + r.durationMs, 0);

  console.log(C.bold('═══════════════════════════════════════════════════════'));
  console.log(C.bold('  AGGREGATED RESULTS'));
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  // Per-suite breakdown
  console.log('  Suite Results:');
  for (const r of results) {
    const icon = r.timedOut ? C.yellow('[TIMEOUT]') : r.passed ? C.green('[PASS]   ') : C.red('[FAIL]   ');
    console.log(`    ${icon} ${r.name.padEnd(30)} ${C.dim(formatDuration(r.durationMs))}`);
  }

  console.log('');
  console.log(`  Suites passed:    ${C.green(passedSuites)} / ${totalSuites}`);
  if (failedSuites > 0) console.log(`  Suites failed:    ${C.red(failedSuites)}`);
  if (timedOutSuites > 0) console.log(`  Suites timed out: ${C.yellow(timedOutSuites)}`);
  console.log(`  Total duration:   ${C.dim(formatDuration(totalDuration))}`);

  const allPassed = failedSuites === 0 && timedOutSuites === 0;

  console.log('');
  console.log(
    allPassed
      ? C.green(C.bold('  ALL TEST SUITES PASSED'))
      : failedSuites > 0
        ? C.red(C.bold(`  ${failedSuites} SUITE(S) FAILED`))
        : C.yellow(C.bold(`  ${timedOutSuites} SUITE(S) TIMED OUT`))
  );
  console.log(C.bold('═══════════════════════════════════════════════════════\n'));

  process.exit(allPassed ? 0 : 1);
}

// ─── Entry Point ─────────────────────────────────────────────────────────

main().catch((err) => {
  console.error(C.red(`\nFatal error in test runner: ${err.message}`));
  console.error(err.stack);
  process.exit(2);
});
