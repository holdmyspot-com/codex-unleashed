# Local npm releases

`tooling/bin/codex-tooling publish-npm-from-release` converts the six public `codex-package-*` GitHub
Release archives into public and early-access npm package families. It creates
one selector package and six platform packages for each family:

- `@holdmyspot/codex-unleashed` and its public platform packages
- `@holdmyspot/codex-unleashed-ea` and its private platform packages

The early-access packages are published with npm access `restricted`; users
must belong to an npm organization team with read access to all seven
early-access packages. The command defaults to the local Verdaccio registry at
`http://127.0.0.1:4873` and uses the `@holdmyspot` scope.

GitHub release tags may use `rust-v0.153.4+25`. For npm package versions,
replace the `+` before the vendor build number with `-`: that release is
published as `0.153.4-25`. This keeps successive vendor builds ordered and
ensures all platform dependencies resolve to the same version.

Build packages from an online GitHub Release without publishing them:

```bash
tooling/bin/codex-tooling publish-npm-from-release \
  --tag rust-v0.153.4+25 \
  --output-dir .cat/work/temp/build-caches/npm-release
```

Publish them to local Verdaccio using an npm config containing credentials:

```bash
tooling/bin/codex-tooling publish-npm-from-release \
  --tag rust-v0.153.4+25 \
  --output-dir .cat/work/temp/build-caches/npm-release \
  --npmrc "$PWD/.cat/work/temp/npmrc" \
  --publish
```

The package can then be tested with:

```bash
scripts/test_local_npm_release.sh .cat/work/temp/build-caches/npm-release/packages/public/main
```

The publisher requires all six `codex-package-<target>.tar.gz` archives and
never contacts public npm unless `--registry` is explicitly changed.

The project launcher bootstraps its bundled runtime through the JDK 27 Maven project when needed. It stores local
build outputs and dependency caches under `.cat/work/temp/build-caches`. The publisher removes extraction staging
after assembly and npm caches after packing or publication. Explicit output directories retain caller ownership;
implicit output is removed after failure and retained after success. `--archive-dir` consumes already downloaded
archives without a GitHub lookup.
