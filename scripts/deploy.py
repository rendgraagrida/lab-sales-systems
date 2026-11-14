#!/usr/bin/env python3
"""Stream a verified release to the fixed remote deployment entrypoint."""

import argparse
import json
import hashlib
from pathlib import Path
import re
import struct
import subprocess
import sys

import ci
import release

ACTIONS = ('migrate', 'deploy', 'status', 'stop', 'rollback', 'recover')
ENVIRONMENTS = ('staging', 'production')
SSH_HOST_RE = re.compile(r'^[a-zA-Z0-9][a-zA-Z0-9_.-]*\Z')
SSH_USER_RE = re.compile(r'^[a-zA-Z0-9_][a-zA-Z0-9_.-]*\Z')
CONTROLLER_DEPENDENCIES = ('ci.py', 'release.py', 'database.py', 'deploy_linux.py',
                           'deploy_gateway.py', 'deploy_entry.py', 'deploy_sandbox.py')
MAX_MANIFEST = 1024 * 1024
CHUNK_SIZE = 1024 * 1024


class DeployError(Exception):
    pass


def check_remote_path(path, name='remote path'):
    if not isinstance(path, str) or not path.startswith('/'):
        raise DeployError(f'{name} must be an absolute POSIX path starting with /')
    if any(c in path for c in ('\x00', '\r', '\n', '\t')):
        raise DeployError(f'{name} contains invalid control characters')
    parts = path.split('/')
    if '..' in parts:
        raise DeployError(f'{name} cannot contain path traversal')
    return path


def check_ssh_host(host):
    if not isinstance(host, str) or not SSH_HOST_RE.fullmatch(host) or host.startswith('-'):
        raise DeployError('invalid ssh-host: expected hostname or ssh alias')
    return host


def check_ssh_user(user):
    if user is None:
        return None
    if not isinstance(user, str) or not SSH_USER_RE.fullmatch(user) or user.startswith('-'):
        raise DeployError('invalid ssh-user')
    return user


def check_ssh_port(port):
    if port is None:
        return None
    try:
        p = int(port)
        if not (1 <= p <= 65535):
            raise ValueError()
        return p
    except (ValueError, TypeError):
        raise DeployError('invalid ssh-port: expected 1..65535') from None


def check_local_file(path, name='file'):
    if path is None:
        return None
    p = ci.checked_path(path)
    with ci.open_regular(p):
        pass
    return p


def build_ssh_base_cmds(ssh_host, ssh_user=None, ssh_port=None, ssh_config=None,
                        ssh_identity=None, ssh_known_hosts=None):
    host = check_ssh_host(ssh_host)
    user = check_ssh_user(ssh_user)
    port = check_ssh_port(ssh_port)
    config_path = check_local_file(ssh_config, 'ssh-config')
    identity_path = check_local_file(ssh_identity, 'ssh-identity')
    known_hosts_path = check_local_file(ssh_known_hosts, 'ssh-known-hosts')

    ssh_cmd = [
        'ssh',
        '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ConnectTimeout=15',
    ]

    if config_path is not None:
        posix_p = config_path.resolve().as_posix()
        ssh_cmd.extend(['-F', posix_p])

    if known_hosts_path is not None:
        posix_p = known_hosts_path.resolve().as_posix()
        ssh_cmd.extend(['-o', f'UserKnownHostsFile={posix_p}'])

    if identity_path is not None:
        posix_p = identity_path.resolve().as_posix()
        ssh_cmd.extend(['-i', posix_p])

    if port is not None:
        ssh_cmd.extend(['-p', str(port)])

    target = f'{user}@{host}' if user else host
    return ssh_cmd, target


