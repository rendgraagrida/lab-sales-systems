#!/usr/bin/python3 -I
"""Administrator-installed, policy-pinned staging deployment gateway."""
import argparse
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import sys
import tempfile

CONTROLLER_ROOT = '/opt/lab-sales-systems/controller'
CONTROLLERS = ('ci.py', 'release.py', 'database.py', 'deploy_linux.py', 'deploy_gateway.py')
ACTIONS = ('migrate', 'deploy', 'status', 'stop', 'rollback', 'recover')
HASH = re.compile(r'[0-9a-f]{64}\Z')
UPLOAD = re.compile(r'/tmp/(crm-upload-[A-Za-z0-9._-]+)/release\Z')
MAX_MANIFEST = 1024 * 1024
MAX_FILE = 512 * 1024 * 1024


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
    keys = {'schema', 'project', 'environment', 'target_config', 'deploy_user', 'upload_parent', 'promotion_root'}
    if (not isinstance(policy, dict) or set(policy) != keys or type(policy['schema']) is not int
            or policy['schema'] != 1 or policy['project'] != 'lab-sales-systems'
            or policy['environment'] not in ('staging', 'production')
            or policy['upload_parent'] != '/tmp' or not isinstance(policy['deploy_user'], str)):
        raise GatewayError('invalid policy')
    with trusted_file(policy['target_config'], 0o600):
        pass
    trusted_directory(policy['promotion_root'])
    user = pwd.getpwnam(policy['deploy_user'])
    if user.pw_uid == 0 or os.environ.get('SUDO_USER') != user.pw_name:
        raise GatewayError('invalid deploying identity')
    return policy, user.pw_uid


def validate_request(request, policy):
    keys = {'schema', 'action', 'environment', 'release_dir', 'revision', 'run_id', 'manifest_sha256', 'controller_sha256'}
    if (not isinstance(request, dict) or set(request) != keys or type(request['schema']) is not int
            or request['schema'] != 1 or request['action'] not in ACTIONS
            or request['environment'] != policy['environment']):
        raise GatewayError('invalid request')
    hashes = request['controller_sha256']
    if (not isinstance(hashes, dict) or set(hashes) != set(CONTROLLERS)
            or any(not isinstance(v, str) or not HASH.fullmatch(v) for v in hashes.values())):
        raise GatewayError('invalid controller hashes')
    values = [request[k] for k in ('release_dir', 'revision', 'run_id', 'manifest_sha256')]
    if request['action'] in ('migrate', 'deploy'):
        if (not all(isinstance(v, str) for v in values) or not UPLOAD.fullmatch(values[0])
                or not HASH.fullmatch(values[3])):
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


def open_dir(name, parent, uid, private=False):
    fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
    info = os.fstat(fd)
    forbidden = 0o077 if private else 0o022
    if info.st_uid != uid or info.st_mode & forbidden:
        os.close(fd)
        raise GatewayError('untrusted upload directory')
    return fd


def read_source(parent, name, uid, limit):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    info = os.fstat(fd)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != uid or info.st_nlink != 1
            or info.st_size > limit or info.st_mode & 0o022):
        os.close(fd)
        raise GatewayError('untrusted upload file')
    return os.fdopen(fd, 'rb')


