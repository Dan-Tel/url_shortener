import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve('benchmarks/reports/read');
const OUTPUT = path.resolve('benchmarks/reports');

// Recursively find all JSON reports
function findFiles(directory) {
    if (!fs.existsSync(directory)) return [];

    return fs.readdirSync(directory, {
        withFileTypes: true,
    }).flatMap((entry) => {
        const fullPath = path.join(directory, entry.name);

        if (entry.isDirectory()) {
            return findFiles(fullPath);
        }

        return [fullPath];
    });
}

function median(values) {
    const sorted = [...values].sort((a, b) => a - b);
    const middle = Math.floor(sorted.length / 2);

    if (sorted.length % 2 !== 0) {
        return sorted[middle];
    }

    return (sorted[middle - 1] + sorted[middle]) / 2;
}

function getMetric(metrics, name) {
    const metric = metrics?.[name];
    return metric?.values ?? metric ?? {};
}

function parseReport(file) {
    const filename = path.basename(file);

    const match = filename.match(
        /^read-(rest-http1|rest-http2|grpc)-(\d+)-(\d+)-(.+)\.json$/
    );

    if (!match) return null;

    const [, stack, rps, run, session] = match;

    const report = JSON.parse(
        fs.readFileSync(file, 'utf8')
    );

    const metrics = report.metrics ?? {};

    const latencyMetric =
        stack === 'grpc'
            ? 'grpc_req_duration'
            : 'http_req_duration';

    const latency = getMetric(metrics, latencyMetric);
    const checks = getMetric(metrics, 'checks');
    const dropped = getMetric(metrics, 'dropped_iterations');
    const iterations = getMetric(metrics, 'iterations');

    if (
        !Number.isFinite(latency.avg) ||
        !Number.isFinite(latency['p(95)']) ||
        !Number.isFinite(latency['p(99)'])
    ) {
        throw new Error(`Missing latency metrics: ${file}`);
    }

    return {
        stack,
        rps: Number(rps),
        run: Number(run),
        session,

        avg: latency.avg,
        p95: latency['p(95)'],
        p99: latency['p(99)'],

        actualRps: iterations.rate ?? 0,
        failedChecks: checks.fails ?? 0,
        dropped: dropped.count ?? 0,
    };
}

// Read all reports
const reports = findFiles(ROOT)
    .filter((file) => file.endsWith('.json'))
    .map(parseReport)
    .filter(Boolean);

if (reports.length === 0) {
    console.log('No benchmark reports found.');
    process.exit(0);
}

// Select the latest session for each protocol
const latestSessions = new Map();

for (const report of reports) {
    const key = `${report.stack}:${report.rps}`;
    const previous = latestSessions.get(key);

    if (!previous || report.session > previous) {
        latestSessions.set(key, report.session);
    }
}

const selected = reports.filter((report) => {
    const key = `${report.stack}:${report.rps}`;

    return report.session === latestSessions.get(key);
});

// Group by protocol and target RPS
const groups = new Map();

for (const report of selected) {
    const key = `${report.stack}:${report.rps}`;

    if (!groups.has(key)) {
        groups.set(key, []);
    }

    groups.get(key).push(report);
}

// Calculate results
const results = [];

for (const [key, runs] of groups) {
    const [stack, rps] = key.split(':');

    results.push({
        stack,
        rps: Number(rps),
        runs: runs.length,

        avg: median(runs.map((r) => r.avg)),
        p95: median(runs.map((r) => r.p95)),
        p99: median(runs.map((r) => r.p99)),

        actualRps: median(runs.map((r) => r.actualRps)),

        failedChecks: runs.reduce(
            (sum, r) => sum + r.failedChecks,
            0
        ),

        dropped: runs.reduce(
            (sum, r) => sum + r.dropped,
            0
        ),
    });
}

const order = {
    'rest-http1': 0,
    'rest-http2': 1,
    grpc: 2,
};

results.sort(
    (a, b) =>
        a.rps - b.rps ||
        order[a.stack] - order[b.stack]
);

const format = (value) => value.toFixed(3);

// Markdown report
const markdown = [
    '# SIS #2 — READ Latency Comparison',
    '',
    'PostgreSQL + Redis, TLS enabled, warmed cache.',
    '',
    'Latency in milliseconds. Values are medians across runs.',
    '',
    '| Protocol | Target RPS | Runs | Avg | p95 | p99 | Actual RPS | Failed checks | Dropped |',
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|',
    ...results.map((r) =>
        `| ${r.stack} | ${r.rps} | ${r.runs} | ${format(r.avg)} | ${format(r.p95)} | ${format(r.p99)} | ${format(r.actualRps)} | ${r.failedChecks} | ${r.dropped} |`
    ),
    '',
    'Note: Median of per-run p95 values is not the pooled p95 across all requests.',
    '',
].join('\n');

// CSV report
const csv = [
    'protocol,target_rps,runs,avg_ms,p95_ms,p99_ms,actual_rps,failed_checks,dropped',
    ...results.map((r) =>
        [
            r.stack,
            r.rps,
            r.runs,
            format(r.avg),
            format(r.p95),
            format(r.p99),
            format(r.actualRps),
            r.failedChecks,
            r.dropped,
        ].join(',')
    ),
    '',
].join('\n');

// Save reports
fs.mkdirSync(OUTPUT, { recursive: true });

fs.writeFileSync(
    path.join(OUTPUT, 'read-summary.md'),
    markdown
);

fs.writeFileSync(
    path.join(OUTPUT, 'read-summary.csv'),
    csv
);

console.log(markdown);

console.log('Saved: benchmarks/reports/read-summary.md');
console.log('Saved: benchmarks/reports/read-summary.csv');

if (results.some((r) => r.failedChecks > 0 || r.dropped > 0)) {
    console.warn(
        'WARNING: Some runs contain failed checks or dropped iterations.'
    );
}