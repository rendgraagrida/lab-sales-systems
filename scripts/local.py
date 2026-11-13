#!/usr/bin/env python3
"""Foreground Windows development launcher. Never provisions or controls the database."""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import signal
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import uuid

import ci
import database as db
from native_windows import NativeJob, WindowsEvent, owns_loopback_listener
import release

ROOT = ci.ROOT


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def bounded_number(value):
    try:
        result = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError('Expected a whole number.') from None
    if not 1 <= result <= 300:
        raise argparse.ArgumentTypeError('Timeout must be between 1 and 300 seconds.')
    return result


def port_number(value):
    try:
        result = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError('Expected a concrete port.') from None
    if not 1024 <= result <= 65535:
        raise argparse.ArgumentTypeError('Port must be between 1024 and 65535.')
    return result


def artifact_path(value):
    path = Path(value)
    return ci.checked_path(path if path.is_absolute() else ROOT / path)


def verify_artifact(path, revision):
    with ci.open_regular(path / 'manifest.json') as file:
        manifest = json.load(file, object_pairs_hook=ci.unique_object)
    if not isinstance(manifest, dict) or not isinstance(manifest.get('run_id'), str):
        raise db.OperationError('Artifact manifest is invalid.')
    ci.verify(path, revision, manifest['run_id'])
    if set(release.derived(path / 'application.jar')) != (
            {'migration/pom.xml'} | {'migration/sql/' + name for name in release.SQL_NAMES}):
        raise db.OperationError('Launcher requires an artifact with the current exact migration lineage.')
    return manifest['files']['application.jar']['sha256']


def receipt_path(state):
    return ci.checked_path(Path(state['folder']) / 'private' / 'local-launcher.json')


def publish(state, receipt):
    db.secure_write(receipt_path(state), json.dumps(receipt, ensure_ascii=True, indent=2) + '\n', replace=True)


def read_receipt(state):
    with ci.open_regular(receipt_path(state)) as file:
        receipt = json.load(file, object_pairs_hook=ci.unique_object)
    if (not isinstance(receipt, dict) or receipt.get('project') != 'lab-sales-systems'
            or receipt.get('root') != str(ROOT.resolve()) or receipt.get('target') != state['name']
            or not isinstance(receipt.get('instance'), str)
            or len(receipt['instance']) != 32
            or any(c not in '0123456789abcdef' for c in receipt['instance'])
            or not isinstance(receipt.get('revision'), str) or not db.REVISION.fullmatch(receipt['revision'])
            or type(receipt.get('port')) is not int or not 1024 <= receipt['port'] <= 65535
            or type(receipt.get('app_pid')) is not int or receipt['app_pid'] <= 0):
        raise db.OperationError('Launcher receipt does not identify this root and target.')
    return receipt


def event_name(receipt, purpose):
    return 'Local\\LabSales-' + receipt['instance'] + '-' + purpose


def probe(port, revision, pid):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    origin = 'http://127.0.0.1:' + str(port)
    try:
        with opener.open(Request(origin + '/health'), timeout=1) as reply:
            health = json.loads(reply.read(16385))
            if (reply.status != 200 or not isinstance(health, dict)
                    or health.get('status') != 'UP' or health.get('application') != 'lab-sales-systems'
                    or health.get('revision') != revision or type(health.get('schemaVersion')) is not int
                    or health.get('schemaVersion') != len(release.SQL_NAMES)):
                return False
        with opener.open(Request(origin + '/login'), timeout=1) as reply:
            return (reply.status == 200 and b'Sign in | Lab Sales Systems' in reply.read(16384)
                    and owns_loopback_listener(pid, port))
    except (OSError, URLError, HTTPError, ValueError):
        return False


def control(state, command, timeout):
    receipt = read_receipt(state)
    if receipt.get('phase') not in ('starting', 'ready'):
        raise db.OperationError('No live launcher is recorded for this target.')
    # Random per-instance kernel events, not a PID or listener, are the control authority.
    with (WindowsEvent(event_name(receipt, 'done')) as done,
          WindowsEvent(event_name(receipt, 'stop')) as stop,
          WindowsEvent(event_name(receipt, 'failed')) as failed):
        if done.wait(0):
            raise db.OperationError('The recorded launcher has already finished.')
        if command == 'status':
            if receipt['phase'] != 'ready' or not probe(receipt['port'], receipt['revision'], receipt['app_pid']):
                raise db.OperationError('Launcher is not functionally ready for the recorded revision.')
            print(json.dumps({k: receipt[k] for k in ('project', 'root', 'target', 'revision', 'port', 'phase')}, ensure_ascii=True))
            return 0
        stop.set()
        if not done.wait(timeout):
            raise db.OperationError('Owned launcher has not confirmed cleanup within the stop deadline; no PID kill attempted.')
        if failed.wait(0):
            raise db.OperationError('Launcher stopped with an error; inspect its private log and receipt.')
    print('Stopped this root and target application; database and all data retained.', flush=True)
    return 0


