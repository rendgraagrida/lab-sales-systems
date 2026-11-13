#!/usr/bin/env python3
"""Linux host deployment adapter for Lab Sales Systems.

Handles root-owned target enrollment, migrations via Flyway, administrative
bootstrap, systemd service management, readiness validation, and fail-closed
recovery/rollback for isolated staging and production targets.
"""

import argparse
import fcntl
import hashlib
import json
import os
import pwd
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from pathlib import Path

# Sibling modules available alongside deploy_linux.py on target
import ci
import database
import release

NAME_PATTERN = re.compile(r'^[a-z][a-z0-9-]{0,62}$')
ROLE_PATTERN = re.compile(r'^[a-z][a-z0-9_]{0,62}$')
DB_PATTERN = re.compile(r'^[a-z][a-z0-9_]{0,62}$')
SYSTEM_ID_PATTERN = re.compile(r'^[0-9]{1,30}$')
REVISION_PATTERN = re.compile(r'^(?:[0-9a-f]{40}|[0-9a-f]{64}|content:[0-9a-f]{64})$')
RUN_ID_PATTERN = re.compile(r'^(?:local|[1-9][0-9]*)$')
HASH_PATTERN = re.compile(r'^[0-9a-f]{64}$')


class DeployError(Exception):
    pass


def fail(message):
    raise DeployError(message)


def validate_clean_path(path_str, name='Path'):
    if (not isinstance(path_str, str) or not path_str.startswith('/')
            or any(ord(c) < 32 or ord(c) == 127 or c in '%"\\' for c in path_str)
            or any(part in ('.', '..', '') for part in path_str.split('/')[1:])):
        fail(f'{name} must be a clean absolute path')
    return Path(path_str)


def validate_file_security(path, expected_mode=0o600, expected_uid=0, name='File'):
    p = validate_clean_path(str(path), name=name)
    if not p.is_file():
        fail(f'{name} does not exist or is not a regular file: {p}')
    if p.is_symlink():
        fail(f'{name} cannot be a symbolic link: {p}')
    st = p.stat()
    mode = st.st_mode & 0o777
    if mode != expected_mode:
        fail(f'{name} mode must be {oct(expected_mode)}, found {oct(mode)}: {p}')
    if st.st_uid != expected_uid:
        fail(f'{name} owner UID must be {expected_uid}, found {st.st_uid}: {p}')
    return p


def validate_ancestors_root_owned(path):
    curr = Path('/')
    for part in Path(path).parts[1:]:
        curr /= part
        try:
            st = curr.lstat()
        except FileNotFoundError:
            break
        if curr.is_symlink() or st.st_uid != 0 or (st.st_mode & 0o022):
            fail(f'Untrusted path component: {curr}')


def parse_env_file(path, allowed_keys):
    validate_file_security(path, expected_mode=0o600, expected_uid=0, name='Environment file')
    data = {}
    with open(path, 'r', encoding='utf-8') as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            if '=' not in line:
                fail(f'Invalid line {line_no} in environment file {path}')
            key, val = line.split('=', 1)
            key = key.strip()
            val = val.strip()
            if key not in allowed_keys or key in data or not val or any(c in val for c in "'\"\\") or any(ord(c) < 32 for c in val):
                fail('Invalid or duplicate environment entry')
            data[key] = val
    missing = set(allowed_keys) - set(data.keys())
    if missing:
        fail(f'Missing required keys {missing} in environment file {path}')
    return data


def canonical_json_bytes(obj):
    return json.dumps(obj, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def digest_object(obj):
    return hashlib.sha256(canonical_json_bytes(obj)).hexdigest()


def compute_file_sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def atomic_write(path, data, mode=0o600, uid=0, gid=0):
    p = Path(path)
    tmp = p.with_name(f'{p.name}.tmp.{os.getpid()}.{time.time_ns()}')
    content = data.encode('utf-8') if isinstance(data, str) else data
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    os.fchmod(fd, mode)
    with os.fdopen(fd, 'wb') as f:
        f.write(content)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, p)
    dfd = os.open(p.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)

