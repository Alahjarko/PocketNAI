#!/usr/bin/env node
// 把代理清单（每行 host:port:user:pass）加密成 Android assets 用的单行 base64。
//
// 用法：
//   node scripts/encrypt-proxy-nodes.mjs <proxies.txt> [输出路径]
// 默认输出：app/src/main/assets/public_proxy_nodes.enc
//
// 加密：AES-256-GCM；密钥 = SHA-256(KEY_SEED) —— 必须与 Kotlin 侧
// PublicProxyNodes.KEY_SEED 一致。输出 = base64( iv(12B) || ciphertext+tag )。
//
// 注意：这只是"防随手提取"级别的混淆（密钥在代码里），不能防专业逆向；
// 换代理凭据后重新生成即可。

import { createHash, randomBytes, createCipheriv } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const KEY_SEED = 'PocketNAI public proxy v1';

const input = process.argv[2];
if (!input) {
  console.error('用法：node scripts/encrypt-proxy-nodes.mjs <proxies.txt> [输出路径]');
  process.exit(1);
}

const here = dirname(fileURLToPath(import.meta.url));
const output = process.argv[3] ?? resolve(here, '../app/src/main/assets/public_proxy_nodes.enc');

const lines = readFileSync(input, 'utf8')
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter((line) => /^[^:\s]+:\d+:[^:\s]+:[^:\s]+$/.test(line));

if (lines.length === 0) {
  console.error('输入文件里没有可识别的行（期望 host:port:user:pass）');
  process.exit(1);
}

const key = createHash('sha256').update(KEY_SEED, 'utf8').digest();
const iv = randomBytes(12);
const cipher = createCipheriv('aes-256-gcm', key, iv);
const ciphertext = Buffer.concat([
  cipher.update(lines.join('\n'), 'utf8'),
  cipher.final(),
  cipher.getAuthTag(),
]);
const packed = Buffer.concat([iv, ciphertext]);

mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, packed.toString('base64'), 'utf8');

console.log(`✔ 已加密 ${lines.length} 个节点 → ${output}`);
