# Local npm releases

`publish_npm_from_release.py` converts the six public `codex-package-*` GitHub
Release archives into public and early-access npm package families. It creates
one selector package and six platform packages for each family:

- `@holdmyspot/codex-unleashed` and its public platform packages
- `@holdmyspot/codex-unleashed-ea` and its private platform packages

The early-access packages are published with npm access `restricted`; users
must belong to an npm organization team with read access to all seven
early-access packages. The script defaults to the local Verdaccio registry at
`http://127.0.0.1:4873` and uses the `@holdmyspot` scope.

GitHub release tags may use `0.153.4+25`, but npm removes SemVer build
metadata when publishing. The script therefore publishes that release as
`0.153.4-unleashed.25` so the vendor build remains distinguishable and all
platform dependencies resolve to the same version.

Build packages from an online GitHub Release without publishing them:

```bash
python3 scripts/publish_npm_from_release.py \
  --tag rust-v0.153.4+25 \
  --output-dir /tmp/codex-npm-release
```

Publish them to local Verdaccio using an npm config containing credentials:

```bash
python3 scripts/publish_npm_from_release.py \
  --tag rust-v0.153.4+25 \
  --output-dir /tmp/codex-npm-release \
  --npmrc /tmp/codex-npmrc \
  --publish
```

The package can then be tested with:

```bash
scripts/test_local_npm_release.sh /tmp/codex-npm-release/packages/public/main
```

The publisher requires all six `codex-package-<target>.tar.gz` archives and
never contacts public npm unless `--registry` is explicitly changed.
