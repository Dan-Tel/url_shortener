
import fs from 'node:fs';
import path from 'node:path';

const REPORTS = 'benchmarks/reports';

const COLORS = {
    'rest-http1': '#64748b',
    'rest-http2': '#3b82f6',
    grpc: '#10b981',
};

const LABELS = {
    'rest-http1': 'REST HTTP/1.1',
    'rest-http2': 'REST HTTP/2',
    grpc: 'gRPC HTTP/2',
};

const ORDER = ['rest-http1', 'rest-http2', 'grpc'];

function readCsv(filename) {
    const location = path.join(REPORTS, filename);

    if (!fs.existsSync(location)) {
        throw new Error(`Missing ${location}`);
    }

    const [header, ...lines] = fs
        .readFileSync(location, 'utf8')
        .trim()
        .split(/\r?\n/);

    const keys = header.split(',');

    return lines.filter(Boolean).map(line => {
        const values = line.split(',');
        return Object.fromEntries(
            keys.map((key, i) => [key, values[i]])
        );
    });
}

const rows = [
    ...readCsv('read-summary.csv').map(row => ({
        ...row,
        operation: 'read',
        rps: Number(row.target_rps),
    })),
    ...readCsv('write-summary.csv').map(row => ({
        ...row,
        rps: Number(row.rps),
    })),
];

function chart(operation, rps) {
    const items = rows
        .filter(r => r.operation === operation && r.rps === rps)
        .sort(
            (a, b) =>
                ORDER.indexOf(a.protocol) - ORDER.indexOf(b.protocol)
        );

    if (!items.length) return '';

    const max = Math.max(
        ...items.map(r => Number(r.p95_ms)),
        0.001
    );

    const bars = items.map(row => {
        const value = Number(row.p95_ms);
        const width = (value / max) * 100;

        return `
      <div class="result">
        <div class="protocol">${LABELS[row.protocol]}</div>
        <div class="track">
          <div class="bar"
               style="width:${width.toFixed(2)}%;
                      background:${COLORS[row.protocol]}">
          </div>
        </div>
        <div class="value">${value.toFixed(3)} ms</div>
      </div>
    `;
    }).join('');

    const table = items.map(row => `
    <tr>
      <td>${LABELS[row.protocol]}</td>
      <td>${Number(row.avg_ms).toFixed(3)}</td>
      <td>${Number(row.p95_ms).toFixed(3)}</td>
      <td>${Number(row.p99_ms).toFixed(3)}</td>
      <td>${Number(row.actual_rps).toFixed(1)}</td>
      <td>${row.dropped}</td>
      <td>${row.failed_checks}</td>
    </tr>
  `).join('');

    const dropped = items.some(r => Number(r.dropped) > 0);

    return `
    <section class="card">
      <h2>${operation.toUpperCase()} — ${rps.toLocaleString()} RPS</h2>
      <p class="subtitle">p95 latency · lower is better</p>

      <div class="bars">${bars}</div>

      <table>
        <thead>
          <tr>
            <th>Protocol</th>
            <th>Avg</th>
            <th>p95</th>
            <th>p99</th>
            <th>Actual RPS</th>
            <th>Dropped</th>
            <th>Failed</th>
          </tr>
        </thead>
        <tbody>${table}</tbody>
      </table>

      ${dropped ? `
        <p class="warning">
          Load target was not fully achieved in one or more runs.
          Interpret these latency results with caution.
        </p>
      ` : ''}
    </section>
  `;
}

const html = `
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport"
        content="width=device-width, initial-scale=1">
  <title>SIS #2 — Protocol Benchmark Results</title>
  <style>
    * { box-sizing: border-box; }

    body {
      font-family: -apple-system, BlinkMacSystemFont,
                   "Segoe UI", sans-serif;
      background: #f5f7fb;
      color: #172033;
      margin: 0;
      padding: 40px 24px;
    }

    main { max-width: 1100px; margin: auto; }

    h1 { font-size: 30px; margin-bottom: 8px; }
    h2 { font-size: 20px; margin-top: 0; }

    .intro, .subtitle {
      color: #64748b;
      font-size: 14px;
    }

    .intro { margin-bottom: 30px; }

    .card {
      background: white;
      border: 1px solid #e2e8f0;
      border-radius: 14px;
      padding: 28px;
      margin-bottom: 24px;
    }

    .bars { margin: 28px 0; }

    .result {
      display: grid;
      grid-template-columns: 145px 1fr 95px;
      gap: 16px;
      align-items: center;
      margin-bottom: 18px;
    }

    .protocol { font-size: 13px; font-weight: 600; }

    .track {
      background: #eef2f7;
      height: 23px;
      border-radius: 5px;
      overflow: hidden;
    }

    .bar { height: 100%; border-radius: 5px; }

    .value {
      text-align: right;
      font-size: 13px;
      font-variant-numeric: tabular-nums;
      font-weight: 600;
    }

    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
      margin-top: 24px;
    }

    th, td {
      padding: 12px 10px;
      border-bottom: 1px solid #e2e8f0;
      text-align: right;
    }

    th:first-child, td:first-child { text-align: left; }

    th { color: #64748b; font-weight: 600; }

    .warning {
      margin-top: 18px;
      font-size: 13px;
      color: #9a6700;
    }

    @media print {
      body { background: white; padding: 0; }
      .card { break-inside: avoid; }
    }
  </style>
</head>
<body>
<main>
  <h1>URL Shortener — Protocol Benchmark Results</h1>
  <p class="intro">
    SIS #2 · PostgreSQL + Redis · TLS enabled ·
    3 repetitions per configuration.
    Values are medians of per-run statistics.
  </p>

  ${chart('read', 100)}
  ${chart('read', 1000)}
  ${chart('read', 5000)}
  ${chart('create', 100)}
  ${chart('delete', 100)}

  <p class="intro">
    Test environment: all services and the load generator
    run locally. p95 values are medians of per-run p95
    statistics, not pooled request percentiles.
    Dropped iterations indicate unmet load targets.
  </p>
</main>
</body>
</html>
`;

const output = path.join(REPORTS, 'final-latency-report.html');

fs.writeFileSync(output, html);
console.log(`Generated: ${output}`);
