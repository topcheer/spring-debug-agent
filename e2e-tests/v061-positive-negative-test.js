/**
 * v0.6.1 Positive/Negative Tool Call Tests
 *
 * POSITIVE: Tools return real data when resources exist
 * NEGATIVE: Tools called with invalid/missing params — must not crash
 *
 * Tool names verified from inspector source code:
 *   Mongo: get_mongo_info, get_mongo_collections, get_mongo_slow_queries, get_mongo_indexes
 *   ES: get_es_cluster_health, get_es_indices, get_es_slow_log
 *   Batch: get_batch_jobs, get_batch_job_executions, get_batch_step_stats, get_batch_failures
 *   Quartz: get_quartz_jobs, get_quartz_triggers, get_quartz_job_history
 *   GraphQL: get_graphql_schema, get_graphql_queries, get_graphql_errors
 *   OpenAPI: get_openapi_spec, validate_openapi, detect_openapi_drift
 *   ThreadPool: get_thread_pools, get_thread_pool_stats, get_rejected_task_counts
 *   WebSocket: get_websocket_sessions, get_websocket_stats, get_websocket_messages
 */

const { spawnSync } = require('child_process');
const RESULTS = { pass: 0, fail: 0, errors: [] };

// ── Helpers ──────────────────────────────────────────────────────

function callTool(message, toolNames) {
    const result = spawnSync('curl', [
        '-s', '-X', 'POST', 'http://localhost:8080/agent/api/chat',
        '-H', 'Content-Type: application/json',
        '-d', JSON.stringify({ message }),
        '--max-time', '45',
    ], { encoding: 'utf8', timeout: 50000, maxBuffer: 10 * 1024 * 1024 });

    const raw = result.stdout || '';
    const toolResults = {};

    const events = raw.split(/\r?\n\s*\r?\n/);
    for (const evt of events) {
        const lines = evt.split(/\r?\n/);
        let eventType = '';
        let dataParts = [];

        for (const line of lines) {
            if (line.startsWith('event:')) eventType = line.slice(6).trim();
            else if (line.startsWith('data:')) dataParts.push(line.slice(5));
        }

        if (eventType !== 'tool_result' || dataParts.length === 0) continue;
        const fullData = dataParts.join('\n').trim();

        for (const toolName of toolNames) {
            const prefix = toolName + ':';
            if (fullData.startsWith(prefix)) {
                let jsonStr = fullData.slice(prefix.length).trim();
                try {
                    toolResults[toolName] = JSON.parse(jsonStr);
                } catch {
                    try {
                        jsonStr = jsonStr.replace(/[\x00-\x1f]/g, '');
                        toolResults[toolName] = JSON.parse(jsonStr);
                    } catch {
                        toolResults[toolName] = { _raw: jsonStr.slice(0, 300) };
                    }
                }
            }
        }
    }

    return toolResults;
}

function assert(name, condition, detail = '') {
    if (condition) {
        RESULTS.pass++;
        console.log(`  PASS  ${name}`);
    } else {
        RESULTS.fail++;
        RESULTS.errors.push(name);
        console.log(`  FAIL  ${name}${detail ? ' -- ' + detail : ''}`);
    }
}

function dt(v) {
    if (v == null) return 'undefined';
    if (v._raw) return `raw:${v._raw.slice(0, 120)}`;
    return JSON.stringify(v).slice(0, 150);
}

function jhas(v, kw) {
    if (v == null) return false;
    return JSON.stringify(v).toLowerCase().includes(kw.toLowerCase());
}

// ── POSITIVE TESTS ───────────────────────────────────────────────

function positiveTests() {
    console.log('\n=== POSITIVE TESTS (expect real data) ===\n');

    // MongoDB
    {
        const d = callTool('Use get_mongo_collections now', ['get_mongo_collections']).get_mongo_collections;
        assert('Mongo: collections has audit_logs', Array.isArray(d) && d.some(c => c.name === 'audit_logs'), dt(d));
        if (Array.isArray(d)) {
            const c = d.find(x => x.name === 'audit_logs');
            assert('Mongo: audit_logs has >=5 docs', c?.documentCount >= 5, `docCount=${c?.documentCount}`);
        }
    }
    {
        const d = callTool('Use get_mongo_info now', ['get_mongo_info']).get_mongo_info;
        assert('Mongo: info databaseName=demo_db', d?.databaseName === 'demo_db', dt(d));
    }
    {
        const d = callTool('Use get_mongo_indexes with collectionName=audit_logs', ['get_mongo_indexes']).get_mongo_indexes;
        assert('Mongo: indexes returns data', Array.isArray(d), dt(d));
    }

    // ES
    {
        const d = callTool('Use get_es_indices now', ['get_es_indices']).get_es_indices;
        assert('ES: indices has products', Array.isArray(d) && d.some(i => i.index === 'products'), dt(d));
    }
    {
        const d = callTool('Use get_es_cluster_health now', ['get_es_cluster_health']).get_es_cluster_health;
        assert('ES: cluster status green/yellow', d?.status === 'green' || d?.status === 'yellow', dt(d));
    }

    // Batch
    {
        const d = callTool('Use get_batch_jobs now', ['get_batch_jobs']).get_batch_jobs;
        assert('Batch: has importOrderJob',
            Array.isArray(d) && d.some(j => j.name === 'importOrderJob' || j.jobName === 'importOrderJob'), dt(d));
    }
    {
        const d = callTool('Use get_batch_job_executions with jobName=importOrderJob', ['get_batch_job_executions']).get_batch_job_executions;
        assert('Batch: executions returns data', d !== undefined, dt(d));
    }

    // Quartz
    {
        const d = callTool('Use get_quartz_jobs now', ['get_quartz_jobs']).get_quartz_jobs;
        assert('Quartz: has cleanup/report job', jhas(d, 'cleanup') || jhas(d, 'report'), dt(d));
    }
    {
        const d = callTool('Use get_quartz_triggers now', ['get_quartz_triggers']).get_quartz_triggers;
        assert('Quartz: triggers exist', jhas(d, 'cleanup') || jhas(d, 'report') || jhas(d, 'Trigger'), dt(d));
    }

    // GraphQL
    {
        const d = callTool('Use get_graphql_schema now', ['get_graphql_schema']).get_graphql_schema;
        assert('GraphQL: has Query type', jhas(d, 'Query') || jhas(d, 'queryType'), dt(d));
        assert('GraphQL: has orderById field', jhas(d, 'orderById'), dt(d));
    }

    // OpenAPI
    {
        const d = callTool('Use get_openapi_spec now', ['get_openapi_spec']).get_openapi_spec;
        assert('OpenAPI: pathCount > 0', (d?.pathCount || 0) > 0, dt(d));
        assert('OpenAPI: operationCount > 0', (d?.operationCount || 0) > 0, dt(d));
    }

    // Thread pools
    {
        const d = callTool('Use get_thread_pools now', ['get_thread_pools']).get_thread_pools;
        assert('ThreadPool: returns list', Array.isArray(d) && d.length > 0, dt(d));
    }

    // WebSocket
    {
        const d = callTool('Use get_websocket_sessions now', ['get_websocket_sessions']).get_websocket_sessions;
        assert('WebSocket: tool runs without crash', d !== undefined, dt(d));
    }
}

