"""Filesystem regression for restoring temporary native-installer policies."""
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock

if sys.platform.startswith('linux'):
    SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
    sys.path.insert(0, str(SCRIPTS))
    import ci_native_postgres as installer
    sys.path.pop(0)


@unittest.skipUnless(sys.platform.startswith('linux'), 'Native installer uses POSIX ownership')
class TemporaryPolicyTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='native-policy-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def existing(self, name, data, mode):
        path = self.root / name
        path.write_bytes(data)
        path.chmod(mode)
        return path, path.stat()

    def assert_restored(self, path, data, before):
        self.assertEqual(data, path.read_bytes())
        after = path.stat()
        self.assertEqual((before.st_uid, before.st_gid, stat.S_IMODE(before.st_mode)),
                         (after.st_uid, after.st_gid, stat.S_IMODE(after.st_mode)))

    def test_partial_replacement_write_restores_existing_policy(self):
        path, before = self.existing('policy', b'original service policy\n', 0o640)
        real_write = Path.write_bytes

        def partial_write(target, data):
            if target == path and data == b'temporary policy\n':
                real_write(target, data[:4])
                raise OSError('injected partial write')
            return real_write(target, data)

        with mock.patch.object(Path, 'write_bytes', partial_write):
            with self.assertRaisesRegex(OSError, 'injected partial write'):
                with installer.replace_temporarily(path, b'temporary policy\n', 0o755):
                    self.fail('Replacement failure must not enter package installation')
        self.assert_restored(path, b'original service policy\n', before)

    def test_partial_creation_failure_removes_new_policy(self):
        path = self.root / 'new-policy'
        real_write = Path.write_bytes

        def partial_write(target, data):
            real_write(target, data[:3])
            raise OSError('injected creation failure')

        with mock.patch.object(Path, 'write_bytes', partial_write):
            with self.assertRaisesRegex(OSError, 'injected creation failure'):
                with installer.replace_temporarily(path, b'new policy\n', 0o755):
                    self.fail('Failed policy creation must not start installation')
        self.assertFalse(os.path.lexists(path))

    def test_nested_chmod_failure_restores_both_original_policies(self):
        config, config_before = self.existing('cluster.conf', b'create_main_cluster = true\n', 0o640)
        policy, policy_before = self.existing('service-policy', b'existing policy\n', 0o700)
        real_chmod = Path.chmod

        def failed_chmod(target, mode, **kwargs):
            real_chmod(target, mode, **kwargs)
            if target == policy and mode == 0o755:
                raise OSError('injected chmod failure')

        with mock.patch.object(Path, 'chmod', failed_chmod):
            with self.assertRaisesRegex(OSError, 'injected chmod failure'):
                with installer.replace_temporarily(config, b'create_main_cluster = false\n', 0o644):
                    with installer.replace_temporarily(policy, b'temporary policy\n', 0o755):
                        self.fail('Failed replacement must not run installation')
        self.assert_restored(config, b'create_main_cluster = true\n', config_before)
        self.assert_restored(policy, b'existing policy\n', policy_before)

    def test_installation_failure_restores_policy_bytes_and_permissions(self):
        path, before = self.existing('policy', b'original\n', 0o640)
        with self.assertRaisesRegex(RuntimeError, 'installation failed'):
            with installer.replace_temporarily(path, b'temporary\n', 0o755):
                self.assertEqual(b'temporary\n', path.read_bytes())
                self.assertEqual(0o755, stat.S_IMODE(path.stat().st_mode))
                raise RuntimeError('installation failed')
        self.assert_restored(path, b'original\n', before)


if __name__ == '__main__':
    unittest.main()
