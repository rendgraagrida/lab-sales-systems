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
import threading
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
    import grp

OPT_IN = os.environ.get('LAB_NATIVE_RECOVERY') == '1'


def allocate_free_port():
    """Bind to an ephemeral port to discover an unused port on loopback."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def run_psql_stdin(pg_port, sql_text, db_name='postgres', timeout=30):
    """Execute SQL via psql using STDIN to prevent credentials appearing in argv."""
    cmd = [
        '/usr/sbin/runuser', '-u', 'postgres', '--',
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


def stop_request(process, cfg):
    if process.poll() is None:
        # Stop the owned controller/children before DB/user teardown, not merely
        # the waiting supervisor. The slice also owns this fixture's runtime.
        result = subprocess.run(
            ['/usr/bin/systemctl', 'stop', cfg['sandbox']['slice']],
            capture_output=True, timeout=40)
        if result.returncode:
            raise AssertionError('Owned request slice did not stop')
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


def write_owned_file(path, data, mode, owned_files):
    """Record exclusive acquisition before writing, including partial-write failures."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        info = os.fstat(fd)
        owned_files[path] = (info.st_dev, info.st_ino, info.st_uid)
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'wb') as output:
            fd = None
            output.write(data.encode('utf-8') if isinstance(data, str) else data)
    finally:
        if fd is not None:
            os.close(fd)


def verify_owned_file(path, owned_files):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode)
            or (info.st_dev, info.st_ino, info.st_uid) != owned_files.get(path)):
        raise AssertionError('Owned fixture file identity changed: ' + str(path))


def unlink_owned_file(path, owned_files):
    if path in owned_files and os.path.lexists(path):
        verify_owned_file(path, owned_files)
        path.unlink()


