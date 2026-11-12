#!/usr/bin/env python3
"""Use only the ephemeral, loopback-only PostgreSQL service of this hosted quality job."""
import json
import os
from pathlib import Path
import secrets
import shutil
import sys
import uuid

import database


def postgres_bin():
    config = shutil.which('pg_config')
    if not config:
        raise database.OperationError('The hosted runner PostgreSQL client configuration is required.')
    config = database.checked_path(Path(config).resolve(strict=True))
    directory = database.checked_path(Path(database.run([str(config), '--bindir'])))
    database.executable(directory, 'psql')
    return directory


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise database.OperationError('This entrypoint is only for the isolated hosted quality runner, not a developer or production DB.')
    pg_bin = postgres_bin()
    folder = database.checked_path(database.ROOT / '.runtime' / ('ci-' + uuid.uuid4().hex))
    folder.mkdir(parents=True)
    database.private(folder)
    (folder / 'private').mkdir()
    database.private(folder / 'private')
    suffix = uuid.uuid4().hex[:12]
    state = {'folder': str(folder), 'port': 5432, 'pg_bin': str(pg_bin),
             'database': 'ls_' + suffix + '_test',
             'accounts': {'operator': {'user': 'postgres', 'password': ''},
                          'owner': {'user': 'ls_' + suffix + '_owner', 'password': ''},
                          'migrator': {'user': 'ls_' + suffix + '_migrator', 'password': secrets.token_urlsafe(36)},
                          'runtime': {'user': 'ls_' + suffix + '_runtime', 'password': secrets.token_urlsafe(36)}}}
    # The service has no host/production secrets or persistent volumes. Trust auth is confined
    # to its loopback-published disposable runner service; app/migration roles use generated SCRAM secrets.
    if database.sql(state, 'SHOW server_version_num;') != '170011':
        raise database.OperationError('The immutable CI service must be PostgreSQL 17.11.')
    if database.sql(state, "SELECT count(*) FROM pg_database WHERE datname NOT IN ('postgres','template0','template1');") != '0':
        raise database.OperationError('CI service is not fresh; refusing to reuse or reset it.')
    state['system_identifier'] = database.sql(state, 'SELECT system_identifier::text FROM pg_control_system();')
    owner = database.identifier(state['accounts']['owner']['user'])
    migrator = database.identifier(state['accounts']['migrator']['user'])
    runtime = database.identifier(state['accounts']['runtime']['user'])
    database.sql(state, f'CREATE ROLE {owner} NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;'
                 + ''.join(f'CREATE ROLE {database.identifier(state["accounts"][key]["user"])} LOGIN NOINHERIT '
                           f'NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '
                           f'{database.literal(state["accounts"][key]["password"])};' for key in ('migrator','runtime'))
                 + f'GRANT {owner} TO {migrator};')
    database.sql(state, f'CREATE DATABASE {database.identifier(state["database"])} TEMPLATE template0 ENCODING \'UTF8\';')
    database.provision_database(state, state['database'])
    database.migrate(state, state['database'])
    database.maven(state, ['clean', 'verify'], state['database'], state['database'])
    print('Actual application Maven verify with isolated PostgreSQL completed; no runtime deploy or remote activation.')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (database.OperationError, OSError, ValueError) as error:
        print('error:', str(error) if isinstance(error, database.OperationError) else 'Hosted quality DB operation failed.', file=sys.stderr)
        sys.exit(1)