// ── NEGATIVE TESTS ───────────────────────────────────────────────

function negativeTests() {
    console.log('\n=== NEGATIVE TESTS (expect graceful handling) ===\n');

    // N1. MongoDB — indexes for nonexistent collection
    {
        const d = callTool('Use get_mongo_indexes with collectionName=does_not_exist', ['get_mongo_indexes']).get_mongo_indexes;
        assert('Mongo(Neg): nonexistent collection indexes handled',
            d !== undefined && (jhas(d, 'error') || jhas(d, 'not found') || (Array.isArray(d) && d.length === 0)),
            dt(d));
    }

    // N2. Batch — executions for nonexistent job (returns info message)
    {
        const d = callTool('Use get_batch_job_executions with jobName=does_not_exist', ['get_batch_job_executions']).get_batch_job_executions;
        assert('Batch(Neg): nonexistent job executions handled',
            d !== undefined && (jhas(d, 'error') || jhas(d, 'not found') || jhas(d, 'info') ||
                jhas(d, 'No executions') || (Array.isArray(d) && d.length === 0)),
            dt(d));
    }

    // N3. Batch — step stats for nonexistent job (returns empty or all stats)
    {
        const d = callTool('Use get_batch_step_stats with jobName=fake_job', ['get_batch_step_stats']).get_batch_step_stats;
        assert('Batch(Neg): nonexistent step stats handled (no crash)',
            d !== undefined, dt(d));
    }

    // N4. Quartz — job history (may be empty since no jobs have run)
    {
        const d = callTool('Use get_quartz_job_history now', ['get_quartz_job_history']).get_quartz_job_history;
        assert('Quartz(Neg): job history handled (empty is OK)',
            d !== undefined, dt(d));
    }

    // N5. GraphQL — queries (may be empty if no queries recorded)
    {
        const d = callTool('Use get_graphql_queries now', ['get_graphql_queries']).get_graphql_queries;
        assert('GraphQL(Neg): queries handled (empty is OK)',
            d !== undefined, dt(d));
    }

    // N6. GraphQL — errors (should be empty or have no errors)
    {
        const d = callTool('Use get_graphql_errors now', ['get_graphql_errors']).get_graphql_errors;
        assert('GraphQL(Neg): errors handled (empty is OK)',
            d !== undefined, dt(d));
    }

    // N7. Batch — failures (should be empty if no failed jobs)
    {
        const d = callTool('Use get_batch_failures now', ['get_batch_failures']).get_batch_failures;
        assert('Batch(Neg): failures handled (empty is OK)',
            d !== undefined, dt(d));
    }

    // N8. OpenAPI — validate (should return issues or empty)
    {
        const d = callTool('Use validate_openapi now', ['validate_openapi']).validate_openapi;
        assert('OpenAPI(Neg): validate returns result',
            d !== undefined, dt(d));
    }

    // N9. ES — slow queries (may return empty/no config)
    {
        const r = callTool('Use get_es_slow_queries now', ['get_es_slow_queries']);
        const d = r.get_es_slow_queries;
        assert('ES(Neg): slow queries handled (no crash)',
            d !== undefined, dt(d));
    }
}

// ── Main ─────────────────────────────────────────────────────────

function main() {
    console.log('+----------------------------------------------+');
    console.log('|  v0.6.1 Positive/Negative Tool Call Tests   |');
    console.log('+----------------------------------------------+');

    const hc = spawnSync('curl', ['-s', '-o', '/dev/null', '-w', '%{http_code}', 'http://localhost:8080/agent/api/health'],
        { encoding: 'utf8', timeout: 5000 });
    if (hc.stdout?.trim() !== '200') {
        console.error('Agent endpoint unreachable');
        process.exit(1);
    }
    console.log('Agent endpoint reachable\n');

    positiveTests();
    negativeTests();

    console.log('\n----------------------------------------------');
    console.log(`  Results: ${RESULTS.pass} passed, ${RESULTS.fail} failed`);
    if (RESULTS.errors.length > 0) {
        console.log(`  Failed: ${RESULTS.errors.join(', ')}`);
    }
    console.log('----------------------------------------------\n');

    process.exit(RESULTS.fail > 0 ? 1 : 0);
}

main();
