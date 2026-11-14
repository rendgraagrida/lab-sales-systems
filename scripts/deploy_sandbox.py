#!/usr/bin/python3 -I
"""Native, fail-closed deployment cgroup and filesystem boundaries."""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import uuid


class SandboxError(Exception):
    pass


_KEYS = {'storage_root', 'filesystem_uuid', 'slice', 'memory_max_bytes', 'cpu_quota_percent',
         'tasks_max', 'temporary_bytes', 'project_memory_max_bytes', 'project_cpu_quota_percent',
         'project_tasks_max'}
_NAME = re.compile(r'[a-z][a-z0-9-]{0,62}\Z')
_UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z')
_CONTROLLER = '/opt/lab-sales-systems/controller/'


def _command(args):
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        return result.stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise SandboxError('required native sandbox facility unavailable') from error


def _path(value):
    if (not isinstance(value, str) or not value.startswith('/') or value == '/'
            or any(p in ('', '.', '..') for p in value[1:].split('/'))
            or not re.fullmatch(r'/[A-Za-z0-9_./-]+', value)):
        raise SandboxError('invalid sandbox path')
    return value


def _trusted_dirs(path):
    current = Path('/')
    for part in Path(path).parts[1:]:
        current = current / part
        info = current.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise SandboxError('untrusted sandbox directory')


def _shape(spec, target_id):
    if not isinstance(spec, dict) or set(spec) != _KEYS or not isinstance(target_id, str) or not _NAME.fullmatch(target_id):
        raise SandboxError('invalid sandbox specification')
    _path(spec['storage_root'])
    if (not isinstance(spec['filesystem_uuid'], str) or not _UUID.fullmatch(spec['filesystem_uuid'])
            or spec['slice'] != f'lab-sales-{target_id}.slice'):
        raise SandboxError('invalid sandbox identity')
    for key in _KEYS - {'storage_root', 'filesystem_uuid', 'slice'}:
        if type(spec[key]) is not int or not 0 < spec[key] <= 2**50:
            raise SandboxError('invalid sandbox resource limit')
    if (spec['project_memory_max_bytes'] > 1024**4
            or spec['project_cpu_quota_percent'] > 102400
            or spec['project_tasks_max'] > 1048576):
        raise SandboxError('sandbox resource limit exceeds supported bounds')
    if (spec['memory_max_bytes'] > spec['project_memory_max_bytes']
            or spec['cpu_quota_percent'] > spec['project_cpu_quota_percent']
            or spec['tasks_max'] > spec['project_tasks_max']
            or spec['temporary_bytes'] > spec['memory_max_bytes']):
        raise SandboxError('child limit exceeds project limit')


def _properties(unit, names):
    text = _command(['/usr/bin/systemctl', 'show', unit, '--property=' + ','.join(names)])
    properties = dict(line.split('=', 1) for line in text.splitlines() if '=' in line)
    if set(properties) != set(names):
        raise SandboxError('missing effective unit properties')
    return properties


def validate(spec, target_id):
    _shape(spec, target_id)
    try:
        _trusted_dirs(spec['storage_root'])
        data = json.loads(_command(['/usr/bin/findmnt', '--json', '--mountpoint', spec['storage_root'],
                                    '--output', 'TARGET,FSTYPE,UUID,OPTIONS']))
        filesystems = data['filesystems']
        if len(filesystems) != 1:
            raise SandboxError('storage mount missing')
        fs = filesystems[0]
        if (fs['target'] != spec['storage_root'] or fs['fstype'] != 'ext4'
                or fs['uuid'] != spec['filesystem_uuid']
                or not {'rw', 'nosuid', 'nodev'} <= set(fs['options'].split(','))):
            raise SandboxError('storage mount identity or options mismatch')
        props = _properties(spec['slice'], ['LoadState', 'ActiveState', 'MemoryMax', 'MemorySwapMax',
                                           'CPUQuotaPerSecUSec', 'TasksMax', 'ControlGroup'])
        if (props['LoadState'] != 'loaded' or props['ActiveState'] != 'active'
                or int(props['MemoryMax']) != spec['project_memory_max_bytes']
                or int(props['MemorySwapMax']) != 0
                or int(props['TasksMax']) != spec['project_tasks_max']
                or props['CPUQuotaPerSecUSec'] != _cpu_usec(spec['project_cpu_quota_percent'])):
            raise SandboxError('project slice limits mismatch')
        group = props['ControlGroup']
        components = spec['slice'][:-6].split('-')
        hierarchy = '/' + '/'.join('-'.join(components[:n]) + '.slice'
                                   for n in range(1, len(components) + 1))
        if group != hierarchy:
            raise SandboxError('project slice cgroup mismatch')
        cgroup = Path('/sys/fs/cgroup') / group.lstrip('/')
        quota, period = (cgroup / 'cpu.max').read_text().split()
        if (int((cgroup / 'memory.max').read_text()) != spec['project_memory_max_bytes']
                or int((cgroup / 'memory.swap.max').read_text()) != 0
                or int((cgroup / 'pids.max').read_text()) != spec['project_tasks_max']
                or quota == 'max'
                or int(quota) * 100 != int(period) * spec['project_cpu_quota_percent']):
            raise SandboxError('project cgroup limits mismatch')
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise SandboxError('sandbox verification failed') from error
    return dict(spec)


