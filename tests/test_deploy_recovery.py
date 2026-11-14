"""Native PostgreSQL 17.11 failed-readiness recovery and two-target isolation regression.

Verifies fail-closed rollback, journal durability, and cross-target isolation
under genuine systemd cgroup confinement, native PostgreSQL 17.11 peer/SCRAM auth,
Flyway schema 7 migration, and the streaming deployment gateway contract.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import ci
import release

if sys.platform.startswith('linux'):
    import database
    import deploy_gateway as gateway
    import deploy_linux
    import deploy_sandbox
    import pwd

OPT_IN = os.environ.get('LAB_NATIVE_RECOVERY') == '1'


def allocate_free_port():
    """Bind to an ephemeral port to discover an unused port on loopback."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def run_psql_stdin(pg_port, sql_text, db_name='postgres', timeout=30):
    """Execute SQL via psql using STDIN to prevent credentials appearing in argv."""
    cmd = [
        'runuser', '-u', 'postgres', '--',
        '/usr/lib/postgresql/17/bin/psql',
        '-X', '-w',
        '-h', '/var/run/postgresql',
        '-p', str(pg_port),
        '-d', db_name,
        '-t', '-A',
        '-v', 'ON_ERROR_STOP=1',
        '-f', '-'
    ]
    res = subprocess.run(
        cmd, input=sql_text, capture_output=True, text=True, timeout=timeout,
        env={'PATH': '/usr/bin:/bin', 'LANG': 'C'}
    )
    if res.returncode != 0:
        raise AssertionError(f"psql query failed on database {db_name} (code {res.returncode})")
    return res.stdout.strip()


def frame_deploy_request(policy_path, action, environment,
                         revision, run_id, manifest_sha, release_dir):
    """Build the framed byte stream expected by deploy_entry / deploy_gateway."""
    controller_root = Path('/opt/lab-sales-systems/controller')
    controller_hashes = {}
    for c_name in ('ci.py', 'release.py', 'database.py', 'deploy_linux.py',
                   'deploy_gateway.py', 'deploy_entry.py', 'deploy_sandbox.py'):
        with open(controller_root / c_name, 'rb') as f:
            controller_hashes[c_name] = hashlib.file_digest(f, 'sha256').hexdigest()

    req = {
        'schema': 2,
        'policy': str(policy_path),
        'action': action,
        'environment': environment,
        'revision': revision,
        'run_id': run_id,
        'manifest_sha256': manifest_sha,
        'controller_sha256': controller_hashes
    }
    header = json.dumps(req, separators=(',', ':'), ensure_ascii=True).encode('utf-8') + b'\n'
    raw_manifest = (Path(release_dir) / 'release.json').read_bytes()
    manifest_obj = json.loads(raw_manifest)

    payload = bytearray()
    payload.extend(header)
    payload.extend(struct.pack('>Q', len(raw_manifest)))
    payload.extend(raw_manifest)
    for file_name in sorted(manifest_obj['files']):
        payload.extend((Path(release_dir) / file_name).read_bytes())
    return bytes(payload)


def app_data_digest(cfg):
    """Snapshot every application table, not only the bootstrap sentinel."""
    names = run_psql_stdin(
        cfg['pg_port'],
        "SELECT c.relname FROM pg_catalog.pg_class c "
        "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
        "WHERE n.nspname='app' AND c.relkind IN ('r','p') ORDER BY c.relname;",
        cfg['database']).splitlines()
    if not names:
        raise AssertionError('Application table inventory is empty')
    rows = []
    for name in names:
        table = database.identifier(name)
        rows.append(
            f"SELECT '{name}|' || count(*)::text || '|' || "
            "md5(COALESCE(string_agg(row_to_json(t)::text, E'\\n' "
            f"ORDER BY row_to_json(t)::text), '')) FROM app.{table} t")
    value = run_psql_stdin(cfg['pg_port'], ' UNION ALL '.join(rows) + ' ORDER BY 1;', cfg['database'])
    return len(names), hashlib.sha256(value.encode()).hexdigest()


