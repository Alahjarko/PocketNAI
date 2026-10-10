#!/usr/bin/env node
// Authorized maintenance only. Credentials stay in memory and curl's stdin config.
// No account reads, login, generation requests, proxy environment or curlrc.
import { createHash, createDecipheriv, createCipheriv, randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { resolve } from 'node:path';

const asset = resolve('app/src/main/assets/public_proxy_nodes.enc');
const output = resolve(process.argv[3] ?? '.tooling/proxy-benchmark-20261010');
const key = createHash('sha256').update('PocketNAI public proxy v1').digest();
const encrypted = readFileSync(asset);
const packed = Buffer.from(encrypted.toString().trim(), 'base64');
const decipher = createDecipheriv('aes-256-gcm', key, packed.subarray(0, 12));
decipher.setAuthTag(packed.subarray(-16));
const lines = Buffer.concat([decipher.update(packed.subarray(12, -16)), decipher.final()])
  .toString('utf8').trim().split(/\r?\n/).filter(Boolean);
const nodes = lines.map((line, index) => {
  const parts = line.split(':');
  if (parts.length !== 4 || !/^\d+$/.test(parts[1])) throw new Error('Invalid encrypted node format');
  return { id: `P${String(index + 1).padStart(3, '0')}`, parts, line };
});
mkdirSync(output, { recursive: true });
const mode = process.argv[2] ?? 'pilot';
const reportPath = resolve(output, 'measurements.json');
const sourceHash = createHash('sha256').update(encrypted).digest('hex');
const uploadPath = resolve(output, 'synthetic-upload.bin');
writeFileSync(uploadPath, randomBytes(1024 * 1024));
const cleanEnv = Object.fromEntries(Object.entries(process.env).filter(([name]) => !/proxy/i.test(name)));
function quote(value) {
  if (/[\r\n\0]/.test(value)) throw new Error('Invalid config value');
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
}
async function request(node, kind, bytes = 0) {
  const nai = kind === 'novelai';
  const upload = kind === 'upload';
  const url = nai ? 'https://image.novelai.net/ai/generate-image/suggest-tags?prompt=landscape&model=nai-diffusion-4-5-full'
    : upload ? 'https://speed.cloudflare.com/__up' : `https://speed.cloudflare.com/__down?bytes=${bytes}&nonce=${randomBytes(8).toString('hex')}`;
  const [host, port, user, password] = node.parts;
  const config = [
    `proxy = ${quote(`socks5h://${host}:${port}`)}`,
    `proxy-user = ${quote(`${user}:${password}`)}`,
    'noproxy = ""', 'silent', 'connect-timeout = 8', `max-time = ${upload || bytes ? 25 : 12}`,
    'retry = 0', 'output = "NUL"', 'write-out = "%{json}"',
    'header = "Cache-Control: no-cache"', 'header = "Accept-Encoding: identity"',
    `url = ${quote(url)}`,
  ];
  if (upload) {
    const payload = resolve(output, `upload-${bytes}.bin`);
    // Synthetic, non-user payload; never write any proxy credential to disk.
    writeFileSync(payload, readFileSync(uploadPath).subarray(0, bytes));
    config.push(`data-binary = ${quote(`@${payload}`)}`, 'header = "Content-Type: application/octet-stream"', 'header = "Expect:"');
  }
  return new Promise((done) => {
    const child = spawn('curl.exe', ['-q', '--config', '-'], { env: cleanEnv, windowsHide: true });
    let stdout = '';
    child.stdout.on('data', data => { stdout += data; });
    child.stderr.resume(); // curl errors can contain endpoint details; never log them.
    child.on('error', () => done({ ok: false, error: 'CURL_START_FAILED' }));
    child.on('close', code => {
      let data;
      try { data = JSON.parse(stdout); } catch { done({ ok: false, error: 'METRICS_UNAVAILABLE' }); return; }
      const status = Number(data.http_code);
      const ok = code === 0 && status === 200 && (upload ? Number(data.size_upload) === bytes : nai || Number(data.size_download) === bytes);
      const duration = upload ? Number(data.time_total) - Number(data.time_pretransfer) : Number(data.time_total) - Number(data.time_starttransfer);
      done({ ok, status, curlCode: code, tcpMs: Number(data.time_connect) * 1000,
        tlsMs: Number(data.time_appconnect) * 1000, ttfbMs: Number(data.time_starttransfer) * 1000,
        totalMs: Number(data.time_total) * 1000, bytes: upload ? Number(data.size_upload) : Number(data.size_download),
        mbps: ok && bytes && duration > 0 ? bytes * 8 / duration / 1e6 : null });
    });
    child.stdin.end(config.join('\n') + '\n');
  });
}
function save(report) { writeFileSync(reportPath, JSON.stringify(report, null, 2)); }
function median(values) { const sorted = values.toSorted((a, b) => a - b); const mid = sorted.length >> 1; return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2; }
function summary(entry) {
  const successful = kind => entry.samples.filter(s => s.kind === kind && s.ok);
  const latency = successful('latency').map(s => s.ttfbMs);
  const nai = successful('novelai').map(s => s.ttfbMs);
  const down = successful('download').map(s => s.mbps);
  const up = successful('upload').map(s => s.mbps);
  return { id: entry.id, attempts: entry.samples.length, failures: entry.samples.filter(s => !s.ok).length,
    latencyMs: latency.length ? median(latency) : null, novelaiMs: nai.length ? median(nai) : null,
    downloadMbps: down.length ? median(down) : null, uploadMbps: up.length ? median(up) : null,
    latencyJitterMs: latency.length ? Math.max(...latency) - Math.min(...latency) : null,
    downloadFloorMbps: down.length ? Math.min(...down) : null, uploadFloorMbps: up.length ? Math.min(...up) : null };
}
function ranking(entries, final = false) {
  const values = entries.map(summary).filter(e => e.latencyMs !== null && e.novelaiMs !== null && e.downloadMbps !== null && e.uploadMbps !== null);
  // User priority: download first; a successful upload is sufficient.
  const fields = [['latencyMs', 0.05, 1], ['novelaiMs', 0.15, 1], ['downloadMbps', 0.8, -1]];
  for (const e of values) e.score = final ? e.failures * 10 : 0;
  for (const [field, weight, direction] of fields) {
    const order = values.toSorted((a, b) => direction * (a[field] - b[field]));
    order.forEach((e, index) => { e.score += weight * index / Math.max(1, order.length - 1); });
  }
  return values.toSorted((a, b) => a.score - b.score);
}
async function sample(report, node, kind, bytes, round) {
  const entry = report.nodes.find(n => n.id === node.id);
  const existing = entry.samples.find(sample => sample.kind === kind && sample.round === round);
  if (existing) return existing;
  const result = await request(node, kind, bytes);
  entry.samples.push({ kind, round, ...result });
  save(report);
  return result;
}

if (mode === 'pilot') {
  console.log(`Inventory: ${nodes.length} nodes; explicit SOCKS5, remote DNS, no system proxy`);
  for (const [kind, bytes] of [['latency', 0], ['novelai', 0], ['download', 2 * 1024 * 1024], ['upload', 512 * 1024]]) {
    console.log(nodes[0].id, kind, JSON.stringify(await request(nodes[0], kind, bytes)));
  }
} else if (mode === 'measure' || mode === 'resume') {
  if (mode === 'measure' && existsSync(reportPath)) throw new Error('Use a new output directory for a new benchmark; existing evidence is preserved');
  const report = mode === 'resume' ? JSON.parse(readFileSync(reportPath, 'utf8')) : { date: '2026-10-10 Asia/Hong_Kong', startedAt: new Date().toISOString(), sourceHash, sourceCount: nodes.length,
    route: 'explicit socks5h; curl -q; proxy env removed; TLS verified; no retries; serial bandwidth',
    nodes: nodes.map(node => ({ id: node.id, samples: [] })) };
  if (report.sourceHash !== sourceHash || (mode === 'resume' && report.finishedAt)) throw new Error('Source changed or benchmark already complete');
  report.priority = 'download 80%; NovelAI TTFB 15%; general HTTPS TTFB 5%; upload must succeed';
  if (mode === 'measure') copyFileSync(asset, resolve(output, 'original-public-proxy-nodes.enc'));
  save(report);
  for (const node of nodes) {
    const existing = report.nodes.find(n => n.id === node.id).samples;
    const next = (kind, bytes) => existing.find(s => s.kind === kind && s.round === 0) ?? sample(report, node, kind, bytes, 0);
    if (existing.some(s => s.round === 0 && !s.ok) || existing.filter(s => s.round === 0).length === 4) continue;
    const ping = await next('latency', 0);
    if (!ping.ok) { console.log(`${node.id} screening: unavailable`); continue; }
    const nai = await next('novelai', 0);
    if (!nai.ok) { console.log(`${node.id} screening: NovelAI unavailable`); continue; }
    await next('download', 2 * 1024 * 1024);
    await next('upload', 512 * 1024);
    console.log(`${node.id} screening: ${JSON.stringify(summary(report.nodes.find(n => n.id === node.id)))}`);
  }
  const first = ranking(report.nodes.map(node => ({ ...node, samples: node.samples.filter(sample => sample.round === 0) })));
  if (first.length < 10) throw new Error(`Only ${first.length} fully reachable nodes; do not alter asset`);
  const candidates = report.candidates ?? first.slice(0, 20).map(e => e.id);
  // Also retain individual winners so one direction cannot be lost in the aggregate score.
  for (const field of ['downloadMbps', 'latencyMs', 'novelaiMs']) {
    const order = first.toSorted((a, b) => (field.endsWith('Ms') ? 1 : -1) * (a[field] - b[field]));
    for (const e of order.slice(0, 5)) if (!candidates.includes(e.id) && candidates.length < 25) candidates.push(e.id);
  }
  report.candidates = candidates; save(report);
  for (let round = 1; round <= 2; round++) {
    const ordered = candidates.map(id => nodes.find(n => n.id === id));
    if (round === 2) ordered.reverse();
    for (const node of ordered) {
      await sample(report, node, 'latency', 0, round);
      await sample(report, node, 'novelai', 0, round);
      await sample(report, node, 'download', 4 * 1024 * 1024, round);
      await sample(report, node, 'upload', 1024 * 1024, round);
      console.log(`${node.id} round ${round}: ${JSON.stringify(summary(report.nodes.find(n => n.id === node.id)))}`);
    }
  }
  report.ranking = ranking(report.nodes.filter(n => candidates.includes(n.id)), true);
  report.finishedAt = new Date().toISOString(); save(report);
  console.log('Final top 10:', JSON.stringify(report.ranking.slice(0, 10)));
} else if (mode === 'apply') {
  const report = JSON.parse(readFileSync(reportPath, 'utf8'));
  if (report.sourceHash !== sourceHash || !report.finishedAt || report.ranking?.length < 10) throw new Error('Incomplete or stale benchmark');
  const picked = report.ranking.slice(0, 10);
  if (picked.some(row => row.failures > 0 || report.nodes.find(n => n.id === row.id).samples.length !== 12)) throw new Error('Selected nodes did not pass all 3 rounds');
  const plain = picked.map(row => nodes.find(n => n.id === row.id).line).join('\n');
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const result = Buffer.concat([iv, cipher.update(plain, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  writeFileSync(asset, result.toString('base64'));
  console.log(`Encrypted asset updated: ${picked.length} nodes; credentials were never written in plaintext`);
} else throw new Error('Use pilot, measure, resume or apply');
