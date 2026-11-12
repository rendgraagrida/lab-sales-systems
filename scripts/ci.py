#!/usr/bin/env python3
"""Project documentation and immutable build artifact checks; no runtime checks."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import stat
import sys
import tempfile
from urllib.parse import unquote, urlsplit
import zlib
import zipfile

ROOT = Path(__file__).resolve().parent.parent
EXCLUDED = {'.git', '.github', '.tools', '.runtime', '.artifacts', '.venv', 'venv',
            'node_modules', 'target', 'build', 'dist', '__pycache__', '.pytest_cache',
            '.mypy_cache', '.cache', 'secrets', '.secrets'}
REVISION = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64}|content:[0-9a-f]{64})\Z')
RUN_ID = re.compile(r'(?:local|[1-9][0-9]*)\Z')
HASH = re.compile(r'[0-9a-f]{64}\Z')


class CheckError(Exception):
    pass


def candidate(revision, run_id):
    if not REVISION.fullmatch(revision):
        raise CheckError('invalid revision: expected a lowercase Git SHA or content digest')
    if not RUN_ID.fullmatch(run_id):
        raise CheckError('invalid run ID: expected a positive decimal or local')


def checked_path(value):
    path = Path(value)
    if '..' in path.parts:
        raise CheckError('path traversal is not allowed')
    path = Path(os.path.abspath(path))
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise CheckError('symlink or reparse point in path')
    return path


def open_regular(path):
    path = checked_path(path)
    flags = os.O_RDONLY | getattr(os, 'O_BINARY', 0) | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise CheckError('expected a regular file')
        return os.fdopen(fd, 'rb')
    except BaseException:
        os.close(fd)
        raise


def digest_file(source, destination=None):
    digest = hashlib.sha256()
    size = 0
    while True:
        chunk = source.read(1024 * 1024)
        if not chunk:
            break
        if destination is not None:
            destination.write(chunk)
        digest.update(chunk)
        size += len(chunk)
    return {'sha256': digest.hexdigest(), 'size': size}


def validate_jar(file):
    try:
        file.seek(0)
        with zipfile.ZipFile(file) as archive:
            if not any(not item.is_dir() for item in archive.infolist()) or archive.testzip() is not None:
                raise CheckError('invalid JAR archive')
    except (zipfile.BadZipFile, NotImplementedError, RuntimeError, EOFError, ValueError, struct.error, zlib.error):
        raise CheckError('invalid JAR archive') from None
    file.seek(0)


def package(jar, output, revision, run_id):
    candidate(revision, run_id)
    output = checked_path(output)
    with open_regular(jar) as source, tempfile.TemporaryFile() as staged:
        entry = digest_file(source, staged)
        validate_jar(staged)
        output.parent.mkdir(parents=True, exist_ok=True)
        checked_path(output.parent)
        os.mkdir(output)
        target = checked_path(output / 'application.jar')
        with target.open('xb') as copy:
            shutil.copyfileobj(staged, copy, length=1024 * 1024)
        manifest = {'schema': 1, 'project': 'lab-sales-systems', 'revision': revision,
                    'run_id': run_id, 'files': {'application.jar': entry}}
        manifest_path = checked_path(output / 'manifest.json')
        # Publish the manifest last. Never recursively clean mutable caller paths.
        with manifest_path.open('x', encoding='ascii', newline='\n') as file:
            json.dump(manifest, file, sort_keys=True, indent=2, ensure_ascii=True)
            file.write('\n')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CheckError('duplicate manifest key')
        result[key] = value
    return result


def verify(artifact_dir, revision, run_id):
    candidate(revision, run_id)
    directory = checked_path(artifact_dir)
    if not directory.is_dir():
        raise CheckError('artifact directory is missing')
    if {entry.name for entry in directory.iterdir()} != {'application.jar', 'manifest.json'}:
        raise CheckError('artifact directory must contain exactly application.jar and manifest.json')
    with open_regular(directory / 'manifest.json') as file:
        try:
            manifest = json.load(file, object_pairs_hook=unique_object)
        except (ValueError, UnicodeError, RecursionError):
            raise CheckError('invalid manifest JSON') from None
    if not isinstance(manifest, dict) or set(manifest) != {'schema', 'project', 'revision', 'run_id', 'files'}:
        raise CheckError('invalid manifest schema')
    if type(manifest['schema']) is not int or manifest['schema'] != 1 or manifest['project'] != 'lab-sales-systems':
        raise CheckError('invalid manifest schema or project')
    if manifest['revision'] != revision or manifest['run_id'] != run_id:
        raise CheckError('artifact candidate does not match revision and run ID')
    files = manifest['files']
    if not isinstance(files, dict) or set(files) != {'application.jar'}:
        raise CheckError('invalid manifest files')
    entry = files['application.jar']
    if (not isinstance(entry, dict) or set(entry) != {'sha256', 'size'}
            or not isinstance(entry['sha256'], str) or not HASH.fullmatch(entry['sha256'])
            or type(entry['size']) is not int or entry['size'] < 0):
        raise CheckError('invalid artifact digest or size')
    with open_regular(directory / 'application.jar') as file:
        if digest_file(file) != entry:
            raise CheckError('artifact digest or size mismatch')
        validate_jar(file)


def clean_markdown(text, remove_inline_code=True):
    # Fenced examples are ignored; heading slugs may retain inline code text.
    lines = []
    fence = None
    for line in text.splitlines():
        match = re.match(r'^\s{0,3}(`{3,}|~{3,})', line)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
            lines.append('')
        elif fence:
            lines.append('')
        else:
            lines.append(re.sub(r'(`+).*?\1', '', line) if remove_inline_code else line)
    return '\n'.join(lines)


def headings(text):
    result = set()
    counts = {}
    for line in clean_markdown(text, remove_inline_code=False).splitlines():
        match = re.match(r'^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$', line)
        if not match:
            continue
        title = re.sub(r'\[([^]]+)\]\([^)]*\)', r'\1', match.group(1)).lower()
        slug = ''.join(c for c in title if c.isalnum() or c in ' _-').replace(' ', '-')
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug if count == 0 else '{}-{}'.format(slug, count))
    return result


def check_docs(root=ROOT):
    root = checked_path(root)
    documents = {}
    for base, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d.lower() not in EXCLUDED and not d.startswith('.'))
        for name in sorted(files):
            if not name.lower().endswith(('.md', '.markdown')):
                continue
            path = checked_path(Path(base) / name)
            with open_regular(path) as file:
                data = file.read()
            if any(byte > 126 or (byte < 32 and byte not in (9, 10, 13)) for byte in data):
                raise CheckError('Markdown must be ASCII without BOM or invalid controls: ' + str(path.relative_to(root)))
            documents[path] = data.decode('ascii')
    for path, text in documents.items():
        cleaned = clean_markdown(text)
        # Inline links and reference definitions; network targets are deliberately ignored.
        targets = re.findall(r'(?<!!)\[[^\]]*\]\(\s*<?([^\s)>]+)>?(?:\s+[^)]*)?\)', cleaned)
        targets += re.findall(r'^\s{0,3}\[[^\]]+\]:\s*<?([^\s>]+)>?', cleaned, re.MULTILINE)
        for target in targets:
            parts = urlsplit(target)
            if parts.scheme or parts.netloc or target.startswith('//'):
                continue
            relative = unquote(parts.path)
            if relative.startswith('/'):
                continue
            destination = (path.parent / relative).resolve() if relative else path
            if not destination.is_relative_to(root):
                raise CheckError('local Markdown link escapes project in ' + str(path.relative_to(root)))
            if not destination.exists():
                raise CheckError('missing local Markdown link target in ' + str(path.relative_to(root)))
            if destination in documents and parts.fragment and unquote(parts.fragment) not in headings(documents[destination]):
                raise CheckError('missing local Markdown anchor in ' + str(path.relative_to(root)))


def main(argv=None):
    parser = argparse.ArgumentParser(description='Check project Markdown or package/verify a built JAR; does not run the application.')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('check-docs', help='Check project Markdown under the script-relative project root')
    pack = commands.add_parser('package', help='Create a new artifact directory from an existing JAR')
    pack.add_argument('--jar', required=True, help='Input JAR path (relative paths use the current directory)')
    pack.add_argument('--output', required=True, help='New artifact directory path (relative to current directory)')
    check = commands.add_parser('verify', help='Verify candidate identity, exact contents, digest, size and JAR')
    check.add_argument('--artifact-dir', required=True, help='Artifact directory path (relative to current directory)')
    for sub in (pack, check):
        sub.add_argument('--revision', required=True, help='40/64 lowercase Git SHA or content:<64 lowercase hex>')
        sub.add_argument('--run-id', required=True, help='Positive decimal run ID or local')
    args = parser.parse_args(argv)
    try:
        if args.command == 'check-docs':
            check_docs()
        elif args.command == 'package':
            package(args.jar, args.output, args.revision, args.run_id)
        else:
            verify(args.artifact_dir, args.revision, args.run_id)
    except (CheckError, OSError, ValueError, zipfile.BadZipFile) as error:
        # OS errors can contain sensitive absolute paths; do not echo them.
        message = str(error) if isinstance(error, CheckError) else 'file or archive operation failed'
        print('error: ' + message, file=sys.stderr)
        return 1
    print(args.command + ': OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
