import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'ci.py'
spec = importlib.util.spec_from_file_location('project_ci', SCRIPT)
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)
REVISION = 'a' * 40


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.jar = self.root / 'input.jar'
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
            archive.writestr('example.class', b'fixture bytes')
        self.output = self.root / 'artifact'

    def create(self):
        ci.package(self.jar, self.output, REVISION, '42')

    def test_valid_artifact_and_candidate(self):
        self.create()
        ci.verify(self.output, REVISION, '42')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, 'b' * 40, '42')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '43')

    def test_tampered_jar_and_manifest_rejected(self):
        self.create()
        with (self.output / 'application.jar').open('ab') as file:
            file.write(b'tampered')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')
        self.output.joinpath('application.jar').write_bytes(self.jar.read_bytes())
        manifest_path = self.output / 'manifest.json'
        manifest = json.loads(manifest_path.read_text(encoding='ascii'))
        manifest['files']['application.jar']['size'] += 1
        manifest_path.write_text(json.dumps(manifest), encoding='ascii')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')

    def test_extra_and_missing_entries_rejected(self):
        self.create()
        extra = self.output / 'other'
        extra.write_text('unexpected', encoding='ascii')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')
        extra.unlink()
        (self.output / 'application.jar').unlink()
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')

    def test_existing_destination_never_overwritten(self):
        self.output.mkdir()
        marker = self.output / 'keep'
        marker.write_text('original', encoding='ascii')
        with self.assertRaises(OSError):
            self.create()
        self.assertEqual(marker.read_text(encoding='ascii'), 'original')
        self.assertEqual({p.name for p in self.output.iterdir()}, {'keep'})

    def test_invalid_jar_leaves_no_artifact(self):
        self.jar.write_bytes(b'not a jar')
        with self.assertRaises(ci.CheckError):
            self.create()
        self.assertFalse(self.output.exists())

    def test_ancestor_swap_never_deletes_unrelated_directory(self):
        parent = self.root / 'output-parent'
        parent.mkdir()
        self.output = parent / 'artifact'
        victim = self.root / 'victim'
        victim_output = victim / 'artifact'
        victim_output.mkdir(parents=True)
        marker = victim_output / 'keep'
        marker.write_text('original', encoding='ascii')
        probe = self.root / 'link-probe'
        try:
            probe.symlink_to(victim, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest('creating symlinks is unavailable')
        probe.unlink()
        original_mkdir = ci.os.mkdir

        def swap_after_create(path, *args, **kwargs):
            original_mkdir(path, *args, **kwargs)
            if Path(path) == self.output:
                parent.rename(self.root / 'moved-parent')
                parent.symlink_to(victim, target_is_directory=True)

        with mock.patch.object(ci.os, 'mkdir', side_effect=swap_after_create):
            with self.assertRaises(ci.CheckError):
                self.create()
        self.assertTrue(marker.exists(), 'packaging removed an unrelated directory')
        self.assertEqual(marker.read_text(encoding='ascii'), 'original')

    def test_manifest_write_failure_leaves_unverified_output_without_cleanup(self):
        original_open = Path.open

        def fail_manifest(path, *args, **kwargs):
            if path == self.output / 'manifest.json':
                raise OSError('simulated manifest write failure')
            return original_open(path, *args, **kwargs)

        with mock.patch.object(Path, 'open', new=fail_manifest):
            with self.assertRaises(OSError):
                self.create()
        self.assertTrue((self.output / 'application.jar').is_file())
        self.assertFalse((self.output / 'manifest.json').exists())
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')

    def test_invalid_candidate_and_traversal_are_rejected(self):
        for revision in ('latest', 'A' * 40, 'a' * 39, 'content:' + '0' * 63):
            with self.subTest(revision=revision), self.assertRaises(ci.CheckError):
                ci.package(self.jar, self.output, revision, '42')
        for run_id in ('0', '-1', '01', 'latest'):
            with self.subTest(run_id=run_id), self.assertRaises(ci.CheckError):
                ci.package(self.jar, self.output, REVISION, run_id)
        with self.assertRaises(ci.CheckError):
            ci.package(self.jar, self.root / 'sub' / '..' / 'artifact', REVISION, '42')
        self.assertFalse(self.output.exists())


    def test_symlink_entries_and_source_are_rejected(self):
        link = self.root / 'linked.jar'
        try:
            link.symlink_to(self.jar)
        except (OSError, NotImplementedError):
            self.skipTest('creating symlinks is unavailable')
        with self.assertRaises(ci.CheckError):
            ci.package(link, self.output, REVISION, '42')
        self.create()
        (self.output / 'application.jar').unlink()
        (self.output / 'application.jar').symlink_to(self.jar)
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')

    def test_symlink_directory_is_rejected(self):
        self.create()
        link = self.root / 'linked-dir'
        try:
            link.symlink_to(self.output, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest('creating symlinks is unavailable')
        with self.assertRaises(ci.CheckError):
            ci.verify(link, REVISION, '42')

    def test_manifest_cannot_name_traversal_or_duplicate_keys(self):
        self.create()
        manifest_path = self.output / 'manifest.json'
        manifest = json.loads(manifest_path.read_text(encoding='ascii'))
        manifest['files']['../application.jar'] = manifest['files'].pop('application.jar')
        manifest_path.write_text(json.dumps(manifest), encoding='ascii')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')
        manifest_path.write_text('{"schema": 1, "schema": 1}', encoding='ascii')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')

    def test_invalid_jar_with_matching_manifest_rejected(self):
        self.create()
        jar = self.output / 'application.jar'
        jar.write_bytes(b'not a jar')
        manifest_path = self.output / 'manifest.json'
        manifest = json.loads(manifest_path.read_text(encoding='ascii'))
        with jar.open('rb') as file:
            manifest['files']['application.jar'] = ci.digest_file(file)
        manifest_path.write_text(json.dumps(manifest), encoding='ascii')
        with self.assertRaises(ci.CheckError):
            ci.verify(self.output, REVISION, '42')


class MarkdownTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / 'README.md').write_text('[Guide](guide.md#hello-world)\n', encoding='ascii')
        (self.root / 'guide.md').write_text('# Hello world\n', encoding='ascii')

    def test_local_target_and_anchor(self):
        ci.check_docs(self.root)
        (self.root / 'guide.md').write_text('# Different\n', encoding='ascii')
        with self.assertRaises(ci.CheckError):
            ci.check_docs(self.root)
        (self.root / 'guide.md').unlink()
        with self.assertRaises(ci.CheckError):
            ci.check_docs(self.root)

    def test_inline_code_heading_keeps_its_anchor_text(self):
        (self.root / 'README.md').write_text('[Guide](guide.md#hello-world)\n', encoding='ascii')
        (self.root / 'guide.md').write_text('# Hello `world`\n', encoding='ascii')
        ci.check_docs(self.root)
        (self.root / 'README.md').write_text('[Guide](guide.md#hello-)\n', encoding='ascii')
        with self.assertRaises(ci.CheckError):
            ci.check_docs(self.root)

    def test_encoding_controls_and_excluded_runtime(self):
        runtime = self.root / '.runtime'
        runtime.mkdir()
        (runtime / 'private.md').write_bytes(b'\xff')
        ci.check_docs(self.root)
        for data in (b'\xef\xbb\xbftext', b'bad\x00text', b'bad\x7ftext'):
            with self.subTest(data=data):
                (self.root / 'guide.md').write_bytes(data)
                with self.assertRaises(ci.CheckError):
                    ci.check_docs(self.root)


if __name__ == '__main__':
    unittest.main()
