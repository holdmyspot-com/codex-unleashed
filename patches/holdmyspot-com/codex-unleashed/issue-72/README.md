# Latest stable TypeScript SDK and npm tooling

## Intent

The TypeScript SDK and repository npm tooling build with the latest stable
pnpm, TypeScript, ESLint, Jest, tsup and zod releases. Existing security pins
and the seven-day minimum release age stay in force.

## Reference and base

- Issue: https://github.com/holdmyspot-com/codex-unleashed/issues/72
- Upstream base: `rust-v0.163.0`, commit
  `83086188ae0614bd882818ff1aa23f3194962012`.
- The patch requires no other downstream patch. In the release queue it
  follows the issue #71 dependency upgrade.

## Scope

`latest-stable-npm-tooling.patch` updates the root and `sdk/typescript`
manifests, the pinned pnpm 12.12.1 package manager and `pnpm-lock.yaml`. The
SDK moves to TypeScript 6, ESLint 10, Jest 30, tsup 9, zod 4 and current
type definitions. One test removes an initializer that the new lint rules
report as unused. Prettier 3.9 joins one union type in `src/threadOptions.ts`
onto a single line.

TypeScript stays on 6.0.3 because TypeScript 7.0.2 is the native-compiler
rewrite and current releases of the SDK's TypeScript tooling don't support
it: typescript-eslint 8.72.0 requires `typescript >=4.8.4 <6.1.0` and ts-jest
29.4.15 requires `<7`. Upgrade TypeScript once both tools accept 7.

pnpm 12 ignores the root `resolutions` and `overrides` fields. The existing
security pins move to `overrides` in `pnpm-workspace.yaml`. The esbuild,
`@modelcontextprotocol/sdk` and rollup pins move to the versions the upgraded
tools require.

`minimumReleaseAge` stays at seven days. tsup 8 injects the `baseUrl` option
that TypeScript 6 deprecates into declaration builds, and tsup 9 requires
rollup 4.64.6. `minimumReleaseAgeExclude` therefore exempts only tsup 9.0.0
(published 2026-10-14) and rollup 4.64.6 with its platform packages (published
2026-10-13). Remove these entries after 2026-10-21, once both releases reach
the minimum age.

`allowBuilds` explicitly denies install scripts for `@parcel/watcher`, esbuild
and unrs-resolver; their prebuilt platform packages provide the binaries.

## Reproduction

On the declared base, `pnpm outdated -r` lists 17 direct development
dependencies of the root and SDK behind their latest release, including
eslint 9, jest 29, zod 3 and TypeScript 5. Afterwards it lists only
TypeScript 7.

## Verification

Build the CLI the SDK tests spawn, as the SDK workflow does, then run the
repository and SDK checks from the patched checkout root:

```sh
bazel build //codex-rs/cli:codex //codex-rs/code-mode-host:codex-code-mode-host
export CODEX_EXEC_PATH=<staged directory>/codex
pnpm install --frozen-lockfile
pnpm run format
pnpm -r --filter ./sdk/typescript run build
pnpm -r --filter ./sdk/typescript run lint
pnpm -r --filter ./sdk/typescript run test
pnpm -r --filter ./sdk/typescript run format
pnpm outdated -r
```

Stage `codex` next to `codex-code-mode-host`. The install reports no ignored
build scripts, release-age violations or trust-policy downgrades. `pnpm why`
shows each overridden package at its pinned version. Formatting, build, lint
with `--max-warnings=0` and tests pass. `pnpm outdated -r` lists only
TypeScript.

## Upstream status

The selected upstream release pins pnpm 10.34.5 and the older SDK toolchain.
