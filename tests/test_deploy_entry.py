import os
from pathlib import Path
import sys
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
if sys.platform.startswith('linux'):
    import deploy_entry as entry
sys.path.pop(0)


@unittest.skipUnless(sys.platform.startswith('linux'), 'Linux entry')
class EntryTests(unittest.TestCase):
    def test_arguments_cannot_select_a_command_or_policy(self):
        with mock.patch.object(entry.os, 'geteuid', return_value=0), \
             mock.patch.object(entry, '_trusted') as trusted:
            for args in (['id'], ['--policy', '/tmp/policy'], ['internal-sftp']):
                self.assertNotEqual(0, entry.main(args))
            trusted.assert_not_called()

    def test_invalid_sudo_identity_is_rejected(self):
        with mock.patch.object(entry.os, 'geteuid', return_value=0), \
             mock.patch.dict(os.environ, {'SUDO_USER': '../root'}), \
             mock.patch.object(entry, '_trusted') as trusted:
            self.assertNotEqual(0, entry.main([]))
            trusted.assert_not_called()


if __name__ == '__main__':
    unittest.main()

