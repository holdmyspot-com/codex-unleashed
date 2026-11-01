#!/usr/bin/env python3
"""Verify release adapter and installer lifecycle using controlled executable boundaries.

These tests execute actual shell scripts and filesystem operations. The Java boundary
is controlled; no result is evidence of native scheduling or compiler execution.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).parent
COMMIT = "dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4"
JAVA = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
if 'prepare-cargo-launch' in args:
 if os.environ.get('CONSTRUCTION_FAILURE')=='true': sys.exit(19)
 out=Path(args[args.index('--output')+1])
 out.write_text(json.dumps({'environment':dict(os.environ),'argv':args}))
else:
 option=next(x for x in args if x.endswith('-launch-config'))
 cfg=Path(args[args.index(option)+1])
 with Path(os.environ['RECORD']).open('a') as stream:
  stream.write(json.dumps({'args':args,'configuration':json.loads(cfg.read_text()),'cwd':os.getcwd()})+'\n')
 os.write(1,b'out\x00\xff');os.write(2,b'err\x00\xfe')
 sys.exit(int(os.environ.get('STATUS','0')))
'''


class ReleaseSchedulerTest(unittest.TestCase):
    """Create separate fixture files and mutable state for every test."""

    def fixture(self, root, platform="Windows", status=0):
        """Construct representable stage inputs and controlled Java/git/admin programs."""
        install = root / 'installation literal ; $()'
        (install/'runtime/bin').mkdir(parents=True)
        for name in ['java', 'java.exe']:
            p=install/'runtime/bin'/name;p.write_text(JAVA);p.chmod(0o755)
        for name in ['toolchain','scratch','history','native']:(install/name).mkdir()
        (install/'native'/('frida.dll' if platform=='Windows' else 'frida')).write_bytes(b'boundary')
        (install/'installation').write_text('version=1\nplatform='+platform+'\n')
        (install/'cgroup-parent').write_text('/sys/fs/cgroup/controlled\n')
        programs=root/'programs';programs.mkdir()
        for name,text in {'git':'#!/usr/bin/env bash\nprintf "%s\\n" '+COMMIT+'\n',
                          'sudo':'#!/usr/bin/env bash\nshift\nif [[ $1 == install ]]; then shift; exec install -m 0444 "${@: -2}"; fi\nexec "$@"\n'}.items():
            p=programs/name;p.write_text(text);p.chmod(0o755)
        workspace=root/'workspace';workspace.mkdir();(workspace/'target').mkdir()
        env=dict(os.environ,PATH=str(programs)+os.pathsep+os.environ['PATH'],RUNNER_OS=platform,
            RUNNER_TEMP=str(root),GITHUB_WORKSPACE=str(workspace),CARGO_TARGET_DIR=str(workspace/'target'),
            RELEASE_SCHEDULER_ROOT=str(install),RELEASE_SCHEDULER_UID=str(os.getuid()),RELEASE_SCHEDULER_GID=str(os.getgid()),
            RELEASE_SCHEDULER_HEADROOM_BYTES='1073741824',RELEASE_SCHEDULER_MAX_JOBS='2',
            RELEASE_SCHEDULER_CONTROL_TIMEOUT_MS='120000',RECORD=str(root/'calls.jsonl'),STATUS=str(status),
            STABLE_GIT_COMMIT=COMMIT,CODEX_BWRAP_SHA256='first actual helper identity')
        return install,workspace,env

    def invoke(self, root, platform="Windows", status=0, construction_failure=False):
        """Run one actual adapter call and return child status and persisted boundary records."""
        install,workspace,env=self.fixture(root,platform,status)
        if construction_failure:env['CONSTRUCTION_FAILURE']='true'
        args=['build','--bin','name with spaces;$(literal)','@literal-argument-file','--release','--timings']
        result=subprocess.run(['bash',str(SCRIPTS/'release-scheduler-cargo.sh'),*args],cwd=workspace,env=env,capture_output=True)
        record=root/'calls.jsonl'
        calls=[json.loads(x) for x in record.read_text().splitlines()] if record.exists() else []
        return result,calls,args,install,workspace,env

    def test_public_dispatch_preserves_binary_streams_arguments_and_cwd(self):
        """Original Cargo argv/cwd and inherited separate binary descriptors cross the adapter."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            result,calls,args,install,workspace,_=self.invoke(Path(tmp))
            self.assertEqual(result.returncode,0,result.stderr)
            call=calls[0];argv=call['args']
            self.assertEqual(argv[argv.index('--')+1:],args)
            self.assertEqual(argv[argv.index('--workspace')+1],str(workspace))
            self.assertEqual(call['cwd'],str(workspace))
            self.assertIn('com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main',argv)
            self.assertEqual(result.stdout,b'out\x00\xff');self.assertEqual(result.stderr,b'err\x00\xfe')
            self.assertFalse((install/'retained-owner').exists())
            self.assertEqual(list(Path(tmp).glob('release-stage-*')),[])

    def test_each_stage_captures_updated_driver_environment(self):
        """New bwrap/STABLE values reach each construction rather than a stale shared JSON."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            root=Path(tmp);_,workspace,env=self.fixture(root)
            for identity in ['first actual helper identity','new completed helper identity']:
                env['CODEX_BWRAP_SHA256']=identity
                result=subprocess.run(['bash',str(SCRIPTS/'release-scheduler-cargo.sh'),'build'],cwd=workspace,env=env,capture_output=True)
                self.assertEqual(result.returncode,0,result.stderr)
            calls=[json.loads(x) for x in (root/'calls.jsonl').read_text().splitlines()]
            self.assertEqual([x['configuration']['environment']['CODEX_BWRAP_SHA256'] for x in calls],
                ['first actual helper identity','new completed helper identity'])

    def test_unix_rejects_mutable_foreign_installation_before_privileged_execution(self):
        """A caller-owned runtime outside the protected namespace is never elevated."""
        for platform in ['Linux','macOS']:
            with self.subTest(platform=platform),tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
                root=Path(tmp)
                destination=root/'source-cache-build/runtime/bin';destination.mkdir(parents=True)
                launcher=destination/'codex-tooling'
                launcher.write_text('#!/usr/bin/env bash\nexec "${CODEX_TEST_JAVA:?}" -cp "${CODEX_TEST_BOOTSTRAP_CLASSPATH:?}" com.holdmyspot.codexunleashed.tooling.Main "$@"\n')
                launcher.chmod(0o755)
                result,calls,_,_,_,_=self.invoke(root,platform)
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(calls,[])
                self.assertIn(b'protected /opt',result.stderr)

    def test_native_failure_retains_authority_and_returns_original_status(self):
        """An uncertain native outcome never invokes fallback or removes borrowed files."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            result,calls,_,install,_,_=self.invoke(Path(tmp),status=37)
            self.assertEqual(result.returncode,37,result.stderr);self.assertEqual(len(calls),1)
            self.assertTrue((install/'retained-owner').exists())
            self.assertEqual(len(list(Path(tmp).glob('release-stage-*'))),1)

    def test_construction_failure_never_starts_schedule(self):
        """A failed construction leaves no consumers or retained ownership marker."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            result,calls,_,install,_,_=self.invoke(Path(tmp),construction_failure=True)
            self.assertEqual(result.returncode,19);self.assertEqual(calls,[])
            self.assertFalse((install/'retained-owner').exists())
            self.assertEqual(list(Path(tmp).glob('release-stage-*')),[])

    def test_installer_copies_inputs_and_cleanup_requires_no_retained_owner(self):
        """Actual Windows caller-owned installation is exclusive and cleanup honors its lease."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            root=Path(tmp);runtime=root/'input-runtime';runtime.mkdir();(runtime/'marker').write_text('original')
            toolchain=root/'input-toolchain';toolchain.mkdir();(toolchain/'marker').write_text('tool')
            frida=root/'frida';frida.write_text('library');output=root/'environment';output.touch()
            env=dict(os.environ,RUNNER_OS='Windows',RUNNER_TEMP=str(root),GITHUB_ENV=str(output))
            command=['bash',str(SCRIPTS/'provision-release-scheduler.sh')]
            result=subprocess.run([*command,'install',str(runtime),str(toolchain),str(frida)],env=env,capture_output=True)
            self.assertEqual(result.returncode,0,result.stderr)
            install=Path(dict(x.split('=',1) for x in output.read_text().splitlines())['RELEASE_SCHEDULER_ROOT'])
            self.assertEqual((install/'runtime/marker').read_text(),'original')
            (install/'retained-owner').touch()
            refused=subprocess.run([*command,'cleanup',str(install)],env=env,capture_output=True)
            self.assertNotEqual(refused.returncode,0);self.assertTrue(install.exists())
            (install/'retained-owner').unlink()
            cleaned=subprocess.run([*command,'cleanup',str(install)],env=env,capture_output=True)
            self.assertEqual(cleaned.returncode,0,cleaned.stderr);self.assertFalse(install.exists());self.assertTrue(runtime.exists())