def promote(request, policy, uid, release_module):
    match = UPLOAD.fullmatch(request['release_dir'])
    tmp = os.open('/tmp', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    opened = [tmp]
    try:
        info = os.fstat(tmp)
        if info.st_uid != 0 or not info.st_mode & stat.S_ISVTX:
            raise GatewayError('untrusted upload parent')
        upload = open_dir(match.group(1), tmp, uid, private=True); opened.append(upload)
        root = open_dir('release', upload, uid); opened.append(root)
        if set(os.listdir(upload)) != {'release'}:
            raise GatewayError('invalid upload inventory')
        with read_source(root, 'release.json', uid, MAX_MANIFEST) as source:
            raw = source.read(MAX_MANIFEST + 1)
        if len(raw) > MAX_MANIFEST or hashlib.sha256(raw).hexdigest() != request['manifest_sha256']:
            raise GatewayError('manifest digest mismatch')
        manifest = parse_json(raw)
        if not isinstance(manifest, dict) or not isinstance(manifest.get('schema_versions'), list):
            raise GatewayError('invalid manifest')
        versions = manifest['schema_versions']
        if versions not in ([str(i) for i in range(1, n + 1)] for n in range(3, len(release_module.SQL_NAMES) + 1)):
            raise GatewayError('invalid schema versions')
        files = manifest.get('files')
        expected = {'app/application.jar', 'app/manifest.json', 'migration/pom.xml'} | {
            'migration/sql/' + name for name in release_module.SQL_NAMES[:len(versions)]}
        if not isinstance(files, dict) or set(files) != expected:
            raise GatewayError('invalid manifest inventory')
        for name, item in files.items():
            limit = MAX_FILE if name == 'app/application.jar' else 4 * 1024 * 1024
            if (not isinstance(item, dict) or set(item) != {'sha256', 'size'}
                    or not isinstance(item['sha256'], str) or not HASH.fullmatch(item['sha256'])
                    or type(item['size']) is not int or not 0 <= item['size'] <= limit):
                raise GatewayError('invalid file metadata')
        app = open_dir('app', root, uid); opened.append(app)
        migration = open_dir('migration', root, uid); opened.append(migration)
        sql = open_dir('sql', migration, uid); opened.append(sql)
        if (set(os.listdir(root)) != {'app', 'migration', 'release.json'}
                or set(os.listdir(app)) != {'application.jar', 'manifest.json'}
                or set(os.listdir(migration)) != {'pom.xml', 'sql'}
                or set(os.listdir(sql)) != set(release_module.SQL_NAMES[:len(versions)])):
            raise GatewayError('invalid upload inventory')
        destination = Path(tempfile.mkdtemp(prefix='crm-release-', dir=policy['promotion_root']))
        os.chmod(destination, 0o755)
        for name in ('app', 'migration', 'migration/sql'):
            (destination / name).mkdir(mode=0o755)
            os.chmod(destination / name, 0o755)
        (destination / 'release.json').write_bytes(raw)
        os.chmod(destination / 'release.json', 0o644)
        directories = {'app': app, 'migration': migration, 'migration/sql': sql}
        for name in sorted(expected):
            directory, basename = name.rsplit('/', 1)
            meta = files[name]
            digest = hashlib.sha256()
            count = 0
            with read_source(directories[directory], basename, uid, meta['size']) as source, (destination / name).open('xb') as target:
                while True:
                    chunk = source.read(min(1024 * 1024, meta['size'] - count + 1))
                    if not chunk:
                        break
                    count += len(chunk)
                    if count > meta['size']:
                        raise GatewayError('upload size changed')
                    digest.update(chunk)
                    target.write(chunk)
            if count != meta['size'] or digest.hexdigest() != meta['sha256']:
                raise GatewayError('upload digest mismatch')
            os.chmod(destination / name, 0o644)
        release_module.verify_release(destination, request['revision'], request['run_id'], request['manifest_sha256'])
        return str(destination)
    finally:
        for fd in reversed(opened):
            os.close(fd)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--policy', required=True)
    parser.add_argument('--request', required=True)
    args = parser.parse_args(argv)
    try:
        if os.geteuid() != 0 or len(args.request.encode('utf-8')) > MAX_MANIFEST:
            raise GatewayError('root and bounded request required')
        policy, uid = load_policy(args.policy)
        request = parse_json(args.request)
        validate_request(request, policy)
        verify_controllers(request['controller_sha256'])
        sys.path.insert(0, CONTROLLER_ROOT)
        import ci
        import release
        import deploy_linux
        adapter_args = [request['action'], '--config', policy['target_config'], '--environment', policy['environment']]
        if request['action'] in ('migrate', 'deploy'):
            try:
                ci.candidate(request['revision'], request['run_id'])
                promoted = promote(request, policy, uid, release)
            except (ci.CheckError, release.ReleaseError) as error:
                raise GatewayError('release rejected') from error
            adapter_args += ['--release-dir', promoted, '--revision', request['revision'],
                             '--run-id', request['run_id'], '--manifest-sha256', request['manifest_sha256']]
        return deploy_linux.main(adapter_args)
    except (GatewayError, OSError, ValueError, KeyError, TypeError, RecursionError) as error:
        print('error: gateway: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