def durable_unlink(path):
    Path(path).unlink()
    fd = os.open(Path(path).parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def read_json(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f, object_pairs_hook=ci.unique_object)


def validate_config(config_path, expected_env):
    if not isinstance(config_path, (str, Path)):
        fail('Config path must be specified')
    cfg_p = validate_clean_path(str(config_path), 'Config')
    validate_file_security(cfg_p, expected_mode=0o600, expected_uid=0, name='Config')
    validate_ancestors_root_owned(cfg_p)

    with open(cfg_p, 'r', encoding='utf-8') as f:
        cfg = json.load(f, object_pairs_hook=ci.unique_object)

    if not isinstance(cfg, dict):
        fail('Config must be a JSON object')

    required_keys = {
        'schema', 'project', 'environment', 'target_id', 'root', 'service_user',
        'port', 'java_home', 'maven_home', 'pg_bin', 'pg_port', 'database',
        'owner_role', 'migrator_role', 'runtime_role', 'system_identifier',
        'machine_id', 'runtime_env_file', 'migration_env_file'
    }
    actual_keys = set(cfg.keys())
    if actual_keys != required_keys:
        fail(f'Config keys mismatch: missing {required_keys - actual_keys}, unexpected {actual_keys - required_keys}')

    if type(cfg['schema']) is not int or cfg['schema'] != 1:
        fail(f"Config schema must be 1, found {cfg['schema']}")
    if cfg['project'] != 'lab-sales-systems':
        fail(f"Config project must be 'lab-sales-systems', found {cfg['project']}")
    if cfg['environment'] not in ('staging', 'production'):
        fail(f"Config environment must be 'staging' or 'production', found {cfg['environment']}")
    if cfg['environment'] != expected_env:
        fail(f"Config environment {cfg['environment']} does not match requested environment {expected_env}")

    if not NAME_PATTERN.match(cfg['target_id']):
        fail(f"Invalid target_id: {cfg['target_id']}")

    root_p = validate_clean_path(cfg['root'], 'Target root')
    if root_p.is_symlink():
        fail(f'Target root cannot be a symlink: {root_p}')
    if not root_p.parent.is_dir() or Path(__file__).resolve().is_relative_to(root_p):
        fail('Target must have an existing parent and must not contain the controller')

    # Validate service user
    user_name = cfg['service_user']
    if not isinstance(user_name, str) or not ROLE_PATTERN.fullmatch(user_name):
        fail('Invalid service user')
    try:
        pw = pwd.getpwnam(user_name)
    except KeyError:
        fail(f'service_user {user_name} does not exist on host')
    if pw.pw_uid == 0:
        fail(f'service_user {user_name} cannot be root (UID 0)')
    # Verify non-login shell
    non_login_shells = {'/usr/sbin/nologin', '/bin/false', '/sbin/nologin', '/usr/bin/false'}
    if pw.pw_shell not in non_login_shells:
        fail(f'service_user {user_name} must have a non-login shell, found {pw.pw_shell}')

    # Ports
    if type(cfg['port']) is not int or not (1024 <= cfg['port'] <= 65535):
        fail(f"Config port must be integer 1024..65535, found {cfg['port']}")
    if type(cfg['pg_port']) is not int or not (1024 <= cfg['pg_port'] <= 65535):
        fail(f"Config pg_port must be integer 1024..65535, found {cfg['pg_port']}")

    # Directories
    for k in ('java_home', 'maven_home', 'pg_bin'):
        dir_p = validate_clean_path(cfg[k], k)
        if not dir_p.is_dir():
            fail(f'Config directory {k} must exist: {dir_p}')
        if dir_p.is_symlink():
            fail(f'Config directory {k} cannot be a symlink: {dir_p}')
        validate_ancestors_root_owned(dir_p)

    # Binaries exist and are executable
    java_bin = dir_p = Path(cfg['java_home']) / 'bin' / 'java'
    if not java_bin.is_file() or not os.access(java_bin, os.X_OK):
        fail(f'java binary not found or not executable at {java_bin}')
    mvn_bin = Path(cfg['maven_home']) / 'bin' / 'mvn'
    if not mvn_bin.is_file() or not os.access(mvn_bin, os.X_OK):
        fail(f'mvn binary not found or not executable at {mvn_bin}')
    psql_bin = Path(cfg['pg_bin']) / 'psql'
    if not psql_bin.is_file() or not os.access(psql_bin, os.X_OK):
        fail(f'psql binary not found or not executable at {psql_bin}')
    for binary in (java_bin, mvn_bin, psql_bin):
        validate_ancestors_root_owned(binary)
    env = {'PATH': str(Path(cfg['java_home']) / 'bin') + ':/usr/bin:/bin',
           'JAVA_HOME': cfg['java_home'], 'LANG': 'C', 'HOME': '/root'}
    for cmd, version in (([str(java_bin), '-version'], '21.0.13'),
                         ([str(mvn_bin), '-version'], '3.9.16'),
                         ([str(psql_bin), '--version'], '17.')):
        result = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=30)
        if result.returncode or version not in result.stdout + result.stderr:
            fail('Configured tool version does not match required pin')

    # DB names and roles
    if not DB_PATTERN.match(cfg['database']):
        fail(f"Invalid database name: {cfg['database']}")
    for r in ('owner_role', 'migrator_role', 'runtime_role'):
        if not ROLE_PATTERN.match(cfg[r]):
            fail(f"Invalid role name {r}: {cfg[r]}")

    if not isinstance(cfg['system_identifier'], str) or not SYSTEM_ID_PATTERN.fullmatch(cfg['system_identifier']):
        fail(f"Invalid system_identifier: {cfg['system_identifier']}")

    # Machine ID
    machine_id_file = Path('/etc/machine-id')
    if not machine_id_file.is_file():
        fail('/etc/machine-id not found on Linux host')
    actual_machine_id = machine_id_file.read_text(encoding='utf-8').strip()
    if cfg['machine_id'] != actual_machine_id:
        fail(f"Config machine_id {cfg['machine_id']} does not match local /etc/machine-id {actual_machine_id}")

    # Environment files validation
    validate_ancestors_root_owned(cfg['runtime_env_file'])
    validate_ancestors_root_owned(cfg['migration_env_file'])
    runtime_env = parse_env_file(cfg['runtime_env_file'], {'LAB_DB_URL', 'LAB_DB_USER', 'LAB_DB_PASSWORD'})
    migration_env = parse_env_file(cfg['migration_env_file'], {
        'LAB_MIGRATION_DB_URL', 'LAB_MIGRATION_DB_USER', 'LAB_MIGRATION_DB_PASSWORD', 'LAB_MIGRATION_OWNER'
    })

    expected_url = f"jdbc:postgresql://127.0.0.1:{cfg['pg_port']}/{cfg['database']}"
    if (runtime_env['LAB_DB_URL'] != expected_url or migration_env['LAB_MIGRATION_DB_URL'] != expected_url
            or runtime_env['LAB_DB_USER'] != cfg['runtime_role']
            or migration_env['LAB_MIGRATION_DB_USER'] != cfg['migrator_role']
            or migration_env['LAB_MIGRATION_OWNER'] != cfg['owner_role']):
        fail('Database environment does not match configured target')

    return cfg, cfg_p


def get_target_paths(cfg):
    root = Path(cfg['root'])
    return {
        'root': root,
        'marker': root / 'state' / 'target_marker.json',
        'lock': root / 'locks' / 'operation.lock',
        'releases': root / 'releases',
        'state': root / 'state',
        'locks': root / 'locks',
        'runtime_state': root / 'state' / 'runtime_state.json',
        'pending_journal': root / 'state' / 'pending_journal.json',
        'unit_name': f"lab-sales-{cfg['target_id']}.service",
        'unit_file': Path('/etc/systemd/system') / f"lab-sales-{cfg['target_id']}.service"
    }


def verify_target_ownership(cfg):
    paths = get_target_paths(cfg)
    validate_ancestors_root_owned(paths['root'])
    validate_file_security(paths['marker'], name='Target marker')
    marker = read_json(paths['marker'])
    expected_digest = digest_object(cfg)
    if marker.get('config_digest') != expected_digest:
        fail(f"Enrolled config digest {marker.get('config_digest')} does not match active config digest {expected_digest}")
    if marker.get('target_id') != cfg['target_id']:
        fail(f"Enrolled target_id {marker.get('target_id')} does not match config target_id {cfg['target_id']}")
    if marker.get('environment') != cfg['environment']:
        fail(f"Enrolled environment {marker.get('environment')} does not match config environment {cfg['environment']}")
    if marker.get('root') != str(paths['root']):
        fail(f"Enrolled root {marker.get('root')} does not match config root {paths['root']}")
    return marker


@contextmanager
def target_lock(cfg):
    paths = get_target_paths(cfg)
    lock_file = paths['lock']
    if not lock_file.exists():
        fail(f"Lock file does not exist at {lock_file}; enroll target first")
    validate_file_security(lock_file, name='Target lock')
    fd = os.open(str(lock_file), os.O_RDWR | os.O_NOFOLLOW)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (BlockingIOError, OSError) as e:
            fail(f"Target {cfg['target_id']} is locked by another operation: {e}")
        yield
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        except OSError:
            pass
        os.close(fd)