class WorkflowSelectionTest(unittest.TestCase):
    """Execute the actual selection step against complete declared target/readiness inputs."""

    def select(self,scheduler,target):
        """Run workflow selection without any Actions checkout, native acquisition or matrix dispatch."""
        import yaml
        workflow=yaml.load((SCRIPTS.parent/'workflows/baseline-release-builds.yml').read_text(),Loader=yaml.BaseLoader)
        step=workflow['jobs']['select']['steps'][-1]
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            output=Path(tmp)/'output'
            environment=dict(os.environ,CACHE_ENABLED='false',CACHE_HIT_REQUIRED='false',SELECTED_TARGET=target,
                VERIFY_INVALIDATION='false',TRACE_SIGNALS='false',SCHEDULER_ENABLED=str(scheduler).lower(),GITHUB_OUTPUT=str(output))
            result=subprocess.run(['bash','-e','-o','pipefail','-c',step['run']],cwd=SCRIPTS.parents[1],env=environment,capture_output=True)
            matrix=json.loads(output.read_text().removeprefix('value=')) if output.exists() else None
            return result,matrix

    def test_default_unscheduled_matrix_is_preserved(self):
        """The default unscheduled path still selects every declared standard release target."""
        result,matrix=self.select(False,'all');self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(len(matrix['include']),6)

    def test_scheduler_rejects_unmet_mac_before_matrix_dispatch(self):
        """A failed native prerequisite prevents required release execution instead of a bypass."""
        result,matrix=self.select(True,'all');self.assertNotEqual(result.returncode,0);self.assertIsNone(matrix)
        self.assertIn(b'prerequisites remain unmet',result.stderr)

    def test_ready_platform_selects_unchanged_target(self):
        """A supported selected Linux target reaches the candidate's later provisioning gate."""
        result,matrix=self.select(True,'x86_64-unknown-linux-musl');self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual([x['target'] for x in matrix['include']],['x86_64-unknown-linux-musl'])


if __name__=='__main__':unittest.main()
