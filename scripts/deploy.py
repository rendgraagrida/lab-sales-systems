#!/usr/bin/env python3
"""Transport controller for deploying Lab Sales Systems to remote Linux hosts via SSH/SCP.

Handles local release verification, dependency staging, safe command execution,
and exit status propagation across Windows and Linux controller workstations.
"""

import argparse
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys

import ci
import release
import json

ACTIONS = ('enroll', 'migrate', 'bootstrap', 'deploy', 'status', 'stop', 'rollback', 'recover')
ENVIRONMENTS = ('staging', 'production')
SSH_HOST_RE = re.compile(r'^[a-zA-Z0-9][a-zA-Z0-9_.-]*\Z')
SSH_USER_RE = re.compile(r'^[a-zA-Z0-9_][a-zA-Z0-9_.-]*\Z')
UPLOAD_DIR_RE = re.compile(r'^/tmp/crm-upload-[A-Za-z0-9._-]+\Z')
CONTROLLER_DEPENDENCIES = ('ci.py', 'release.py', 'deploy_linux.py', 'database.py')

# Fixed sudo program: no uploaded code executes until all copied hashes match.
PROMOTE = r'''
import hashlib, json, os, pathlib, stat, subprocess, sys, tempfile
source, entries_json, action_json = sys.argv[1:]
entries = json.loads(entries_json)
action = json.loads(action_json)
os.umask(0o022)
def trusted_directory(path):
    info = os.lstat(path)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError("untrusted upload directory ancestor")

for path in ("/", "/opt", "/opt/lab-sales-systems"):
    trusted_directory(path)
uploads = "/opt/lab-sales-systems/uploads"
trusted_directory(uploads)
root = pathlib.Path(tempfile.mkdtemp(prefix="crm-release-", dir=uploads))
os.chmod(root, 0o711)
for name, expected in entries.items():
    if name.startswith("/") or any(p in ("", ".", "..") for p in name.split("/")):
        raise ValueError("invalid staged filename")
    dest = root / name
    dest.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
    fd = os.open(os.path.join(source, name), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as src, open(dest, "xb") as out:
        if not stat.S_ISREG(os.fstat(src.fileno()).st_mode):
            raise ValueError("invalid staged file")
        digest = hashlib.sha256()
        while True:
            chunk = src.read(1048576)
            if not chunk:
                break
            digest.update(chunk)
            out.write(chunk)
        if digest.hexdigest() != expected:
            raise ValueError("staged file digest mismatch")
    os.chmod(dest, 0o644)
if action["release"]:
    cmd = [sys.executable, str(root / "release.py"), "verify", "--release-dir",
           str(root / "release"), "--revision", action["revision"], "--run-id",
           action["run_id"], "--manifest-sha256", action["manifest"]]
    subprocess.run(cmd, check=True)
cmd = [sys.executable, str(root / "deploy_linux.py"), action["name"], "--config",
       action["config"], "--environment", action["environment"]]
if action["release"]:
    cmd += ["--release-dir", str(root / "release"), "--revision", action["revision"],
            "--run-id", action["run_id"], "--manifest-sha256", action["manifest"]]
if action["bootstrap"]:
    cmd += ["--bootstrap-file", action["bootstrap"]]
sys.exit(subprocess.run(cmd).returncode)
'''


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
    scp_cmd = [
        'scp',
        '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ConnectTimeout=15',
    ]

    if config_path is not None:
        posix_p = config_path.resolve().as_posix()
        ssh_cmd.extend(['-F', posix_p])
        scp_cmd.extend(['-F', posix_p])
    if known_hosts_path is not None:
        posix_p = known_hosts_path.resolve().as_posix()
        ssh_cmd.extend(['-o', f'UserKnownHostsFile={posix_p}'])
        scp_cmd.extend(['-o', f'UserKnownHostsFile={posix_p}'])
    if identity_path is not None:
        posix_p = identity_path.resolve().as_posix()
        ssh_cmd.extend(['-i', posix_p])
        scp_cmd.extend(['-i', posix_p])
    if port is not None:
        ssh_cmd.extend(['-p', str(port)])
        scp_cmd.extend(['-P', str(port)])

    target = f'{user}@{host}' if user else host
    return ssh_cmd, scp_cmd, target


