import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
try:
    import ci
    import local
    import release
finally:
    sys.path.pop(0)


class Reply:
    status = 200

    def __init__(self, data):
        self.data = data

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, limit):
        return self.data[:limit]


class LauncherBoundaryTests(unittest.TestCase):
    def test_readiness_requires_exact_current_schema_and_revision(self):
        revision = 'a' * 40
        current = len(release.SQL_NAMES)
        for version, actual_revision, expected in ((current, revision, True), (current - 1, revision, False),
                                                    (current + 1, revision, False), (True, revision, False),
                                                    (current, 'b' * 40, False)):
            health = json.dumps({'status': 'UP', 'application': 'lab-sales-systems',
                                 'revision': actual_revision, 'schemaVersion': version}).encode()
            opener = mock.Mock()
            opener.open.side_effect = [Reply(health), Reply(b'Sign in | Lab Sales Systems')]
            with self.subTest(version=version, revision=actual_revision), \
                    mock.patch.object(local, 'build_opener', return_value=opener), \
                    mock.patch.object(local, 'owns_loopback_listener', return_value=True):
                self.assertEqual(expected, local.probe(8187, revision, 42))

    def test_launcher_rejects_old_artifact_even_if_valid(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for names in (release.V4_SQL_NAMES, release.V5_SQL_NAMES, release.V6_SQL_NAMES, release.SQL_NAMES):
                jar = root / ('input' + str(len(names)) + '.jar')
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr(release.POM, (Path(__file__).resolve().parents[1] / 'pom.xml').read_bytes())
                    for name in names:
                        archive.writestr(release.SQL_PREFIX + name, 'SELECT 1;')
                artifact = root / ('app' + str(len(names)))
                ci.package(jar, artifact, 'a' * 40, 'local')
                if names != release.SQL_NAMES:
                    with self.assertRaises(local.db.OperationError):
                        local.verify_artifact(artifact, 'a' * 40)
                else:
                    self.assertEqual(64, len(local.verify_artifact(artifact, 'a' * 40)))


if __name__ == '__main__':
    unittest.main()
