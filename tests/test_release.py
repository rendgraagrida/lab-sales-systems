import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
try:
    import ci
    import release
finally:
    sys.path.pop(0)

REVISION = 'a' * 40


class ReleaseBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.jar = self.root / 'input.jar'
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr(release.POM, (SCRIPTS.parent / 'pom.xml').read_bytes())
            for name in release.SQL_NAMES:
                archive.writestr(release.SQL_PREFIX + name, 'CREATE TABLE example_' + name[:2].lower() + '(id integer);')
        self.app = self.root / 'application'
        ci.package(self.jar, self.app, REVISION, 'local')
        self.output = self.root / 'release'

    def package(self):
        release.package_release(self.app, self.output, REVISION, 'local')
        return hashlib.sha256((self.output / 'release.json').read_bytes()).hexdigest()

    def test_release_requires_and_attests_current_schema_lineage(self):
        digest = self.package()
        manifest = release.verify_release(self.output, REVISION, 'local', digest)
        manifest['schema_versions'] = ['1', '2', '3']
        manifest_path = self.output / 'release.json'
        manifest_path.write_text(json.dumps(manifest), encoding='ascii')
        replacement = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
        with self.assertRaises(release.ReleaseError):
            release.verify_release(self.output, REVISION, 'local', replacement)

    def test_new_historical_release_is_refused(self):
        for names in (release.LEGACY_SQL_NAMES, release.V4_SQL_NAMES, release.V5_SQL_NAMES, release.V6_SQL_NAMES):
            with self.subTest(names=names):
                with zipfile.ZipFile(self.jar, 'w') as archive:
                    archive.writestr(release.POM, (SCRIPTS.parent / 'pom.xml').read_bytes())
                    for name in names:
                        archive.writestr(release.SQL_PREFIX + name, 'SELECT 1;')
                app = self.root / ('legacy-application-' + str(len(names)))
                ci.package(self.jar, app, REVISION, 'local')
                with self.assertRaises(release.ReleaseError):
                    release.package_release(app, self.output, REVISION, 'local')
                self.assertFalse(self.output.exists())

    def test_schema_identity_follows_embedded_migrations(self):
        current = release.schema_identity(self.jar)
        legacy = self.root / 'legacy.jar'
        with zipfile.ZipFile(legacy, 'w') as archive:
            archive.writestr(release.POM, (SCRIPTS.parent / 'pom.xml').read_bytes())
            for name in release.LEGACY_SQL_NAMES:
                archive.writestr(release.SQL_PREFIX + name, 'CREATE TABLE example_' + name[:2].lower() + '(id integer);')
        old_identity = release.schema_identity(legacy)
        self.assertTrue(current.startswith(old_identity + ',4|'))
        self.assertNotEqual(current, old_identity)

    def test_retained_historical_release_verifies_only_its_own_tuple(self):
        for names in (release.LEGACY_SQL_NAMES, release.V4_SQL_NAMES, release.V5_SQL_NAMES, release.V6_SQL_NAMES):
            with self.subTest(names=names):
                jar = self.root / ('historical-' + str(len(names)) + '.jar')
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr(release.POM, (SCRIPTS.parent / 'pom.xml').read_bytes())
                    for name in names:
                        archive.writestr(release.SQL_PREFIX + name, 'SELECT 1;')
                directory = self.root / ('release-' + str(len(names)))
                directory.mkdir()
                ci.package(jar, directory / 'app', REVISION, 'local')
                files = {}
                for path in (directory / 'app').iterdir():
                    with path.open('rb') as source:
                        files['app/' + path.name] = ci.digest_file(source)
                for relative, data in release.derived(jar).items():
                    target = directory / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
                    with target.open('rb') as source:
                        files[relative] = ci.digest_file(source)
                manifest = {'schema': 1, 'project': 'lab-sales-systems', 'revision': REVISION,
                            'run_id': 'local', 'schema_versions': [str(i) for i in range(1, len(names) + 1)],
                            'files': files}
                path = directory / 'release.json'
                path.write_text(json.dumps(manifest), encoding='ascii')
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                self.assertEqual(manifest, release.verify_release(directory, REVISION, 'local', digest))
                manifest['schema_versions'] = ['1', '2', '3', '4', '5', '6', '7']
                path.write_text(json.dumps(manifest), encoding='ascii')
                with self.assertRaises(release.ReleaseError):
                    release.verify_release(directory, REVISION, 'local', hashlib.sha256(path.read_bytes()).hexdigest())

    def test_wrong_identity_digest_and_extra_file_are_rejected(self):
        digest = self.package()
        for revision, run_id, expected_digest in ((REVISION, 'local', '0' * 64), ('b' * 40, 'local', digest), (REVISION, '42', digest)):
            with self.subTest(revision=revision, run_id=run_id), self.assertRaises(release.ReleaseError):
                release.verify_release(self.output, revision, run_id, expected_digest)
        extra = self.output / 'migration' / 'secret.env'
        extra.write_text('not a permitted release entry', encoding='ascii')
        with self.assertRaises(release.ReleaseError):
            release.verify_release(self.output, REVISION, 'local', digest)

    def test_rehashed_migration_substitution_cannot_detach_sql_from_jar(self):
        self.package()
        relative = 'migration/sql/' + release.SQL_NAMES[0]
        sql = self.output / relative
        sql.write_text('DROP TABLE unrelated;', encoding='ascii')
        manifest_path = self.output / 'release.json'
        manifest = json.loads(manifest_path.read_text(encoding='ascii'))
        manifest['files'][relative] = {'sha256': hashlib.sha256(sql.read_bytes()).hexdigest(), 'size': sql.stat().st_size}
        manifest_path.write_text(json.dumps(manifest), encoding='ascii')
        replacement_digest = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
        with self.assertRaises(release.ReleaseError):
            release.verify_release(self.output, REVISION, 'local', replacement_digest)

    def test_unsafe_archive_is_refused_without_touching_existing_destination(self):
        with zipfile.ZipFile(self.jar, 'a') as archive:
            archive.writestr('../escape.sql', 'untrusted')
        unsafe_app = self.root / 'unsafe-application'
        ci.package(self.jar, unsafe_app, REVISION, 'local')
        self.app = unsafe_app
        with self.assertRaises(release.ReleaseError):
            release.package_release(self.app, self.output, REVISION, 'local')
        self.assertFalse(self.output.exists())
        self.assertFalse((self.root / 'escape.sql').exists())

    def test_existing_release_is_never_overwritten(self):
        self.output.mkdir()
        marker = self.output / 'keep'
        marker.write_text('original', encoding='ascii')
        with self.assertRaises(release.ReleaseError):
            release.package_release(self.app, self.output, REVISION, 'local')
        self.assertEqual('original', marker.read_text(encoding='ascii'))
        self.assertEqual({'keep'}, {path.name for path in self.output.iterdir()})


if __name__ == '__main__':
    unittest.main()
