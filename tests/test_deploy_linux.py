import copy
import json
import os
from contextlib import nullcontext
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
from types import SimpleNamespace

if sys.platform == 'linux':
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
    try:
        import deploy_linux
    finally:
        sys.path.pop(0)


@unittest.skipUnless(sys.platform == 'linux', 'Native Linux deployment adapter')
class RollbackHistoryTests(unittest.TestCase):
    def release(self, revision, jar, manifest, run):
        return {'revision': revision * 40, 'jar_sha256': jar * 64,
                'manifest_sha256': manifest * 64, 'run_id': str(run), 'main_pid': 100 + run}

    def test_repeated_current_release_does_not_hide_previous_release(self):
        first = self.release('a', '1', '2', 1)
        current = self.release('b', '3', '4', 2)
        state = {'accepted_release': current, 'history': [first, current, dict(current)]}
        original = copy.deepcopy(state)
        self.assertEqual(first, deploy_linux.previous_distinct_release(state))
        self.assertEqual(original, state)

    def test_new_run_manifest_for_same_application_is_not_a_rollback(self):
        first = self.release('a', '1', '2', 1)
        prior_run = self.release('b', '3', '4', 2)
        current = self.release('b', '3', '5', 3)
        state = {'accepted_release': current, 'history': [first, prior_run, current]}
        self.assertEqual(first, deploy_linux.previous_distinct_release(state))

    def test_most_recent_distinct_application_wins_after_prior_rollback(self):
        first = self.release('a', '1', '2', 1)
        other = self.release('b', '3', '4', 2)
        current = dict(first)
        state = {'accepted_release': current, 'history': [first, other, current, dict(current)]}
        self.assertEqual(other, deploy_linux.previous_distinct_release(state))

    def test_changed_jar_at_same_revision_remains_a_distinct_rollback(self):
        previous = self.release('a', '1', '2', 1)
        current = self.release('a', '3', '4', 2)
        state = {'accepted_release': current, 'history': [previous, current]}
        self.assertEqual(previous, deploy_linux.previous_distinct_release(state))

    def test_missing_distinct_history_fails_instead_of_redeploying_current(self):
        current = self.release('a', '1', '2', 1)
        for state in ({}, {'accepted_release': current, 'history': []},
                      {'accepted_release': current, 'history': [current, dict(current)]}):
            with self.subTest(state=state), self.assertRaises(deploy_linux.DeployError):
                deploy_linux.previous_distinct_release(state)


