"""Disposable native sandbox integration fixture; never use on a VPS."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import uuid

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import deploy_sandbox as sandbox
sys.path.pop(0)


def hosted_root():
    return (sys.platform.startswith('linux') and os.geteuid() == 0
            and os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted')


def native(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


class SandboxFixture:
    """Owns only a random image, mount and unit pair on an isolated hosted runner."""
    def __enter__(self):
        if not hosted_root():
            raise RuntimeError('sandbox fixture requires isolated GitHub-hosted Linux root')
        self.base = Path(tempfile.mkdtemp(prefix='lab-sandbox-', dir='/run'))
        self.base.chmod(0o755)
        self.storage = self.base / 'storage'
        self.storage.mkdir(mode=0o755)
        self.outside = self.base / 'outside'
        self.outside.mkdir(mode=0o777)
        self.outside.chmod(0o777)
        self.image = self.base / 'storage.img'
        self.target_id = 'test' + uuid.uuid4().hex[:12]
        self.slice = 'lab-sales-' + self.target_id + '.slice'
        self.mount = native('/usr/bin/systemd-escape', '--path', '--suffix=mount', str(self.storage))
        self.unit_dir = Path('/run/systemd/system')
        self.mount_file = self.unit_dir / self.mount
        self.slice_file = self.unit_dir / self.slice
        self.units_created = False
        try:
            native('/usr/bin/fallocate', '-l', '128M', str(self.image))
            native('/usr/sbin/mkfs.ext4', '-F', '-q', str(self.image))
            fs_uuid = native('/usr/sbin/blkid', '-s', 'UUID', '-o', 'value', str(self.image))
            self.mount_file.write_text('[Unit]\nDescription=Disposable test mount\n[Mount]\n'
                                       f'What={self.image}\nWhere={self.storage}\nType=ext4\n'
                                       'Options=loop,rw,nosuid,nodev\n')
            self.units_created = True
            self.slice_file.write_text('[Unit]\nDescription=Disposable test slice\n[Slice]\n'
                                       'MemoryMax=536870912\nMemorySwapMax=0\n'
                                       'CPUQuota=200%\nTasksMax=128\n')
            native('/usr/bin/systemctl', 'daemon-reload')
            native('/usr/bin/systemctl', 'start', self.mount, self.slice)
            self.home = self.storage / 'home'
            self.home.mkdir(mode=0o700)
            self.migration_uid = self.migration_gid = 65534
            os.chown(self.home, self.migration_uid, self.migration_gid)
            self.sandbox = {'storage_root': str(self.storage), 'filesystem_uuid': fs_uuid,
                            'slice': self.slice, 'memory_max_bytes': 268435456,
                            'cpu_quota_percent': 100, 'tasks_max': 64, 'temporary_bytes': 16777216,
                            'project_memory_max_bytes': 536870912, 'project_cpu_quota_percent': 200,
                            'project_tasks_max': 128}
            self.cfg = {'target_id': self.target_id, 'sandbox': self.sandbox}
            return self
        except BaseException:
            self.__exit__(*sys.exc_info())
            raise

    def run_controller_probe(self, source):
        """Run test-owned source inside a real disposable controller unit."""
        if not isinstance(source, str):
            raise TypeError('probe source must be text')
        directory = self.base / ('probe-' + uuid.uuid4().hex)
        directory.mkdir(mode=0o755)
        for path in SCRIPTS.glob('*.py'):
            shutil.copyfile(path, directory / path.name)
        data = {'cfg': self.cfg, 'storage': str(self.storage), 'home': str(self.home),
                'outside': str(self.outside), 'migration_uid': self.migration_uid,
                'migration_gid': self.migration_gid}
        (directory / 'fixture.json').write_text(json.dumps(data))
        probe = directory / 'probe.py'
        probe.write_text(
            'import json,sys\nfrom pathlib import Path\n'
            'sys.path.insert(0, str(Path(__file__).parent))\n'
            'data=json.loads((Path(__file__).parent / "fixture.json").read_text())\n'
            'globals().update(data)\n'
            'storage,home,outside = map(Path, (storage,home,outside))\n'
            'import deploy_sandbox as sandbox\n'
            'import deploy_sandbox, deploy_linux\n'
            'assert sandbox.in_controller(cfg["sandbox"], cfg["target_id"])\n'
            + source + '\n')
        unit = f'lab-sales-{self.target_id}-control-{uuid.uuid4().hex}.service'
        args = sandbox._arguments(self.cfg, 'controller', unit, 120)
        return subprocess.run(args + ['--', '/usr/bin/python3', '-I', str(probe)],
                              capture_output=True, text=True, check=False, timeout=150)

    def __exit__(self, exc_type, exc, tb):
        if self.units_created:
            subprocess.run(['/usr/bin/systemctl', 'stop', self.slice, self.mount],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            self.slice_file.unlink(missing_ok=True)
            self.mount_file.unlink(missing_ok=True)
            subprocess.run(['/usr/bin/systemctl', 'daemon-reload'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if self.storage.is_mount():
            raise RuntimeError('fixture mount failed to stop; retained for safe cleanup')
        shutil.rmtree(self.base)


class SpecTests(unittest.TestCase):
    def test_shape_rejects_boolean_and_wrong_slice(self):
        spec = {'storage_root': '/srv/lab', 'filesystem_uuid': '12345678-1234-1234-1234-123456789abc',
                'slice': 'lab-sales-test.slice', 'memory_max_bytes': 100, 'cpu_quota_percent': 100,
                'tasks_max': 5, 'temporary_bytes': 10, 'project_memory_max_bytes': 200,
                'project_cpu_quota_percent': 200, 'project_tasks_max': 10}
        for key, value in (('tasks_max', True), ('slice', 'other.slice'), ('memory_max_bytes', 201)):
            invalid = dict(spec, **{key: value})
            with self.subTest(key=key), self.assertRaises(sandbox.SandboxError):
                sandbox._shape(invalid, 'test')


@unittest.skipUnless(hosted_root(), 'requires isolated GitHub-hosted Linux root')
class NativeSandboxTests(unittest.TestCase):
    def test_mount_slice_and_unprivileged_filesystem_identity(self):
        with SandboxFixture() as fixture:
            sandbox.validate(fixture.sandbox, fixture.target_id)
            with self.assertRaises(sandbox.SandboxError):
                sandbox.validate(dict(fixture.sandbox, filesystem_uuid='12345678-1234-1234-1234-123456789abc'), fixture.target_id)
            command = ['/bin/sh', '-c', 'id -u; id -g; id -G; touch "$HOME/ok"; '
                       'touch /tmp/ok; if touch "$1/no" 2>/dev/null; then exit 7; fi',
                       'fixture', str(fixture.outside)]
            with self.assertRaises(sandbox.SandboxError):
                sandbox.run_child(fixture.cfg, command, uid=fixture.migration_uid,
                                  gid=fixture.migration_gid, home=str(fixture.home), purpose='migration')
            result = fixture.run_controller_probe(
                'result = sandbox.run_child(cfg, ' + repr(command) + ', uid=migration_uid, '
                'gid=migration_gid, home=str(home), purpose="migration", environment={"HOME": str(home)})\n'
                'print(json.dumps({"status": result.returncode, "stdout": result.stdout, "stderr": result.stderr}))')
            self.assertEqual(0, result.returncode, result.stderr)
            child = json.loads(result.stdout)
            self.assertEqual(0, child['status'], child['stderr'])
            self.assertEqual(['65534', '65534', '65534'], child['stdout'].splitlines())
            self.assertTrue((fixture.home / 'ok').exists())
            self.assertFalse((fixture.outside / 'no').exists())

    def test_child_tmpfs_limit_and_cgroup_membership(self):
        with SandboxFixture() as fixture:
            code = (
                'import os\n'
                'from pathlib import Path\n'
                'group=Path("/proc/self/cgroup").read_text().strip().split("::",1)[1]\n'
                'base=Path("/sys/fs/cgroup") / group.lstrip("/")\n'
                'assert int((base/"memory.max").read_text()) == 268435456\n'
                'assert int((base/"pids.max").read_text()) == 64\n'
                'assert os.getgroups() == []\n'
                'try:\n'
                '    with open("/tmp/large","wb") as f:\n'
                '        for _ in range(20): f.write(b"x"*1048576)\n'
                'except OSError:\n'
                '    pass\n'
                'else:\n'
                '    raise AssertionError("tmpfs limit not enforced")\n')
            source = (
                'result = sandbox.run_child(cfg, ["/usr/bin/python3", "-c", ' + repr(code) + '], '
                'uid=migration_uid, gid=migration_gid, home=str(home), purpose="migration", '
                'environment={"HOME": str(home)})\n'
                'print(json.dumps({"status": result.returncode, "stderr": result.stderr}))')
            probe = fixture.run_controller_probe(source)
            self.assertEqual(0, probe.returncode, probe.stderr)
            child = json.loads(probe.stdout)
            self.assertEqual(0, child['status'], child['stderr'])

    def test_missing_slice_and_mount_fail_closed(self):
        with SandboxFixture() as fixture:
            native('/usr/bin/systemctl', 'stop', fixture.slice)
            with self.assertRaises(sandbox.SandboxError):
                sandbox.validate(fixture.sandbox, fixture.target_id)
            native('/usr/bin/systemctl', 'start', fixture.slice)
            native('/usr/bin/systemctl', 'stop', fixture.mount)
            with self.assertRaises(sandbox.SandboxError):
                sandbox.validate(fixture.sandbox, fixture.target_id)

    def test_controller_scope_is_real_and_detects_unconfined_process(self):
        with SandboxFixture() as fixture:
            self.assertFalse(sandbox.in_controller(fixture.sandbox, fixture.target_id))
            controller = fixture.base / 'controller'
            controller.mkdir(mode=0o755)
            shutil.copyfile(SCRIPTS / 'deploy_sandbox.py', controller / 'deploy_sandbox.py')
            script = controller / 'deploy_linux.py'
            result_file = fixture.storage / 'controller-result.json'
            script.write_text(
                'import json,sys\n'
                'from pathlib import Path\n'
                'sys.path.insert(0, str(Path(__file__).parent))\n'
                'import deploy_sandbox as sandbox\n'
                'cfg=json.loads(sys.argv[1])\n'
                'result=sandbox.in_controller(cfg["sandbox"], cfg["target_id"])\n'
                'Path(sys.argv[2]).write_text(json.dumps(result))\n'
                'sys.exit(0 if result else 1)\n')
            with mock.patch.object(sandbox, '_CONTROLLER', str(controller) + '/'):
                status = sandbox.run_controller(
                    fixture.cfg, ['/usr/bin/python3', '-I', str(script),
                                  json.dumps(fixture.cfg), str(result_file)])
            self.assertEqual(0, status)
            self.assertTrue(json.loads(result_file.read_text()))


if __name__ == '__main__':
    unittest.main()
