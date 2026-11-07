#!/usr/bin/env python3
"""Verify retained-worker debug staging and release target selection with the actual workflow steps."""
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).parent


class ReleaseDebugPreparationTest(unittest.TestCase):
    """Exercise local action staging and the connected-client release barrier, without hosting SSH."""

    def fixture(self, root):
        """Create a clean source checkout and an action whose probe represents its local consumer."""
        repository=root/'upstream';repository.mkdir()
        subprocess.run(['git','init','--quiet',str(repository)],check=True)
        (repository/'tracked').write_text('pristine')
        subprocess.run(['git','add','tracked'],cwd=repository,check=True)
        source=root/'restart-debug-action';source.mkdir()
        (source/'probe.sh').write_text('#!/usr/bin/env bash\nprintf "action available\\n"\n')
        evidence=root/'evidence';evidence.mkdir()
        return repository,source,evidence

    def prepare(self, repository, source, evidence):
        """Run the production staging command against the three caller-owned roots."""
        return subprocess.run(['bash',str(SCRIPTS/'release-debug-session.sh'),
            'prepare',str(source),str(repository),str(evidence)],capture_output=True)

    def test_staged_action_executes_without_changing_upstream_inventory(self):
        """The staged local action runs while Git continues to report only its original source."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            repository,source,evidence=self.fixture(Path(tmp))
            result=self.prepare(repository,source,evidence)
            self.assertEqual(result.returncode,0,result.stderr)
            probe=subprocess.run(['bash',str(repository/'.github/actions/debug-failed-runner/probe.sh')],capture_output=True)
            self.assertEqual(probe.returncode,0,probe.stderr)
            self.assertEqual(probe.stdout,b'action available\n')
            status=subprocess.run(['git','status','--porcelain','--untracked-files=all'],cwd=repository,capture_output=True,check=True)
            self.assertEqual(status.stdout,b'A  tracked\n')

    def test_existing_action_and_stale_readiness_are_rejected(self):
        """Preparation cannot overwrite upstream action files or trust an earlier readiness signal."""
        for conflict in ['action','readiness']:
            with self.subTest(conflict=conflict),tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
                repository,source,evidence=self.fixture(Path(tmp))
                if conflict=='action':
                    action=repository/'.github/actions/debug-failed-runner';action.mkdir(parents=True)
                    (action/'probe.sh').write_text('exit 43\n')
                else:
                    (evidence/'client-ready').write_text('stale')
                result=self.prepare(repository,source,evidence)
                self.assertNotEqual(result.returncode,0)
                self.assertIn(b'already exists',result.stderr)
                if conflict=='action':
                    probe=subprocess.run(['bash',str(action/'probe.sh')],capture_output=True)
                    self.assertEqual(probe.returncode,43)

    def test_client_barrier_preserves_state_until_actual_ready_signal(self):
        """The release waits with partial outputs intact until the connected client publishes readiness."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            repository,source,evidence=self.fixture(Path(tmp))
            prepared=self.prepare(repository,source,evidence)
            self.assertEqual(prepared.returncode,0,prepared.stderr)
            partial=evidence/'partial-output';partial.write_bytes(b'owned\x00state')
            process=subprocess.Popen(['bash',str(SCRIPTS/'release-debug-session.sh'),
                'wait-for-client',str(evidence)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                with self.assertRaises(subprocess.TimeoutExpired):
                    process.wait(timeout=0.2)
                self.assertEqual(partial.read_bytes(),b'owned\x00state')
                (evidence/'client-ready').touch()
                stdout,stderr=process.communicate(timeout=5)
                self.assertEqual(process.returncode,0,stderr)
                self.assertEqual(partial.read_bytes(),b'owned\x00state')
            finally:
                if process.poll() is None:
                    process.terminate();process.communicate()

    def test_windows_runner_paths_are_converted_before_staging(self):
        """Windows runner path records reach the same Git Bash staging boundary after cygpath conversion."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            root=Path(tmp)
            repository,source,evidence=self.fixture(root)
            commands=root/'commands';commands.mkdir()
            converter=commands/'cygpath'
            converter.write_text('#!/usr/bin/env bash\ncase "$2" in\nC:/source) printf "%s\\n" "$DEBUG_SOURCE";;\nC:/checkout) printf "%s\\n" "$DEBUG_CHECKOUT";;\nC:/evidence) printf "%s\\n" "$DEBUG_EVIDENCE";;\n*) printf "%s\\n" "$2";;\nesac\n')
            converter.chmod(0o755)
            environment=dict(os.environ,PATH=str(commands)+os.pathsep+os.environ['PATH'],
                DEBUG_SOURCE=str(source),DEBUG_CHECKOUT=str(repository),DEBUG_EVIDENCE=str(evidence))
            result=subprocess.run(['bash',str(SCRIPTS/'release-debug-session.sh'),
                'prepare','C:/source','C:/checkout','C:/evidence'],env=environment,capture_output=True)
            self.assertEqual(result.returncode,0,result.stderr)
            probe=subprocess.run(['bash',str(repository/'.github/actions/debug-failed-runner/probe.sh')],capture_output=True)
            self.assertEqual(probe.stdout,b'action available\n')

    def test_workflow_stages_preserved_action_before_its_consumer(self):
        """The actual workflow staging command can publish the preserved action for its local consumer."""
        import yaml
        workflow=yaml.load((SCRIPTS.parent/'workflows/baseline-release-builds.yml').read_text(),Loader=yaml.BaseLoader)
        steps=workflow['jobs']['build']['steps']
        staging=next((step for step in steps if step.get('name')=='Prepare retained release worker'),None)
        self.assertIsNotNone(staging,'Workflow has no retained-worker preparation')
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            root=Path(tmp)
            repository,source,evidence=self.fixture(root)
            source.rename(root/'release-debug-action')
            evidence.rename(root/'baseline-evidence')
            environment=dict(os.environ,RUNNER_TEMP=str(root),GITHUB_WORKSPACE=str(repository))
            shutil.copy2(SCRIPTS/'release-debug-session.sh',root/'release-debug-session.sh')
            result=subprocess.run(['bash','-e','-o','pipefail','-c',staging['run']],env=environment,capture_output=True)
            self.assertEqual(result.returncode,0,result.stderr)
            probe=subprocess.run(['bash',str(repository/'.github/actions/debug-failed-runner/probe.sh')],capture_output=True)
            self.assertEqual(probe.stdout,b'action available\n')


class WorkflowSelectionTest(unittest.TestCase):
    """Execute the actual selection step against the declared release targets."""

    def select(self,target,quiet_stop_minutes='75',cache='false',patches=''):
        """Run workflow selection without any Actions checkout, native acquisition or matrix dispatch."""
        import yaml
        workflow=yaml.load((SCRIPTS.parent/'workflows/baseline-release-builds.yml').read_text(),Loader=yaml.BaseLoader)
        step=workflow['jobs']['select']['steps'][-1]
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            output=Path(tmp)/'output'
            environment=dict(os.environ,CACHE_ENABLED=cache,CACHE_HIT_REQUIRED='false',SELECTED_TARGET=target,
                VERIFY_INVALIDATION='false',TRACE_SIGNALS='false',DOWNSTREAM_PATCHES=patches,
                QUIET_STOP_MINUTES=quiet_stop_minutes,GITHUB_OUTPUT=str(output))
            result=subprocess.run(['bash','-e','-o','pipefail','-c',step['run']],cwd=SCRIPTS.parents[1],env=environment,capture_output=True)
            matrix=json.loads(output.read_text().removeprefix('value=')) if output.exists() else None
            return result,matrix

    def test_default_matrix_selects_every_target(self):
        """The default selection includes every declared standard release target."""
        result,matrix=self.select('all');self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(len(matrix['include']),6)

    def test_rejects_cached_builds_of_downstream_patches(self):
        """A patched build stops before dispatch when it would read or write the pristine compiled cache."""
        patch='patches/holdmyspot-com/codex-unleashed/issue-47/0001.patch'
        result,matrix=self.select('x86_64-unknown-linux-musl',cache='true',patches=patch)
        self.assertNotEqual(result.returncode,0);self.assertIsNone(matrix)
        self.assertIn(b'patches require cache=false',result.stderr)
        result,matrix=self.select('all',patches=patch);self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(len(matrix['include']),6)

    def test_rejects_quiet_stop_outside_whole_minutes_up_to_the_step_limit(self):
        """A quiet-build stop period that is not a whole number of minutes from 1 to 300 stops before dispatch."""
        for value in ['0','301','1.5','']:
            result,matrix=self.select('x86_64-unknown-linux-musl',value)
            self.assertNotEqual(result.returncode,0,value);self.assertIsNone(matrix,value)
            self.assertIn(b'quiet_stop_minutes must be a whole number from 1 to 300',result.stderr,value)
        result,matrix=self.select('x86_64-unknown-linux-musl','20');self.assertEqual(result.returncode,0,result.stderr)


if __name__=='__main__':unittest.main()
