#!/usr/bin/env python3
"""Explicit local lab DB operations. Never discovers, resets or deletes user databases."""
import argparse
from contextlib import contextmanager
import csv
from datetime import date
import getpass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import uuid

from ci import checked_path

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / '.runtime'
NAME = re.compile(r'[a-z][a-z0-9_]{1,24}\Z')
REVISION = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64}|content:[0-9a-f]{64})\Z')
PG_BIN = ROOT / '.tools' / 'postgres' / 'pgsql' / 'bin'
JAVA_HOME = (Path(os.environ['JAVA_HOME']) if os.environ.get('GITHUB_ACTIONS') == 'true'
             else ROOT / '.tools' / 'jdk' / 'jdk-21.0.13+9')
READ_ONLY = ('roles', 'flyway_schema_history')
INSERT_ONLY = ('setup_receipts', 'audit_events', 'journal_lines', 'stock_movements', 'scenario_baseline_versions',
               'opening_balance_sources', 'inventory_balances', 'inventory_valuation_pools',
               'instrument_serials', 'inventory_lots')
MUTABLE = ('app_users', 'scenario_workspaces', 'workspace_memberships', 'user_roles', 'legal_entities',
           'entity_memberships', 'customers', 'contacts', 'suppliers', 'uoms', 'products',
           'customer_contact_links', 'customer_entity_accounts', 'supplier_entity_accounts',
           'product_entity_settings', 'warehouses', 'payment_terms', 'price_books', 'price_book_lines',
           'chart_accounts', 'fiscal_periods', 'tax_codes', 'tax_versions', 'document_sequences',
           'journal_entries', 'payment_posting_config')
TRIGGER_FUNCTIONS = ('reject_audit_mutation', 'journal_entry_immutable', 'journal_line_immutable',
                     'journal_balance_guard', 'legal_entity_currency_guard',
                     'protect_approved_price_book', 'protect_approved_price_lines')


class OperationError(Exception):
    pass


def executable(directory, name):
    path = checked_path(directory / (name + ('.exe' if os.name == 'nt' else '')))
    if not path.is_file():
        raise OperationError('Required local executable is unavailable: ' + name)
    return str(path)


def private(path):
    path = checked_path(path)
    if os.name == 'nt':
        identity = subprocess.run(['whoami', '/user', '/fo', 'csv', '/nh'], capture_output=True,
                                  text=True, timeout=20, check=True)
        sid = next(csv.reader(io.StringIO(identity.stdout)))[1]
        if not re.fullmatch(r'S-[0-9-]+', sid):
            raise OperationError('Cannot establish current Windows identity for private files.')
        grants = ['*' + sid + (':(OI)(CI)F' if path.is_dir() else ':F'),
                  '*S-1-5-18' + (':(OI)(CI)F' if path.is_dir() else ':F')]
        result = subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', grants[0],
                                 '/grant:r', grants[1]], capture_output=True, timeout=20)
        if result.returncode:
            raise OperationError('Cannot enforce private local storage permissions.')
    else:
        path.chmod(0o700 if path.is_dir() else 0o600)


def secure_write(path, text, replace=False):
    checked_path(path)
    if not replace:
        with path.open('x', encoding='utf-8', newline='\n') as file:
            file.write(text)
        private(path)
    else:
        temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.new')
        secure_write(temporary, text)
        os.replace(temporary, path)