def run_psql(cfg, sql_command, db_name='postgres', timeout=30):
    psql_bin = str(Path(cfg['pg_bin']) / 'psql')
    cmd = [
        'runuser', '-u', 'postgres', '--',
        psql_bin,
        '-h', '/var/run/postgresql',
        '-p', str(cfg['pg_port']),
        '-d', db_name,
        '-X', '-t', '-A', '-v', 'ON_ERROR_STOP=1',
        '-c', sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                         env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C'})
    if res.returncode != 0:
        fail(f"PostgreSQL query failed (code {res.returncode})")
    return res.stdout.strip()


def verify_db_cluster_and_ownership(cfg):
    identity = run_psql(cfg, "SELECT current_user || '|' || system_identifier::text FROM pg_control_system();")
    if identity != 'postgres|' + str(cfg['system_identifier']):
        fail('PostgreSQL local administrator or cluster identity mismatch')
    db_owner = run_psql(cfg, f"SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_database WHERE datname='{cfg['database']}';")
    if db_owner != 'postgres':
        fail('Configured database is absent or not owned by postgres')
    schema_owners = run_psql(cfg, "SELECT string_agg(nspname || '=' || pg_get_userbyid(nspowner), ',' ORDER BY nspname) FROM pg_namespace WHERE nspname IN ('app','extensions');", db_name=cfg['database'])
    if schema_owners != 'app=' + cfg['owner_role'] + ',extensions=postgres':
        fail('Configured schema owners do not match target')
    if len({cfg['owner_role'], cfg['migrator_role'], cfg['runtime_role'], 'postgres'}) != 4:
        fail('Database roles must be distinct')
    role_names = ','.join(database.literal(cfg[r]) for r in ('owner_role', 'migrator_role', 'runtime_role'))
    roles = run_psql(cfg, "SELECT string_agg(rolname || ':' || rolcanlogin::int || ':' || (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)::int, ',' ORDER BY rolname) FROM pg_roles WHERE rolname IN (" + role_names + ");", db_name=cfg['database'])
    expected = ','.join(sorted([cfg['owner_role'] + ':0:0', cfg['migrator_role'] + ':1:0', cfg['runtime_role'] + ':1:0']))
    if roles != expected:
        fail('Configured database roles have unexpected attributes')
    memberships = run_psql(cfg, "SELECT string_agg(member.rolname || '>' || parent.rolname, ',' ORDER BY member.rolname,parent.rolname) FROM pg_auth_members m JOIN pg_roles member ON member.oid=m.member JOIN pg_roles parent ON parent.oid=m.roleid WHERE member.rolname IN (" + role_names + ");", db_name=cfg['database'])
    if memberships != cfg['migrator_role'] + '>' + cfg['owner_role']:
        fail('Configured database role membership mismatch')


def verify_unit_ownership(cfg, expected_sha=None, allow_unloaded=False):
    paths = get_target_paths(cfg)
    unit = paths['unit_file']
    result = subprocess.run(['systemctl', 'show', paths['unit_name'], '--property=FragmentPath', '--value'], capture_output=True, text=True)
    fragment = result.stdout.strip()
    dropins = subprocess.run(['systemctl', 'show', paths['unit_name'], '--property=DropInPaths', '--value'], capture_output=True, text=True)
    if dropins.stdout.strip():
        fail('Systemd drop-ins are not owned by target')
    if unit.is_symlink():
        fail('Systemd unit is a symlink')
    if not unit.exists():
        if fragment:
            fail('Unexpected systemd fragment for target')
        return False
    validate_file_security(unit, expected_mode=0o644, name='Systemd unit')
    if not expected_sha or compute_file_sha256(unit) != expected_sha:
        fail('Systemd unit does not match recorded ownership')
    if fragment != str(unit):
        if not allow_unloaded or fragment or get_systemd_main_pid(paths['unit_name'])[1] not in ('inactive', 'unknown'):
            fail('Systemd fragment does not match recorded unit')
    return True


def action_enroll(cfg):
    paths = get_target_paths(cfg)
    root = paths['root']

    # Must be executed as root
    if os.geteuid() != 0:
        fail('deploy_linux.py must be run as root (UID 0)')

    # Refuse preexisting unowned service unit
    if paths['unit_file'].exists() or paths['unit_file'].is_symlink():
        fail('Service unit already exists')
    verify_unit_ownership(cfg)
    verify_db_cluster_and_ownership(cfg)

    if root.exists() and (not root.is_dir() or any(root.iterdir())):
        fail('Target root is not empty')

    # Check ancestors root-owned
    validate_ancestors_root_owned(root.parent)

    # Service user gid
    pw = pwd.getpwnam(cfg['service_user'])
    svc_uid = pw.pw_uid
    svc_gid = pw.pw_gid

    # Create root directory structure
    root.mkdir(parents=True, exist_ok=True)
    os.chmod(root, 0o755)
    os.chown(root, 0, 0)

    paths['locks'].mkdir(parents=True, exist_ok=True)
    os.chmod(paths['locks'], 0o700)
    os.chown(paths['locks'], 0, 0)

    paths['state'].mkdir(parents=True, exist_ok=True)
    os.chmod(paths['state'], 0o700)
    os.chown(paths['state'], 0, 0)

    paths['releases'].mkdir(parents=True, exist_ok=True)
    # Service user needs to traverse releases to read JAR
    os.chmod(paths['releases'], 0o755)
    os.chown(paths['releases'], 0, 0)

    # Initialize empty lock file
    lock_file = paths['lock']
    if not lock_file.exists():
        lock_file.touch(mode=0o600)
        os.chown(lock_file, 0, 0)

    with target_lock(cfg):
        if paths['marker'].exists() or paths['runtime_state'].exists():
            fail('Target enrollment state already exists')
        # Verify DB cluster is reachable and identities match
        verify_db_cluster_and_ownership(cfg)

        marker = {
            'target_id': cfg['target_id'],
            'environment': cfg['environment'],
            'root': str(root),
            'config_digest': digest_object(cfg),
            'enrolled_at_ns': time.time_ns(),
            'machine_id': cfg['machine_id'],
            'system_identifier': str(cfg['system_identifier']),
            'service_user': cfg['service_user'],
            'port': cfg['port'],
            'database': cfg['database'],
            'owner_role': cfg['owner_role'],
            'migrator_role': cfg['migrator_role'],
            'runtime_role': cfg['runtime_role']
        }
        atomic_write(paths['marker'], canonical_json_bytes(marker), mode=0o600)
        # Initialize empty runtime state
        runtime_state = {
            'target_id': cfg['target_id'],
            'accepted_release': None,
            'accepted_unit_sha256': None,
            'history': []
        }
        atomic_write(paths['runtime_state'], canonical_json_bytes(runtime_state), mode=0o600)

    print(f"Target {cfg['target_id']} enrolled successfully at {root}")
    return 0


def check_port_not_occupied_by_foreign_process(port, expected_pid=None):
    # Verify via socket binding or listening PID
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
        s.bind(('127.0.0.1', port))
        s.close()
        return None
    except OSError:
        # Port is listening; find PID
        pass
    s.close()

    # Read listening socket owner from /proc/net/tcp
    # Or ss/lsof
    pid = find_pid_listening_on_port(port)
    if expected_pid is not None and pid == expected_pid:
        return pid
    fail(f"Port {port} is occupied by foreign process (PID {pid}); refusing to reuse or kill foreign listener")


def find_pid_listening_on_port(port):
    res = subprocess.run(['ss', '-H', '-tlpn', f'sport = :{port}'], capture_output=True, text=True)
    if res.returncode:
        fail('Unable to inspect listening socket ownership')
    rows = res.stdout.splitlines()
    if not rows:
        return None
    owners = []
    for row in rows:
        matches = re.findall(r'pid=(\d+)', row)
        if len(matches) != 1:
            fail('Ambiguous socket ownership')
        owners.append(int(matches[0]))
    if len(owners) != 1:
        fail('Multiple listeners on configured port')
    return owners[0]


def get_systemd_main_pid(unit_name):
    cmd = ['systemctl', 'show', unit_name, '--property=MainPID', '--property=ActiveState']
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        return 0, 'unknown'
    main_pid = 0
    state = 'unknown'
    for line in res.stdout.strip().splitlines():
        if line.startswith('MainPID='):
            try:
                main_pid = int(line.split('=', 1)[1])
            except ValueError:
                pass
        elif line.startswith('ActiveState='):
            state = line.split('=', 1)[1].strip()
    return main_pid, state


def action_migrate(cfg, release_dir, revision, run_id, manifest_sha):
    paths = get_target_paths(cfg)
    verify_target_ownership(cfg)

    # Release validation
    rel_meta = release.verify_release(release_dir, revision, run_id, manifest_sha)
    if rel_meta['schema_versions'] != list(database.SCHEMA_VERSIONS):
        fail('Only the current exact schema lineage may be migrated')

    with target_lock(cfg):
        verify_db_cluster_and_ownership(cfg)
        if paths['pending_journal'].exists():
            fail('Pending rollout requires recovery')
        runtime_state = read_json(paths['runtime_state'])
        verify_unit_ownership(cfg, runtime_state.get('accepted_unit_sha256'))

        # Check service status:
        main_pid, state = get_systemd_main_pid(paths['unit_name'])
        is_running = (state == 'active' and main_pid > 0)
        if not is_running and (main_pid or state not in ('inactive', 'failed')):
            fail('Service state is not safely inactive')

        history_exists = run_psql(cfg, "SELECT to_regclass('app.flyway_schema_history') IS NOT NULL;", db_name=cfg['database'])
        applied_versions = ''
        if history_exists == 't':
            applied_versions = run_psql(cfg, "SELECT COALESCE(string_agg(version, ',' ORDER BY installed_rank), '') FROM app.flyway_schema_history WHERE success AND type='SQL';", db_name=cfg['database'])
            bad = run_psql(cfg, "SELECT count(*) FROM app.flyway_schema_history WHERE NOT success OR type NOT IN ('SQL','SCHEMA') OR (type='SQL' AND (version IS NULL OR script IS NULL OR checksum IS NULL OR (version,script) NOT IN (('1','V1__identity.sql'),('2','V2__masters.sql'),('3','V3__finance.sql'),('4','V4__procurement_inventory_ap.sql'),('5','V5__local_crm_goods_o2c.sql'))));", db_name=cfg['database'])
            if applied_versions not in ('1,2,3,4', '1,2,3,4,5') or bad != '0':
                fail('Partial or unexpected Flyway history')
        else:
            objects = run_psql(cfg, "SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='app') + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='app') + (SELECT count(*) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='app' AND t.typrelid=0);", db_name=cfg['database'])
            if objects != '0':
                fail('Fresh application schema is not empty')

        foreign_schemas = run_psql(cfg,
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
            "WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('app','extensions','pg_catalog','information_schema') "
            "AND n.nspname NOT LIKE 'pg_toast%';",
            db_name=cfg['database']
        )
        if foreign_schemas != '0':
            fail("Foreign or unowned tables found in database; refusing migration")
        if applied_versions != '1,2,3,4,5' and (main_pid or state not in ('inactive', 'failed')):
            fail('Service must be inactive for schema migration')
        dest = paths['releases'] / manifest_sha
        copy_release_immutable(Path(release_dir), dest)
        release.verify_release(str(dest), revision, run_id, manifest_sha)
        release_dir = str(dest)

        if applied_versions == '1,2,3,4,5':
            run_flyway(cfg, Path(release_dir) / 'migration' / 'pom.xml', ['flyway:validate'])
        else:
            if applied_versions == '1,2,3,4':
                run_flyway(cfg, Path(release_dir) / 'migration' / 'pom.xml', ['-Dflyway.target=4', 'flyway:validate'])
            run_flyway(cfg, Path(release_dir) / 'migration' / 'pom.xml', ['flyway:migrate', 'flyway:validate'])
        require_schema_compatible(cfg, release_dir)

        if not is_running:
            grant_runtime_privileges(cfg)
        prove_runtime(cfg)

        # Store migration receipt
        receipt = {
            'target_id': cfg['target_id'],
            'environment': cfg['environment'],
            'database': cfg['database'],
            'manifest_sha256': manifest_sha,
            'revision': revision,
            'run_id': run_id,
            'schema_versions': rel_meta['schema_versions'],
            'system_identifier': str(cfg['system_identifier']),
            'migrated_at_ns': time.time_ns()
        }
        receipt_path = paths['state'] / f"migrate_receipt_{manifest_sha}.json"
        atomic_write(receipt_path, canonical_json_bytes(receipt), mode=0o600)

    print(f"Migration completed and receipt stored at {receipt_path}")
    return 0


def run_flyway(cfg, pom_path, goals):
    if not pom_path.is_file():
        fail(f"Migration pom.xml not found at {pom_path}")

    # Read migration env file
    mig_env_data = parse_env_file(cfg['migration_env_file'], {
        'LAB_MIGRATION_DB_URL', 'LAB_MIGRATION_DB_USER', 'LAB_MIGRATION_DB_PASSWORD', 'LAB_MIGRATION_OWNER'
    })

    env = {
        'JAVA_HOME': cfg['java_home'],
        'PATH': f"{Path(cfg['java_home']) / 'bin'}:/usr/bin:/bin",
        'HOME': '/root',
        'LANG': 'C',
        'LAB_MIGRATION_DB_URL': mig_env_data['LAB_MIGRATION_DB_URL'],
        'LAB_MIGRATION_DB_USER': mig_env_data['LAB_MIGRATION_DB_USER'],
        'LAB_MIGRATION_DB_PASSWORD': mig_env_data['LAB_MIGRATION_DB_PASSWORD'],
        'LAB_MIGRATION_OWNER': mig_env_data['LAB_MIGRATION_OWNER']
    }

    mvn_bin = str(Path(cfg['maven_home']) / 'bin' / 'mvn')
    cmd = [mvn_bin, '--batch-mode', '--no-transfer-progress', '-f', str(pom_path)] + goals
    res = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=300)
    if res.returncode != 0:
        fail(f"Flyway command failed (code {res.returncode})")