@unittest.skipUnless(sys.platform == 'linux', 'Native Linux deployment adapter')
class SchemaCompatibilityTests(unittest.TestCase):
    def test_exact_lineage_and_checksum_required(self):
        cfg = {'database': 'lab'}
        expected = '1|V1__identity.sql|10,2|V2__masters.sql|20,3|V3__finance.sql|30,4|V4__procurement_inventory_ap.sql|40,5|V5__local_crm_goods_o2c.sql|50,6|V6__assets_service_contracts.sql|60,7|V7__draft_service_invoice_source_guard.sql|70'
        for actual, bad, accepted in ((expected, '0', True), (expected.rsplit(',', 1)[0], '0', False),
                                      (expected.replace('|50', '|51'), '0', False), (expected, '1', False)):
            with self.subTest(actual=actual, bad=bad), \
                    mock.patch.object(deploy_linux.release, 'schema_identity', return_value=expected), \
                    mock.patch.object(deploy_linux, 'run_migrator_psql', side_effect=[actual, bad]):
                if accepted:
                    deploy_linux.require_schema_compatible(cfg, '/release')
                else:
                    with self.assertRaises(deploy_linux.DeployError):
                        deploy_linux.require_schema_compatible(cfg, '/release')

    def test_failed_rollout_does_not_restart_schema4_release_on_schema5(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            unit = root / 'unit'
            unit.write_text('old unit', encoding='ascii')
            previous = {'manifest_sha256': 'a' * 64, 'revision': 'b' * 40, 'run_id': '1'}
            cfg = {'target_id': 'target', 'database': 'lab'}
            old_hash = deploy_linux.compute_file_sha256(unit)
            journal = {'target_id': 'target', 'config_digest': deploy_linux.digest_object(cfg),
                       'candidate_unit_sha256': 'c' * 64,
                       'previous_state': {'accepted_release': previous, 'accepted_unit_sha256': old_hash}}
            paths = {'unit_file': unit, 'releases': root, 'unit_name': 'lab.service',
                     'pending_journal': root / 'pending'}
            with mock.patch.object(deploy_linux, 'verify_unit_ownership'), \
                    mock.patch.object(deploy_linux.release, 'verify_release'), \
                    mock.patch.object(deploy_linux.release, 'schema_identity', return_value='1|v1|1,2|v2|2,3|v3|3,4|v4|4'), \
                    mock.patch.object(deploy_linux, 'run_migrator_psql', side_effect=['1|v1|1,2|v2|2,3|v3|3,4|v4|4,5|v5|5', '0']), \
                    mock.patch.object(deploy_linux, 'systemctl') as systemctl:
                with self.assertRaises(deploy_linux.DeployError):
                    deploy_linux.rollback_on_failed_deploy(cfg, paths, journal)
                systemctl.assert_not_called()
            self.assertEqual('old unit', unit.read_text(encoding='ascii'))

    def test_failed_candidate_is_stopped_before_incompatible_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            unit = root / 'unit'
            unit.write_text('candidate unit', encoding='ascii')
            previous = {'manifest_sha256': 'a' * 64, 'revision': 'b' * 40, 'run_id': '1'}
            cfg = {'target_id': 'target', 'database': 'lab'}
            journal = {'target_id': 'target', 'config_digest': deploy_linux.digest_object(cfg),
                       'candidate_unit_sha256': deploy_linux.compute_file_sha256(unit),
                       'previous_state': {'accepted_release': previous, 'accepted_unit_sha256': 'd' * 64}}
            paths = {'unit_file': unit, 'releases': root, 'unit_name': 'lab.service',
                     'pending_journal': root / 'pending'}
            with mock.patch.object(deploy_linux, 'verify_unit_ownership'), \
                    mock.patch.object(deploy_linux.release, 'verify_release'), \
                    mock.patch.object(deploy_linux, 'get_systemd_main_pid', side_effect=[(42, 'active'), (0, 'inactive')]), \
                    mock.patch.object(deploy_linux, 'require_schema_compatible', side_effect=deploy_linux.DeployError('incompatible')), \
                    mock.patch.object(deploy_linux, 'systemctl') as systemctl:
                with self.assertRaises(deploy_linux.DeployError):
                    deploy_linux.rollback_on_failed_deploy(cfg, paths, journal)
                systemctl.assert_called_once_with('stop', 'lab.service')
            self.assertEqual('candidate unit', unit.read_text(encoding='ascii'))

    def test_old_release_refused_against_schema6(self):
        cfg = {'database': 'lab'}
        old = '1|v1|1,2|v2|2,3|v3|3,4|v4|4,5|v5|5'
        with mock.patch.object(deploy_linux.release, 'schema_identity', return_value=old), \
                mock.patch.object(deploy_linux, 'run_migrator_psql', side_effect=[old + ',6|v6|6', '0']):
            with self.assertRaises(deploy_linux.DeployError):
                deploy_linux.require_schema_compatible(cfg, '/release')

    def test_v4_upgrade_requires_inactive_service_then_validates_before_migrating(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {'pending_journal': root / 'pending', 'runtime_state': root / 'state',
                     'unit_name': 'lab.service', 'releases': root, 'state': root}
            cfg = {'database': 'lab', 'target_id': 'target', 'environment': 'staging',
                   'system_identifier': '123'}
            history = ['1,2,3,4', '0']
            for service, permitted in (((0, 'inactive'), True), ((8, 'active'), False),
                                       ((0, 'activating'), False)):
                with self.subTest(service=service), \
                        mock.patch.object(deploy_linux, 'get_target_paths', return_value=paths), \
                        mock.patch.object(deploy_linux, 'verify_target_ownership'), \
                        mock.patch.object(deploy_linux, 'target_lock', return_value=nullcontext()), \
                        mock.patch.object(deploy_linux, 'validate_migration_user'), \
                        mock.patch.object(deploy_linux, 'verify_db_cluster_and_ownership'), \
                        mock.patch.object(deploy_linux, 'read_json', return_value={}), \
                        mock.patch.object(deploy_linux, 'verify_unit_ownership'), \
                        mock.patch.object(deploy_linux.release, 'verify_release', return_value={'schema_versions': ['1', '2', '3', '4', '5', '6', '7']}), \
                        mock.patch.object(deploy_linux, 'get_systemd_main_pid', return_value=service), \
                        mock.patch.object(deploy_linux, 'run_psql', side_effect=['t', '0']), \
                        mock.patch.object(deploy_linux, 'run_migrator_psql', side_effect=history), \
                        mock.patch.object(deploy_linux, 'copy_release_immutable'), \
                        mock.patch.object(deploy_linux, 'run_flyway') as flyway, \
                        mock.patch.object(deploy_linux, 'require_schema_compatible'), \
                        mock.patch.object(deploy_linux, 'grant_runtime_privileges'), \
                        mock.patch.object(deploy_linux, 'prove_runtime'), \
                        mock.patch.object(deploy_linux, 'atomic_write'):
                    if permitted:
                        deploy_linux.action_migrate(cfg, '/release', 'a' * 40, '1', 'b' * 64)
                        self.assertEqual([['-Dflyway.target=4', 'flyway:validate'],
                                          ['flyway:migrate', 'flyway:validate']],
                                         [call.args[2] for call in flyway.call_args_list])
                    else:
                        with self.assertRaises(deploy_linux.DeployError):
                            deploy_linux.action_migrate(cfg, '/release', 'a' * 40, '1', 'b' * 64)
                        flyway.assert_not_called()


@unittest.skipUnless(sys.platform == 'linux', 'Native Linux deployment adapter')
class MigrationIdentityTests(unittest.TestCase):
    def setUp(self):
        if os.geteuid() != 0:
            self.skipTest('Requires root to exercise the real UID/GID transition')
        self.temp = tempfile.TemporaryDirectory(dir='/')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root.chmod(0o755)
        self.home = self.root / 'home'
        self.home.mkdir(mode=0o700)
        os.chown(self.home, 65534, 65534)
        self.migration = SimpleNamespace(pw_uid=65534, pw_gid=65534,
                                         pw_dir=str(self.home), pw_shell='/usr/sbin/nologin')
        self.service = SimpleNamespace(pw_uid=65533, pw_gid=65533,
                                       pw_dir='/nonexistent', pw_shell='/usr/sbin/nologin')
        self.cfg = {'migration_user': 'migration', 'service_user': 'service'}
        self.accounts = mock.patch.object(deploy_linux.pwd, 'getpwnam',
                                          side_effect=lambda name: {'migration': self.migration,
                                                                    'service': self.service}[name])
        self.accounts.start()
        self.addCleanup(self.accounts.stop)

    def test_invalid_identity_and_home_are_rejected(self):
        deploy_linux.validate_migration_user(self.cfg)
        for field, value in (('pw_uid', 0), ('pw_uid', self.service.pw_uid),
                             ('pw_gid', 0), ('pw_gid', self.service.pw_gid),
                             ('pw_shell', '/bin/bash'), ('pw_dir', 'relative')):
            with self.subTest(field=field, value=value):
                original = getattr(self.migration, field)
                setattr(self.migration, field, value)
                with self.assertRaises(deploy_linux.DeployError):
                    deploy_linux.validate_migration_user(self.cfg)
                setattr(self.migration, field, original)
        self.home.chmod(0o777)
        with self.assertRaises(deploy_linux.DeployError):
            deploy_linux.validate_migration_user(self.cfg)
        self.home.chmod(0o700)
        os.chown(self.home, 0, 0)
        with self.assertRaises(deploy_linux.DeployError):
            deploy_linux.validate_migration_user(self.cfg)
        with self.assertRaises(deploy_linux.DeployError):
            deploy_linux.validate_migration_user({'service_user': 'service'})
        os.chown(self.home, 65534, 65534)
        self.root.chmod(0o777)
        with self.assertRaises(deploy_linux.DeployError):
            deploy_linux.validate_migration_user(self.cfg)
        self.root.chmod(0o755)
        link = self.root / 'home-link'
        link.symlink_to(self.home, target_is_directory=True)
        self.migration.pw_dir = str(link)
        with self.assertRaises(deploy_linux.DeployError):
            deploy_linux.validate_migration_user(self.cfg)

    def test_maven_process_is_isolated_from_root_and_service_secrets(self):
        java_home = self.root / 'java'
        maven_home = self.root / 'maven'
        for directory in (java_home / 'bin', maven_home / 'bin'):
            directory.mkdir(parents=True)
        java = java_home / 'bin' / 'java'
        java.write_text('#!/bin/sh\nexit 0\n', encoding='ascii')
        java.chmod(0o755)
        secret = self.root / 'runtime-secret'
        secret.write_text('private', encoding='ascii')
        os.chown(secret, self.service.pw_uid, self.service.pw_gid)
        secret.chmod(0o600)
        root_secret = self.root / 'root-secret'
        root_secret.write_text('private', encoding='ascii')
        root_secret.chmod(0o600)
        mvn = maven_home / 'bin' / 'mvn'
        mvn.write_text(
            '#!/usr/bin/python3\n'
            'import json, os\n'
            f'secrets = {[str(secret), str(root_secret)]!r}\n'
            'readable = []\n'
            'for secret in secrets:\n'
            '    try:\n'
            '        with open(secret) as f:\n'
            '            f.read()\n'
            '        readable.append(True)\n'
            '    except PermissionError:\n'
            '        readable.append(False)\n'
            'with open(os.path.join(os.environ["HOME"], "result.json"), "w") as f:\n'
            '    json.dump({"uid": os.getuid(), "gid": os.getgid(), "groups": os.getgroups(), '
            '"cwd": os.getcwd(), "home": os.environ["HOME"], "readable": readable, '
            '"env": sorted(os.environ)}, f)\n', encoding='ascii')
        mvn.chmod(0o755)
        pom = self.root / 'pom.xml'
        pom.write_text('<project/>', encoding='ascii')
        self.cfg.update(java_home=str(java_home), maven_home=str(maven_home),
                        migration_env_file='/unused')
        values = {'LAB_MIGRATION_DB_URL': 'url', 'LAB_MIGRATION_DB_USER': 'user',
                  'LAB_MIGRATION_DB_PASSWORD': 'password', 'LAB_MIGRATION_OWNER': 'owner'}
        with mock.patch.object(deploy_linux, 'parse_env_file', return_value=values):
            deploy_linux.run_flyway(self.cfg, pom, ['flyway:validate'])
        result = json.loads((self.home / 'result.json').read_text(encoding='ascii'))
        self.assertEqual((65534, 65534, []), (result['uid'], result['gid'], result['groups']))
        self.assertEqual(str(self.home), result['home'])
        self.assertEqual(str(self.home), result['cwd'])
        self.assertEqual([False, False], result['readable'])
        self.assertNotIn('LAB_DB_PASSWORD', result['env'])


if __name__ == '__main__':
    unittest.main()