def deploy_transport(action, environment, remote_config, ssh_host, ssh_user=None,
                     ssh_port=None, ssh_config=None, ssh_identity=None,
                     ssh_known_hosts=None, release_dir=None, revision=None,
                     run_id=None, manifest_sha256=None):
    if action not in ACTIONS:
        raise DeployError(f'unknown action: {action}')
    if environment not in ENVIRONMENTS:
        raise DeployError(f'unknown environment: {environment}')
    clean_config = check_remote_path(remote_config, 'remote-config')
    if action not in ('migrate', 'deploy') and any(
            value is not None for value in (release_dir, revision, run_id, manifest_sha256)):
        raise DeployError('release arguments are not allowed for this action')

    manifest = None
    raw_manifest = None
    if action in ('migrate', 'deploy'):
        if not (release_dir and revision and run_id and manifest_sha256):
            raise DeployError(f'action {action} requires --release-dir, --revision, --run-id, --manifest-sha256')
        ci.candidate(revision, run_id)
        if not isinstance(manifest_sha256, str) or not ci.HASH.fullmatch(manifest_sha256):
            raise DeployError('invalid manifest-sha256')
        try:
            manifest = release.verify_release(release_dir, revision, run_id, manifest_sha256)
            with ci.open_regular(Path(release_dir) / 'release.json') as source:
                raw_manifest = source.read(MAX_MANIFEST + 1)
            if len(raw_manifest) > MAX_MANIFEST or hashlib.sha256(raw_manifest).hexdigest() != manifest_sha256:
                raise DeployError('release manifest changed or is too large')
            if manifest['schema_versions'] != [str(i) for i in range(1, len(release.SQL_NAMES) + 1)]:
                raise DeployError('release must use current schema lineage')
            total = 0
            for name, item in manifest['files'].items():
                limit = 512 * 1024 * 1024 if name == 'app/application.jar' else 4 * 1024 * 1024
                if item['size'] > limit:
                    raise DeployError('release file exceeds transport limit')
                total += item['size']
            if total > 576 * 1024 * 1024:
                raise DeployError('release exceeds transport limit')
        except (release.ReleaseError, OSError, ci.CheckError) as err:
            raise DeployError('local release verification failed') from err

    scripts_dir = Path(__file__).resolve().parent
    entries = {}
    for dep in CONTROLLER_DEPENDENCIES:
        with ci.open_regular(scripts_dir / dep) as source:
            entries[dep] = ci.digest_file(source)['sha256']
    ssh_cmd, target = build_ssh_base_cmds(
        ssh_host, ssh_user, ssh_port, ssh_config, ssh_identity, ssh_known_hosts)
    request = {'schema': 2, 'policy': clean_config, 'action': action, 'environment': environment,
               'revision': revision, 'run_id': run_id, 'manifest_sha256': manifest_sha256,
               'controller_sha256': entries}
    header = json.dumps(request, separators=(',', ':'), ensure_ascii=True).encode('utf-8') + b'\n'
    if len(header) > 65536:
        raise DeployError('request header too large')
    process = subprocess.Popen(ssh_cmd + [target, 'crm-deploy-v2'], stdin=subprocess.PIPE)
    local_error = None
    try:
        process.stdin.write(header)
        if manifest is not None:
            process.stdin.write(struct.pack('>Q', len(raw_manifest)))
            process.stdin.write(raw_manifest)
            for name in sorted(manifest['files']):
                meta = manifest['files'][name]
                digest = hashlib.sha256()
                count = 0
                with ci.open_regular(Path(release_dir) / name) as source:
                    while True:
                        chunk = source.read(CHUNK_SIZE)
                        if not chunk:
                            break
                        count += len(chunk)
                        if count > meta['size']:
                            raise DeployError('release changed during streaming')
                        digest.update(chunk)
                        process.stdin.write(chunk)
                if count != meta['size'] or digest.hexdigest() != meta['sha256']:
                    raise DeployError('release changed during streaming')
    except (BrokenPipeError, OSError, ci.CheckError, DeployError) as error:
        local_error = error
    finally:
        try:
            process.stdin.close()
        except BrokenPipeError:
            pass
    status = process.wait()
    if local_error is not None and status == 0:
        raise DeployError('release streaming failed') from local_error
    return status


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=ACTIONS, help='Action to execute on target')
    parser.add_argument('--environment', required=True, choices=ENVIRONMENTS, help='Target environment')
    parser.add_argument('--remote-config', required=True, help='Absolute path to installed gateway policy JSON')
    parser.add_argument('--ssh-host', required=True, help='SSH host or alias')
    parser.add_argument('--ssh-user', help='Optional SSH user')
    parser.add_argument('--ssh-port', type=int, help='Optional SSH port (1..65535)')
    parser.add_argument('--ssh-config', help='Optional local SSH config file')
    parser.add_argument('--ssh-identity', help='Optional local SSH identity/key file')
    parser.add_argument('--ssh-known-hosts', help='Optional local SSH known_hosts file')
    parser.add_argument('--release-dir', help='Local path to verified release directory')
    parser.add_argument('--revision', help='Candidate revision (Git SHA or content:<hex>)')
    parser.add_argument('--run-id', help='Candidate run ID (local or integer)')
    parser.add_argument('--manifest-sha256', help='External release.json SHA-256')

    args = parser.parse_args(argv)

    try:
        return deploy_transport(
            action=args.action,
            environment=args.environment,
            remote_config=args.remote_config,
            ssh_host=args.ssh_host,
            ssh_user=args.ssh_user,
            ssh_port=args.ssh_port,
            ssh_config=args.ssh_config,
            ssh_identity=args.ssh_identity,
            ssh_known_hosts=args.ssh_known_hosts,
            release_dir=args.release_dir,
            revision=args.revision,
            run_id=args.run_id,
            manifest_sha256=args.manifest_sha256
        )
    except DeployError as err:
        print(f'error: {err}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