def grant_runtime_privileges(cfg):
    user = f'"{cfg["runtime_role"]}"'
    statements = [
        f'REVOKE ALL ON ALL TABLES IN SCHEMA app FROM {user};',
        f'REVOKE ALL ON ALL SEQUENCES IN SCHEMA app FROM {user};',
        f'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA app FROM {user};',
        'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA app FROM PUBLIC;'
    ]
    for table in database.READ_ONLY:
        statements.append(f'GRANT SELECT ON app."{table}" TO {user};')
    for tables, privileges in ((database.INSERT_ONLY, 'SELECT,INSERT'), (database.MUTABLE, 'SELECT,INSERT,UPDATE')):
        for table in tables:
            statements.append(f'GRANT {privileges} ON app."{table}" TO {user};')
            seq = run_psql(cfg,
                f"SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='app' AND table_name='{table}' AND column_name='id' AND is_identity='YES') THEN pg_get_serial_sequence('app.{table}','id') END;",
                db_name=cfg['database']
            )
            if seq:
                if not re.fullmatch(r'app\.[a-z_][a-z0-9_]*', seq):
                    fail(f"Unexpected sequence name: {seq}")
                statements.append(f'GRANT USAGE,SELECT ON SEQUENCE {seq} TO {user};')
    for function in database.TRIGGER_FUNCTIONS:
        statements.append(f'GRANT EXECUTE ON FUNCTION app."{function}"() TO {user};')

    owner_user = f'"{cfg["owner_role"]}"'
    revoke_defaults = f'ALTER DEFAULT PRIVILEGES FOR ROLE {owner_user} REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;'

    full_sql = 'BEGIN;SET ROLE ' + owner_user + ';' + ''.join(statements) + revoke_defaults + 'COMMIT;'
    run_psql(cfg, full_sql, db_name=cfg['database'])

