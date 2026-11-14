from pathlib import Path
import sys
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
try:
    import deploy
finally:
    sys.path.pop(0)


class TransportTests(unittest.TestCase):
    def test_unsupported_and_invalid_inputs_never_connect(self):
        with mock.patch.object(deploy.subprocess, 'run') as run:
            for action, environment in (('bootstrap', 'staging'), ('enroll', 'staging'), ('status', 'other')):
                with self.subTest(action=action), self.assertRaises(deploy.DeployError):
                    deploy.deploy_transport(action, environment, '/etc/policy', 'example')
            with self.assertRaises(deploy.DeployError):
                deploy.deploy_transport('status', 'staging', '/etc/policy', 'example', release_dir='/tmp/release')
            run.assert_not_called()



if __name__ == '__main__':
    unittest.main()