def _cpu_usec(percent):
    # systemctl renders CPUQuotaPerSecUSec as a human-readable timespan.
    value = percent * 10000
    if value % 1000000 == 0:
        return str(value // 1000000) + 's'
    if value % 1000 == 0:
        return str(value // 1000) + 'ms'
    return str(value) + 'us'


def unit_dependencies(spec):
    root = _path(spec['storage_root'])
    mount = _command(['/usr/bin/systemd-escape', '--path', '--suffix=mount', root])
    return [f'RequiresMountsFor={root}', f'Requires={mount}', f'After={mount}',
            f'BindsTo={mount}', f'AssertPathIsMountPoint={root}']


def unit_directives(spec, target_id, purpose):
    _shape(spec, target_id)
    if purpose not in ('controller', 'migration', 'bootstrap', 'runtime'):
        raise SandboxError('invalid sandbox purpose')
    directives = [f"Slice={spec['slice']}", f"MemoryMax={spec['memory_max_bytes']}",
                  'MemorySwapMax=0', f"CPUQuota={spec['cpu_quota_percent']}%",
                  f"TasksMax={spec['tasks_max']}", 'KillMode=control-group',
                  'NoNewPrivileges=yes', 'ProtectSystem=strict', 'ProtectHome=yes',
                  'PrivateDevices=yes', 'ProtectKernelTunables=yes', 'ProtectKernelModules=yes',
                  'ProtectControlGroups=yes', 'RestrictSUIDSGID=yes',
                  f'LogNamespace=lab-sales-{target_id}']
    for path in ('/tmp', '/var/tmp', '/dev/shm'):
        directives.append(f"TemporaryFileSystem={path}:rw,nosuid,nodev,size={spec['temporary_bytes']}")
    if purpose == 'controller':
        directives.extend([f"ReadWritePaths={spec['storage_root']}", 'ReadWritePaths=/etc/systemd/system'])
    return directives


def in_controller(spec, target_id):
    validate(spec, target_id)
    try:
        lines = Path('/proc/self/cgroup').read_text().splitlines()
        prefix = f'lab-sales-{target_id}-control-'
        matching = [line for line in lines if line.startswith('0::')]
        if len(matching) != 1:
            return False
        parts = matching[0][3:].strip('/').split('/')
        if (len(parts) < 2 or parts[-2] != spec['slice'] or not parts[-1].startswith(prefix)
                or not re.fullmatch(re.escape(prefix) + r'[0-9a-f]{32}\.service', parts[-1])):
            return False
        props = _properties(parts[-1], ['Slice', 'MemoryMax', 'MemorySwapMax', 'TasksMax',
                            'CPUQuotaPerSecUSec', 'NoNewPrivileges', 'ProtectSystem', 'ProtectHome',
                            'PrivateDevices', 'ProtectKernelTunables', 'ProtectKernelModules',
                            'ProtectControlGroups', 'RestrictSUIDSGID', 'KillMode', 'LogNamespace'])
        if not (props['Slice'] == spec['slice'] and int(props['MemoryMax']) == spec['memory_max_bytes']
                and int(props['MemorySwapMax']) == 0 and int(props['TasksMax']) == spec['tasks_max']
                and props['CPUQuotaPerSecUSec'] == _cpu_usec(spec['cpu_quota_percent'])
                and props['NoNewPrivileges'] == 'yes' and props['ProtectSystem'] == 'strict'
                and props['ProtectHome'] == 'yes' and props['PrivateDevices'] == 'yes'
                and props['ProtectKernelTunables'] == 'yes' and props['ProtectKernelModules'] == 'yes'
                and props['ProtectControlGroups'] == 'yes' and props['RestrictSUIDSGID'] == 'yes'
                and props['KillMode'] == 'control-group'
                and props['LogNamespace'] == f'lab-sales-{target_id}'):
            return False
        cgroup = Path('/sys/fs/cgroup').joinpath(*parts)
        quota, period = (cgroup / 'cpu.max').read_text().split()
        if (int((cgroup / 'memory.max').read_text()) != spec['memory_max_bytes']
                or int((cgroup / 'memory.swap.max').read_text()) != 0
                or int((cgroup / 'pids.max').read_text()) != spec['tasks_max']
                or quota == 'max' or int(quota) * 100 != int(period) * spec['cpu_quota_percent']):
            return False
        mounts = Path('/proc/self/mountinfo').read_text().splitlines()
        for path in ('/tmp', '/var/tmp', '/dev/shm'):
            entries = [line.split(' - ', 1) for line in mounts if line.split(' ')[4] == path]
            if len(entries) != 1 or entries[0][1].split(' ')[0] != 'tmpfs':
                return False
            fs = os.statvfs(path)
            if fs.f_blocks * fs.f_frsize > spec['temporary_bytes']:
                return False
        return True
    except (OSError, ValueError, SandboxError):
        return False


def _arguments(cfg, purpose, unit, timeout):
    spec = validate(cfg['sandbox'], cfg['target_id'])
    args = ['/usr/bin/systemd-run', '--wait', '--pipe', '--collect', '--quiet', '--service-type=exec',
            '--expand-environment=no', '--unit=' + unit]
    for directive in unit_dependencies(spec) + unit_directives(spec, cfg['target_id'], purpose):
        args.append('--property=' + directive.replace('\\', '\\\\'))
    args.append('--property=RuntimeMaxSec=' + str(timeout))
    return args


def _env(args, environment):
    for key, value in (environment or {}).items():
        if (key not in ('JAVA_HOME', 'PATH', 'HOME', 'LANG') or not isinstance(value, str)
                or '\n' in value or '\x00' in value or (key == 'LANG' and value != 'C')):
            raise SandboxError('invalid nonsecret environment')
        args.append('--setenv=' + key + '=' + value)


def run_controller(cfg, command, environment=None):
    if (os.geteuid() != 0 or len(command) < 3 or command[0] != '/usr/bin/python3'
            or command[1] != '-I' or command[2] not in
            (_CONTROLLER + 'deploy_gateway.py', _CONTROLLER + 'deploy_linux.py')):
        raise SandboxError('invalid controller command')
    _trusted_dirs(_CONTROLLER.rstrip('/'))
    info = os.lstat(command[2])
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise SandboxError('untrusted controller command')
    unit = f"lab-sales-{cfg['target_id']}-control-{uuid.uuid4().hex}.service"
    args = _arguments(cfg, 'controller', unit, 1800)
    _env(args, environment)
    try:
        return subprocess.run(args + ['--'] + command, check=False).returncode
    except OSError as error:
        raise SandboxError('controller execution failed') from error


def run_child(cfg, command, *, uid, gid, home, purpose, environment=None, environment_file=None, timeout=360):
    if os.geteuid() != 0 or purpose not in ('migration', 'bootstrap') or type(uid) is not int or type(gid) is not int or uid <= 0 or gid <= 0:
        raise SandboxError('invalid child identity')
    if not in_controller(cfg['sandbox'], cfg['target_id']):
        raise SandboxError('child execution requires confined controller')
    root = Path(cfg['sandbox']['storage_root'])
    if not Path(_path(home)).is_relative_to(root) or not command or not os.path.isabs(command[0]):
        raise SandboxError('invalid child command or home')
    home_path = Path(home)
    try:
        if home_path.resolve(strict=True) != home_path:
            raise SandboxError('invalid child home')
        info = home_path.stat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != uid or info.st_mode & 0o022:
            raise SandboxError('untrusted child home')
        if environment_file is not None:
            info = os.lstat(_path(environment_file))
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0
                    or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
                raise SandboxError('untrusted child environment file')
    except OSError as error:
        raise SandboxError('child path unavailable') from error
    if type(timeout) is not int or not 0 < timeout <= 3600:
        raise SandboxError('invalid child timeout')
    unit = f"lab-sales-{cfg['target_id']}-{purpose}-{uuid.uuid4().hex}.service"
    args = _arguments(cfg, purpose, unit, timeout)
    args.append('--property=ReadWritePaths=' + home)
    args.append('--property=WorkingDirectory=' + home)
    if environment_file is not None:
        args.append('--property=EnvironmentFile=' + _path(environment_file))
    child_environment = dict(environment or {})
    child_environment.update(HOME=home, LANG='C')
    _env(args, child_environment)
    args += ['--', '/usr/bin/setpriv', '--reuid', str(uid), '--regid', str(gid), '--clear-groups', '--no-new-privs', '--'] + command
    try:
        return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False, timeout=timeout + 30)
    except (OSError, subprocess.TimeoutExpired) as error:
        subprocess.run(['/usr/bin/systemctl', 'stop', unit], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        raise SandboxError('child execution failed') from error
