#!/usr/bin/env python3
"""Verify release adapter and installer lifecycle using controlled executable boundaries.

These tests execute actual shell scripts and filesystem operations. The Java boundary
is controlled; no result is evidence of native scheduling or compiler execution.
"""
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

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

    def test_unix_propagates_bootstrap_rejection_before_privileged_execution(self):
        """A rejecting bootstrap validator prevents the shell adapter from launching its target."""
        for platform in ['Linux','macOS']:
            with self.subTest(platform=platform),tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
                root=Path(tmp)
                destination=root/'source-cache-build/runtime/bin';destination.mkdir(parents=True)
                launcher=destination/'codex-tooling'
                launcher.write_text('#!/usr/bin/env bash\nprintf "bootstrap validation refused\\n" >&2\nexit 29\n')
                launcher.chmod(0o755)
                result,calls,_,_,_,_=self.invoke(root,platform)
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(calls,[])
                self.assertEqual(result.returncode,29)
                self.assertIn(b'bootstrap validation refused',result.stderr)

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


class ReleaseNativePreparationTest(unittest.TestCase):
    """Exercise shell routing; the controlled runtime does not establish native receipt validity."""

    def fixture(self, root, platform='Linux', architecture='X64', validation_status=0):
        """Supply a process boundary matching the producer/validator CLI and its recorded exit contract."""
        runtime=root/'source-cache-build/runtime';(runtime/'bin').mkdir(parents=True)
        support=root/'support literal ; $()';support.mkdir()
        output=root/'native literal ; $()'
        record=root/'native-calls.jsonl'
        tool=root/'runtime-fixture.py'
        tool.write_text('''import json,os,sys
from pathlib import Path
args=sys.argv[1:]
with Path(os.environ['NATIVE_RECORD']).open('a') as target:
 target.write(json.dumps(args)+'\\n')
if args[0]=='prepare-release-native':
 output=Path(args[1]);output.mkdir();(output/'product').write_bytes(b'fixture products')
elif args[0]=='validate-release-native':
 sys.exit(int(os.environ['NATIVE_VALIDATION_STATUS']))
else:
 sys.exit(90)
''')
        # The packaged entry point is a Bash launcher; its controlled child records exact argv.
        (runtime/'bin/codex-tooling').write_text(
            '#!/usr/bin/env bash\nexec "$FIXTURE_PYTHON" "$FIXTURE_TOOL" "$@"\n')
        env=dict(os.environ,RUNNER_OS=platform,RUNNER_ARCH=architecture,RUNNER_TEMP=str(root),
            FIXTURE_PYTHON=sys.executable,FIXTURE_TOOL=str(tool),NATIVE_RECORD=str(record),
            NATIVE_VALIDATION_STATUS=str(validation_status))
        return runtime,support,output,record,env

    def prepare(self, runtime, support, output, env):
        """Invoke the actual provisioner's shell-facing preparation operation."""
        return subprocess.run(['bash',str(SCRIPTS/'provision-release-scheduler.sh'),'prepare-native',
            str(runtime),str(output),str(support)],env=env,capture_output=True)

    def calls(self, record):
        """Read only the exact runtime requests issued by the shell under test."""
        return [json.loads(line) for line in record.read_text().splitlines()] if record.exists() else []

    def test_existing_products_validate_without_production(self):
        """Retained products select validation only, preserving quoted paths and the worker target."""
        for platform,architecture,native_arch in [('Linux','X64','x86_64'),('macOS','ARM64','arm64'),
            ('Windows','ARM64','arm64')]:
            with self.subTest(platform=platform),tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
                runtime,support,output,record,env=self.fixture(Path(tmp),platform,architecture)
                output.mkdir();(output/'product').write_bytes(b'retained')
                result=self.prepare(runtime,support,output,env)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(self.calls(record),[
                    ['validate-release-native',str(output),str(support),platform,native_arch]])
                self.assertEqual((output/'product').read_bytes(),b'retained')

    def test_absent_products_produce_then_validate(self):
        """An absent output selects fresh production followed by current-runtime validation."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            runtime,support,output,record,env=self.fixture(Path(tmp))
            result=self.prepare(runtime,support,output,env)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(self.calls(record),[
                ['prepare-release-native',str(output),str(support)],
                ['validate-release-native',str(output),str(support),'Linux','x86_64']])

    def test_rejected_products_preserve_bytes_and_failure_status(self):
        """A validator rejection is returned without replacing retained products or invoking production."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            runtime,support,output,record,env=self.fixture(Path(tmp),validation_status=61)
            output.mkdir();(output/'product').write_bytes(b'recovery evidence')
            result=self.prepare(runtime,support,output,env)
            self.assertEqual(result.returncode,61,result.stderr)
            self.assertEqual(len(self.calls(record)),1)
            self.assertEqual(self.calls(record)[0][0],'validate-release-native')
            self.assertEqual((output/'product').read_bytes(),b'recovery evidence')

    def test_unsupported_worker_architecture_starts_no_runtime(self):
        """An unsupported worker target is refused before production or validation starts."""
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            runtime,support,output,record,env=self.fixture(Path(tmp),architecture='X86')
            result=self.prepare(runtime,support,output,env)
            self.assertEqual(result.returncode,2,result.stderr)
            self.assertEqual(self.calls(record),[])
            self.assertFalse(output.exists())

    def test_workflow_rejection_stops_before_installation(self):
        """The actual release step stops at rejected reuse before querying the toolchain or elevating."""
        import yaml
        workflow=yaml.load((SCRIPTS.parent/'workflows/baseline-release-builds.yml').read_text(),Loader=yaml.BaseLoader)
        step=next(step for step in workflow['jobs']['build']['steps']
            if step.get('name')=='Provision the job-exclusive scheduler installation')
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            root=Path(tmp)
            runtime,support,_,record,env=self.fixture(root,validation_status=61)
            (root/'release-scheduler-native').mkdir()
            output=root/'release-native';output.mkdir();(output/'product').write_bytes(b'recovery evidence')
            shutil.copy2(SCRIPTS/'provision-release-scheduler.sh',root/'provision-release-scheduler.sh')
            (root/'baseline-evidence').mkdir()
            programs=root/'programs';programs.mkdir()
            after_validation=root/'after-validation'
            for name in ['rustc','sudo']:
                program=programs/name
                program.write_text('#!'+sys.executable+'\nfrom pathlib import Path\nimport os\n'
                    'Path(os.environ["AFTER_VALIDATION"]).touch()\nraise SystemExit(91)\n')
                program.chmod(0o755)
            env.update(PATH=str(programs)+os.pathsep+env['PATH'],AFTER_VALIDATION=str(after_validation))
            result=subprocess.run(['bash','-e','-o','pipefail','-c',step['run']],env=env,capture_output=True)
            self.assertEqual(result.returncode,61,result.stderr)
            self.assertEqual(self.calls(record),[
                ['validate-release-native',str(output),str(root/'release-scheduler-native'),'Linux','x86_64']])
            self.assertFalse(after_validation.exists())
            self.assertEqual((output/'product').read_bytes(),b'recovery evidence')


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
        return subprocess.run(['bash',str(SCRIPTS/'provision-release-scheduler.sh'),
            'prepare-debug-session',str(source),str(repository),str(evidence)],capture_output=True)

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
            process=subprocess.Popen(['bash',str(SCRIPTS/'provision-release-scheduler.sh'),
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
            result=subprocess.run(['bash',str(SCRIPTS/'provision-release-scheduler.sh'),
                'prepare-debug-session','C:/source','C:/checkout','C:/evidence'],env=environment,capture_output=True)
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
            shutil.copy2(SCRIPTS/'provision-release-scheduler.sh',root/'provision-release-scheduler.sh')
            result=subprocess.run(['bash','-e','-o','pipefail','-c',staging['run']],env=environment,capture_output=True)
            self.assertEqual(result.returncode,0,result.stderr)
            probe=subprocess.run(['bash',str(repository/'.github/actions/debug-failed-runner/probe.sh')],capture_output=True)
            self.assertEqual(probe.stdout,b'action available\n')


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