def stop_request(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


@unittest.skipUnless(OPT_IN, 'Native recovery test requires opt-in via LAB_NATIVE_RECOVERY=1')
class DeployRecoveryRegressionTests(unittest.TestCase):
    """Permanent regression exercising actual failed-readiness rollback and two-target isolation."""

    def test_two_target_failed_readiness_recovery_and_isolation(self):
        # 1. Assert host and toolchain prerequisites without skipping
        self.assertTrue(sys.platform.startswith('linux'), "Native recovery requires Linux")
        self.assertTrue(hasattr(os, 'geteuid') and os.geteuid() == 0, "Native recovery requires root (UID 0)")
        self.assertEqual('true', os.environ.get('GITHUB_ACTIONS'), "Requires GitHub Actions environment")
        self.assertEqual('github-hosted', os.environ.get('RUNNER_ENVIRONMENT'), "Requires GitHub-hosted runner")

        for bin_name in ('postgres', 'initdb', 'pg_ctl', 'psql'):
            bin_path = Path('/usr/lib/postgresql/17/bin') / bin_name
            self.assertTrue(bin_path.is_file() and os.access(bin_path, os.X_OK),
                            f"Missing required PostgreSQL binary: {bin_path}")

        psql_ver = subprocess.run(
            ['/usr/lib/postgresql/17/bin/psql', '--version'],
            capture_output=True, text=True, check=True
        ).stdout
        self.assertIn('17.11', psql_ver, f"Expected PostgreSQL 17.11, found: {psql_ver.strip()}")

        java_bin = Path('/opt/lab-sales-recovery-runtime/jdk/bin/java')
        mvn_bin = Path('/opt/lab-sales-recovery-runtime/maven/bin/mvn')
        self.assertTrue(java_bin.is_file() and os.access(java_bin, os.X_OK), "Missing root runtime JDK")
        self.assertTrue(mvn_bin.is_file() and os.access(mvn_bin, os.X_OK), "Missing root runtime Maven")

        java_ver = subprocess.run([str(java_bin), '-version'], capture_output=True, text=True, check=True).stderr
        self.assertIn('21.0.13', java_ver, f"Expected JDK 21.0.13, found: {java_ver.strip()}")

        mvn_env = {
            'JAVA_HOME': '/opt/lab-sales-recovery-runtime/jdk',
            'PATH': '/opt/lab-sales-recovery-runtime/jdk/bin:/usr/bin:/bin',
            'HOME': '/root',
            'LANG': 'C'
        }
        mvn_ver = subprocess.run([str(mvn_bin), '--version'], capture_output=True, text=True, check=True, env=mvn_env).stdout
        self.assertIn('3.9.16', mvn_ver, f"Expected Maven 3.9.16, found: {mvn_ver.strip()}")

        machine_id_file = Path('/etc/machine-id')
        self.assertTrue(machine_id_file.is_file(), "Missing /etc/machine-id")
        machine_id = machine_id_file.read_text(encoding='utf-8').strip()
        self.assertTrue(len(machine_id) > 0, "Empty /etc/machine-id")

        root_dir = Path(__file__).resolve().parents[1]
        revision = os.environ.get('REVISION')
        run_id = os.environ.get('RUN_ID')
        self.assertIsInstance(revision, str, 'Exact CI revision is required')
        self.assertIsInstance(run_id, str, 'Exact CI run ID is required')
        ci.candidate(revision, run_id)
        source_release = root_dir / '.releases' / revision
        manifest_sha = hashlib.sha256((source_release / 'release.json').read_bytes()).hexdigest()
        rel_meta = release.verify_release(str(source_release), revision, run_id, manifest_sha)
        self.assertEqual(list(database.SCHEMA_VERSIONS), rel_meta['schema_versions'])
        # ProtectHome and private /tmp hide the checkout and host temp paths.
        inputs = Path(tempfile.mkdtemp(prefix='lab-sales-recovery-input-', dir='/opt'))
        inputs.chmod(0o755)
        self.addCleanup(shutil.rmtree, inputs)
        release_dir = inputs / 'accepted'
        shutil.copytree(source_release, release_dir)
        for path in [release_dir, *release_dir.rglob('*')]:
            path.chmod(0o755 if path.is_dir() else 0o644)

        real_jar = release_dir / 'app' / 'application.jar'
        self.assertTrue(real_jar.is_file(), "Missing release application.jar")

        print(f"[RECOVERY_TEST] Checkpoint 1: Verified toolchain, PG 17.11, JDK 21.0.13, Maven 3.9.16, revision {revision[:12]}")

        # 2. Install and verify trusted controller files at /opt/lab-sales-systems/controller
        controller_root = Path('/opt/lab-sales-systems/controller')
        controller_files = ('ci.py', 'release.py', 'database.py', 'deploy_linux.py',
                            'deploy_gateway.py', 'deploy_entry.py', 'deploy_sandbox.py')
        gateway.trusted_directory('/opt')
        self.assertFalse(os.path.lexists(controller_root), 'Refusing an existing installed controller')
        controller_parent_existed = controller_root.parent.exists()
        if controller_parent_existed:
            gateway.trusted_directory(str(controller_root.parent))
        controller_root.mkdir(parents=True, mode=0o755)

        def cleanup_controller():
            shutil.rmtree(controller_root)
            if not controller_parent_existed:
                controller_root.parent.rmdir()

        self.addCleanup(cleanup_controller)
        for c_name in controller_files:
            dst_file = controller_root / c_name
            dst_file.write_bytes((root_dir / 'scripts' / c_name).read_bytes())
            dst_file.chmod(0o755)

        # 3. Create a new owned native PostgreSQL 17.11 cluster on a unique port
        pg_port = allocate_free_port()
        self.assertNotEqual(5432, pg_port, "Must not bind to default CI PostgreSQL port 5432")
        allocated_ports = {5432, pg_port}

        pg_cluster_dir = Path(f'/var/lib/postgresql/lab-rec-cluster-{uuid.uuid4().hex[:12]}')
        pg_cluster_dir.mkdir(mode=0o700)
        postgres_pw = pwd.getpwnam('postgres')
        os.chown(pg_cluster_dir, postgres_pw.pw_uid, postgres_pw.pw_gid)

        def cleanup_cluster():
            if (pg_cluster_dir / 'postmaster.pid').exists():
                result = subprocess.run(
                    ['runuser', '-u', 'postgres', '--',
                     '/usr/lib/postgresql/17/bin/pg_ctl', '-D', str(pg_cluster_dir),
                     '-m', 'fast', '-w', '-t', '30', 'stop'], capture_output=True, timeout=45)
                self.assertEqual(0, result.returncode, 'Owned native cluster did not stop')
            self.assertFalse((pg_cluster_dir / 'postmaster.pid').exists())
            shutil.rmtree(pg_cluster_dir)

        self.addCleanup(cleanup_cluster)
        password_file = pg_cluster_dir.with_name(pg_cluster_dir.name + '.password')
        password_file.write_text(secrets.token_urlsafe(32) + '\n', encoding='utf-8')
        password_file.chmod(0o600)
        os.chown(password_file, postgres_pw.pw_uid, postgres_pw.pw_gid)
        self.addCleanup(password_file.unlink, missing_ok=True)

        initdb_res = subprocess.run(
            ['runuser', '-u', 'postgres', '--',
             '/usr/lib/postgresql/17/bin/initdb',
             '-D', str(pg_cluster_dir),
             '-A', 'scram-sha-256',
             '--auth-local=peer',
             '--pwfile=' + str(password_file),
             '--encoding=UTF8',
             '--locale=C'],
            capture_output=True, text=True
        )
        self.assertEqual(0, initdb_res.returncode, "initdb failed")
        password_file.unlink()

        conf_path = pg_cluster_dir / 'postgresql.conf'
        with open(conf_path, 'a', encoding='utf-8') as f:
            f.write(f"\nport = {pg_port}\nlisten_addresses = '127.0.0.1'\nunix_socket_directories = '/var/run/postgresql'\n")

        hba_path = pg_cluster_dir / 'pg_hba.conf'
        hba_path.write_text(
            "local   all             postgres                                peer\n"
            "host    all             all             127.0.0.1/32            scram-sha-256\n",
            encoding='utf-8'
        )
        os.chmod(hba_path, 0o600)
        os.chown(hba_path, postgres_pw.pw_uid, postgres_pw.pw_gid)

        pg_ctl_start = subprocess.run(
            ['runuser', '-u', 'postgres', '--',
             '/usr/lib/postgresql/17/bin/pg_ctl',
             '-D', str(pg_cluster_dir),
             '-l', str(pg_cluster_dir / 'postgres.log'),
             '-w', 'start'],
            capture_output=True, text=True
        )
        self.assertEqual(0, pg_ctl_start.returncode, "pg_ctl start failed")

        sys_id = run_psql_stdin(pg_port, "SELECT system_identifier::text FROM pg_catalog.pg_control_system();")
        self.assertEqual('170011', run_psql_stdin(pg_port, 'SHOW server_version_num;'))
        self.assertTrue(re.fullmatch(r'^[0-9]{1,30}$', sys_id), f"Invalid system_identifier: {sys_id}")

        print(f"[RECOVERY_TEST] Checkpoint 2: Native PostgreSQL 17.11 cluster started on port {pg_port}")

        # 4. Set up two distinct targets A and B with bounded storage, project slices, and non-login users
        gateways_dir = Path('/etc/lab-sales-systems/gateways')
        if gateways_dir.exists():
            gateway.trusted_directory(str(gateways_dir))
        else:
            gateways_dir.mkdir(parents=True, mode=0o755)
            self.addCleanup(gateways_dir.rmdir)

        targets = {}
        for target_label in ('A', 'B'):
            target_id = f"t{target_label.lower()}{uuid.uuid4().hex[:10]}"
            target_port = allocate_free_port()
            while target_port in allocated_ports:
                target_port = allocate_free_port()
            allocated_ports.add(target_port)
            base_dir = Path(tempfile.mkdtemp(prefix=f'lab-rec-{target_id}-', dir='/srv'))
            base_dir.chmod(0o755)

            storage_dir = base_dir / 'storage'
            storage_dir.mkdir(mode=0o755)
            os.chown(storage_dir, 0, 0)

            img_path = base_dir / 'storage.img'
            mount_unit = subprocess.run(
                ['/usr/bin/systemd-escape', '--path', '--suffix=mount', str(storage_dir)],
                check=True, text=True, capture_output=True).stdout.strip()
            slice_name = f'lab-sales-{target_id}.slice'
            mount_file = Path('/run/systemd/system') / mount_unit
            slice_file = Path('/run/systemd/system') / slice_name
            self.assertFalse(os.path.lexists(mount_file))
            self.assertFalse(os.path.lexists(slice_file))

            def cleanup_mount_and_slice(m_unit=mount_unit, s_unit=slice_name,
                                        b_dir=base_dir, s_dir=storage_dir):
                subprocess.run(['/usr/bin/systemctl', 'stop', s_unit, m_unit],
                               capture_output=True, timeout=30, check=False)
                self.assertFalse(s_dir.is_mount(), 'Owned storage mount did not stop; retained')
                (Path('/run/systemd/system') / s_unit).unlink(missing_ok=True)
                (Path('/run/systemd/system') / m_unit).unlink(missing_ok=True)
                subprocess.run(['/usr/bin/systemctl', 'daemon-reload'], check=True, capture_output=True)
                shutil.rmtree(b_dir)

            self.addCleanup(cleanup_mount_and_slice)
            subprocess.run(['/usr/bin/fallocate', '-l', '2G', str(img_path)], check=True)
            subprocess.run(['/usr/sbin/mkfs.ext4', '-F', '-q', str(img_path)], check=True)
            fs_uuid = subprocess.run(
                ['/usr/sbin/blkid', '-s', 'UUID', '-o', 'value', str(img_path)],
                check=True, text=True, capture_output=True
            ).stdout.strip()


            mount_file.write_text(
                f"[Unit]\nDescription=Target {target_id} storage mount\n"
                f"[Mount]\nWhat={img_path}\nWhere={storage_dir}\nType=ext4\nOptions=loop,rw,nosuid,nodev\n",
                encoding='utf-8'
            )
            slice_file.write_text(
                f"[Unit]\nDescription=Target {target_id} slice\n"
                f"[Slice]\nMemoryMax=2147483648\nMemorySwapMax=0\nCPUQuota=400%\nTasksMax=512\n",
                encoding='utf-8'
            )

            subprocess.run(['/usr/bin/systemctl', 'daemon-reload'], check=True)
            subprocess.run(['/usr/bin/systemctl', 'start', mount_unit, slice_name], check=True)


            journal_conf = Path(f'/etc/systemd/journald@lab-sales-{target_id}.conf')

            self.assertFalse(os.path.lexists(journal_conf))

            def cleanup_journal(t_id=target_id, j_conf=journal_conf):
                subprocess.run(
                    ['/usr/bin/systemctl', 'stop', f'systemd-journald@lab-sales-{t_id}.service',
                     f'systemd-journald@lab-sales-{t_id}.socket'],
                    capture_output=True, timeout=30, check=False)
                pid = subprocess.run(
                    ['/usr/bin/systemctl', 'show', f'systemd-journald@lab-sales-{t_id}.service',
                     '--property=MainPID', '--value'], capture_output=True, text=True, check=True)
                self.assertEqual('0', pid.stdout.strip(), 'Owned journal process did not stop')
                j_conf.unlink(missing_ok=True)

            self.addCleanup(cleanup_journal)
            journal_conf.write_text("[Journal]\nStorage=volatile\n", encoding='utf-8')
            journal_conf.chmod(0o644)

            suffix = target_id[:8]
            svc_user = f"lss_{suffix}"
            mig_user = f"lsm_{suffix}"
            dep_user = f"lsd_{suffix}"

            mig_home = storage_dir / 'home'
            mig_home.mkdir(mode=0o700)

            def cleanup_user(user):
                result = subprocess.run(['/usr/sbin/userdel', user], capture_output=True)
                self.assertEqual(0, result.returncode, 'Owned fixture user did not delete')
                subprocess.run(['/usr/sbin/groupdel', user], capture_output=True, check=False)

            for user in (svc_user, mig_user, dep_user):
                args = ['/usr/sbin/useradd', '--system', '--user-group',
                        '--shell', '/usr/sbin/nologin', '--no-create-home']
                if user == mig_user:
                    args += ['--home-dir', str(mig_home)]
                subprocess.run([*args, user], check=True, capture_output=True)
                self.addCleanup(cleanup_user, user)

            mig_pw = pwd.getpwnam(mig_user)
            os.chown(mig_home, mig_pw.pw_uid, mig_pw.pw_gid)
            mig_home.chmod(0o700)

            promotion_dir = storage_dir / 'promotion'
            promotion_dir.mkdir(mode=0o755)
            os.chown(promotion_dir, 0, 0)

            target_root = storage_dir / 'target'

            db_name = f"lsdb_{suffix}"
            owner_role = f"lso_{suffix}"
            migrator_role = f"lsmig_{suffix}"
            runtime_role = f"lsrt_{suffix}"
            mig_pass = secrets.token_urlsafe(32)
            rt_pass = secrets.token_urlsafe(32)

            sql_roles = (
                f'CREATE ROLE "{owner_role}" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n'
                f'CREATE ROLE "{migrator_role}" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD \'{mig_pass}\';\n'
                f'CREATE ROLE "{runtime_role}" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD \'{rt_pass}\';\n'
                f'GRANT "{owner_role}" TO "{migrator_role}";\n'
                f'CREATE DATABASE "{db_name}" TEMPLATE template0 ENCODING \'UTF8\';\n'
            )

            def cleanup_db(d_name=db_name, o_role=owner_role, m_role=migrator_role, r_role=runtime_role):
                run_psql_stdin(pg_port, f'DROP DATABASE IF EXISTS "{d_name}" WITH (FORCE);', 'postgres')
                run_psql_stdin(pg_port, f'DROP ROLE IF EXISTS "{m_role}", "{r_role}", "{o_role}";', 'postgres')

            self.addCleanup(cleanup_db)
            run_psql_stdin(pg_port, sql_roles, 'postgres')

            sql_provision = (
                f'REVOKE ALL ON DATABASE "{db_name}" FROM PUBLIC;\n'
                f'GRANT CONNECT ON DATABASE "{db_name}" TO "{migrator_role}","{runtime_role}";\n'
                f'REVOKE ALL ON SCHEMA public FROM PUBLIC;\n'
                f'CREATE SCHEMA app AUTHORIZATION "{owner_role}";\n'
                f'CREATE SCHEMA extensions;\n'
                f'CREATE EXTENSION btree_gist WITH SCHEMA extensions;\n'
                f'GRANT USAGE ON SCHEMA app TO "{migrator_role}","{runtime_role}";\n'
                f'GRANT USAGE ON SCHEMA extensions TO "{owner_role}","{migrator_role}","{runtime_role}";\n'
                f'ALTER DEFAULT PRIVILEGES FOR ROLE "{owner_role}" IN SCHEMA app REVOKE ALL ON TABLES FROM PUBLIC;\n'
                f'ALTER DEFAULT PRIVILEGES FOR ROLE "{owner_role}" IN SCHEMA app REVOKE ALL ON SEQUENCES FROM PUBLIC;\n'
                f'ALTER ROLE "{runtime_role}" IN DATABASE "{db_name}" SET search_path=app,pg_catalog;\n'
                f'ALTER ROLE "{migrator_role}" IN DATABASE "{db_name}" SET search_path=app,extensions,pg_catalog;\n'
                f'ALTER DEFAULT PRIVILEGES FOR ROLE "{owner_role}" REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;\n'
            )
            run_psql_stdin(pg_port, sql_provision, db_name)

            env_dir = storage_dir / 'env'
            env_dir.mkdir(mode=0o700)
            os.chown(env_dir, 0, 0)

            rt_env_file = env_dir / 'runtime.env'
            rt_env_file.write_text(
                f"LAB_DB_URL=jdbc:postgresql://127.0.0.1:{pg_port}/{db_name}\n"
                f"LAB_DB_USER={runtime_role}\n"
                f"LAB_DB_PASSWORD={rt_pass}\n",
                encoding='utf-8'
            )
            rt_env_file.chmod(0o600)
            os.chown(rt_env_file, 0, 0)

            mig_env_file = env_dir / 'migration.env'
            mig_env_file.write_text(
                f"LAB_MIGRATION_DB_URL=jdbc:postgresql://127.0.0.1:{pg_port}/{db_name}\n"
                f"LAB_MIGRATION_DB_USER={migrator_role}\n"
                f"LAB_MIGRATION_DB_PASSWORD={mig_pass}\n"
                f"LAB_MIGRATION_OWNER={owner_role}\n",
                encoding='utf-8'
            )
            mig_env_file.chmod(0o600)
            os.chown(mig_env_file, 0, 0)

            cfg_dir = storage_dir / 'cfg'
            cfg_dir.mkdir(mode=0o700)
            os.chown(cfg_dir, 0, 0)
            cfg_file = cfg_dir / 'target.json'

            sandbox_spec = {
                'storage_root': str(storage_dir),
                'filesystem_uuid': fs_uuid,
                'slice': slice_name,
                'memory_max_bytes': 1073741824,
                'cpu_quota_percent': 200,
                'tasks_max': 256,
                'temporary_bytes': 67108864,
                'project_memory_max_bytes': 2147483648,
                'project_cpu_quota_percent': 400,
                'project_tasks_max': 512
            }

            target_cfg = {
                'schema': 1,
                'project': 'lab-sales-systems',
                'environment': 'staging',
                'target_id': target_id,
                'root': str(target_root),
                'service_user': svc_user,
                'migration_user': mig_user,
                'port': target_port,
                'java_home': '/opt/lab-sales-recovery-runtime/jdk',
                'maven_home': '/opt/lab-sales-recovery-runtime/maven',
                'pg_bin': '/usr/lib/postgresql/17/bin',
                'pg_port': pg_port,
                'database': db_name,
                'owner_role': owner_role,
                'migrator_role': migrator_role,
                'runtime_role': runtime_role,
                'system_identifier': sys_id,
                'machine_id': machine_id,
                'runtime_env_file': str(rt_env_file),
                'migration_env_file': str(mig_env_file),
                'sandbox': sandbox_spec
            }
            cfg_file.write_text(json.dumps(target_cfg, indent=2), encoding='utf-8')
            cfg_file.chmod(0o600)
            os.chown(cfg_file, 0, 0)

            policy_file = gateways_dir / f"{dep_user}.json"
            policy = {
                'schema': 2,
                'project': 'lab-sales-systems',
                'environment': 'staging',
                'target_config': str(cfg_file),
                'deploy_user': dep_user,
                'promotion_root': str(promotion_dir)
            }
            policy_file.write_text(json.dumps(policy, indent=2), encoding='utf-8')
            policy_file.chmod(0o600)
            os.chown(policy_file, 0, 0)
            self.addCleanup(policy_file.unlink, missing_ok=True)

            unit_file = Path('/etc/systemd/system') / f"lab-sales-{target_id}.service"

            def cleanup_svc(u_name=f"lab-sales-{target_id}.service", u_file=unit_file):
                if u_file.exists():
                    stopped = subprocess.run(['/usr/bin/systemctl', 'stop', u_name],
                                             capture_output=True, timeout=40)
                    self.assertEqual(0, stopped.returncode, 'Owned application did not stop')
                    pid, state = deploy_linux.get_systemd_main_pid(u_name)
                    self.assertEqual(0, pid, 'Owned application still has a MainPID')
                    self.assertIn(state, ('inactive', 'failed'))
                    subprocess.run(['/usr/bin/systemctl', 'disable', u_name],
                                   check=True, capture_output=True)
                    u_file.unlink()
                    subprocess.run(['/usr/bin/systemctl', 'daemon-reload'],
                                   check=True, capture_output=True)

            self.addCleanup(cleanup_svc)

            targets[target_label] = {
                'id': target_id,
                'port': target_port,
                'cfg': target_cfg,
                'cfg_file': cfg_file,
                'policy_file': policy_file,
                'dep_user': dep_user,
                'paths': deploy_linux.get_target_paths(target_cfg),
                'cfg_dir': cfg_dir
            }

        print(f"[RECOVERY_TEST] Checkpoint 3: Two targets prepared (A={targets['A']['id']}, B={targets['B']['id']})")

        # 5. Administrative enroll, migrate, bootstrap, and deploy for both targets
        for label in ('A', 'B'):
            t = targets[label]

            enroll_code = deploy_sandbox.run_controller(
                t['cfg'],
                ['/usr/bin/python3', '-I', '/opt/lab-sales-systems/controller/deploy_linux.py',
                 'enroll', '--config', str(t['cfg_file']), '--environment', 'staging']
            )
            self.assertEqual(0, enroll_code, f"Enrollment failed for target {label}")

            mig_code = deploy_sandbox.run_controller(
                t['cfg'],
                ['/usr/bin/python3', '-I', '/opt/lab-sales-systems/controller/deploy_linux.py',
                 'migrate', '--config', str(t['cfg_file']), '--environment', 'staging',
                 '--release-dir', str(release_dir), '--revision', revision,
                 '--run-id', run_id, '--manifest-sha256', manifest_sha]
            )
            self.assertEqual(0, mig_code, f"Migration failed for target {label}")

            boot_intent_file = t['cfg_dir'] / 'bootstrap_intent.json'
            boot_data = {
                'login': f"admin_{t['id'][:8]}",
                'password': f"RecoveryPassword_{secrets.token_urlsafe(16)}",
                'workspace_code': f"W{label}",
                'workspace_name': f"Workspace {label}",
                'business_date': '2026-11-14'
            }
            boot_intent_file.write_text(json.dumps(boot_data), encoding='utf-8')
            boot_intent_file.chmod(0o600)
            os.chown(boot_intent_file, 0, 0)

            boot_code = deploy_sandbox.run_controller(
                t['cfg'],
                ['/usr/bin/python3', '-I', '/opt/lab-sales-systems/controller/deploy_linux.py',
                 'bootstrap', '--config', str(t['cfg_file']), '--environment', 'staging',
                 '--release-dir', str(release_dir), '--revision', revision,
                 '--run-id', run_id, '--manifest-sha256', manifest_sha,
                 '--bootstrap-file', str(boot_intent_file)]
            )
            boot_intent_file.unlink(missing_ok=True)
            self.assertEqual(0, boot_code, f"Bootstrap failed for target {label}")

            payload = frame_deploy_request(
                t['policy_file'], 'deploy', 'staging',
                revision, run_id, manifest_sha, release_dir
            )
            deploy_proc = subprocess.run(
                ['/usr/bin/python3', '-I', '/opt/lab-sales-systems/controller/deploy_entry.py'],
                input=payload, capture_output=True,
                env={'PATH': '/usr/bin:/bin', 'LANG': 'C', 'SUDO_USER': t['dep_user']}
            )
            self.assertEqual(0, deploy_proc.returncode,
                             f"Framed initial deployment failed for target {label}: {deploy_proc.stderr.strip()[:200]}")

            main_pid, state = deploy_linux.get_systemd_main_pid(t['paths']['unit_name'])
            self.assertEqual('active', state, f"Target {label} not active")
            self.assertGreater(main_pid, 0, f"Target {label} invalid MainPID")
            self.assertEqual(main_pid, deploy_linux.find_pid_listening_on_port(t['port']),
                             f"Target {label} port listener mismatch")
            deploy_linux.verify_functional_readiness(t['port'], revision, 7, timeout=10)

        print("[RECOVERY_TEST] Checkpoint 4: Both targets enrolled, migrated, bootstrapped, and initially deployed")

        # 6. Record baseline snapshots of Target A and Target B
        t_a = targets['A']
        t_b = targets['B']

        old_a_unit_text = t_a['paths']['unit_file'].read_text(encoding='utf-8')
        old_a_unit_sha = hashlib.sha256(old_a_unit_text.encode('utf-8')).hexdigest()
        old_a_state = deploy_linux.read_json(t_a['paths']['runtime_state'])
        old_a_accepted = old_a_state['accepted_release']
        old_a_manifest_sha = old_a_accepted['manifest_sha256']
        old_a_revision = old_a_accepted['revision']
        old_a_jar_sha = old_a_accepted['jar_sha256']
        old_a_main_pid = old_a_accepted['main_pid']
        old_a_db_receipt = deploy_linux.initial_admin_receipt(t_a['cfg'])
        old_a_bootstrap_bytes = (t_a['paths']['state'] / 'bootstrap_receipt.json').read_bytes()
        old_a_data_digest = app_data_digest(t_a['cfg'])
        old_b_main_pid, old_b_state = deploy_linux.get_systemd_main_pid(t_b['paths']['unit_name'])
        old_b_db_receipt = deploy_linux.initial_admin_receipt(t_b['cfg'])
        old_b_bootstrap_bytes = (t_b['paths']['state'] / 'bootstrap_receipt.json').read_bytes()
        old_b_runtime_bytes = t_b['paths']['runtime_state'].read_bytes()
        old_b_unit_bytes = t_b['paths']['unit_file'].read_bytes()
        old_b_data_digest = app_data_digest(t_b['cfg'])

        print(f"[RECOVERY_TEST] Checkpoint 5: Baseline captured - Target A MainPID={old_a_main_pid}, Target B MainPID={old_b_main_pid}")

        # 7. Construct intentionally broken candidate JAR and package verified release
        bad_revision = 'f' * 40
        bad_run_id = str(int(run_id) + 1) if run_id.isdigit() else '999'

        bad_tmp_dir = inputs / 'bad'
        bad_tmp_dir.mkdir(mode=0o755)

        bad_jar_path = bad_tmp_dir / 'application.jar'
        with zipfile.ZipFile(real_jar, 'r') as src_zip, \
             zipfile.ZipFile(bad_jar_path, 'w', compression=zipfile.ZIP_DEFLATED) as dst_zip:
            for item in src_zip.infolist():
                content = src_zip.read(item.filename)
                if item.filename == 'BOOT-INF/classes/application.properties':
                    content = content + b'\nserver.servlet.context-path=/recovery-probe\n'
                dst_zip.writestr(item, content)

        bad_app_dir = bad_tmp_dir / 'app_artifact'
        ci.package(bad_jar_path, bad_app_dir, bad_revision, bad_run_id)

        bad_release_dir = bad_tmp_dir / 'release_bundle'
        bad_manifest = release.package_release(bad_app_dir, bad_release_dir, bad_revision, bad_run_id)
        bad_manifest_sha = hashlib.sha256((bad_release_dir / 'release.json').read_bytes()).hexdigest()

        # Run action_migrate through controller to produce genuine migrate receipt for bad candidate
        bad_mig_code = deploy_sandbox.run_controller(
            t_a['cfg'],
            ['/usr/bin/python3', '-I', '/opt/lab-sales-systems/controller/deploy_linux.py',
             'migrate', '--config', str(t_a['cfg_file']), '--environment', 'staging',
             '--release-dir', str(bad_release_dir), '--revision', bad_revision,
             '--run-id', bad_run_id, '--manifest-sha256', bad_manifest_sha]
        )
        self.assertEqual(0, bad_mig_code, "Pre-migration for bad candidate failed")
        self.assertTrue((t_a['paths']['state'] / f"migrate_receipt_{bad_manifest_sha}.json").is_file(),
                        "Missing migrate receipt for candidate")

        print(f"[RECOVERY_TEST] Checkpoint 6: Broken candidate release packaged with context-path mutation, sha={bad_manifest_sha[:12]}")

        # 8. Deploy broken candidate to Target A via framed gateway input and monitor concurrently
        bad_payload = frame_deploy_request(
            t_a['policy_file'], 'deploy', 'staging',
            bad_revision, bad_run_id, bad_manifest_sha, bad_release_dir
        )

        bad_proc = subprocess.Popen(
            ['/usr/bin/python3', '-I', '/opt/lab-sales-systems/controller/deploy_entry.py'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={'PATH': '/usr/bin:/bin', 'LANG': 'C', 'SUDO_USER': t_a['dep_user']}
        )
        self.addCleanup(stop_request, bad_proc)
        bad_proc.stdin.write(bad_payload)
        bad_proc.stdin.close()
        bad_proc.stdin = None

        observed_candidate_pid = None
        observed_candidate_context_health = False
        target_b_remained_healthy = True

        poll_start = time.monotonic()
        while time.monotonic() - poll_start < 75:
            if bad_proc.poll() is not None:
                break

            curr_a_pid, curr_a_state = deploy_linux.get_systemd_main_pid(t_a['paths']['unit_name'])
            if curr_a_pid and curr_a_pid != old_a_main_pid and curr_a_state == 'active':
                observed_candidate_pid = curr_a_pid

                # Context path health should respond with bad_revision
                try:
                    probe_req = urllib.request.Request(f"http://127.0.0.1:{t_a['port']}/recovery-probe/health")
                    with urllib.request.urlopen(probe_req, timeout=1) as resp:
                        if resp.status == 200:
                            body = json.loads(resp.read().decode('utf-8'))
                            if (body.get('status') == 'UP' and body.get('revision') == bad_revision
                                    and body.get('application') == 'lab-sales-systems'
                                    and body.get('schemaVersion') == 7
                                    and deploy_linux.find_pid_listening_on_port(t_a['port']) == curr_a_pid):
                                observed_candidate_context_health = True
                except Exception:
                    pass

            # Monitor Target B concurrently: must remain undisturbed with identical PID and active state
            curr_b_pid, curr_b_state = deploy_linux.get_systemd_main_pid(t_b['paths']['unit_name'])
            if curr_b_pid != old_b_main_pid or curr_b_state != 'active':
                target_b_remained_healthy = False
            try:
                with urllib.request.urlopen(
                        f"http://127.0.0.1:{t_b['port']}/health", timeout=3) as response:
                    health = json.loads(response.read())
                    if (response.status != 200 or health.get('status') != 'UP'
                            or health.get('revision') != revision or health.get('schemaVersion') != 7):
                        target_b_remained_healthy = False
            except (OSError, ValueError):
                target_b_remained_healthy = False

            time.sleep(1)

        bad_stdout, bad_stderr = bad_proc.communicate(timeout=90)
        self.assertNotEqual(0, bad_proc.returncode, "Expected non-zero exit code from failed rollout")
        self.assertIsNotNone(observed_candidate_pid, "Failed candidate service was never observed running")
        self.assertTrue(observed_candidate_context_health,
                        "Failed candidate was not observed reaching HTTP readiness on /recovery-probe/health")
        self.assertTrue(target_b_remained_healthy, "Target B experienced disruption during Target A failure")

        print(f"[RECOVERY_TEST] Checkpoint 7: Observed candidate PID {observed_candidate_pid} active and failing readiness; deploy exited non-zero")

        # 9. Assert Target A recovered state
        self.assertFalse(t_a['paths']['pending_journal'].exists(), "Pending journal must be unlinked after recovery")

        recovered_unit_text = t_a['paths']['unit_file'].read_text(encoding='utf-8')
        self.assertEqual(old_a_unit_text, recovered_unit_text, "Target A unit file was not restored to old accepted unit")
        self.assertTrue(deploy_linux.verify_unit_ownership(t_a['cfg'], old_a_unit_sha),
                        "Unit ownership verification failed on recovered unit")

        recovered_state = deploy_linux.read_json(t_a['paths']['runtime_state'])
        recovered_acc = recovered_state['accepted_release']
        self.assertEqual(old_a_manifest_sha, recovered_acc['manifest_sha256'], "Restored manifest SHA mismatch")
        self.assertEqual(old_a_revision, recovered_acc['revision'], "Restored revision mismatch")
        self.assertEqual(old_a_jar_sha, recovered_acc['jar_sha256'], "Restored JAR SHA mismatch")

        # Target A restarted the original release: MainPID is active and listening on port
        recovered_a_pid, recovered_a_state = deploy_linux.get_systemd_main_pid(t_a['paths']['unit_name'])
        self.assertEqual('active', recovered_a_state, "Recovered Target A service is not active")
        self.assertGreater(recovered_a_pid, 0, "Recovered Target A has invalid MainPID")
        self.assertEqual(recovered_a_pid, deploy_linux.find_pid_listening_on_port(t_a['port']),
                         "Recovered Target A listening port PID mismatch")

        # Functional HTTP readiness and login restored
        deploy_linux.verify_functional_readiness(t_a['port'], old_a_revision, 7, timeout=10)

        # Database sentinel receipts and data digest unchanged
        self.assertEqual(old_a_db_receipt, deploy_linux.initial_admin_receipt(t_a['cfg']),
                         "Target A bootstrap initial admin receipt altered")
        self.assertEqual(old_a_data_digest, app_data_digest(t_a['cfg']),
                         'Target A application data changed during failed rollout')
        self.assertEqual(old_a_bootstrap_bytes,
                         (t_a['paths']['state'] / 'bootstrap_receipt.json').read_bytes())

        deploy_linux.check_receipt(t_a['cfg'], 'migrate', old_a_manifest_sha, old_a_revision, run_id)
        deploy_linux.verify_bootstrap_receipt(t_a['cfg'])

        print(f"[RECOVERY_TEST] Checkpoint 8: Target A fully recovered (new PID={recovered_a_pid}, old rev={old_a_revision[:12]}, schema 7 UP)")

        # 10. Assert Target B completely isolated and unchanged
        final_b_pid, final_b_state = deploy_linux.get_systemd_main_pid(t_b['paths']['unit_name'])
        self.assertEqual(old_b_main_pid, final_b_pid, "Target B MainPID changed unexpectedly")
        self.assertEqual('active', final_b_state, "Target B is not active")

        deploy_linux.verify_functional_readiness(t_b['port'], revision, 7, timeout=5)
        self.assertEqual(old_b_db_receipt, deploy_linux.initial_admin_receipt(t_b['cfg']),
                         "Target B bootstrap initial admin receipt altered")
        self.assertEqual(old_b_data_digest, app_data_digest(t_b['cfg']),
                         'Target B application data changed')
        self.assertEqual(old_b_bootstrap_bytes,
                         (t_b['paths']['state'] / 'bootstrap_receipt.json').read_bytes())
        self.assertEqual(old_b_runtime_bytes, t_b['paths']['runtime_state'].read_bytes())
        self.assertEqual(old_b_unit_bytes, t_b['paths']['unit_file'].read_bytes())

        print(f"[RECOVERY_TEST] Checkpoint 9: Target B unaffected (PID={final_b_pid} identical, UP, data digest intact)")


if __name__ == '__main__':
    unittest.main()
