import hashlib
import json
import os
from contextlib import ExitStack
from pathlib import Path
import sys
import shutil
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
try:
    import ci
    import release
    if sys.platform.startswith('linux'):
        import deploy_gateway as gateway
finally:
    sys.path.pop(0)

REVISION = 'a' * 40


@unittest.skipUnless(sys.platform.startswith('linux'), 'Linux descriptor and ownership semantics required')
class GatewayBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.upload = tempfile.TemporaryDirectory(prefix='crm-upload-', dir='/tmp')
        self.addCleanup(self.upload.cleanup)
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        jar = self.root / 'input.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr(release.POM, (SCRIPTS.parent / 'pom.xml').read_bytes())
            for name in release.SQL_NAMES:
                archive.writestr(release.SQL_PREFIX + name, 'SELECT 1;')
        app = self.root / 'app'
        ci.package(jar, app, REVISION, 'local')
        self.release = Path(self.upload.name) / 'release'
        release.package_release(app, self.release, REVISION, 'local')
        self.digest = hashlib.sha256((self.release / 'release.json').read_bytes()).hexdigest()
        self.policy = {'environment': 'staging', 'promotion_root': str(self.root)}
        self.request = {'schema': 1, 'action': 'migrate', 'environment': 'staging',
                        'release_dir': str(self.release), 'revision': REVISION, 'run_id': 'local',
                        'manifest_sha256': self.digest,
                        'controller_sha256': {name: '0' * 64 for name in gateway.CONTROLLERS}}

    def promote(self):
        return gateway.promote(self.request, self.policy, os.getuid(), release)

    def run_gateway(self, adapter):
        policy = dict(self.policy, target_config='/trusted/target.json')
        uid = os.getuid()
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(gateway.os, 'geteuid', side_effect=[0, uid, uid]))
            stack.enter_context(mock.patch.object(gateway, 'load_policy', return_value=(policy, uid)))
            stack.enter_context(mock.patch.object(gateway, 'verify_controllers'))
            stack.enter_context(mock.patch.dict(sys.modules, {'deploy_linux': SimpleNamespace(main=adapter)}))
            return gateway.main(['--policy', '/trusted/policy.json', '--request', json.dumps(self.request)])

    def test_verified_promotion_is_independent_of_upload(self):
        with self.promote() as promoted:
            (self.release / 'migration' / 'pom.xml').write_text('tampered')
            self.assertEqual(REVISION, release.verify_release(promoted, REVISION, 'local', self.digest)['revision'])
            self.assertNotEqual(self.release, Path(promoted))
        self.assertFalse(Path(promoted).exists())
        self.assertTrue(self.release.exists())

    def test_invalid_identity_environment_and_operations_rejected(self):
        for change in ({'environment': 'production'}, {'action': 'bootstrap'},
                       {'action': 'enroll'}, {'release_dir': '/etc/release'},
                       {'controller_sha256': {'ci.py': '0' * 64}}, {'schema': True}):
            with self.subTest(change=change), self.assertRaises(gateway.GatewayError):
                gateway.validate_request(dict(self.request, **change), self.policy)
        with self.assertRaises(gateway.GatewayError):
            gateway.validate_request(dict(self.request, action='status'), self.policy)
        gateway.validate_request(dict(self.request, action='status', release_dir=None,
                                      revision=None, run_id=None, manifest_sha256=None), self.policy)

    def test_policy_identity_is_bound_to_sudo_user(self):
        policy_file = self.root / 'policy.json'
        config_file = self.root / 'target.json'
        config_file.write_text('{}')
        policy_file.write_text(json.dumps({
            'schema': 1, 'project': 'lab-sales-systems', 'environment': 'staging',
            'target_config': str(config_file), 'deploy_user': 'deployer',
            'upload_parent': '/tmp', 'promotion_root': str(self.root)}))
        user = mock.Mock(pw_uid=1234, pw_name='deployer')
        with mock.patch.object(gateway, 'trusted_file', side_effect=lambda path, mode=None: open(path, 'rb')), \
             mock.patch.object(gateway, 'trusted_directory'), \
             mock.patch.object(gateway.pwd, 'getpwnam', return_value=user), \
             mock.patch.dict(os.environ, {'SUDO_USER': 'someone-else'}):
            with self.assertRaises(gateway.GatewayError):
                gateway.load_policy(str(policy_file))
        user.pw_uid = 0
        with mock.patch.object(gateway, 'trusted_file', side_effect=lambda path, mode=None: open(path, 'rb')), \
             mock.patch.object(gateway, 'trusted_directory'), \
             mock.patch.object(gateway.pwd, 'getpwnam', return_value=user), \
             mock.patch.dict(os.environ, {'SUDO_USER': 'deployer'}):
            with self.assertRaises(gateway.GatewayError):
                gateway.load_policy(str(policy_file))


    def test_duplicate_json_and_traversal_are_rejected(self):
        with self.assertRaises(gateway.GatewayError):
            gateway.parse_json('{"schema":1,"schema":1}')
        for path in ('/tmp/../etc', '/tmp//policy', '/tmp/./policy'):
            with self.subTest(path=path), self.assertRaises(gateway.GatewayError):
                gateway.clean_path(path)

    def test_tampering_extra_files_and_wrong_identity_fail(self):
        with self.assertRaises(gateway.GatewayError):
            with gateway.promote(self.request, self.policy, os.getuid() + 1, release):
                self.fail('untrusted upload accepted')
        extra = self.release / 'app' / 'extra'
        extra.write_text('unexpected')
        with self.assertRaises(gateway.GatewayError):
            with self.promote():
                self.fail('unexpected file accepted')
        extra.unlink()
        (self.release / 'migration' / 'pom.xml').write_text('changed')
        with self.assertRaises(gateway.GatewayError):
            with self.promote():
                self.fail('tampered upload accepted')

    def test_symlink_and_nonregular_file_rejected(self):
        file = self.release / 'migration' / 'pom.xml'
        file.unlink()
        file.symlink_to('/etc/passwd')
        with self.assertRaises(OSError):
            with self.promote():
                self.fail('symlink accepted')
        file.unlink()
        os.mkfifo(file)
        with self.assertRaises(gateway.GatewayError):
            with self.promote():
                self.fail('nonregular file accepted')

    def test_directory_swap_cannot_redirect_open_descriptor(self):
        original = gateway.read_source
        changed = False
        def swap(parent, name, uid, limit):
            nonlocal changed
            if name == 'application.jar' and not changed:
                changed = True
                os.rename(self.release / 'app', self.release / 'old-app')
                (self.release / 'app').symlink_to('/etc')
            return original(parent, name, uid, limit)
        with mock.patch.object(gateway, 'read_source', side_effect=swap):
            with self.promote() as promoted:
                self.assertTrue(changed)
                release.verify_release(promoted, REVISION, 'local', self.digest)

    def test_repeated_failed_copies_are_removed_without_touching_upload(self):
        source = self.release / 'migration' / 'pom.xml'
        original = source.read_bytes()
        source.write_bytes(bytes([original[0] ^ 1]) + original[1:])
        for _ in range(3):
            with self.assertRaises(gateway.GatewayError):
                with self.promote():
                    self.fail('tampered upload accepted')
            self.assertEqual([], list(self.root.glob('crm-release-*')))
            self.assertTrue(source.exists())

    def test_verification_failure_removes_completed_copy(self):
        def reject(promoted, *_args):
            self.assertTrue((Path(promoted) / 'app' / 'application.jar').is_file())
            raise release.ReleaseError('rejected after copy')
        with mock.patch.object(release, 'verify_release', side_effect=reject):
            for _ in range(3):
                with self.assertRaises(release.ReleaseError):
                    with self.promote():
                        self.fail('invalid release accepted')
                self.assertEqual([], list(self.root.glob('crm-release-*')))
                self.assertTrue(self.release.exists())

    def test_adapter_consumption_and_failures_clean_only_promotion(self):
        retained = self.root / 'retained-release'
        seen = []

        def consume(args):
            promoted = Path(args[args.index('--release-dir') + 1])
            release.verify_release(promoted, REVISION, 'local', self.digest)
            shutil.copytree(promoted, retained)
            seen.append(promoted)
            return 0

        self.assertEqual(0, self.run_gateway(consume))
        self.assertFalse(seen[0].exists())
        self.assertTrue(self.release.exists())
        release.verify_release(retained, REVISION, 'local', self.digest)

        def fail(args):
            promoted = Path(args[args.index('--release-dir') + 1])
            self.assertTrue(promoted.is_dir())
            seen.append(promoted)
            return 1

        for _ in range(3):
            self.assertEqual(1, self.run_gateway(fail))
            self.assertFalse(seen[-1].exists())
            self.assertEqual([], list(self.root.glob('crm-release-*')))
            self.assertTrue(self.release.exists())
        release.verify_release(retained, REVISION, 'local', self.digest)

    def test_promotion_lock_refuses_concurrent_holder_and_symlink(self):
        with gateway.promotion_lock(self.policy):
            with self.assertRaises(gateway.GatewayError):
                with gateway.promotion_lock(self.policy):
                    self.fail('concurrent promotion accepted')
            contender = subprocess.run(
                [sys.executable, '-c',
                 'import sys; sys.path.insert(0, sys.argv[1]); import deploy_gateway as g; '
                 "lock = g.promotion_lock({'promotion_root': sys.argv[2]}); lock.__enter__()",
                 str(SCRIPTS), str(self.root)],
                capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, contender.returncode)
            self.assertIn('promotion already in progress', contender.stderr)
        lock = self.root / '.gateway-promotion.lock'
        lock.unlink()
        lock.symlink_to(self.release / 'release.json')
        with self.assertRaises(OSError):
            with gateway.promotion_lock(self.policy):
                self.fail('symlink lock accepted')
        self.assertTrue((self.release / 'release.json').is_file())

    def test_noncanonical_arguments_rejected_before_policy_or_target_effect(self):
        request = json.dumps(self.request)
        invalid = (
            ['--policy', '/fixed', '--request', request, '--policy', '/other'],
            ['--policy', '/fixed', '--request', request, '--request', request],
            ['--request', request, '--policy', '/fixed'],
            ['--policy', '/fixed', '--request', request, 'extra'],
        )
        marker = self.root / 'target-effect'
        def touch_target(_):
            marker.write_text('unexpected')
            raise AssertionError('policy loaded for noncanonical arguments')
        with mock.patch.object(gateway, 'load_policy', side_effect=touch_target):
            for argv in invalid:
                with self.subTest(argv=argv), self.assertRaises(SystemExit) as result:
                    gateway.main(argv)
                self.assertEqual(2, result.exception.code)
                self.assertFalse(marker.exists())

    def test_controller_digest_mismatch_rejected(self):
        with mock.patch.object(gateway, 'CONTROLLER_ROOT', str(SCRIPTS)), \
             mock.patch.object(gateway, 'trusted_directory'), \
             mock.patch.object(gateway, 'trusted_file', side_effect=lambda path: open(path, 'rb')):
            with self.assertRaises(gateway.GatewayError):
                gateway.verify_controllers(self.request['controller_sha256'])


if __name__ == '__main__':
    unittest.main()
