#!/usr/bin/env python3
"""Reject newly reachable Git blobs larger than 512000 bytes.

Run in the repository with --base and --head commit revisions. An all-zero base
checks every object reachable from head (a new branch's push event). Exit zero
when accepted, one for oversized blobs, or two for invalid arguments/Git failures.
Existing blobs reachable from base are grandfathered. Intermediate commits count,
even when a later commit removes their large file. No worktree files are read.
"""
import argparse
import subprocess
import sys
import tempfile

# Retained repository policy: Git blobs above this exact byte count are rejected.
MAX_BLOB_BYTES = 512000


def resolve_commit(revision):
    """Resolve a commit revision in the current repository; propagate Git failures."""
    return subprocess.check_output(
        ['git', 'rev-parse', '--verify', '--end-of-options', revision + '^{commit}'],
        text=True).strip()


def check_sizes(records, diagnostics):
    """Report oversized blobs from Git batch-check records and return 1 if any exist.

    Records contain object ID, type and byte size, one per line. Trees and commits
    have no blob policy. Diagnostics names each rejected blob and its byte count.
    """
    result = 0
    for record in records:
        object_id, object_type, size = record.split()
        if object_type == 'blob' and int(size) > MAX_BLOB_BYTES:
            print(f'Blob {object_id} is {size} bytes; maximum is {MAX_BLOB_BYTES}. '
                  f'Remove it from the proposed commits (git log --find-object={object_id}).',
                  file=diagnostics)
            result = 1
    return result


def main():
    """Check the requested Git range; report operational failures with exit two."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='Excluded ancestor commit, or all-zero new-branch SHA')
    parser.add_argument('--head', required=True, help='Proposed commit')
    arguments = parser.parse_args()
    try:
        head = resolve_commit(arguments.head)
        is_new_branch = bool(arguments.base) and set(arguments.base) == {'0'}
        revisions = [head] if is_new_branch else [head, '^' + resolve_commit(arguments.base)]
        # Object IDs can grow with history; spool them to disk and stream size records.
        with tempfile.TemporaryFile() as object_ids:
            subprocess.run(['git', 'rev-list', '--objects', '--no-object-names', *revisions],
                           stdout=object_ids, check=True)
            object_ids.seek(0)
            with subprocess.Popen(['git', 'cat-file', '--batch-check'], stdin=object_ids,
                                  stdout=subprocess.PIPE, text=True) as sizes:
                try:
                    result = check_sizes(sizes.stdout, sys.stderr)
                except ValueError:
                    sizes.kill()
                    sizes.wait()
                    raise
                returncode = sizes.wait()
                if returncode:
                    raise subprocess.CalledProcessError(returncode, sizes.args)
        return result
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f'Blob policy could not read the requested Git objects: {error}. '
              'Fetch both commits and retry from the repository checkout.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
