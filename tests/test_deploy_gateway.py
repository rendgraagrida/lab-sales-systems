import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
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
        return Path(gateway.promote(self.request, self.policy, os.getuid(), release))

    def test_verified_promotion_is_independent_of_upload(self):
        promoted = self.promote()
        (self.release / 'migration' / 'pom.xml').write_text('tampered')
        self.assertEqual(REVISION, release.verify_release(promoted, REVISION, 'local', self.digest)['revision'])
        self.assertNotEqual(self.release, promoted)

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
            gateway.promote(self.request, self.policy, os.getuid() + 1, release)
        extra = self.release / 'app' / 'extra'
        extra.write_text('unexpected')
        with self.assertRaises(gateway.GatewayError):
            self.promote()
        extra.unlink()
        (self.release / 'migration' / 'pom.xml').write_text('changed')
        with self.assertRaises(gateway.GatewayError):
            self.promote()

    def test_symlink_and_nonregular_file_rejected(self):
        file = self.release / 'migration' / 'pom.xml'
        file.unlink()
        file.symlink_to('/etc/passwd')
        with self.assertRaises(OSError):
            self.promote()
        file.unlink()
        os.mkfifo(file)
        with self.assertRaises(gateway.GatewayError):
            self.promote()

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
            promoted = self.promote()
        self.assertTrue(changed)
        release.verify_release(promoted, REVISION, 'local', self.digest)

    def test_controller_digest_mismatch_rejected(self):
        with mock.patch.object(gateway, 'CONTROLLER_ROOT', str(SCRIPTS)), \
             mock.patch.object(gateway, 'trusted_directory'), \
             mock.patch.object(gateway, 'trusted_file', side_effect=lambda path: open(path, 'rb')):
            with self.assertRaises(gateway.GatewayError):
                gateway.verify_controllers(self.request['controller_sha256'])


if __name__ == '__main__':
    unittest.main()
