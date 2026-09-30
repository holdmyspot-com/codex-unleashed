#!/usr/bin/env python3
"""Reserve an immutable release source tag."""

import argparse
from enum import Enum
import json
import os
import re
from urllib.error import HTTPError
from urllib.request import Request, urlopen


class Method(str, Enum):
    GET = 'GET'
    POST = 'POST'


def resolve_build_source(build_run, repository):
    if (build_run['repository']['full_name'] != repository
            or build_run['path'] != '.github/workflows/build-release.yml'):
        raise ValueError('artifacts must come from this repository\'s release build workflow')
    return {'source_sha': build_run['head_sha'],
            'source_ref': f"refs/heads/{build_run['head_branch']}",
            'source_run_attempt': build_run['run_attempt']}


def ensure_release_tag(repository, tag, sha, api):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('repository must be owner/name')
    if not re.fullmatch(r'rust-v\d+\.\d+\.\d+\+\d+', tag):
        raise ValueError('tag must be rust-vX.Y.Z+N')
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('source SHA must contain 40 lowercase hexadecimal characters')

    ref_path = f'repos/{repository}/git/ref/tags/{tag}'
    try:
        result = api(Method.GET, ref_path)
    except HTTPError as error:
        if error.code != 404:
            raise
        try:
            result = api(Method.POST, f'repos/{repository}/git/refs', {
                'ref': f'refs/tags/{tag}', 'sha': sha
            })
        except HTTPError as create_error:
            if create_error.code != 422:
                raise
            # A concurrent reservation must still identify the same source.
            result = api(Method.GET, ref_path)

    if result['object']['type'] != 'commit' or result['object']['sha'] != sha:
        raise ValueError(f'release tag {tag} identifies a different commit; refusing replacement')


def request_github(token, method: Method, path, payload=None):
    request = Request(
        f'https://api.github.com/{path}',
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json',
                 'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'},
        method=method.value
    )
    with urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--artifact-run-id', required=True, type=int)
    args = parser.parse_args()
    token = os.environ['GH_TOKEN']
    api = lambda method, path, payload=None: request_github(token, method, path, payload)
    source = resolve_build_source(
        api(Method.GET, f'repos/{args.repository}/actions/runs/{args.artifact_run_id}'),
        args.repository
    )
    ensure_release_tag(args.repository, args.tag, source['source_sha'], api)
    for key, value in source.items():
        print(f'{key}={value}')


if __name__ == '__main__':
    main()
