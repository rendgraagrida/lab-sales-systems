#!/usr/bin/python3 -I
"""Administrator-installed, policy-pinned staging deployment gateway."""
import argparse
from contextlib import contextmanager
import fcntl
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import stat
import sys
import struct
import tempfile

CONTROLLER_ROOT = '/opt/lab-sales-systems/controller'
CONTROLLERS = ('ci.py', 'release.py', 'database.py', 'deploy_linux.py',
               'deploy_gateway.py', 'deploy_entry.py', 'deploy_sandbox.py')
ACTIONS = ('migrate', 'deploy', 'status', 'stop', 'rollback', 'recover')
HASH = re.compile(r'[0-9a-f]{64}\Z')
MAX_MANIFEST = 1024 * 1024
MAX_FILE = 512 * 1024 * 1024
MAX_TOTAL = 576 * 1024 * 1024


class GatewayError(Exception):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise GatewayError('duplicate JSON key')
        result[key] = value
    return result


def parse_json(data):
    return json.loads(data, object_pairs_hook=unique_object)


def clean_path(value):
    if (not isinstance(value, str) or not value.startswith('/') or value == '/'
            or any(part in ('', '.', '..') for part in value[1:].split('/'))
            or any(ord(c) < 32 or ord(c) == 127 for c in value)):
        raise GatewayError('invalid absolute path')
    return value


def trusted_directory(path):
    clean_path(path)
    current = '/'
    for part in path[1:].split('/'):
        current = os.path.join(current, part)
        info = os.lstat(current)
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise GatewayError('untrusted directory: ' + current)