def launch(state, args):
    artifact = artifact_path(args.artifact)
    digest = verify_artifact(artifact, args.revision)
    java = db.executable(db.JAVA_HOME / 'bin', 'java')
    receipt = {'project': 'lab-sales-systems', 'root': str(ROOT.resolve()), 'target': args.name,
               'instance': uuid.uuid4().hex, 'revision': args.revision, 'artifact': str(artifact),
               'jar_sha256': digest, 'port': args.port, 'phase': 'starting', 'controller_pid': os.getpid()}
    log = ci.checked_path(Path(state['folder']) / 'private' / ('local-' + receipt['instance'] + '.log'))
    result = 1
    with ExitStack() as events:
        stop = events.enter_context(WindowsEvent(event_name(receipt, 'stop'), create=True))
        done = events.enter_context(WindowsEvent(event_name(receipt, 'done'), create=True))
        failed = events.enter_context(WindowsEvent(event_name(receipt, 'failed'), create=True))
        try:
            with db.locked(state):
                published = False
                try:
                    db.unused_port(args.port)
                    db.identity(state)  # Explicit existing dependency; never start/migrate/stop it here.
                    db.secure_write(log, '')
                    env = db.application_env(state, args.revision)
                    env['LAB_PORT'] = str(args.port)
                    for key in list(env):
                        if key.startswith(('SPRING_', 'SERVER_')) or key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                            env.pop(key, None)
                    with NativeJob() as job:
                        child = job.spawn([java, '-Xmx384m', '-jar', str(artifact / 'application.jar')], env, ROOT, log)
                        events.callback(child.close)
                        receipt['app_pid'] = child.pid
                        publish(state, receipt)
                        published = True
                        deadline = time.monotonic() + args.timeout
                        ready = False
                        while not stop.wait(0):
                            code = child.poll()
                            if code is not None:
                                raise db.OperationError('Owned application exited unexpectedly (exit ' + str(code) + '); inspect its private log.')
                            if time.monotonic() >= deadline:
                                raise db.OperationError('Functional readiness/revision deadline failed; owned application will be stopped.')
                            if probe(args.port, args.revision, child.pid) and child.poll() is None:
                                if time.monotonic() >= deadline:
                                    raise db.OperationError('Functional readiness/revision deadline failed; owned application will be stopped.')
                                ready = True
                                break
                            stop.wait(0.2)
                        if ready:
                            receipt['phase'] = 'ready'
                            publish(state, receipt)
                            print('READY root=' + str(ROOT.resolve()) + ' target=' + args.name + ' revision=' + args.revision, flush=True)
                            print('Application http://127.0.0.1:' + str(args.port) + ' (local development only)', flush=True)
                            print('Stop: \"' + str(ROOT / 'local.cmd') + '\" stop --name ' + args.name + ' or Ctrl+C in this foreground console.', flush=True)
                            while not stop.wait(1):
                                if child.poll() is not None:
                                    raise db.OperationError('Owned application exited after readiness; no restart or foreign-process reuse attempted.')
                                if not probe(args.port, args.revision, child.pid):
                                    raise db.OperationError('Application dependency/readiness failed after startup; owned application will be stopped.')
                    result = 0
                except KeyboardInterrupt:
                    result = 130
                finally:
                    # Publication and finalization share one ownership lock: no old-instance overwrite.
                    if published:
                        receipt['phase'] = 'stopped' if result in (0, 130) else 'failed'
                        receipt['exit_code'] = result
                        publish(state, receipt)
        except KeyboardInterrupt:
            result = 130
        except (db.OperationError, ci.CheckError, OSError, ValueError, KeyError, TypeError) as error:
            message = str(error) if isinstance(error, (db.OperationError, ci.CheckError)) else 'Native input/process operation failed.'
            print('error: ' + message + ' No data reset or foreign process cleanup attempted.', file=sys.stderr, flush=True)
            result = 1
        finally:
            # Per-instance result survives a successor publishing its receipt after lock release.
            if result:
                failed.set()
            done.set()
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('run', 'stop', 'status'):
        item = sub.add_parser(command)
        item.add_argument('--name', required=True)
        item.add_argument('--timeout', type=bounded_number, default=90 if command == 'run' else 30)
        if command == 'run':
            item.add_argument('--artifact', required=True)
            item.add_argument('--revision', required=True)
            item.add_argument('--port', type=port_number, default=8187)
    args = parser.parse_args(argv)
    try:
        if os.name != 'nt':
            raise db.OperationError('This local launcher targets native Windows; no WSL/POSIX fallback is selected.')
        ci.checked_path(Path(__file__).absolute())
        state = db.state_for(args.name)
        state['name'] = args.name
        if args.command == 'run':
            ci.candidate(args.revision, 'local')
            if hasattr(signal, 'SIGBREAK'):
                signal.signal(signal.SIGBREAK, signal.default_int_handler)
            return launch(state, args)
        return control(state, args.command, args.timeout)
    except (db.OperationError, ci.CheckError, release.ReleaseError, OSError, ValueError, KeyError, TypeError):
        print('error: Invalid local input, unavailable prerequisite or inactive owned instance; no process/data mutation is implied.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
