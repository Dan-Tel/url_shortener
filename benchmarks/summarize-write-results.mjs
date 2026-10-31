
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'benchmarks/reports/write';
const OUTPUT = 'benchmarks/reports';

function findFiles(dir) {
    if (!fs.existsSync(dir)) return [];

    return fs.readdirSync(dir, { withFileTypes: true })
        .flatMap((entry) => {
            const file = path.join(dir, entry.name);
            return entry.isDirectory() ? findFiles(file) : [file];
        });
}

function median(values) {
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);

    return sorted.length % 2
        ? sorted[mid]
        : (sorted[mid - 1] + sorted[mid]) / 2;
}

function getMetric(metrics, name) {
    const metric = metrics?.[name];
    return metric?.values ?? metric ?? {};
}

function parseReport(file) {
    const name = path.basename(file);

    const match = name.match(
        /^(create|delete)-(rest-http1|rest-http2|grpc)-(\d+)-(\d+)-(.+)\.json$/
    );

    if (!match) return null;

    const [, operation, stack, rps, run, session] = match;

    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    const metrics = data.metrics ?? {};

    const metricName =
        stack === 'grpc'
            ? 'grpc_req_duration'
            : 'http_req_duration';

    const latency = getMetric(metrics, metricName);
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
        operation,
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

const reports = findFiles(ROOT)
    .filter(file => file.endsWith('.json'))
    .map(parseReport)
    .filter(Boolean);

// Latest session per operation + protocol + RPS
const latest = new Map();

for (const report of reports) {
    const key = `${report.operation}:${report.stack}:${report.rps}`;
    const previous = latest.get(key);

    if (!previous || report.session > previous) {
        latest.set(key, report.session);
    }
}

const selected = reports.filter(report => {
    const key = `${report.operation}:${report.stack}:${report.rps}`;
    return latest.get(key) === report.session;
});

const groups = new Map();

for (const report of selected) {
    const key = `${report.operation}:${report.stack}:${report.rps}`;

    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(report);
}

const rows = [...groups.values()].map(runs => ({
    operation: runs[0].operation,
    stack: runs[0].stack,
    rps: runs[0].rps,
    runs: runs.length,
    avg: median(runs.map(r => r.avg)),
    p95: median(runs.map(r => r.p95)),
    p99: median(runs.map(r => r.p99)),
    actualRps: median(runs.map(r => r.actualRps)),
    failedChecks: runs.reduce((sum, r) => sum + r.failedChecks, 0),
    dropped: runs.reduce((sum, r) => sum + r.dropped, 0),
}));

const order = {
    'rest-http1': 0,
    'rest-http2': 1,
    grpc: 2,
};

rows.sort((a, b) =>
    a.operation.localeCompare(b.operation) ||
    a.rps - b.rps ||
    order[a.stack] - order[b.stack]
);

const fmt = n => n.toFixed(3);

const markdown = [
    '# SIS #2 — CREATE / DELETE Latency',
    '',
    'PostgreSQL + Redis, TLS enabled.',
    'Latency in ms; medians of per-run statistics.',
    '',
    '| Operation | Protocol | RPS | Runs | Avg | p95 | p99 | Actual RPS | Failed checks | Dropped |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|',
    ...rows.map(r =>
        `| ${r.operation} | ${r.stack} | ${r.rps} | ${r.runs} | ${fmt(r.avg)} | ${fmt(r.p95)} | ${fmt(r.p99)} | ${fmt(r.actualRps)} | ${r.failedChecks} | ${r.dropped} |`
    ),
    '',
].join('\n');

const csv = [
    'operation,protocol,rps,runs,avg_ms,p95_ms,p99_ms,actual_rps,failed_checks,dropped',
    ...rows.map(r => [
        r.operation,
        r.stack,
        r.rps,
        r.runs,
        fmt(r.avg),
        fmt(r.p95),
        fmt(r.p99),
        fmt(r.actualRps),
        r.failedChecks,
        r.dropped,
    ].join(',')),
    '',
].join('\n');

fs.mkdirSync(OUTPUT, { recursive: true });

fs.writeFileSync(path.join(OUTPUT, 'write-summary.md'), markdown);
fs.writeFileSync(path.join(OUTPUT, 'write-summary.csv'), csv);

console.log(markdown);
console.log('Saved: benchmarks/reports/write-summary.md');
console.log('Saved: benchmarks/reports/write-summary.csv');

if (rows.some(r => r.failedChecks > 0 || r.dropped > 0)) {
    console.warn('WARNING: Some runs had failed checks or dropped iterations.');
}
