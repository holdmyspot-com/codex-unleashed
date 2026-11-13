#!/usr/bin/env node
/** Runs the installed host-specific Codex CLI, preserving its arguments, standard streams and terminal status. */
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const require = createRequire(import.meta.url);
const metadata = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
const platformName = `${metadata.name}-${process.platform}-${process.arch}`;
const platformRoot = path.dirname(require.resolve(`${platformName}/package.json`));
const targets = {
  linux: { x64: 'x86_64-unknown-linux-musl', arm64: 'aarch64-unknown-linux-musl' },
  darwin: { x64: 'x86_64-apple-darwin', arm64: 'aarch64-apple-darwin' },
  win32: { x64: 'x86_64-pc-windows-msvc', arm64: 'aarch64-pc-windows-msvc' }
};
const target = targets[process.platform]?.[process.arch];
if (!target) throw new Error(`Unsupported Codex platform: ${process.platform}/${process.arch}`);
const packageRoot = path.join(platformRoot, 'vendor', target);
const nativeMetadata = JSON.parse(readFileSync(path.join(packageRoot, 'codex-package.json'), 'utf8'));
const child = spawn(path.join(packageRoot, nativeMetadata.entrypoint), process.argv.slice(2), { stdio: 'inherit' });
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('close', (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  else process.exitCode = code ?? 1;
});
