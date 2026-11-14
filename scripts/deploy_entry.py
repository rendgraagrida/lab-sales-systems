#!/usr/bin/python3 -I
"""Fixed, zero-argument SSH deployment supervisor."""
import json
import os
from pathlib import Path
import pwd
import re
import stat
import sys

CONTROLLER_ROOT = '/opt/lab-sales-systems/controller'
POLICY_ROOT = '/etc/lab-sales-systems/gateways'


def _trusted(path, directory):
    path = Path(path)
    current = Path('/')
    for part in path.parts[1:]:
        current = current / part
        info = current.lstat()
        if info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('untrusted installed controller')
        if current == path and not directory:
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('invalid installed controller')
        elif not stat.S_ISDIR(info.st_mode):
            raise ValueError('invalid installed controller directory')


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv or os.geteuid() != 0:
        print('error: entry requires root and no arguments', file=sys.stderr)
        return 1
    try:
        user = os.environ.get('SUDO_USER', '')
        if not re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', user):
            raise ValueError('invalid deploying identity')
        account = pwd.getpwnam(user)
        if account.pw_uid == 0 or account.pw_name != user:
            raise ValueError('invalid deploying identity')
        _trusted(CONTROLLER_ROOT, True)
        for name in ('deploy_gateway.py', 'deploy_sandbox.py'):
            _trusted(CONTROLLER_ROOT + '/' + name, False)
        sys.path.insert(0, CONTROLLER_ROOT)
        import deploy_gateway as gateway
        import deploy_sandbox as sandbox
        policy_path = POLICY_ROOT + '/' + user + '.json'
        policy, _ = gateway.load_policy(policy_path)
        if policy['deploy_user'] != user:
            raise ValueError('policy identity mismatch')
        with gateway.trusted_file(policy['target_config'], 0o600) as source:
            data = source.read(gateway.MAX_MANIFEST + 1)
        if len(data) > gateway.MAX_MANIFEST:
            raise ValueError('target config too large')
        cfg = gateway.parse_json(data)
        sandbox.validate(cfg['sandbox'], cfg['target_id'])
        if not Path(policy['promotion_root']).is_relative_to(cfg['sandbox']['storage_root']):
            raise ValueError('promotion outside bounded storage')
        with gateway.promotion_lock(dict(policy, promotion_root=policy['promotion_root']), lock_name='.gateway-request.lock'):
            return sandbox.run_controller(cfg, ['/usr/bin/python3', '-I',
                                                CONTROLLER_ROOT + '/deploy_gateway.py',
                                                '--policy', policy_path, '--stdio'])
    except (OSError, ValueError, KeyError, TypeError, ImportError) as error:
        print('error: entry rejected: ' + str(error), file=sys.stderr)
        return 1
    except Exception as error:
        print('error: entry rejected: ' + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
