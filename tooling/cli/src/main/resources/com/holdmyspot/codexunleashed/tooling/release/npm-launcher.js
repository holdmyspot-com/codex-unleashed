#!/usr/bin/env node
import { existsSync } from "node:fs";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const require = createRequire(import.meta.url);
const target = {
  linux: { x64: "x86_64-unknown-linux-musl", arm64: "aarch64-unknown-linux-musl" },
  darwin: { x64: "x86_64-apple-darwin", arm64: "aarch64-apple-darwin" },
  win32: { x64: "x86_64-pc-windows-msvc", arm64: "aarch64-pc-windows-msvc" },
}[process.platform]?.[process.arch];
if (!target) throw new Error(`Unsupported platform: ${process.platform}/${process.arch}`);
const platformName = {
  "x86_64-unknown-linux-musl": "linux-x64",
  "aarch64-unknown-linux-musl": "linux-arm64",
  "x86_64-apple-darwin": "darwin-x64",
  "aarch64-apple-darwin": "darwin-arm64",
  "x86_64-pc-windows-msvc": "win32-x64",
  "aarch64-pc-windows-msvc": "win32-arm64",
}[target];
const platformPackage = `__PLATFORM_PACKAGE_PREFIX__${platformName}`;
let platformRoot;
try {
  platformRoot = path.dirname(require.resolve(`${platformPackage}/package.json`));
} catch {
  platformRoot = root;
}
const executable = path.join(platformRoot, "vendor", target, "bin", process.platform === "win32" ? "codex.exe" : "codex");
if (!existsSync(executable)) throw new Error(`Missing Codex binary for ${target}`);
const child = spawn(executable, process.argv.slice(2), { stdio: "inherit" });
child.on("close", (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  else process.exit(code ?? 1);
});
