#!/usr/bin/env python3
"""Prepare pinned native tools only on the disposable GitHub-hosted quality runner."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import pwd
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
VERSION = '17.11-1.pgdg24.04+2'
FINGERPRINT = 'B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8'
KEY_URL = 'https://www.postgresql.org/media/keys/ACCC4CF8.asc'
REPOSITORY = 'https://apt.postgresql.org/pub/repos/apt/'
RUNTIME = Path('/opt/lab-sales-recovery-runtime')
SOURCE = Path('/etc/apt/sources.list.d/lab-sales-recovery-pgdg.list')
KEYRING = Path('/usr/share/keyrings/lab-sales-recovery-pgdg.gpg')
POLICY = Path('/usr/sbin/policy-rc.d')
CLUSTER_CONFIG = Path('/etc/postgresql-common/createcluster.conf')
OPT = Path('/opt')
OPT_STATE = Path('/var/lib/lab-sales-recovery-opt.json')


def run(*args, env=None):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, env=env).stdout


def guard_host():
    if (sys.platform != 'linux' or os.geteuid() != 0 or
            os.environ.get('GITHUB_ACTIONS') != 'true' or
            os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted' or
            platform.machine() != 'x86_64'):
        raise RuntimeError('Only root on an amd64 GitHub-hosted Linux runner is allowed')
    os_release = Path('/etc/os-release').read_text()
    values = dict(line.split('=', 1) for line in os_release.splitlines() if '=' in line)
    if values.get('ID', '').strip('"') != 'ubuntu' or values.get('VERSION_ID', '').strip('"') != '24.04':
        raise RuntimeError('Ubuntu 24.04 is required')
    if run('dpkg', '--print-architecture').strip() != 'amd64' or not Path('/run/systemd/system').is_dir():
        raise RuntimeError('amd64 dpkg and active systemd are required')
    run('systemctl', 'show-environment')
    for path in (SOURCE.parent, KEYRING.parent, POLICY.parent,
                 CLUSTER_CONFIG.parent, OPT_STATE.parent):
        info = path.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise RuntimeError(f'Untrusted system directory: {path}')
    if not stat.S_ISDIR(OPT.lstat().st_mode):
        raise RuntimeError('/opt must be a real directory, not a symlink')


def guard_prepare():
    for path in (RUNTIME, SOURCE, KEYRING, OPT_STATE):
        if os.path.lexists(path):
            raise RuntimeError(f'Refusing pre-existing path: {path}')
    if not CLUSTER_CONFIG.is_file() or CLUSTER_CONFIG.is_symlink():
        raise RuntimeError('Existing PostgreSQL cluster policy is required')
    if os.path.lexists(POLICY) and (POLICY.is_symlink() or not POLICY.is_file()):
        raise RuntimeError('Cannot safely preserve existing service policy')
    if Path('/etc/postgresql/17').exists() or Path('/usr/lib/postgresql/17').exists():
        raise RuntimeError('Refusing an existing PostgreSQL 17 installation or cluster')


def prepare_owned_opt():
    """Owner-authorized temporary trust on this disposable runner only."""
    fd = os.open(OPT, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        saved = {'schema': 1, 'device': info.st_dev, 'inode': info.st_ino,
                 'uid': info.st_uid, 'gid': info.st_gid, 'mode': stat.S_IMODE(info.st_mode)}
        state_fd = os.open(OPT_STATE, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(state_fd, 'w', encoding='ascii') as state:
            json.dump(saved, state, sort_keys=True)
            state.write('\n')
            state.flush()
            os.fsync(state.fileno())
        os.fchown(fd, 0, 0)
        os.fchmod(fd, 0o755)
        trusted = os.fstat(fd)
        if trusted.st_uid != 0 or trusted.st_gid != 0 or stat.S_IMODE(trusted.st_mode) != 0o755:
            raise RuntimeError('Cannot establish temporary /opt trust')
        print('Temporary runner /opt trust established; original uid/gid/mode:',
              saved['uid'], saved['gid'], oct(saved['mode']), flush=True)
    finally:
        os.close(fd)


def restore_owned_opt():
    if not os.path.lexists(OPT_STATE):
        print('No prepared runner /opt ownership state to restore', flush=True)
        return
    state_fd = os.open(OPT_STATE, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(state_fd, 'r', encoding='ascii') as state:
        info = os.fstat(state.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
            raise RuntimeError('Untrusted runner /opt restoration receipt')
        raw = state.read(4097)
        if len(raw) > 4096:
            raise RuntimeError('Oversized runner /opt restoration receipt')
        saved = json.loads(raw)
    if (not isinstance(saved, dict) or set(saved) !=
            {'schema', 'device', 'inode', 'uid', 'gid', 'mode'} or saved['schema'] != 1
            or any(type(value) is not int or value < 0 for value in saved.values())
            or saved['mode'] > 0o7777):
        raise RuntimeError('Invalid runner /opt restoration receipt')
    fd = os.open(OPT, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        current = os.fstat(fd)
        if (current.st_dev, current.st_ino) != (saved['device'], saved['inode']):
            raise RuntimeError('Runner /opt identity changed; refusing restoration')
        os.fchown(fd, saved['uid'], saved['gid'])
        os.fchmod(fd, saved['mode'])
        restored = os.fstat(fd)
        if (restored.st_uid, restored.st_gid, stat.S_IMODE(restored.st_mode)) != (
                saved['uid'], saved['gid'], saved['mode']):
            raise RuntimeError('Runner /opt ownership restoration mismatch')
    finally:
        os.close(fd)
    OPT_STATE.unlink()
    print('Original runner /opt identity/owner/group/mode restored:', saved['uid'],
          saved['gid'], oct(saved['mode']), flush=True)


def replace_temporarily(path, data, mode):
    existed = os.path.lexists(path)
    original = path.read_bytes() if existed else None
    info = path.stat() if existed else None
    path.write_bytes(data)
    path.chmod(mode)
    return original, info


def restore(path, saved):
    data, info = saved
    if info is None:
        path.unlink(missing_ok=True)
    else:
        path.write_bytes(data)
        os.chown(path, info.st_uid, info.st_gid)
        path.chmod(stat.S_IMODE(info.st_mode))


def install_postgres():
    with tempfile.TemporaryDirectory(prefix='lab-sales-pgdg-') as tmp:
        armored = Path(tmp) / 'key.asc'
        with urllib.request.urlopen(KEY_URL, timeout=60) as response:
            armored.write_bytes(response.read(1024 * 1024 + 1))
        if armored.stat().st_size > 1024 * 1024:
            raise RuntimeError('PGDG key exceeds size limit')
        output = run('gpg', '--batch', '--with-colons', '--import-options', 'show-only', '--import', str(armored))
        # Only the verified primary key may authorize the repository; signing subkeys are allowed.
        records = [line.split(':') for line in output.splitlines()]
        primary_fingerprints = [records[index + 1][9] for index, fields in enumerate(records[:-1])
                                if fields[0] == 'pub' and records[index + 1][0] == 'fpr']
        if primary_fingerprints != [FINGERPRINT] or sum(fields[0] == 'pub' for fields in records) != 1:
            raise RuntimeError('PGDG primary key fingerprint mismatch')
        try:
            run('gpg', '--batch', '--dearmor', '--output', str(KEYRING), str(armored))
            KEYRING.chmod(0o644)
            SOURCE.write_text(f'deb [arch=amd64 signed-by={KEYRING}] {REPOSITORY} noble-pgdg main\n')
            SOURCE.chmod(0o644)
            run('apt-get', 'update')
            for package in ('postgresql-17', 'postgresql-client-17'):
                available = run('apt-cache', 'madison', package)
                if not any(len(parts := line.split('|')) >= 3 and parts[1].strip() == VERSION
                           and parts[2].split()
                           and parts[2].split()[0].rstrip('/') == REPOSITORY.rstrip('/')
                           for line in available.splitlines()):
                    raise RuntimeError(f'Exact PGDG package unavailable: {package}={VERSION}')
            original_config = CLUSTER_CONFIG.read_text()
            config = re.sub(r'^[ \t]*create_main_cluster[ \t]*=.*$', '', original_config, flags=re.MULTILINE)
            config += '\ncreate_main_cluster = false\n'
            cluster_saved = replace_temporarily(CLUSTER_CONFIG, config.encode(), 0o644)
            try:
                policy_saved = replace_temporarily(POLICY, b'#!/bin/sh\nexit 101\n', 0o755)
                try:
                    env = os.environ.copy()
                    env['DEBIAN_FRONTEND'] = 'noninteractive'
                    print(run('apt-get', 'install', '-y', '--no-install-recommends',
                              'postgresql-17=' + VERSION, 'postgresql-client-17=' + VERSION, env=env))
                finally:
                    restore(POLICY, policy_saved)
            finally:
                restore(CLUSTER_CONFIG, cluster_saved)
        finally:
            SOURCE.unlink(missing_ok=True)
            KEYRING.unlink(missing_ok=True)
    for package in ('postgresql-17', 'postgresql-client-17'):
        installed = run('dpkg-query', '-W', '-f=${Status} ${Version}', package).strip()
        if installed != 'install ok installed ' + VERSION:
            raise RuntimeError(f'Unexpected installed package: {package}: {installed}')
        print(package, installed)
    pwd.getpwnam('postgres')
    for name in ('postgres', 'pg_ctl', 'initdb', 'psql', 'pg_dump'):
        binary = Path('/usr/lib/postgresql/17/bin') / name
        output = run(str(binary), '--version').strip()
        if not re.search(r'\b17\.11(?:\s|$)', output):
            raise RuntimeError(f'Unexpected PostgreSQL binary version: {output}')
        print(output, 'sha256=' + hashlib.sha256(binary.read_bytes()).hexdigest())


def wrapper_maven():
    properties = {}
    for line in (ROOT / '.mvn/wrapper/maven-wrapper.properties').read_text().splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            properties[key.strip()] = value.strip()
    url = properties.get('distributionUrl', '')
    if url != 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip':
        raise RuntimeError('Unexpected Maven wrapper distribution')
    value = 0
    for char in url:
        value = (31 * value + ord(char)) % 4294967296
    return ROOT / '.tools/maven-user-home/wrapper/dists/apache-maven-3.9.16' / format(value, 'x')


def copy_runtimes():
    java_home = Path(os.environ['JAVA_HOME'])
    maven_home = wrapper_maven()
    if not (java_home / 'bin/java').is_file() or not (maven_home / 'bin/mvn').is_file():
        raise RuntimeError('Configured JDK or resolved project wrapper Maven is missing')
    RUNTIME.mkdir(mode=0o755)
    for source, name in ((java_home, 'jdk'), (maven_home, 'maven')):
        destination = RUNTIME / name
        shutil.copytree(source, destination, symlinks=False)
        for directory, dirs, files in os.walk(destination):
            for path in [Path(directory)] + [Path(directory) / item for item in dirs + files]:
                path.chmod(stat.S_IMODE(path.stat().st_mode) & ~0o022)
    java = RUNTIME / 'jdk/bin/java'
    mvn = RUNTIME / 'maven/bin/mvn'
    java_version = run(str(java), '-version')
    env = {'JAVA_HOME': str(RUNTIME / 'jdk'), 'PATH': str(RUNTIME / 'jdk/bin') + ':/usr/bin:/bin',
           'HOME': '/root', 'LANG': 'C'}
    maven_version = run(str(mvn), '--version', env=env)
    if not re.search(r'\b21\.0\.13\+9(?:\D|$)', java_version) or not re.search(r'Apache Maven 3\.9\.16\b', maven_version):
        raise RuntimeError('Copied JDK or Maven version does not match the required pin')
    for path, output in ((java, java_version), (mvn, maven_version)):
        print(output.strip(), 'sha256=' + hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'restore'))
    action = parser.parse_args().action
    guard_host()
    if action == 'restore':
        restore_owned_opt()
        return
    guard_prepare()
    try:
        prepare_owned_opt()
        install_postgres()
        copy_runtimes()
    except BaseException:
        restore_owned_opt()
        raise


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, KeyError, subprocess.CalledProcessError) as error:
        print(f'Native recovery setup failed: {error}', file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stdout:
            print(error.stdout, file=sys.stderr)
        sys.exit(1)