def run(command, env=None, input_text=None, timeout=120, log=None):
    result = subprocess.run(command, env=env, input=input_text, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if log is not None:
        # SQL commands containing credential definitions never supply a log target.
        secure_write(log, result.stdout + result.stderr)
    if result.returncode:
        raise OperationError('Local operation failed (exit ' + str(result.returncode) + '); no success is claimed.')
    return result.stdout.strip()


def sql(state, statement, database=None, role='operator', timeout=60):
    account = state['accounts'][role]
    env = os.environ.copy()
    env.update(PGPASSWORD=account['password'], PGCONNECT_TIMEOUT='5', PGCLIENTENCODING='UTF8')
    return run([executable(Path(state['pg_bin']), 'psql'), '-X', '--no-password',
                '--set=ON_ERROR_STOP=1', '--tuples-only', '--no-align',
                '--host=127.0.0.1', '--port=' + str(state['port']), '--username=' + account['user'],
                '--dbname=' + (database or 'postgres')], env, statement, timeout)


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def identifier(value):
    if not re.fullmatch(r'[a-z][a-z0-9_]{0,62}', value):
        raise OperationError('Invalid internal database identity.')
    return '"' + value + '"'


def directory(name):
    if not NAME.fullmatch(name):
        raise OperationError('Name must be 2-25 lowercase letters/digits/underscores and start with a letter.')
    return checked_path(RUNTIME / name)


def state_for(name):
    folder = directory(name)
    path = checked_path(folder / 'private' / 'state.json')
    with path.open(encoding='utf-8') as file:
        state = json.load(file)
    if state.get('project') != 'lab-sales-systems' or state.get('root') != str(ROOT.resolve()):
        raise OperationError('State belongs to another project or root; refusing reuse.')
    if Path(state['folder']).resolve() != folder or Path(state['data']).resolve() != folder / 'data':
        raise OperationError('Unexpected data-directory ownership; refusing action.')
    checked_path(state['data'])
    for account in state['accounts'].values():
        identifier(account['user'])
    identifier(state['database'])
    return state


def save(state):
    secure_write(Path(state['folder']) / 'private' / 'state.json',
                 json.dumps(state, indent=2, ensure_ascii=True) + '\n', replace=True)


@contextmanager
def locked(state):
    lock = checked_path(Path(state['folder']) / 'operation.lock')
    if lock.is_dir():
        raise OperationError('A legacy operation directory requires owner inspection; refusing to remove it.')
    try:
        secure_write(lock, '0')
    except FileExistsError:
        pass
    with lock.open('r+b') as file:
        acquired = False
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            acquired = True
        except OSError:
            raise OperationError('Another operation owns this target lock; no process will be stopped.') from None
        try:
            yield
        finally:
            if acquired:
                file.seek(0)
                if os.name == 'nt':
                    msvcrt.locking(file.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(file.fileno(), fcntl.LOCK_UN)


def identity(state):
    found = sql(state, 'SELECT system_identifier::text FROM pg_control_system();')
    if found != state['system_identifier']:
        raise OperationError('Listening PostgreSQL is not the owned cluster; refusing action.')


def unused_port(port):
    if not 1024 <= port <= 65535:
        raise OperationError('Use a concrete unprivileged local port.')
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        if os.name == 'nt':
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        try:
            sock.bind(('127.0.0.1', port))
        except OSError:
            raise OperationError('Requested port has another listener; no process will be reused, killed or moved.') from None


def start(state):
    unused_port(state['port'])
    log = Path(state['folder']) / 'private' / ('pg-start-' + uuid.uuid4().hex + '.log')
    secure_write(log, '')
    # A persistent Windows child must not retain captured controller pipes.
    with log.open('a', encoding='utf-8') as stream:
        result = subprocess.run([executable(Path(state['pg_bin']), 'pg_ctl'), '-D', state['data'], '-l',
            str(Path(state['folder']) / 'postgres.log'), '-w', '-t', '30', 'start'],
            stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, timeout=45, close_fds=True)
    if result.returncode:
        raise OperationError('Owned PostgreSQL startup failed; inspect its private controller log.')
    identity(state)


def initialize(args):
    folder = directory(args.name)
    unused_port(args.port)
    pg_bin = checked_path(Path(args.postgres_bin).resolve())
    version = run([executable(pg_bin, 'postgres'), '--version'])
    if version != 'postgres (PostgreSQL) 17.11':
        raise OperationError('This baseline requires the identified PostgreSQL 17.11 binary.')
    RUNTIME.mkdir(exist_ok=True)
    checked_path(RUNTIME)
    folder.mkdir()  # Exclusive: existing state/data is never replaced or removed.
    private(folder)
    secret_dir = folder / 'private'
    secret_dir.mkdir()
    private(secret_dir)
    suffix = uuid.uuid4().hex[:12]
    accounts = {key: {'user': 'ls_' + suffix + '_' + key, 'password': secrets.token_urlsafe(36)}
                for key in ('operator', 'owner', 'migrator', 'runtime')}
    accounts['owner']['password'] = ''  # NOLOGIN role, never a pool credential.
    state = {'schema': 1, 'project': 'lab-sales-systems', 'root': str(ROOT.resolve()),
             'folder': str(folder), 'data': str(folder / 'data'), 'port': args.port,
             'pg_bin': str(pg_bin), 'database': 'ls_' + suffix + '_m1', 'accounts': accounts,
             'phase': 'INITIALIZING', 'system_identifier': None}
    secure_write(secret_dir / 'state.json', json.dumps(state, indent=2) + '\n')
    password_file = secret_dir / 'init-password'
    secure_write(password_file, accounts['operator']['password'] + '\n')
    run([executable(pg_bin, 'initdb'), '-D', state['data'], '--username=' + accounts['operator']['user'],
         '--pwfile=' + str(password_file), '--auth-host=scram-sha-256', '--auth-local=scram-sha-256',
         '--encoding=UTF8', '--locale=C'], timeout=120, log=secret_dir / 'initdb.log')
    password_file.unlink()  # Only the temporary secret created by this invocation.
    config = Path(state['data']) / 'postgresql.conf'
    with config.open('a', encoding='utf-8', newline='\n') as file:
        file.write("\n# Project-owned isolated lab instance\nlisten_addresses = '127.0.0.1'\nport = " + str(args.port)
                   + "\nshared_buffers = '64MB'\nmax_connections = 24\npassword_encryption = 'scram-sha-256'\n"
                     "log_statement = 'none'\nlog_min_error_statement = 'panic'\nlog_connections = off\n")
    control = run([executable(pg_bin, 'pg_controldata'), state['data']], env={**os.environ, 'LC_ALL': 'C'})
    match = re.search(r'Database system identifier:\s*(\d+)', control)
    if not match:
        raise OperationError('Cannot establish owned cluster system identity.')
    state['system_identifier'] = match.group(1)
    save(state)
    with locked(state):
        start(state)
        finish_provision(state)
    print('Created a new isolated project cluster; no existing database was connected, reset or deleted.')
    print('Target:', args.name, 'loopback port', args.port, 'state: .runtime/' + args.name + '/private/state.json (do not share)')


def finish_provision(state):
    identity(state)
    if state['phase'] != 'INITIALIZING':
        raise OperationError('Only a verified fresh INITIALIZING target may finish initial provisioning.')
    if sql(state, "SELECT count(*) FROM pg_database WHERE datname NOT IN ('postgres','template0','template1');") != '0':
        raise OperationError('Initial target is no longer fresh; no database is reset or overwritten.')
    accounts = state['accounts']
    for key in ('owner', 'migrator', 'runtime'):
        if sql(state, 'SELECT count(*) FROM pg_roles WHERE rolname=' + literal(accounts[key]['user']) + ';') != '0':
            raise OperationError('Initial role already exists; stop for explicit inventory, not automatic repair.')
    owner, migrator = (identifier(accounts[key]['user']) for key in ('owner', 'migrator'))
    sql(state, f'CREATE ROLE {owner} NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;'
        + ''.join(f'CREATE ROLE {identifier(accounts[key]["user"])} LOGIN NOINHERIT NOSUPERUSER NOCREATEDB '
                  f'NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD {literal(accounts[key]["password"])};'
                  for key in ('migrator', 'runtime'))
        + f'GRANT {owner} TO {migrator};')
    sql(state, f'CREATE DATABASE {identifier(state["database"])} OWNER {identifier(accounts["operator"]["user"])} TEMPLATE template0 ENCODING \'UTF8\';')
    provision_database(state, state['database'])
    state['phase'] = 'PROVISIONED_UNMIGRATED'
    save(state)


def secure_function_defaults(state, database):
    owner = identifier(state['accounts']['owner']['user'])
    # A schema-level REVOKE cannot cancel PostgreSQL's database-wide PUBLIC default.
    sql(state, f'ALTER DEFAULT PRIVILEGES FOR ROLE {owner} REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;', database)


def provision_database(state, database):
    owner, migrator, runtime = (identifier(state['accounts'][key]['user']) for key in ('owner', 'migrator', 'runtime'))
    db = identifier(database)
    sql(state, f'REVOKE ALL ON DATABASE {db} FROM PUBLIC; GRANT CONNECT ON DATABASE {db} TO {migrator},{runtime};'
        + 'REVOKE ALL ON SCHEMA public FROM PUBLIC;'
        + f'CREATE SCHEMA app AUTHORIZATION {owner}; CREATE SCHEMA extensions;'
        + 'CREATE EXTENSION btree_gist WITH SCHEMA extensions;'
        + f'GRANT USAGE ON SCHEMA app TO {migrator},{runtime}; GRANT USAGE ON SCHEMA extensions TO {owner},{migrator},{runtime};'
        + f'ALTER DEFAULT PRIVILEGES FOR ROLE {owner} IN SCHEMA app REVOKE ALL ON TABLES FROM PUBLIC;'
        + f'ALTER DEFAULT PRIVILEGES FOR ROLE {owner} IN SCHEMA app REVOKE ALL ON SEQUENCES FROM PUBLIC;'
        + f'ALTER ROLE {runtime} IN DATABASE {db} SET search_path=app,pg_catalog;'
        + f'ALTER ROLE {migrator} IN DATABASE {db} SET search_path=app,extensions,pg_catalog;', database)
    secure_function_defaults(state, database)


def source_revision():
    manifest = {}
    for base in (ROOT / 'src', ROOT / 'scripts'):
        for path in sorted(base.rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts:
                manifest[path.relative_to(ROOT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest['pom.xml'] = hashlib.sha256((ROOT / 'pom.xml').read_bytes()).hexdigest()
    return 'content:' + hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode('ascii')).hexdigest()


def maven(state, goals, database, test_database=None):
    env = os.environ.copy()
    home = checked_path(JAVA_HOME)
    for key in list(env):
        if key.startswith(('LAB_MIGRATION_', 'LAB_BOOTSTRAP_')):
            env.pop(key, None)
    env.update(JAVA_HOME=str(home), MAVEN_USER_HOME=str(ROOT / '.tools' / 'maven-user-home'),
               MAVEN_OPTS='-Xmx512m')
    if any(goal.startswith('flyway:') for goal in goals):
        env.update(LAB_MIGRATION_DB_URL='jdbc:postgresql://127.0.0.1:' + str(state['port']) + '/' + database,
                   LAB_MIGRATION_DB_USER=state['accounts']['migrator']['user'],
                   LAB_MIGRATION_DB_PASSWORD=state['accounts']['migrator']['password'],
                   LAB_MIGRATION_OWNER=state['accounts']['owner']['user'])
    env['PATH'] = str(home / 'bin') + os.pathsep + env.get('PATH', '')
    if test_database:
        env.update(LAB_TEST_DB_URL='jdbc:postgresql://127.0.0.1:' + str(state['port']) + '/' + test_database,
                   LAB_TEST_DB_USER=state['accounts']['runtime']['user'],
                   LAB_TEST_DB_PASSWORD=state['accounts']['runtime']['password'],
                   LAB_TEST_REVISION=source_revision())
    command = ([str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(ROOT / 'mvnw')])
    command += ['--batch-mode', '--no-transfer-progress',
                '-f', str(ROOT / 'pom.xml'), '-Dmaven.repo.local=' + str(ROOT / '.tools' / 'maven-repository')] + goals
    logfile = Path(state['folder']) / 'private' / ('maven-' + uuid.uuid4().hex + '.log')
    run(command, env=env, timeout=900, log=logfile)
    print('Maven operation passed; private log:', logfile.relative_to(ROOT))


def grant_runtime(state, database):
    user = identifier(state['accounts']['runtime']['user'])
    statements = [f'REVOKE ALL ON ALL TABLES IN SCHEMA app FROM {user};',
                  f'REVOKE ALL ON ALL SEQUENCES IN SCHEMA app FROM {user};',
                  f'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA app FROM {user};',
                  'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA app FROM PUBLIC;']
    for table in READ_ONLY:
        statements.append(f'GRANT SELECT ON app.{identifier(table)} TO {user};')
    for tables, privileges in ((INSERT_ONLY, 'SELECT,INSERT'), (MUTABLE, 'SELECT,INSERT,UPDATE')):
        for table in tables:
            statements.append(f'GRANT {privileges} ON app.{identifier(table)} TO {user};')
            sequence = sql(state,
                "SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='app'"
                + ' AND table_name=' + literal(table) + " AND column_name='id' AND is_identity='YES')"
                + ' THEN pg_get_serial_sequence(' + literal('app.' + table) + ",'id') ELSE NULL END;", database)
            if sequence:
                if not re.fullmatch(r'app\.[a-z_][a-z0-9_]*', sequence):
                    raise OperationError('Unexpected sequence identity; refusing grant.')
                statements.append(f'GRANT USAGE ON SEQUENCE {sequence} TO {user};')
    for function in TRIGGER_FUNCTIONS:
        statements.append(f'GRANT EXECUTE ON FUNCTION app.{identifier(function)}() TO {user};')
    sql(state, 'BEGIN;' + ''.join(statements) + 'COMMIT;', database)


def migrate(state, database):
    identity(state)
    foreign = sql(state, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('app','extensions','pg_catalog','information_schema') "
                        "AND n.nspname NOT LIKE 'pg_toast%';", database)
    if foreign != '0':
        raise OperationError('Unknown/legacy schema lineage found; no automatic upgrade, repair or reset is permitted.')
    secure_function_defaults(state, database)
    maven(state, ['flyway:migrate', 'flyway:validate'], database)
    grant_runtime(state, database)
    version = sql(state, "SELECT string_agg(version,',' ORDER BY installed_rank) FROM app.flyway_schema_history WHERE success AND type='SQL';", database)
    if version != '1,2,3':
        raise OperationError('Unexpected migrated schema identity.')


def application_env(state, revision):
    if not REVISION.fullmatch(revision):
        raise OperationError('An exact lowercase content/Git candidate revision is required.')
    env = os.environ.copy()
    # A runtime child must never receive migration/operator credentials, even from an inherited shell.
    for key in list(env):
        if key.startswith(('LAB_MIGRATION_', 'LAB_BOOTSTRAP_')) or key in ('PGPASSWORD', 'PGPASSFILE'):
            env.pop(key, None)
    env.update(LAB_DB_URL='jdbc:postgresql://127.0.0.1:' + str(state['port']) + '/' + state['database'],
               LAB_DB_USER=state['accounts']['runtime']['user'], LAB_DB_PASSWORD=state['accounts']['runtime']['password'],
               LAB_REVISION=revision, LAB_BIND_ADDRESS='127.0.0.1', LAB_PORT='8187')
    return env


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    init = sub.add_parser('init', help='Create a NEW project-owned isolated cluster and roles; never reuse existing data')
    init.add_argument('--name', required=True)
    init.add_argument('--port', required=True, type=int)
    init.add_argument('--postgres-bin', default=str(PG_BIN))
    for command in ('status', 'start', 'stop', 'migrate', 'verify', 'backup'):
        item = sub.add_parser(command)
        item.add_argument('--name', required=True)
    for command in ('bootstrap', 'run'):
        item = sub.add_parser(command)
        item.add_argument('--name', required=True)
        item.add_argument('--revision', required=True)
        item.add_argument('--jar', default=str(ROOT / 'target' / 'lab-sales-systems-1.0.0.jar'))
        if command == 'bootstrap':
            item.add_argument('--login', required=True)
            item.add_argument('--workspace-code', required=True)
            item.add_argument('--workspace-name', required=True)
            item.add_argument('--business-date', required=True, type=date.fromisoformat)
    args = parser.parse_args(argv)
    try:
        if args.command == 'init':
            initialize(args)
            return 0
        state = state_for(args.name)
        with locked(state):
            if args.command == 'start':
                start(state)
            else:
                identity(state)
                if args.command == 'status':
                    print('Owned cluster identity matched; phase:', state['phase'], 'port:', state['port'])
                elif args.command == 'stop':
                    run([executable(Path(state['pg_bin']), 'pg_ctl'), '-D', state['data'], '-w', '-t', '30', '-m', 'fast', 'stop'], timeout=45)
                    print('Stopped only the identity-verified project cluster; data retained.')
                elif args.command == 'migrate':
                    migrate(state, state['database'])
                    state['phase'] = 'M1_SCHEMA_MIGRATED'
                    save(state)
                elif args.command == 'verify':
                    test_database = state['database'] + '_test_' + uuid.uuid4().hex[:8]
                    identifier(test_database)
                    sql(state, f'CREATE DATABASE {identifier(test_database)} TEMPLATE template0 ENCODING \'UTF8\';')
                    provision_database(state, test_database)
                    migrate(state, test_database)
                    maven(state, ['clean', 'verify'], state['database'], test_database)
                    state.setdefault('verification_databases', []).append(test_database)
                    save(state)  # Test data retained, never DROP/clean/reset.
                elif args.command == 'backup':
                    backup = checked_path(Path(state['folder']) / 'private' / ('backup-' + uuid.uuid4().hex + '.dump'))
                    env = {**os.environ, 'PGPASSWORD': state['accounts']['operator']['password']}
                    run([executable(Path(state['pg_bin']), 'pg_dump'), '-h', '127.0.0.1', '-p', str(state['port']),
                         '-U', state['accounts']['operator']['user'], '--no-password', '-Fc', '-f', str(backup), state['database']], env, timeout=120)
                    private(backup)
                    print('Created project-owned private backup; no restore or backup deletion implied:', backup.relative_to(ROOT))
                elif args.command in ('bootstrap', 'run'):
                    jar = checked_path(Path(args.jar).resolve())
                    if not jar.is_file():
                        raise OperationError('Build the actual application JAR first.')
                    env = application_env(state, args.revision)
                    command = [executable(JAVA_HOME / 'bin', 'java'), '-Xmx384m', '-jar', str(jar)]
                    if args.command == 'bootstrap':
                        password = os.environ.get('LAB_BOOTSTRAP_PASSWORD') or getpass.getpass('New named administrator password (minimum 16 characters): ')
                        if len(password) < 16:
                            raise OperationError('Bootstrap password must have at least 16 characters.')
                        env.update(LAB_BOOTSTRAP_PASSWORD=password, LAB_BOOTSTRAP_LOGIN=args.login,
                                   LAB_BOOTSTRAP_WORKSPACE_CODE=args.workspace_code, LAB_BOOTSTRAP_WORKSPACE_NAME=args.workspace_name,
                                   LAB_BOOTSTRAP_BUSINESS_DATE=args.business_date.isoformat())
                        command.append('--bootstrap-admin')
                        run(command, env=env, timeout=90,
                            log=Path(state['folder']) / 'private' / ('bootstrap-' + uuid.uuid4().hex + '.log'))
                        print('Explicit named-admin bootstrap completed; no business role/entity access was granted automatically.')
                    else:
                        unused_port(8187)
                        print('Running only the project application at http://127.0.0.1:8187; Ctrl+C stops this child. This is local development, not deployment.', flush=True)
                        child = subprocess.Popen(command, env=env)
                        try:
                            return child.wait()
                        except KeyboardInterrupt:
                            child.terminate()
                            try:
                                child.wait(timeout=15)
                            except subprocess.TimeoutExpired:
                                child.kill()
                                child.wait(timeout=10)
                            return 130
        return 0
    except (OperationError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        message = str(error) if isinstance(error, OperationError) else 'Local input/process operation failed; no target reset or success is implied.'
        print('error:', message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
