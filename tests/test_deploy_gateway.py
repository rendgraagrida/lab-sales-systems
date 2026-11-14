import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import ci
import release
if sys.platform.startswith('linux'):
    import deploy_gateway as gateway
sys.path.pop(0)


@unittest.skipUnless(sys.platform.startswith('linux'), 'Linux gateway')
class FramingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        jar = self.root / 'source.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr(release.POM, (SCRIPTS.parent / 'pom.xml').read_bytes())
            for name in release.SQL_NAMES:
                archive.writestr(release.SQL_PREFIX + name, 'SELECT 1;')
        app = self.root / 'source'
        self.revision = 'a' * 40
        ci.package(jar, app, self.revision, 'local')
        self.bundle = self.root / 'bundle'
        self.manifest = release.package_release(app, self.bundle, self.revision, 'local')
        raw = (self.bundle / 'release.json').read_bytes()
        self.payload = struct.pack('>Q', len(raw)) + raw + b''.join(
            (self.bundle / name).read_bytes() for name in sorted(self.manifest['files']))
        self.policy = {'environment': 'staging', 'promotion_root': str(self.root)}
        self.request = {'schema': 2, 'policy': '/etc/policy', 'action': 'deploy',
                        'environment': 'staging', 'revision': self.revision, 'run_id': 'local',
                        'manifest_sha256': hashlib.sha256(raw).hexdigest(),
                        'controller_sha256': {name: '0' * 64 for name in gateway.CONTROLLERS}}

    def test_receive_verified_and_cleanup(self):
        with gateway.receive(self.request, self.policy, io.BytesIO(self.payload), release) as path:
            self.assertEqual(self.revision, release.verify_release(path, self.revision, 'local',
                                                                    self.request['manifest_sha256'])['revision'])
        self.assertFalse(Path(path).exists())
        self.assertTrue(self.bundle.exists())

    def test_short_extra_and_tampered_streams_are_rejected_and_removed(self):
        tampered = bytearray(self.payload)
        tampered[-1] ^= 1
        for payload in (self.payload[:3], self.payload[:-1], self.payload + b'x', bytes(tampered),
                        struct.pack('>Q', gateway.MAX_MANIFEST + 1)):
            with self.subTest(length=len(payload)), self.assertRaises(gateway.GatewayError):
                with gateway.receive(self.request, self.policy, io.BytesIO(payload), release):
                    self.fail('malformed stream accepted')
            self.assertEqual([], list(self.root.glob('crm-release-*')))

    def test_request_identity_and_duplicate_keys(self):
        gateway.validate_request(self.request, self.policy, '/etc/policy')
        for change in ({'schema': True}, {'policy': '/other'}, {'environment': 'production'},
                       {'action': 'bootstrap'}, {'controller_sha256': {}}, {'action': 'status'}):
            with self.subTest(change=change), self.assertRaises(gateway.GatewayError):
                gateway.validate_request(dict(self.request, **change), self.policy, '/etc/policy')
        with self.assertRaises(gateway.GatewayError):
            gateway.parse_json('{"schema":2,"schema":2}')

    def test_manifest_limits_and_lineage_rejected_before_receipt(self):
        for change in ('oversize', 'lineage'):
            manifest = json.loads(json.dumps(self.manifest))
            if change == 'oversize':
                manifest['files']['app/application.jar']['size'] = gateway.MAX_FILE + 1
            else:
                manifest['schema_versions'] = ['1', '2', '3']
            raw = json.dumps(manifest).encode()
            request = dict(self.request, manifest_sha256=hashlib.sha256(raw).hexdigest())
            payload = struct.pack('>Q', len(raw)) + raw
            with self.subTest(change=change), self.assertRaises(gateway.GatewayError):
                with gateway.receive(request, self.policy, io.BytesIO(payload), release):
                    self.fail('invalid manifest accepted')
            self.assertEqual([], list(self.root.glob('crm-release-*')))


    def test_noncanonical_cli_rejected(self):
        for args in (['--stdio', '--policy', '/etc/policy'],
                     ['--policy', '/etc/policy', '--stdio', 'extra'],
                     ['--policy', '/etc/policy', '--request', '{}']):
            with self.subTest(args=args), self.assertRaises(SystemExit):
                gateway.main(args)

    def test_lock_rejects_concurrent_holder(self):
        with gateway.promotion_lock(self.policy):
            with self.assertRaises(gateway.GatewayError):
                with gateway.promotion_lock(self.policy):
                    self.fail('concurrent lock accepted')


if __name__ == '__main__':
    unittest.main()
