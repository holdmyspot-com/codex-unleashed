#!/usr/bin/env python3
"""Exercise maven-retry.sh with a fake Maven that fails like a hosted runner whose Central requests miss."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("maven-retry.sh")
MISS = "[ERROR] \tCould not find artifact org.apache.maven.plugins:maven-clean-plugin:jar:3.5.0 in central (https://repo.maven.apache.org/maven2)"
FAKE_MAVEN = """#!/usr/bin/env bash
# Fails with a Central miss unless the run resolves Central through a global settings file (-gs).
printf '%s\\n' "$*" >> "$FAKE_MAVEN_CALLS"
if [[ "$FAKE_MAVEN_MODE" == compile-error ]]; then echo '[ERROR] COMPILATION ERROR'; exit 1; fi
for argument in "$@"; do
  if [[ "$argument" == -gs && "$FAKE_MAVEN_MODE" == miss-on-central ]]; then exit 0; fi
done
echo "$FAKE_MAVEN_MISS"
exit 1
"""
FAKE_CURL = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FAKE_CURL_CALLS"
printf 'HTTP/2 404\\nx-served-by: cache-fixture\\nremote_ip=203.0.113.7 http_code=404\\n'
"""


class MavenRetryTest(unittest.TestCase):
    """Check which attempts resolve Central through the fallback, what is diagnosed and when the script gives up."""

    def run_script(self, mode, attempts="3"):
        """Run the script against the fake Maven and return its result with the fake Maven's and curl's call logs."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tools = root / "tools"
            tools.mkdir()
            for name, text in (("mvn", FAKE_MAVEN), ("curl", FAKE_CURL)):
                (tools / name).write_text(text)
                (tools / name).chmod(0o755)
            calls = root / "mvn-calls.log"
            curl_calls = root / "curl-calls.log"
            environment = {**os.environ, "PATH": f"{tools}{os.pathsep}{os.environ['PATH']}",
                           "FAKE_MAVEN_MODE": mode, "FAKE_MAVEN_CALLS": calls.as_posix(),
                           "FAKE_MAVEN_MISS": MISS, "FAKE_CURL_CALLS": curl_calls.as_posix(),
                           "MAVEN_ATTEMPTS": attempts, "MAVEN_RETRY_DELAY_SECONDS": "0"}
            result = subprocess.run([shutil.which("bash"), SCRIPT.as_posix(), (root / "repository").as_posix(), "package"],
                                    capture_output=True, text=True, env=environment)
            read = lambda path: path.read_text().splitlines() if path.exists() else []
            return result, read(calls), read(curl_calls)

    def test_a_central_miss_is_retried_through_the_fallback_mirror(self):
        """The second attempt, which follows a miss from Central, resolves through the global fallback settings."""
        result, calls, _ = self.run_script("miss-on-central")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertNotIn("-gs", calls[0])
        self.assertIn("-gs", calls[1])
        self.assertIn("maven-central.storage-download.googleapis.com", result.stderr)

    def test_a_miss_is_diagnosed_from_the_missing_artifact_url(self):
        """The runner's own answer for the missing jar is printed, built from the artifact in the Maven error."""
        result, _, curl_calls = self.run_script("miss-on-central")
        url = ("https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-clean-plugin/3.5.0/"
               "maven-clean-plugin-3.5.0.jar")
        self.assertEqual(len(curl_calls), 1)
        self.assertTrue(curl_calls[0].endswith(url), curl_calls[0])
        self.assertIn("x-served-by: cache-fixture", result.stderr)

    def test_a_miss_that_persists_fails_after_all_attempts(self):
        """Maven's exit status is returned once every attempt has missed, alternating between Central and the mirror."""
        result, calls, _ = self.run_script("always-miss")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(["-gs" in call for call in calls], [False, True, False])

    def test_another_failure_is_not_retried(self):
        """A compilation error fails on the first attempt with no diagnostics."""
        result, calls, curl_calls = self.run_script("compile-error")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(curl_calls, [])


if __name__ == "__main__":
    unittest.main()
