These reference archives contain a 49,153-byte sparse file named
`bin/` followed by 90 `é` characters and `-codex`. Its bytes at offsets
`0`, `8192`, `16384`, `24576`, `32768`, `40960`, and `49152` are `255`;
all other bytes are zero.

GNU tar 1.35 creates these archives with `--sparse`, `--mtime=@0`,
`--owner=0 --group=0 --numeric-owner`, and these format options:

- `gnu.tar.gz`: `--format=gnu`
- `pax-0.0.tar.gz`: `--format=posix --sparse-version=0.0`
- `pax-0.1.tar.gz`: `--format=posix --sparse-version=0.1`
- `pax-1.0.tar.gz`: `--format=posix --sparse-version=1.0`

The extraction test compares every resulting byte with the independent payload
definition above. It consumes these producer-generated archives without requiring
GNU tar on the test machine.
