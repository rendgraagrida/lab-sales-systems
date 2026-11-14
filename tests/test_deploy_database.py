"""Real PostgreSQL privilege-boundary regression, only on the disposable hosted CI service."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest
import uuid
import zipfile

HOSTED_ROOT = (sys.platform == 'linux' and hasattr(os, 'geteuid') and os.geteuid() == 0
               and os.environ.get('GITHUB_ACTIONS') == 'true'
               and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted')
if sys.platform == 'linux':
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
    try:
        import ci_database
        import deploy_linux
        import release
    finally:
        sys.path.pop(0)


@unittest.skipUnless(HOSTED_ROOT, 'Requires root on the disposable GitHub-hosted Linux runner')
class DatabaseBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.psql = str(ci_database.postgres_bin() / 'psql')
        self.suffix = uuid.uuid4().hex[:16]
        self.db = 'boundary_' + self.suffix
        self.owner = 'boundary_owner_' + self.suffix
        self.migrator = 'boundary_migrator_' + self.suffix
        self.password = secrets.token_urlsafe(32)
        self.created_db = False
        self.created_owner = False
        self.created_migrator = False
        self.addCleanup(self.cleanup_database)
        self.assertEqual('170011', self.admin('SHOW server_version_num'))
        self.assertEqual('0', self.admin("SELECT count(*) FROM pg_catalog.pg_database WHERE datname NOT IN ('postgres','template0','template1')"))
        self.admin(f'CREATE ROLE "{self.owner}" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS')
        self.created_owner = True
        self.admin(f'CREATE ROLE "{self.migrator}" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD \'{self.password}\'')
        self.created_migrator = True
        self.admin(f'GRANT "{self.owner}" TO "{self.migrator}"')
        self.admin(f'CREATE DATABASE "{self.db}" TEMPLATE template0')
        self.created_db = True
        self.temp = tempfile.TemporaryDirectory(dir='/')
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        env = root / 'migration.env'
        env.write_text(f'LAB_MIGRATION_DB_URL=jdbc:postgresql://127.0.0.1:5432/{self.db}\n'
                       f'LAB_MIGRATION_DB_USER={self.migrator}\nLAB_MIGRATION_DB_PASSWORD={self.password}\n'
                       f'LAB_MIGRATION_OWNER={self.owner}\n', encoding='utf-8')
        env.chmod(0o600)
        self.cfg = {'pg_bin': str(Path(self.psql).parent), 'pg_port': 5432, 'database': self.db,
                    'owner_role': self.owner, 'migrator_role': self.migrator, 'migration_env_file': str(env)}
        app = root / 'app'
        app.mkdir()
        self.jar = app / 'application.jar'
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr(release.POM, (Path(__file__).resolve().parents[1] / 'pom.xml').read_bytes())
            for name in release.SQL_NAMES:
                archive.writestr(release.SQL_PREFIX + name, 'SELECT 1;')
        self.release_dir = root
        rows = []
        for row in release.schema_identity(self.jar).split(','):
            version, script, checksum = row.split('|')
            rows.append(f"({version},'{version}','{script}',{checksum},true,'SQL')")
        self.admin(f"""
            CREATE SCHEMA app AUTHORIZATION "{self.owner}";
            GRANT USAGE ON SCHEMA app TO "{self.migrator}";
            SET ROLE "{self.owner}";
            CREATE TABLE app.history_data(installed_rank integer, version text, script text, checksum integer, success boolean, type text);
            INSERT INTO app.history_data VALUES {','.join(rows)};
            CREATE TABLE app.receipt_data(receipt_key text, id integer, user_id integer, workspace_id integer, payload_hash text);
            INSERT INTO app.receipt_data VALUES ('INITIAL_ADMIN',1,2,3,'fixture');
            CREATE TABLE app.probe_log(current_name text, session_name text, auth_denied boolean, role_denied boolean, copy_denied boolean, other_create boolean);
            GRANT SELECT ON app.history_data, app.receipt_data TO "{self.migrator}";
            GRANT INSERT ON app.probe_log TO "{self.migrator}";
            CREATE FUNCTION app.probe() RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER AS $$
            DECLARE a boolean := false; r boolean := false; c boolean := false;
            BEGIN
              BEGIN EXECUTE 'SET SESSION AUTHORIZATION postgres'; EXCEPTION WHEN insufficient_privilege THEN a := true; END;
              BEGIN EXECUTE 'SET ROLE postgres'; EXCEPTION WHEN insufficient_privilege THEN r := true; END;
              BEGIN EXECUTE 'COPY (SELECT 1) TO PROGRAM ''true'''; EXCEPTION WHEN insufficient_privilege THEN c := true; END;
              INSERT INTO app.probe_log VALUES (current_user, session_user, a, r, c,
                  pg_catalog.has_database_privilege(current_user, 'postgres', 'CREATE'));
              RETURN true;
            END $$;
            CREATE VIEW app.flyway_schema_history WITH (security_invoker=true) AS
              SELECT * FROM app.history_data WHERE app.probe();
            CREATE VIEW app.setup_receipts WITH (security_invoker=true) AS
              SELECT * FROM app.receipt_data WHERE app.probe();
            GRANT SELECT ON app.flyway_schema_history, app.setup_receipts TO "{self.migrator}";
        """, self.db)

    def admin(self, sql, db='postgres'):
        result = subprocess.run([self.psql, '-X', '-w', '-h', '127.0.0.1', '-p', '5432', '-U', 'postgres',
                                 '-d', db, '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                                input=sql, env={'PATH': '/usr/bin:/bin', 'LANG': 'C'},
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise AssertionError('Fixture PostgreSQL command failed')
        return result.stdout.strip()

    def cleanup_database(self):
        if self.created_db:
            self.admin(f'DROP DATABASE "{self.db}" WITH (FORCE)')
        if self.created_migrator:
            self.admin(f'DROP ROLE "{self.migrator}"')
        if self.created_owner:
            self.admin(f'DROP ROLE "{self.owner}"')

    def test_untrusted_history_and_receipt_execute_as_direct_migrator(self):
        self.assertEqual(f'{self.owner}|{self.migrator}',
                         deploy_linux.run_migrator_psql(self.cfg, "SELECT current_user || '|' || session_user"))
        database_identity = "SELECT datname || '|' || datdba::text || '|' || COALESCE(datacl::text,'') FROM pg_catalog.pg_database WHERE datname='postgres'"
        before = self.admin(database_identity)
        deploy_linux.require_schema_compatible(self.cfg, self.release_dir)
        self.assertEqual({'id': 1, 'user_id': 2, 'workspace_id': 3, 'payload_hash': 'fixture'},
                         deploy_linux.initial_admin_receipt(self.cfg))
        self.assertEqual('0', self.admin(f"SELECT count(*) FROM app.probe_log WHERE current_name <> '{self.owner}' OR session_name <> '{self.migrator}' OR NOT auth_denied OR NOT role_denied OR NOT copy_denied OR other_create", self.db))
        self.assertGreater(int(self.admin('SELECT count(*) FROM app.probe_log', self.db)), 0)
        other = subprocess.run([self.psql, '-X', '-w', '-h', '127.0.0.1', '-p', '5432',
                                '-U', self.migrator, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1',
                                '-c', f'BEGIN; CREATE SCHEMA \"boundary_{self.suffix}\"; ROLLBACK;'],
                               env={'PATH': '/usr/bin:/bin', 'LANG': 'C', 'PGPASSWORD': self.password},
                               capture_output=True, text=True, timeout=30)
        self.assertNotEqual(0, other.returncode, 'Migrator must not create objects in another database')
        self.assertEqual(before, self.admin(database_identity))
        self.assertEqual('0', self.admin(f"SELECT count(*) FROM pg_catalog.pg_database WHERE datname NOT IN ('postgres','template0','template1','{self.db}')"))