def deploy_transport(action, environment, remote_config, ssh_host, ssh_user=None,
                     ssh_port=None, ssh_config=None, ssh_identity=None,
                     ssh_known_hosts=None, release_dir=None, revision=None,
                     run_id=None, manifest_sha256=None, bootstrap_file=None):
    if action not in ACTIONS:
        raise DeployError(f'unknown action: {action}')
    if environment not in ENVIRONMENTS:
        raise DeployError(f'unknown environment: {environment}')
    clean_config = check_remote_path(remote_config, 'remote-config')
    if action not in ('migrate', 'bootstrap', 'deploy') and any(
            value is not None for value in (release_dir, revision, run_id, manifest_sha256)):
        raise DeployError('release arguments are not allowed for this action')
    if action != 'bootstrap' and bootstrap_file is not None:
        raise DeployError('bootstrap file is only allowed for bootstrap')

    if action in ('migrate', 'bootstrap', 'deploy'):
        if not (release_dir and revision and run_id and manifest_sha256):
            raise DeployError(f'action {action} requires --release-dir, --revision, --run-id, --manifest-sha256')
        ci.candidate(revision, run_id)
        if not isinstance(manifest_sha256, str) or not ci.HASH.fullmatch(manifest_sha256):
            raise DeployError('invalid manifest-sha256: expected 64 lowercase hex digits')
        # Local verification must succeed before any remote transport
        try:
            release_metadata = release.verify_release(release_dir, revision, run_id, manifest_sha256)
        except release.ReleaseError as err:
            raise DeployError(f'local release verification failed: {err}') from err

    clean_bootstrap = None
    if action == 'bootstrap':
        if not bootstrap_file:
            raise DeployError('action bootstrap requires --bootstrap-file')
        clean_bootstrap = check_remote_path(bootstrap_file, 'bootstrap-file')

    scripts_dir = Path(__file__).resolve().parent
    entries = {}
    for dep in CONTROLLER_DEPENDENCIES:
        with ci.open_regular(scripts_dir / dep) as source:
            entries[dep] = ci.digest_file(source)['sha256']
    if action in ('migrate', 'bootstrap', 'deploy'):
        entries.update({'release/' + name: metadata['sha256']
                        for name, metadata in release_metadata['files'].items()})
        entries['release/release.json'] = manifest_sha256

    ssh_cmd, scp_cmd, target = build_ssh_base_cmds(
        ssh_host, ssh_user, ssh_port, ssh_config, ssh_identity, ssh_known_hosts
    )

    mktemp_cmd = 'sh -c "umask 077 && mktemp -d /tmp/crm-upload-XXXXXXXXXX"'
    create_proc = subprocess.run(ssh_cmd + [target, mktemp_cmd], stdout=subprocess.PIPE,
                                 stderr=None, text=True, check=False)
    if create_proc.returncode:
        return create_proc.returncode
    remote_upload_dir = create_proc.stdout.strip()
    if not UPLOAD_DIR_RE.fullmatch(remote_upload_dir):
        raise DeployError('unexpected remote upload directory')
    dep_files = [str(ci.checked_path(scripts_dir / dep)) for dep in CONTROLLER_DEPENDENCIES]
    result = subprocess.run(scp_cmd + dep_files + [f'{target}:{remote_upload_dir}/'])
    if result.returncode:
        return result.returncode
    if action in ('migrate', 'bootstrap', 'deploy'):
        mkdir_cmd = 'mkdir -m 0700 ' + shlex.quote(remote_upload_dir + '/release')
        result = subprocess.run(ssh_cmd + [target, mkdir_cmd])
        if result.returncode:
            return result.returncode
        rel_root = ci.checked_path(release_dir)
        rel_items = [str(rel_root / item) for item in ('app', 'migration', 'release.json')]
        result = subprocess.run(scp_cmd + ['-r'] + rel_items + [f'{target}:{remote_upload_dir}/release/'])
        if result.returncode:
            return result.returncode
    request = {'name': action, 'config': clean_config, 'environment': environment,
               'release': action in ('migrate', 'bootstrap', 'deploy'),
               'revision': revision, 'run_id': run_id, 'manifest': manifest_sha256,
               'bootstrap': clean_bootstrap}
    command = ['sudo', '-n', 'python3', '-c', PROMOTE, remote_upload_dir,
               json.dumps(entries, separators=(',', ':')), json.dumps(request, separators=(',', ':'))]
    # Retain uniquely created staging directories; never recursively delete a caller path.
    return subprocess.run(ssh_cmd + [target, ' '.join(shlex.quote(arg) for arg in command)]).returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=ACTIONS, help='Action to execute on target')
    parser.add_argument('--environment', required=True, choices=ENVIRONMENTS, help='Target environment')
    parser.add_argument('--remote-config', help='Absolute POSIX path to remote target configuration JSON')
    parser.add_argument('--config', help='Alias for --remote-config')
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
    parser.add_argument('--bootstrap-file', help='Absolute path to private server bootstrap JSON')

    args = parser.parse_args(argv)
    remote_cfg = args.remote_config or args.config
    if not remote_cfg:
        print('error: --remote-config (or --config) is required', file=sys.stderr)
        return 1

    try:
        return deploy_transport(
            action=args.action,
            environment=args.environment,
            remote_config=remote_cfg,
            ssh_host=args.ssh_host,
            ssh_user=args.ssh_user,
            ssh_port=args.ssh_port,
            ssh_config=args.ssh_config,
            ssh_identity=args.ssh_identity,
            ssh_known_hosts=args.ssh_known_hosts,
            release_dir=args.release_dir,
            revision=args.revision,
            run_id=args.run_id,
            manifest_sha256=args.manifest_sha256,
            bootstrap_file=args.bootstrap_file
        )
    except DeployError as err:
        print(f'error: {err}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