def prove_runtime(cfg):
    values = parse_env_file(cfg['runtime_env_file'], {'LAB_DB_URL', 'LAB_DB_USER', 'LAB_DB_PASSWORD'})
    checks = ["current_user=" + database.literal(cfg['runtime_role']),
              "NOT has_database_privilege(current_user,current_database(),'CREATE')",
              "NOT has_database_privilege(current_user,current_database(),'TEMP')",
              "NOT has_schema_privilege(current_user,'app','CREATE')",
              "NOT has_schema_privilege(current_user,'public','CREATE')"]
    for tables, allowed in ((database.READ_ONLY, {'SELECT'}),
                            (database.INSERT_ONLY, {'SELECT', 'INSERT'}),
                            (database.MUTABLE, {'SELECT', 'INSERT', 'UPDATE'})):
        for table in tables:
            for privilege in ('SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'):
                expression = "has_table_privilege(current_user," + database.literal('app.' + table) + "," + database.literal(privilege) + ")"
                checks.append(expression if privilege in allowed else 'NOT ' + expression)
    for function in database.TRIGGER_FUNCTIONS:
        checks.append("has_function_privilege(current_user," + database.literal('app.' + function + '()') + ",'EXECUTE')")
    command = [str(Path(cfg['pg_bin']) / 'psql'), '-X', '-h', '127.0.0.1', '-p', str(cfg['pg_port']),
               '-U', cfg['runtime_role'], '-d', cfg['database'], '-t', '-A', '-v', 'ON_ERROR_STOP=1',
               '-c', 'SELECT ' + ' AND '.join(checks)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=30,
                            env={'PATH': '/usr/bin:/bin', 'PGPASSWORD': values['LAB_DB_PASSWORD'], 'LANG': 'C'})
    if result.returncode or result.stdout.strip() != 't':
        fail('Runtime role or schema privilege proof failed')


def action_bootstrap(cfg, release_dir, revision, run_id, manifest_sha, bootstrap_file):
    paths = get_target_paths(cfg)
    verify_target_ownership(cfg)

    # Validate release metadata
    rel_meta = release.verify_release(release_dir, revision, run_id, manifest_sha)

    # Validate bootstrap file security
    b_path = validate_file_security(bootstrap_file, expected_mode=0o600, expected_uid=0, name='Bootstrap file')
    validate_ancestors_root_owned(b_path)

    with open(b_path, 'r', encoding='utf-8') as f:
        b_data = json.load(f, object_pairs_hook=ci.unique_object)

    req_keys = {'login', 'password', 'workspace_code', 'workspace_name', 'business_date'}
    if not isinstance(b_data, dict) or set(b_data) != req_keys or any(not isinstance(v, str) or not v for v in b_data.values()):
        fail(f"Bootstrap file must contain exactly keys {req_keys}")

    if len(b_data['password']) < 16:
        fail("Bootstrap password must be at least 16 characters")
    if not re.fullmatch(r'^[0-9]{4}-[0-9]{2}-[0-9]{2}$', b_data['business_date']):
        fail("Bootstrap business_date must be YYYY-MM-DD")

    with target_lock(cfg):
        verify_db_cluster_and_ownership(cfg)

        check_receipt(cfg, 'migrate', manifest_sha, revision, run_id)
        if paths['pending_journal'].exists():
            fail('Pending rollout requires recovery')
        dest = paths['releases'] / manifest_sha
        copy_release_immutable(Path(release_dir), dest)
        release.verify_release(str(dest), revision, run_id, manifest_sha)
        jar_path = dest / 'app' / 'application.jar'

        rt_env_data = parse_env_file(cfg['runtime_env_file'], {'LAB_DB_URL', 'LAB_DB_USER', 'LAB_DB_PASSWORD'})
        boot_env = {
            'JAVA_HOME': cfg['java_home'],
            'PATH': f"{Path(cfg['java_home']) / 'bin'}:/usr/bin:/bin",
            'LAB_DB_URL': rt_env_data['LAB_DB_URL'],
            'LAB_DB_USER': rt_env_data['LAB_DB_USER'],
            'LAB_DB_PASSWORD': rt_env_data['LAB_DB_PASSWORD'],
            'LAB_REVISION': revision,
            'LAB_BOOTSTRAP_LOGIN': b_data['login'],
            'LAB_BOOTSTRAP_PASSWORD': b_data['password'],
            'LAB_BOOTSTRAP_WORKSPACE_CODE': b_data['workspace_code'],
            'LAB_BOOTSTRAP_WORKSPACE_NAME': b_data['workspace_name'],
            'LAB_BOOTSTRAP_BUSINESS_DATE': b_data['business_date']
        }

        # Run as service user
        java_bin = str(Path(cfg['java_home']) / 'bin' / 'java')
        pw = pwd.getpwnam(cfg['service_user'])

        # Prepare safe subprocess execution
        def demote():
            os.setgroups([])
            os.setgid(pw.pw_gid)
            os.setuid(pw.pw_uid)

        cmd = [java_bin, '-Xmx384m', '-jar', str(jar_path), '--bootstrap-admin']
        res = subprocess.run(cmd, env=boot_env, preexec_fn=demote, capture_output=True, text=True, timeout=120)
        if res.returncode != 0:
            fail(f"Bootstrap invocation failed (code {res.returncode})")
        setup_receipt = initial_admin_receipt(cfg)

        # Store bootstrap receipt (without password!)
        intent_hash = hashlib.sha256(canonical_json_bytes({
            'login': b_data['login'],
            'workspace_code': b_data['workspace_code'],
            'workspace_name': b_data['workspace_name'],
            'business_date': b_data['business_date']
        })).hexdigest()

        b_receipt = {
            'target_id': cfg['target_id'],
            'environment': cfg['environment'],
            'database': cfg['database'],
            'config_digest': digest_object(cfg),
            'setup_receipt': setup_receipt,
            'manifest_sha256': manifest_sha,
            'revision': revision,
            'run_id': run_id,
            'intent_hash': intent_hash,
            'login': b_data['login'],
            'workspace_code': b_data['workspace_code'],
            'business_date': b_data['business_date'],
            'system_identifier': str(cfg['system_identifier']),
            'bootstrapped_at_ns': time.time_ns()
        }
        b_receipt_path = paths['state'] / 'bootstrap_receipt.json'
        atomic_write(b_receipt_path, canonical_json_bytes(b_receipt), mode=0o600)

    print(f"Bootstrap completed successfully; receipt stored at {b_receipt_path}")
    return 0


