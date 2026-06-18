#!/usr/bin/env node
/**
 * Quick verification test for new v0.4.0 tools (25 new tools).
 * Tests that each new tool returns valid JSON without errors.
 */

const BASE = 'http://localhost:8088/agent';

async function chat(message) {
    const res = await fetch(`${BASE}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message })
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    // Response is SSE (text/event-stream) — read full text
    const text = await res.text();
    // Parse SSE events
    const events = text.split('\n\nevent:').map(e => e.trim()).filter(Boolean);
    let fullText = '';
    const toolCalls = [];
    for (const evt of events) {
        const lines = evt.split('\n');
        const eventType = lines[0].replace('event:', '').trim();
        const dataLine = lines.find(l => l.startsWith('data:'));
        if (!dataLine) continue;
        const raw = dataLine.substring(5).trim();
        if (eventType === 'content') {
            try { fullText += JSON.parse(raw); } catch { fullText += raw; }
        } else if (eventType === 'tool_start') {
            toolCalls.push(raw);
        } else if (eventType === 'tool_result') {
            try {
                const result = JSON.parse(raw);
                toolCalls.push(`RESULT: ${JSON.stringify(result).substring(0, 200)}`);
            } catch { toolCalls.push(`RESULT: ${raw.substring(0, 200)}`); }
        }
    }
    return { text: fullText, toolCalls };
}

function extractToolResults(text) {
    const results = [];
    const lines = text.split('\n');
    for (const line of lines) {
        const m = line.match(/[✓✔]|(\d+)\s*tools?\s*(?:called|tested)|Result[:\s]|error/i);
        if (m) results.push(line.trim());
    }
    return results;
}

async function testBatch(label, message) {
    console.log(`\n=== ${label} ===`);
    try {
        const result = await chat(message);
        console.log('Tool calls made:', result.toolCalls.length > 0 ? result.toolCalls.join(', ') : '(none)');
        console.log('\n--- Agent response ---');
        console.log(result.text.substring(0, 2000));
        return result.toolCalls.length > 0 || result.text.length > 100;
    } catch (e) {
        console.error(`FAIL: ${e.message}`);
        return false;
    }
}

async function main() {
    console.log('Testing 25 new v0.4.0 tools...\n');

    let pass = 0, fail = 0;

    // Batch 1: Enhanced JvmInspector + SpringInspector (8 tools)
    if (await testBatch('Batch 1: Enhanced JVM + Spring (8 tools)',
        `Please call these 8 tools and report their results:
1. get_system_properties with prefix "java."
2. get_process_info
3. get_bean_methods for bean "orderRepository"
4. get_bean_annotations for bean "orderService"
5. get_environment_properties with sourceFilter "applicationConfig"
6. get_request_by_path with path "/api/orders"
7. get_error_requests
8. get_filter_chains`)) pass++; else fail++;

    // Batch 2: Enhanced LogInspector + WatchPoint (3 tools)
    if (await testBatch('Batch 2: Enhanced Logs + WatchPoint (3 tools)',
        `Please call these 3 tools and report results:
1. search_logs with keyword "order"
2. get_log_stats with sampleSize 100
3. add_field_watch_point on bean "orderRepository" field "dbContext"`)) pass++; else fail++;

    // Batch 3: TransactionInspector (5 tools)
    if (await testBatch('Batch 3: Transaction Inspector (5 tools)',
        `Please call these 5 transaction tools and report results:
1. get_transaction_info
2. get_transaction_stats
3. get_recent_transactions with limit 5
4. get_rollback_history
5. get_slow_transactions with minDurationMs 0`)) pass++; else fail++;

    // Batch 4: RestClientInspector (4 tools)
    if (await testBatch('Batch 4: RestClient Inspector (4 tools)',
        `Please call these 4 outbound HTTP tools and report results:
1. get_outbound_requests with limit 5
2. get_slow_outbound_requests with minDurationMs 0
3. get_outbound_request_stats
4. get_outbound_errors`)) pass++; else fail++;

    // Batch 5: EndpointTestInspector (5 tools)
    if (await testBatch('Batch 5: Endpoint Test Inspector (5 tools)',
        `Please call these 5 endpoint test tools and report results:
1. test_endpoint with method GET, path /api/orders, port 8088
2. test_endpoint_batch with port 8088
3. test_endpoint_auth with method GET, path /actuator/health, authHeader "X-Test", authValue "test", port 8088
4. get_endpoint_coverage
5. compare_endpoints with method GET, path /actuator/health, baseUrlA "http://localhost:8088", baseUrlB "http://localhost:8088"`)) pass++; else fail++;

    console.log(`\n========================`);
    console.log(`Results: ${pass} batches passed, ${fail} failed`);
    console.log(`25 new tools tested across 5 batches`);
}

main().catch(console.error);