def trusted_file(path, mode=None):
    clean_path(path)
    parent = os.path.dirname(path)
    if parent != '/':
        trusted_directory(parent)
    info = os.lstat(path)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022
            or (mode is not None and stat.S_IMODE(info.st_mode) != mode)):
        raise GatewayError('untrusted file: ' + path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    opened = os.fstat(fd)
    if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
        os.close(fd)
        raise GatewayError('trusted file changed')
    return os.fdopen(fd, 'rb')


def load_policy(path):
    with trusted_file(path, 0o600) as source:
        data = source.read(MAX_MANIFEST + 1)
    if len(data) > MAX_MANIFEST:
        raise GatewayError('policy too large')
    policy = parse_json(data)
    keys = {'schema', 'project', 'environment', 'target_config', 'deploy_user', 'promotion_root'}
    if (not isinstance(policy, dict) or set(policy) != keys or type(policy['schema']) is not int
            or policy['schema'] != 2 or policy['project'] != 'lab-sales-systems'
            or policy['environment'] not in ('staging', 'production')
            or not isinstance(policy['deploy_user'], str)):
        raise GatewayError('invalid policy')
    with trusted_file(policy['target_config'], 0o600):
        pass
    trusted_directory(policy['promotion_root'])
    user = pwd.getpwnam(policy['deploy_user'])
    if user.pw_uid == 0 or user.pw_name != policy['deploy_user']:
        raise GatewayError('invalid deploying identity')
    return policy, user.pw_uid


def validate_request(request, policy, policy_path):
    keys = {'schema', 'policy', 'action', 'environment', 'revision', 'run_id', 'manifest_sha256', 'controller_sha256'}
    if (not isinstance(request, dict) or set(request) != keys or type(request['schema']) is not int
            or request['schema'] != 2 or request['policy'] != policy_path
            or request['action'] not in ACTIONS or request['environment'] != policy['environment']):
        raise GatewayError('invalid request')
    hashes = request['controller_sha256']
    if (not isinstance(hashes, dict) or set(hashes) != set(CONTROLLERS)
            or any(not isinstance(v, str) or not HASH.fullmatch(v) for v in hashes.values())):
        raise GatewayError('invalid controller hashes')
    values = [request[k] for k in ('revision', 'run_id', 'manifest_sha256')]
    if request['action'] in ('migrate', 'deploy'):
        if not all(isinstance(v, str) for v in values) or not HASH.fullmatch(values[2]):
            raise GatewayError('invalid release request')
    elif any(value is not None for value in values):
        raise GatewayError('release fields forbidden')


def verify_controllers(hashes):
    trusted_directory(CONTROLLER_ROOT)
    for name in CONTROLLERS:
        with trusted_file(os.path.join(CONTROLLER_ROOT, name)) as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        if digest != hashes[name]:
            raise GatewayError('installed controller mismatch: ' + name)
    cache = os.path.join(CONTROLLER_ROOT, '__pycache__')
    if os.path.lexists(cache):
        trusted_directory(cache)
        for name in CONTROLLERS:
            cached = importlib.util.cache_from_source(os.path.join(CONTROLLER_ROOT, name))
            if os.path.lexists(cached):
                with trusted_file(cached):
                    pass



@contextmanager
def promotion_lock(policy, lock_name='.gateway-promotion.lock'):
    root = os.open(policy['promotion_root'], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        info = os.fstat(root)
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise GatewayError('untrusted promotion directory')
        fd = os.open(lock_name,
                     os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=root)
        try:
            info = os.fstat(fd)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                    or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600):
                raise GatewayError('untrusted promotion lock')
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise GatewayError('promotion already in progress') from error
            yield
        finally:
            os.close(fd)
    finally:
        os.close(root)


def read_exact(source, count):
    chunks = []
    while count:
        chunk = source.read(min(count, 1024 * 1024))
        if not chunk:
            raise GatewayError('short request stream')
        chunks.append(chunk)
        count -= len(chunk)
    return b''.join(chunks)


@contextmanager
def receive(request, policy, source, release_module):
    size = struct.unpack('>Q', read_exact(source, 8))[0]
    if size > MAX_MANIFEST:
        raise GatewayError('manifest too large')
    raw = read_exact(source, size)
    if hashlib.sha256(raw).hexdigest() != request['manifest_sha256']:
        raise GatewayError('manifest digest mismatch')
    manifest = parse_json(raw)
    if (not isinstance(manifest, dict)
            or set(manifest) != {'schema', 'project', 'revision', 'run_id', 'schema_versions', 'files'}
            or type(manifest['schema']) is not int or manifest['schema'] != 1
            or manifest['project'] != 'lab-sales-systems'
            or manifest['revision'] != request['revision'] or manifest['run_id'] != request['run_id']
            or manifest['schema_versions'] != [str(i) for i in range(1, len(release_module.SQL_NAMES) + 1)]):
        raise GatewayError('invalid manifest identity')
    files = manifest['files']
    expected = {'app/application.jar', 'app/manifest.json', 'migration/pom.xml'} | {
        'migration/sql/' + name for name in release_module.SQL_NAMES}
    if not isinstance(files, dict) or set(files) != expected:
        raise GatewayError('invalid manifest inventory')
    total = 0
    for name, item in files.items():
        limit = MAX_FILE if name == 'app/application.jar' else 4 * 1024 * 1024
        if (not isinstance(item, dict) or set(item) != {'sha256', 'size'}
                or not isinstance(item['sha256'], str) or not HASH.fullmatch(item['sha256'])
                or type(item['size']) is not int or not 0 <= item['size'] <= limit):
            raise GatewayError('invalid file metadata')
        total += item['size']
    if total > MAX_TOTAL:
        raise GatewayError('release too large')
    destination = Path(tempfile.mkdtemp(prefix='crm-release-', dir=policy['promotion_root']))
    try:
        os.chmod(destination, 0o755)
        for name in ('app', 'migration', 'migration/sql'):
            (destination / name).mkdir(mode=0o755)
            os.chmod(destination / name, 0o755)
        (destination / 'release.json').write_bytes(raw)
        os.chmod(destination / 'release.json', 0o644)
        for name in sorted(expected):
            meta = files[name]
            digest = hashlib.sha256()
            remaining = meta['size']
            with (destination / name).open('xb') as target:
                while remaining:
                    chunk = source.read(min(remaining, 1024 * 1024))
                    if not chunk:
                        raise GatewayError('short file stream')
                    remaining -= len(chunk)
                    digest.update(chunk)
                    target.write(chunk)
            if digest.hexdigest() != meta['sha256']:
                raise GatewayError('file digest mismatch')
            os.chmod(destination / name, 0o644)
        if source.read(1):
            raise GatewayError('trailing request data')
        release_module.verify_release(destination, request['revision'], request['run_id'], request['manifest_sha256'])
        yield str(destination)
    finally:
        shutil.rmtree(destination)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--policy', required=True)
    parser.add_argument('--stdio', action='store_true', required=True)
    argv = sys.argv[1:] if argv is None else argv
    if argv not in (['--help'], ['-h']) and (len(argv) != 3 or argv[0] != '--policy' or argv[2] != '--stdio'):
        parser.error('expected --policy PATH --stdio')
    args = parser.parse_args(argv)
    try:
        if os.geteuid() != 0:
            raise GatewayError('root required')
        policy, _ = load_policy(args.policy)
        with trusted_file(policy['target_config'], 0o600) as config_source:
            config_bytes = config_source.read(MAX_MANIFEST + 1)
        if len(config_bytes) > MAX_MANIFEST:
            raise GatewayError('target config too large')
        config = parse_json(config_bytes)
        sys.path.insert(0, CONTROLLER_ROOT)
        trusted_directory(CONTROLLER_ROOT)
        for name in CONTROLLERS:
            with trusted_file(os.path.join(CONTROLLER_ROOT, name)):
                pass
        import deploy_sandbox
        deploy_sandbox.validate(config['sandbox'], config['target_id'])
        if not Path(policy['promotion_root']).is_relative_to(config['sandbox']['storage_root']):
            raise GatewayError('promotion outside bounded storage')
        if not deploy_sandbox.in_controller(config['sandbox'], config['target_id']):
            raise GatewayError('controller confinement required')
        source = sys.stdin.buffer
        header = source.readline(65537)
        if len(header) > 65536 or not header.endswith(b'\n'):
            raise GatewayError('invalid request header')
        request = parse_json(header.decode('utf-8'))
        if json.dumps(request, separators=(',', ':'), ensure_ascii=True).encode('utf-8') + b'\n' != header:
            raise GatewayError('noncanonical request header')
        validate_request(request, policy, args.policy)
        verify_controllers(request['controller_sha256'])
        import ci
        import release
        import deploy_linux
        adapter_args = [request['action'], '--config', policy['target_config'], '--environment', policy['environment']]
        with promotion_lock(policy):
            if request['action'] in ('migrate', 'deploy'):
                ci.candidate(request['revision'], request['run_id'])
                with receive(request, policy, source, release) as promoted:
                    adapter_args += ['--release-dir', promoted, '--revision', request['revision'],
                                     '--run-id', request['run_id'], '--manifest-sha256', request['manifest_sha256']]
                    return deploy_linux.main(adapter_args)
            if source.read(1):
                raise GatewayError('trailing request data')
            return deploy_linux.main(adapter_args)
    except (GatewayError, OSError, ValueError, KeyError, TypeError, RecursionError) as error:
        print('error: gateway: ' + str(error), file=sys.stderr)
        return 1
    except Exception as error:
        print('error: gateway rejected: ' + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