def render_systemd_unit(cfg, release_jar_path, revision):
    # WorkingDirectory takes a single path, unlike systemd's command-line directives.
    # Validated paths reject control characters, percent specifiers and escapes.
    java_bin = str(Path(cfg['java_home']) / 'bin' / 'java')
    working_dir = str(cfg['root'])
    env_file = str(cfg['runtime_env_file'])

    return f"""[Unit]
Description=Lab Sales Systems ({cfg['target_id']})
After=network.target
# TARGET_ID: {cfg['target_id']}

[Service]
Type=simple
User={cfg['service_user']}
WorkingDirectory={working_dir}
EnvironmentFile={env_file}
Environment="LAB_REVISION={revision}"
Environment="LAB_BIND_ADDRESS=127.0.0.1"
Environment="LAB_PORT={cfg['port']}"
UnsetEnvironment=JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH
UnsetEnvironment=LAB_MIGRATION_DB_URL LAB_MIGRATION_DB_USER LAB_MIGRATION_DB_PASSWORD LAB_MIGRATION_OWNER
UnsetEnvironment=LAB_BOOTSTRAP_LOGIN LAB_BOOTSTRAP_PASSWORD LAB_BOOTSTRAP_WORKSPACE_CODE LAB_BOOTSTRAP_WORKSPACE_NAME LAB_BOOTSTRAP_BUSINESS_DATE
ExecStart="{java_bin}" -Xmx384m -jar "{str(release_jar_path)}"
Restart=on-failure
RestartSec=5s
TimeoutStopSec=20s
KillMode=control-group

NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
"""


def verify_functional_readiness(port, expected_revision, expected_schema, timeout=60):
    start_time = time.monotonic()
    health_url = f"http://127.0.0.1:{port}/health"
    login_url = f"http://127.0.0.1:{port}/login"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    health_passed = False
    login_passed = False

    while time.monotonic() - start_time < timeout:
        if not health_passed:
            try:
                req = urllib.request.Request(health_url)
                with opener.open(req, timeout=3) as resp:
                    if resp.status == 200:
                        body = json.loads(resp.read().decode('utf-8'))
                        if (body.get('status') == 'UP' and
                                body.get('application') == 'lab-sales-systems' and
                                body.get('schemaVersion') == expected_schema and
                                body.get('revision') == expected_revision):
                            health_passed = True
            except Exception:
                pass

        if health_passed and not login_passed:
            try:
                req = urllib.request.Request(login_url)
                with opener.open(req, timeout=3) as resp:
                    if resp.status == 200:
                        content = resp.read().decode('utf-8', errors='ignore')
                        if 'action="/login"' in content or 'name="username"' in content:
                            login_passed = True
            except Exception:
                pass

        if health_passed and login_passed:
            return True

        time.sleep(1)

    fail(f"Readiness check timed out after {timeout}s (health={health_passed}, login={login_passed})")

def check_receipt(cfg, kind, sha, revision, run_id):
    path = get_target_paths(cfg)['state'] / f'{kind}_receipt_{sha}.json'
    validate_file_security(path, name='Receipt')
    receipt = read_json(path)
    expected = {'target_id': cfg['target_id'], 'environment': cfg['environment'],
                'database': cfg['database'], 'system_identifier': str(cfg['system_identifier']),
                'manifest_sha256': sha, 'revision': revision, 'run_id': run_id}
    if any(receipt.get(key) != value for key, value in expected.items()):
        fail('Receipt identity does not match release and target')
    return receipt


def initial_admin_receipt(cfg):
    value = run_psql(cfg,
        "SELECT json_build_object('id',id,'user_id',user_id,'workspace_id',workspace_id,"
        "'payload_hash',payload_hash)::text FROM app.setup_receipts WHERE receipt_key='INITIAL_ADMIN';",
        db_name=cfg['database'])
    if not value:
        fail('Bootstrap database receipt missing')
    return json.loads(value)


def verify_bootstrap_receipt(cfg):
    path = get_target_paths(cfg)['state'] / 'bootstrap_receipt.json'
    validate_file_security(path, name='Target bootstrap receipt')
    receipt = read_json(path)
    expected = {'target_id': cfg['target_id'], 'environment': cfg['environment'],
                'database': cfg['database'], 'system_identifier': str(cfg['system_identifier']),
                'config_digest': digest_object(cfg)}
    if any(receipt.get(key) != value for key, value in expected.items()):
        fail('Bootstrap receipt identity does not match target')
    if receipt.get('setup_receipt') != initial_admin_receipt(cfg):
        fail('Bootstrap database receipt does not match attested setup')


def previous_distinct_release(state):
    current = state.get('accepted_release')
    if current:
        identity = (current['revision'], current['jar_sha256'])
        for prior in reversed(state.get('history', [])):
            if (prior['revision'], prior['jar_sha256']) != identity:
                return prior
    fail('No previous distinct compatible release in target history to roll back to')


def require_schema_compatible(cfg, release_dir):
    expected = release.schema_identity(Path(release_dir) / 'app' / 'application.jar')
    actual = run_psql(cfg,
        "SELECT COALESCE(string_agg(version || '|' || script || '|' || checksum::text, ',' ORDER BY installed_rank), '') "
        "FROM app.flyway_schema_history WHERE success AND type='SQL';", db_name=cfg['database'])
    bad = run_psql(cfg, "SELECT count(*) FROM app.flyway_schema_history "
        "WHERE NOT success OR type NOT IN ('SQL','SCHEMA') OR (type='SQL' AND "
        "(version IS NULL OR script IS NULL OR checksum IS NULL));", db_name=cfg['database'])
    if bad != '0' or actual != expected:
        fail('Release is incompatible with the applied database schema; refusing rollout or recovery')