@unittest.skipUnless(OPT_IN, 'Native recovery test requires opt-in via LAB_NATIVE_RECOVERY=1')
class DeployRecoveryRegressionTests(unittest.TestCase):
    """Permanent regression exercising actual failed-readiness rollback and two-target isolation."""

    def test_two_target_failed_readiness_recovery_and_isolation(self):
        # 1. Assert host and toolchain prerequisites without skipping
        self.assertTrue(sys.platform.startswith('linux'), "Native recovery requires Linux")
        self.assertTrue(hasattr(os, 'geteuid') and os.geteuid() == 0, "Native recovery requires root (UID 0)")
        self.assertEqual('true', os.environ.get('GITHUB_ACTIONS'), "Requires GitHub Actions environment")
        self.assertEqual('github-hosted', os.environ.get('RUNNER_ENVIRONMENT'), "Requires GitHub-hosted runner")
        original_umask = os.umask(0o022)
        self.addCleanup(os.umask, original_umask)
        print(f"[RECOVERY_TEST] Runner creation mask observed {original_umask:#05o}; fixture mask 0022")
        owned_files = {}
        resources = []
        quiescent = False

        def quiesce_targets():
            nonlocal quiescent
            if quiescent:
                return
            for resource in resources:
                slice_file = resource['slice_file']
                if slice_file in owned_files:
                    verify_owned_file(slice_file, owned_files)
                    stopped = subprocess.run(
                        ['/usr/bin/systemctl', 'stop', resource['slice']],
                        capture_output=True, timeout=40)
                    self.assertEqual(0, stopped.returncode, 'Owned target slice did not stop')
                    pid, state = deploy_linux.get_systemd_main_pid(resource['unit'])
                    self.assertEqual(0, pid, 'Owned app still runs; dependencies retained')
                    self.assertIn(state, ('inactive', 'failed', 'unknown'))
                    group = subprocess.run(
                        ['/usr/bin/systemctl', 'show', resource['slice'],
                         '--property=ControlGroup', '--value'],
                        check=True, capture_output=True, text=True, timeout=10).stdout.strip()
                    if group:
                        events = Path('/sys/fs/cgroup') / group.lstrip('/') / 'cgroup.events'
                        if events.exists():
                            self.assertIn('populated 0', events.read_text(),
                                          'Owned controller/children still run; dependencies retained')
            quiescent = True

        def remove_owned_directory(path):
            quiesce_targets()
            for resource in resources:
                if resource['base'].is_relative_to(path):
                    self.assertFalse(resource['storage'].is_mount(),
                                     'Owned storage still mounted; directory retained')
            self.assertFalse((path / 'storage').is_mount(), 'Owned storage still mounted; retained')
            self.assertFalse((path / 'postmaster.pid').exists(),
                             'Native cluster still runs; data directory retained')
            if path.exists():
                shutil.rmtree(path)

        def remove_owned_file(path):
            quiesce_targets()
            unlink_owned_file(path, owned_files)

        def ensure_trusted_directories(path):
            current = Path('/')
            for part in path.parts[1:]:
                current = current / part
                if not os.path.lexists(current):
                    current.mkdir(mode=0o755)
                    self.addCleanup(current.rmdir)
                gateway.trusted_directory(str(current))

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
        self.addCleanup(remove_owned_directory, inputs)
        inputs.chmod(0o755)
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
        else:
            controller_root.parent.mkdir(mode=0o755)
            self.addCleanup(controller_root.parent.rmdir)
        controller_root.mkdir(mode=0o755)

        def cleanup_controller():
            quiesce_targets()
            shutil.rmtree(controller_root)

        self.addCleanup(cleanup_controller)
        # Explicit acquisition mode also bounds inherited default ACLs; umask alone
        # did not stop the hosted interpreter from creating this directory0777.
        cache = controller_root / '__pycache__'
        cache.mkdir(mode=0o755)
        gateway.trusted_directory(str(cache))
        print(f"[RECOVERY_TEST] Fresh owned cache mode={stat.S_IMODE(cache.stat().st_mode):04o}; "
              f"parent_default_acl={'system.posix_acl_default' in os.listxattr(controller_root)}")
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
        self.addCleanup(remove_owned_directory, pg_cluster_dir)
        postgres_pw = pwd.getpwnam('postgres')
        os.chown(pg_cluster_dir, postgres_pw.pw_uid, postgres_pw.pw_gid)

        def cleanup_cluster():
            quiesce_targets()
            if (pg_cluster_dir / 'postmaster.pid').exists():
                result = subprocess.run(
                    ['/usr/sbin/runuser', '-u', 'postgres', '--',
                     '/usr/lib/postgresql/17/bin/pg_ctl', '-D', str(pg_cluster_dir),
                     '-m', 'fast', '-w', '-t', '30', 'stop'], capture_output=True, timeout=45)
                self.assertEqual(0, result.returncode, 'Owned native cluster did not stop')
            self.assertFalse((pg_cluster_dir / 'postmaster.pid').exists())
            shutil.rmtree(pg_cluster_dir)

        self.addCleanup(cleanup_cluster)
        password_file = pg_cluster_dir.with_name(pg_cluster_dir.name + '.password')
        self.addCleanup(unlink_owned_file, password_file, owned_files)
        write_owned_file(password_file, secrets.token_urlsafe(32) + '\n', 0o600, owned_files)
        os.chown(password_file, postgres_pw.pw_uid, postgres_pw.pw_gid)
        password_info = password_file.stat()
        owned_files[password_file] = (password_info.st_dev, password_info.st_ino, password_info.st_uid)

        initdb_res = subprocess.run(
            ['/usr/sbin/runuser', '-u', 'postgres', '--',
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
            ['/usr/sbin/runuser', '-u', 'postgres', '--',
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
        ensure_trusted_directories(gateways_dir)

        targets = {}
        for target_label in ('A', 'B'):
            target_id = f"t{target_label.lower()}{uuid.uuid4().hex[:10]}"
            target_port = allocate_free_port()
            while target_port in allocated_ports:
                target_port = allocate_free_port()
            allocated_ports.add(target_port)
            base_dir = Path(tempfile.mkdtemp(prefix=f'lab-rec-{target_id}-', dir='/srv'))
            self.addCleanup(remove_owned_directory, base_dir)
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
            unit_name = f"lab-sales-{target_id}.service"
            for name in (mount_unit, slice_name, unit_name):
                result = subprocess.run(
                    ['/usr/bin/systemctl', 'show', name, '--property=LoadState',
                     '--property=FragmentPath', '--property=DropInPaths', '--property=ActiveState',
                     '--property=ControlGroup', '--property=Transient'],
                    capture_output=True, text=True, timeout=10)
                properties = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
                self.assertFalse(properties.get('FragmentPath') or properties.get('DropInPaths'))
                self.assertIn(properties.get('ActiveState'), ('inactive', 'failed'))
                self.assertFalse(properties.get('ControlGroup'))
                self.assertNotEqual('yes', properties.get('Transient'))
                # systemd can synthesize an inactive, unconfigured slice without a unit file.
                self.assertTrue(
                    properties.get('LoadState') == 'not-found' or
                    (name == slice_name and properties.get('LoadState') == 'loaded'),
                    'Refusing existing unit: ' + name)
            resources.append({'base': base_dir, 'storage': storage_dir, 'slice': slice_name,
                              'slice_file': slice_file, 'unit': unit_name})

            def cleanup_mount_and_slice(m_unit=mount_unit, m_file=mount_file,
                                        s_file=slice_file, b_dir=base_dir, s_dir=storage_dir):
                quiesce_targets()
                if m_file in owned_files:
                    verify_owned_file(m_file, owned_files)
                    subprocess.run(['/usr/bin/systemctl', 'stop', m_unit],
                                   capture_output=True, timeout=30, check=True)
                self.assertFalse(s_dir.is_mount(), 'Owned storage mount did not stop; retained')
                unlink_owned_file(s_file, owned_files)
                unlink_owned_file(m_file, owned_files)
                subprocess.run(['/usr/bin/systemctl', 'daemon-reload'], check=True, capture_output=True)
                remove_owned_directory(b_dir)

            self.addCleanup(cleanup_mount_and_slice)
            subprocess.run(['/usr/bin/fallocate', '-l', '2G', str(img_path)], check=True)
            subprocess.run(['/usr/sbin/mkfs.ext4', '-F', '-q', str(img_path)], check=True)
            fs_uuid = subprocess.run(
                ['/usr/sbin/blkid', '-s', 'UUID', '-o', 'value', str(img_path)],
                check=True, text=True, capture_output=True
            ).stdout.strip()


            write_owned_file(
                mount_file, f"[Unit]\nDescription=Target {target_id} storage mount\n"
                f"[Mount]\nWhat={img_path}\nWhere={storage_dir}\nType=ext4\nOptions=loop,rw,nosuid,nodev\n",
                0o644, owned_files)
            write_owned_file(
                slice_file, f"[Unit]\nDescription=Target {target_id} slice\n"
                f"[Slice]\nMemoryMax=2147483648\nMemorySwapMax=0\nCPUQuota=400%\nTasksMax=512\n",
                0o644, owned_files)

            subprocess.run(['/usr/bin/systemctl', 'daemon-reload'], check=True)
            subprocess.run(['/usr/bin/systemctl', 'start', mount_unit, slice_name], check=True)


            journal_conf = Path(f'/etc/systemd/journald@lab-sales-{target_id}.conf')

            self.assertFalse(os.path.lexists(journal_conf))
            for journal_unit in (f'systemd-journald@lab-sales-{target_id}.service',
                                 f'systemd-journald@lab-sales-{target_id}.socket'):
                pid, state = deploy_linux.get_systemd_main_pid(journal_unit)
                self.assertEqual(0, pid, 'Refusing an existing journal instance')
                self.assertIn(state, ('inactive', 'failed', 'unknown'))

            def cleanup_journal(t_id=target_id, j_conf=journal_conf):
                quiesce_targets()
                if j_conf not in owned_files:
                    return
                verify_owned_file(j_conf, owned_files)
                subprocess.run(
                    ['/usr/bin/systemctl', 'stop', f'systemd-journald@lab-sales-{t_id}.service',
                     f'systemd-journald@lab-sales-{t_id}.socket'],
                    capture_output=True, timeout=30, check=True)
                pid = subprocess.run(
                    ['/usr/bin/systemctl', 'show', f'systemd-journald@lab-sales-{t_id}.service',
                     '--property=MainPID', '--value'], capture_output=True, text=True, check=True)
                self.assertEqual('0', pid.stdout.strip(), 'Owned journal process did not stop')
                unlink_owned_file(j_conf, owned_files)

            self.addCleanup(cleanup_journal)
            write_owned_file(journal_conf, "[Journal]\nStorage=volatile\n", 0o644, owned_files)

            suffix = target_id[:8]
            svc_user = f"lss_{suffix}"
            mig_user = f"lsm_{suffix}"
            dep_user = f"lsd_{suffix}"

            mig_home = storage_dir / 'home'
            mig_home.mkdir(mode=0o700)

            def cleanup_user(user, uid, gid):
                quiesce_targets()
                account = pwd.getpwnam(user)
                self.assertEqual((uid, gid), (account.pw_uid, account.pw_gid),
                                 'Fixture account identity changed; retained')
                result = subprocess.run(['/usr/sbin/userdel', user], capture_output=True)
                self.assertEqual(0, result.returncode, 'Owned fixture user did not delete')
                try:
                    group = grp.getgrnam(user)
                except KeyError:
                    group = None
                if group is not None:
                    self.assertEqual(gid, group.gr_gid, 'Fixture group identity changed; retained')
                    subprocess.run(['/usr/sbin/groupdel', user], capture_output=True, check=True)
                with self.assertRaises(KeyError):
                    pwd.getpwnam(user)
                with self.assertRaises(KeyError):
                    grp.getgrnam(user)

            for user in (svc_user, mig_user, dep_user):
                with self.assertRaises(KeyError, msg='Refusing an existing fixture account'):
                    pwd.getpwnam(user)
                with self.assertRaises(KeyError, msg='Refusing an existing fixture group'):
                    grp.getgrnam(user)
                args = ['/usr/sbin/useradd', '--system', '--user-group',
                        '--shell', '/usr/sbin/nologin', '--no-create-home']
                if user == mig_user:
                    args += ['--home-dir', str(mig_home)]
                subprocess.run([*args, user], check=True, capture_output=True)
                account = pwd.getpwnam(user)
                self.addCleanup(cleanup_user, user, account.pw_uid, account.pw_gid)

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
                quiesce_targets()
                run_psql_stdin(pg_port, f'DROP DATABASE IF EXISTS "{d_name}" WITH (FORCE);', 'postgres')
                run_psql_stdin(pg_port, f'DROP ROLE IF EXISTS "{m_role}", "{r_role}", "{o_role}";', 'postgres')

            self.assertEqual('0', run_psql_stdin(
                pg_port, f"SELECT count(*) FROM pg_catalog.pg_database WHERE datname='{db_name}';"),
                'Refusing an existing fixture database')
            self.assertEqual('0', run_psql_stdin(
                pg_port, f"SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname IN "
                f"('{owner_role}','{migrator_role}','{runtime_role}');"),
                'Refusing existing fixture roles')
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
            write_owned_file(
                rt_env_file, f"LAB_DB_URL=jdbc:postgresql://127.0.0.1:{pg_port}/{db_name}\n"
                f"LAB_DB_USER={runtime_role}\nLAB_DB_PASSWORD={rt_pass}\n", 0o600, owned_files)

            mig_env_file = env_dir / 'migration.env'
            write_owned_file(
                mig_env_file, f"LAB_MIGRATION_DB_URL=jdbc:postgresql://127.0.0.1:{pg_port}/{db_name}\n"
                f"LAB_MIGRATION_DB_USER={migrator_role}\nLAB_MIGRATION_DB_PASSWORD={mig_pass}\n"
                f"LAB_MIGRATION_OWNER={owner_role}\n", 0o600, owned_files)

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
            write_owned_file(cfg_file, json.dumps(target_cfg, indent=2), 0o600, owned_files)

            policy_file = gateways_dir / f"{dep_user}.json"
            policy = {
                'schema': 2,
                'project': 'lab-sales-systems',
                'environment': 'staging',
                'target_config': str(cfg_file),
                'deploy_user': dep_user,
                'promotion_root': str(promotion_dir)
            }
            self.addCleanup(remove_owned_file, policy_file)
            write_owned_file(policy_file, json.dumps(policy, indent=2), 0o600, owned_files)

            unit_file = Path('/etc/systemd/system') / f"lab-sales-{target_id}.service"

            def cleanup_svc(cfg=target_cfg, u_name=unit_name, u_file=unit_file):
                quiesce_targets()
                if u_file.exists():
                    paths = deploy_linux.get_target_paths(cfg)
                    expected = set()
                    if paths['runtime_state'].exists():
                        expected.add(deploy_linux.read_json(paths['runtime_state']).get('accepted_unit_sha256'))
                    if paths['pending_journal'].exists():
                        expected.add(deploy_linux.read_json(paths['pending_journal']).get('candidate_unit_sha256'))
                    actual = deploy_linux.compute_file_sha256(u_file)
                    self.assertIn(actual, expected, 'Refusing unowned application unit cleanup')
                    deploy_linux.verify_unit_ownership(cfg, actual, allow_unloaded=True)
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
            write_owned_file(boot_intent_file, json.dumps(boot_data), 0o600, owned_files)

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
            cache = controller_root / '__pycache__'
            self.assertTrue(cache.is_dir(), 'Installed entrypoint did not create its native cache')
            for path in (controller_root, cache, *cache.glob('*.pyc')):
                info = path.lstat()
                print(f"[RECOVERY_TEST] Installed {path.name}: "
                      f"uid={info.st_uid} gid={info.st_gid} mode={stat.S_IMODE(info.st_mode):04o}")
                self.assertEqual(0, info.st_uid)
                self.assertEqual(0, info.st_mode & 0o022, 'Installed cache is writable')
            gateway.verify_controllers({
                name: hashlib.sha256((controller_root / name).read_bytes()).hexdigest()
                for name in controller_files})
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
        observer_stop = threading.Event()
        observer_ready = threading.Event()
        rollout_started = threading.Event()
        observer_errors = []
        observations = {'candidate_pid': None, 'root_health_404': False,
                        'b_samples': 0, 'max_sample_gap': 0.0, 'last_sample': None}

        def observe_targets():
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            while not observer_stop.is_set():
                now = time.monotonic()
                if observations['last_sample'] is not None:
                    observations['max_sample_gap'] = max(
                        observations['max_sample_gap'], now - observations['last_sample'])
                observations['last_sample'] = now
                try:
                    b_pid, b_state = deploy_linux.get_systemd_main_pid(t_b['paths']['unit_name'])
                    if (b_pid != old_b_main_pid or b_state != 'active'
                            or deploy_linux.find_pid_listening_on_port(t_b['port']) != old_b_main_pid):
                        raise AssertionError('Target B process/listener changed')
                    with opener.open(f"http://127.0.0.1:{t_b['port']}/health", timeout=3) as response:
                        body = json.loads(response.read())
                        if (response.status != 200 or body.get('status') != 'UP'
                                or body.get('application') != 'lab-sales-systems'
                                or body.get('revision') != revision or body.get('schemaVersion') != 7):
                            raise AssertionError('Target B health identity changed')
                    if (t_b['paths']['runtime_state'].read_bytes() != old_b_runtime_bytes
                            or t_b['paths']['unit_file'].read_bytes() != old_b_unit_bytes
                            or (t_b['paths']['state'] / 'bootstrap_receipt.json').read_bytes()
                            != old_b_bootstrap_bytes):
                        raise AssertionError('Target B accepted files changed')
                    observations['b_samples'] += 1
                except Exception as error:
                    if len(observer_errors) < 10:
                        observer_errors.append(type(error).__name__ + ': ' + str(error))
                finally:
                    observer_ready.set()

                if rollout_started.is_set() and observations['candidate_pid'] is None:
                    try:
                        a_pid, a_state = deploy_linux.get_systemd_main_pid(t_a['paths']['unit_name'])
                        if a_pid and a_pid != old_a_main_pid and a_state == 'active':
                            with opener.open(
                                    f"http://127.0.0.1:{t_a['port']}/recovery-probe/health",
                                    timeout=1) as response:
                                body = json.loads(response.read())
                                candidate_ready = (response.status == 200 and body.get('status') == 'UP'
                                    and body.get('application') == 'lab-sales-systems'
                                    and body.get('revision') == bad_revision and body.get('schemaVersion') == 7)
                            root_status = None
                            try:
                                with opener.open(f"http://127.0.0.1:{t_a['port']}/health",
                                                 timeout=1) as response:
                                    root_status = response.status
                            except urllib.error.HTTPError as error:
                                root_status = error.code
                            current_pid, current_state = deploy_linux.get_systemd_main_pid(
                                t_a['paths']['unit_name'])
                            if (candidate_ready and root_status == 404 and current_pid == a_pid
                                    and current_state == 'active'
                                    and deploy_linux.find_pid_listening_on_port(t_a['port']) == a_pid):
                                observations['candidate_pid'] = a_pid
                                observations['root_health_404'] = True
                    except (OSError, ValueError, urllib.error.URLError):
                        # Candidate startup/recovery is transient; only matching bad revision counts.
                        pass
                observer_stop.wait(0.5)

        observer = threading.Thread(target=observe_targets, name='native-recovery-observer')

        def stop_observer():
            observer_stop.set()
            observer.join(timeout=20)
            self.assertFalse(observer.is_alive(), 'Fixture observer did not stop')

        observer.start()
        self.addCleanup(stop_observer)
        self.assertTrue(observer_ready.wait(timeout=10), 'Target B observer did not start')
        self.assertEqual([], observer_errors, 'Target B unhealthy before candidate migration')

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

        rollout_started.set()
        bad_proc = subprocess.Popen(
            ['/usr/bin/python3', '-I', '/opt/lab-sales-systems/controller/deploy_entry.py'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={'PATH': '/usr/bin:/bin', 'LANG': 'C', 'SUDO_USER': t_a['dep_user']}
        )
        self.addCleanup(stop_request, bad_proc, t_a['cfg'])
        # communicate drains both output pipes while sending the frame under one deadline.
        bad_stdout, bad_stderr = bad_proc.communicate(input=bad_payload, timeout=210)
        self.assertNotEqual(0, bad_proc.returncode, "Expected non-zero exit code from failed rollout")
        self.assertIn(b'Rollout failed; previous state recovered', bad_stdout + bad_stderr,
                      'Failure did not complete the intended rollout/recovery path')
        self.assertIsNotNone(observations['candidate_pid'],
                             "Bad-revision service/listener was never observed running")
        self.assertTrue(observations['root_health_404'],
                        'Candidate did not exhibit the intended root-readiness failure')

        print(f"[RECOVERY_TEST] Checkpoint 7: Bad-revision PID {observations['candidate_pid']} "
              "served context health and rejected root health; rollout recovered and exited non-zero")

        # 9. Assert Target A recovered state
        self.assertFalse(t_a['paths']['pending_journal'].exists(), "Pending journal must be unlinked after recovery")

        recovered_unit_text = t_a['paths']['unit_file'].read_text(encoding='utf-8')
        self.assertEqual(old_a_unit_text, recovered_unit_text, "Target A unit file was not restored to old accepted unit")
        self.assertTrue(deploy_linux.verify_unit_ownership(t_a['cfg'], old_a_unit_sha),
                        "Unit ownership verification failed on recovered unit")

        recovered_state = deploy_linux.read_json(t_a['paths']['runtime_state'])
        self.assertEqual(old_a_state, recovered_state, 'Full Target A accepted state/history changed')
        recovered_acc = recovered_state['accepted_release']
        self.assertEqual(old_a_manifest_sha, recovered_acc['manifest_sha256'], "Restored manifest SHA mismatch")
        self.assertEqual(old_a_revision, recovered_acc['revision'], "Restored revision mismatch")
        self.assertEqual(old_a_jar_sha, recovered_acc['jar_sha256'], "Restored JAR SHA mismatch")

        # Target A restarted the original release: MainPID is active and listening on port
        recovered_a_pid, recovered_a_state = deploy_linux.get_systemd_main_pid(t_a['paths']['unit_name'])
        self.assertEqual('active', recovered_a_state, "Recovered Target A service is not active")
        self.assertGreater(recovered_a_pid, 0, "Recovered Target A has invalid MainPID")
        self.assertNotIn(recovered_a_pid, (old_a_main_pid, observations['candidate_pid']),
                         'Recovered predecessor must be a distinct restarted process')
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
        stop_observer()
        self.assertEqual([], observer_errors, 'Target B disrupted during migration/rollout/recovery')
        self.assertGreaterEqual(observations['b_samples'], 2)
        print(f"[RECOVERY_TEST] B observed from before migration through final recovery: "
              f"samples={observations['b_samples']} max_gap={observations['max_sample_gap']:.3f}s")
        quiesce_targets()


if __name__ == '__main__':
    unittest.main()