def copy_release_immutable(src_release_dir, dest_release_dir):
    if not dest_release_dir.exists():
        tmp_dest = dest_release_dir.with_name(f"{dest_release_dir.name}.tmp.{os.getpid()}")
        shutil.copytree(src_release_dir, tmp_dest, symlinks=True)
        for root, dirs, files in os.walk(tmp_dest):
            for name in dirs + files:
                p = Path(root) / name
                if p.is_symlink():
                    fail('Symlink in release')
                os.chmod(p, 0o755 if p.is_dir() else 0o644)
                os.chown(p, 0, 0)
        os.chmod(tmp_dest, 0o755)
        os.chown(tmp_dest, 0, 0)
        os.rename(tmp_dest, dest_release_dir)
    validate_ancestors_root_owned(dest_release_dir)
    for root, dirs, files in os.walk(dest_release_dir):
        for name in dirs + files:
            p = Path(root) / name
            if p.is_symlink() or p.stat().st_uid != 0 or p.stat().st_mode & 0o022:
                fail('Untrusted installed release')


def systemctl(*args):
    subprocess.run(['systemctl', *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def ready_owned(cfg, revision, schema_version):
    verify_functional_readiness(cfg['port'], revision, schema_version)
    pid, state = get_systemd_main_pid(get_target_paths(cfg)['unit_name'])
    if state != 'active' or not pid or find_pid_listening_on_port(cfg['port']) != pid:
        fail('Service and listener ownership mismatch')
    return pid


def rollout_locked(cfg, release_dir, revision, run_id, manifest_sha):
    paths = get_target_paths(cfg)
    verify_target_ownership(cfg)
    verify_db_cluster_and_ownership(cfg)
    if paths['pending_journal'].exists():
        fail('Pending rollout requires recovery')
    rel_meta = release.verify_release(release_dir, revision, run_id, manifest_sha)
    require_schema_compatible(cfg, release_dir)
    check_receipt(cfg, 'migrate', manifest_sha, revision, run_id)
    verify_bootstrap_receipt(cfg)
    validate_file_security(paths['runtime_state'], name='Runtime state')
    state = read_json(paths['runtime_state'])
    previous = state.get('accepted_release')
    old_hash = state.get('accepted_unit_sha256')
    owned = verify_unit_ownership(cfg, old_hash)
    if bool(previous) != owned:
        fail('Accepted state and unit disagree')
    pid, status = get_systemd_main_pid(paths['unit_name'])
    check_port_not_occupied_by_foreign_process(cfg['port'], pid if owned and status == 'active' and pid else None)
    dest = paths['releases'] / manifest_sha
    copy_release_immutable(Path(release_dir), dest)
    release.verify_release(str(dest), revision, run_id, manifest_sha)
    jar = dest / 'app' / 'application.jar'
    unit = render_systemd_unit(cfg, jar, revision)
    unit_sha = hashlib.sha256(unit.encode()).hexdigest()
    journal = {'target_id': cfg['target_id'], 'config_digest': digest_object(cfg),
               'candidate_unit_sha256': unit_sha, 'previous_state': json.loads(json.dumps(state))}
    atomic_write(paths['pending_journal'], canonical_json_bytes(journal))
    try:
        atomic_write(paths['unit_file'], unit, mode=0o644)
        systemctl('daemon-reload')
        verify_unit_ownership(cfg, unit_sha)
        systemctl('restart', paths['unit_name'])
        pid = ready_owned(cfg, revision, int(rel_meta['schema_versions'][-1]))
        systemctl('enable', paths['unit_name'])
        accepted = {'manifest_sha256': manifest_sha, 'revision': revision, 'run_id': run_id,
                    'jar_sha256': compute_file_sha256(jar), 'main_pid': pid}
        state['accepted_release'] = accepted
        state['accepted_unit_sha256'] = unit_sha
        state['history'].append(accepted)
        atomic_write(paths['runtime_state'], canonical_json_bytes(state))
        durable_unlink(paths['pending_journal'])
    except Exception:
        rollback_on_failed_deploy(cfg, paths, journal)
        fail('Rollout failed; previous state recovered')
    return 0


def action_deploy(cfg, release_dir, revision, run_id, manifest_sha):
    with target_lock(cfg):
        return rollout_locked(cfg, release_dir, revision, run_id, manifest_sha)


def rollback_on_failed_deploy(cfg, paths, journal):
    if journal.get('target_id') != cfg['target_id'] or journal.get('config_digest') != digest_object(cfg):
        fail('Pending journal identity mismatch')
    previous_state = journal['previous_state']
    previous = previous_state.get('accepted_release')
    old_hash = previous_state.get('accepted_unit_sha256')
    unit = paths['unit_file']
    if unit.exists():
        current_hash = compute_file_sha256(unit)
        if current_hash not in (old_hash, journal['candidate_unit_sha256']):
            fail('Pending unit ownership mismatch')
        verify_unit_ownership(cfg, current_hash, allow_unloaded=True)
    elif previous:
        fail('Pending previous unit missing')
    if previous:
        sha, revision, run_id = previous['manifest_sha256'], previous['revision'], previous['run_id']
        dest = paths['releases'] / sha
        # Never leave a failed candidate running when its predecessor cannot use the migrated schema.
        if unit.exists() and compute_file_sha256(unit) == journal['candidate_unit_sha256']:
            pid, status = get_systemd_main_pid(paths['unit_name'])
            if pid or status not in ('inactive', 'failed'):
                systemctl('stop', paths['unit_name'])
                pid, status = get_systemd_main_pid(paths['unit_name'])
            if pid or status not in ('inactive', 'failed'):
                fail('Cannot prove failed candidate service is stopped')
        previous_meta = release.verify_release(str(dest), revision, run_id, sha)
        require_schema_compatible(cfg, dest)
        text = render_systemd_unit(cfg, dest / 'app' / 'application.jar', revision)
        if hashlib.sha256(text.encode()).hexdigest() != old_hash:
            fail('Previous unit identity mismatch')
        atomic_write(unit, text, mode=0o644)
        systemctl('daemon-reload')
        verify_unit_ownership(cfg, old_hash)
        systemctl('restart', paths['unit_name'])
        ready_owned(cfg, revision, int(previous_meta['schema_versions'][-1]))
    elif unit.exists():
        fragment = subprocess.run(['systemctl', 'show', paths['unit_name'], '--property=FragmentPath', '--value'], capture_output=True, text=True).stdout.strip()
        if fragment:
            pid, state = get_systemd_main_pid(paths['unit_name'])
            if state in ('active', 'activating', 'reloading', 'deactivating') or pid:
                systemctl('stop', paths['unit_name'])
                pid, state = get_systemd_main_pid(paths['unit_name'])
            if pid or state not in ('inactive', 'failed'):
                fail('Cannot prove failed candidate service is stopped')
            enabled = subprocess.run(['systemctl', 'is-enabled', paths['unit_name']],
                                     capture_output=True, text=True).stdout.strip()
            if enabled == 'enabled':
                systemctl('disable', paths['unit_name'])
            elif enabled != 'disabled':
                fail('Cannot prove failed candidate service is disabled')
        unit.unlink()
        systemctl('daemon-reload')
    atomic_write(paths['runtime_state'], canonical_json_bytes(previous_state))
    durable_unlink(paths['pending_journal'])


def action_status(cfg):
    paths = get_target_paths(cfg)
    verify_target_ownership(cfg)
    verify_db_cluster_and_ownership(cfg)
    validate_file_security(paths['runtime_state'], name='Runtime state')
    verify_unit_ownership(cfg, read_json(paths['runtime_state']).get('accepted_unit_sha256'))

    # Read-only status inspection
    main_pid, state = get_systemd_main_pid(paths['unit_name'])
    listener_pid = find_pid_listening_on_port(cfg['port'])

    r_state = read_json(paths['runtime_state']) if paths['runtime_state'].exists() else {}
    accepted = r_state.get('accepted_release')

    status_obj = {
        'target_id': cfg['target_id'],
        'environment': cfg['environment'],
        'systemd_state': state,
        'main_pid': main_pid,
        'listener_pid': listener_pid,
        'accepted_release': accepted,
        'pending_journal': paths['pending_journal'].exists()
    }

    if state == 'active':
        if not accepted or listener_pid != main_pid or not main_pid or paths['pending_journal'].exists():
            fail('Active service identity does not match accepted state')
        rel = paths['releases'] / accepted['manifest_sha256']
        rel_meta = release.verify_release(str(rel), accepted['revision'], accepted['run_id'], accepted['manifest_sha256'])
        if compute_file_sha256(rel / 'app' / 'application.jar') != accepted['jar_sha256']:
            fail('Accepted application digest mismatch')
        ready_owned(cfg, accepted['revision'], int(rel_meta['schema_versions'][-1]))
        status_obj['ready'] = True

    print(json.dumps(status_obj, indent=2, sort_keys=True))
    return 0


def action_stop(cfg):
    paths = get_target_paths(cfg)
    verify_target_ownership(cfg)

    with target_lock(cfg):
        verify_target_ownership(cfg)
        verify_db_cluster_and_ownership(cfg)
        if paths['pending_journal'].exists():
            fail('Pending rollout requires recovery')
        state = read_json(paths['runtime_state'])
        if not verify_unit_ownership(cfg, state.get('accepted_unit_sha256')):
            fail('No owned service unit')
        systemctl('stop', paths['unit_name'])
        systemctl('disable', paths['unit_name'])
        print(f"Service {paths['unit_name']} stopped and disabled.")
    return 0


def action_recover(cfg):
    paths = get_target_paths(cfg)
    verify_target_ownership(cfg)

    with target_lock(cfg):
        verify_target_ownership(cfg)
        verify_db_cluster_and_ownership(cfg)
        if not paths['pending_journal'].exists():
            print("No pending journal found; target state is consistent.")
            return 0

        validate_file_security(paths['pending_journal'], name='Pending journal')
        journal = read_json(paths['pending_journal'])
        if journal.get('target_id') != cfg['target_id']:
            fail("Pending journal target_id does not match config")

        print("Interrupted rollout detected in pending journal; recovering previous accepted state...")
        rollback_on_failed_deploy(cfg, paths, journal)
        print("Recovery completed.")
    return 0


def action_rollback(cfg):
    paths = get_target_paths(cfg)
    verify_target_ownership(cfg)

    with target_lock(cfg):
        verify_db_cluster_and_ownership(cfg)
        r_state = read_json(paths['runtime_state'])
        prior = previous_distinct_release(r_state)
        prior_sha = prior['manifest_sha256']
        prior_rev = prior['revision']
        prior_run_id = prior['run_id']
        prior_rel_dir = paths['releases'] / prior_sha

        if not prior_rel_dir.is_dir():
            fail(f"Previous release directory not found at {prior_rel_dir}")

        # Deploy prior release
        return rollout_locked(cfg, str(prior_rel_dir), prior_rev, prior_run_id, prior_sha)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=[
        'enroll', 'migrate', 'bootstrap', 'deploy', 'status', 'stop', 'rollback', 'recover'
    ])
    parser.add_argument('--config', required=True, help='Absolute path to target config JSON')
    parser.add_argument('--environment', required=True, choices=['staging', 'production'])
    parser.add_argument('--release-dir', help='Absolute path to verified release directory')
    parser.add_argument('--revision', help='Release source revision')
    parser.add_argument('--run-id', help='Release run ID')
    parser.add_argument('--manifest-sha256', help='External release.json SHA-256')
    parser.add_argument('--bootstrap-file', help='Absolute path to root-owned 0600 private bootstrap JSON')

    args = parser.parse_args(argv)
    os.environ.clear()
    os.environ.update(PATH='/usr/sbin:/usr/bin:/sbin:/bin', LANG='C', HOME='/root')

    try:
        if sys.platform != 'linux' or os.geteuid() != 0:
            fail('Linux and root privileges are required')
        cfg, cfg_path = validate_config(args.config, args.environment)

        if args.action in ('migrate', 'bootstrap', 'deploy'):
            if not (args.release_dir and args.revision and args.run_id and args.manifest_sha256):
                fail(f"Action '{args.action}' requires --release-dir, --revision, --run-id, and --manifest-sha256")
            if not REVISION_PATTERN.match(args.revision):
                fail(f"Invalid revision: {args.revision}")
            if not RUN_ID_PATTERN.match(args.run_id):
                fail(f"Invalid run_id: {args.run_id}")
            if not HASH_PATTERN.match(args.manifest_sha256):
                fail(f"Invalid manifest_sha256: {args.manifest_sha256}")
            validate_clean_path(args.release_dir, 'Release directory')
        elif any((args.release_dir, args.revision, args.run_id, args.manifest_sha256)):
            fail('Release arguments are not valid for this action')

        if args.action == 'bootstrap':
            if not args.bootstrap_file:
                fail("Action 'bootstrap' requires --bootstrap-file")
        elif args.bootstrap_file:
            fail('Bootstrap file is not valid for this action')

        if args.action == 'enroll':
            return action_enroll(cfg)
        elif args.action == 'migrate':
            return action_migrate(cfg, args.release_dir, args.revision, args.run_id, args.manifest_sha256)
        elif args.action == 'bootstrap':
            return action_bootstrap(cfg, args.release_dir, args.revision, args.run_id, args.manifest_sha256, args.bootstrap_file)
        elif args.action == 'deploy':
            return action_deploy(cfg, args.release_dir, args.revision, args.run_id, args.manifest_sha256)
        elif args.action == 'status':
            return action_status(cfg)
        elif args.action == 'stop':
            return action_stop(cfg)
        elif args.action == 'rollback':
            return action_rollback(cfg)
        elif args.action == 'recover':
            return action_recover(cfg)
        else:
            fail(f"Unhandled action: {args.action}")

    except DeployError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except Exception:
        print("Unexpected failure; details suppressed to protect secrets", file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
