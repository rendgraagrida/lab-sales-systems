#!/usr/bin/env python3
"""Create and verify a release bound to the executable JAR and candidate identity."""
import argparse
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib

import ci


class ReleaseError(Exception):
    pass


POM = 'META-INF/maven/dev.labtrial/lab-sales-systems/pom.xml'
SQL_PREFIX = 'BOOT-INF/classes/db/foundation/'
LEGACY_SQL_NAMES = ('V1__identity.sql', 'V2__masters.sql', 'V3__finance.sql')
SQL_NAMES = LEGACY_SQL_NAMES + ('V4__procurement_inventory_ap.sql',)
NS = '{http://maven.apache.org/POM/4.0.0}'


def derived(jar):
    with ci.open_regular(jar) as source, zipfile.ZipFile(source) as archive:
        seen = set()
        selected = {}
        total = 0
        for item in archive.infolist():
            name = item.filename
            parts = PurePosixPath(name).parts
            is_symlink = (item.create_system == 3 and stat.S_ISLNK(item.external_attr >> 16))
            if (name in seen or name.startswith('/') or '\\' in name or '..' in parts
                    or is_symlink):
                raise ReleaseError('unsafe or duplicate JAR entry')
            seen.add(name)
            total += item.file_size
            if (total > 1024 * 1024 * 1024 or item.file_size > 256 * 1024 * 1024
                    or (item.file_size > 1024 * 1024 and item.file_size > max(item.compress_size, 1) * 1000)):
                raise ReleaseError('JAR expansion limit exceeded')
            if name == POM or (name.startswith(SQL_PREFIX) and not item.is_dir()):
                if item.file_size > 4 * 1024 * 1024:
                    raise ReleaseError('migration entry too large')
                selected[name] = archive.read(item)
        names = tuple(name for name in SQL_NAMES if SQL_PREFIX + name in selected)
        expected = {POM} | {SQL_PREFIX + name for name in names}
        if set(selected) != expected or names not in (LEGACY_SQL_NAMES, SQL_NAMES):
            raise ReleaseError('unexpected embedded migration lineage or POM')
    data = selected[POM]
    if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
        raise ReleaseError('unsafe embedded POM')
    root = ET.fromstring(data)
    if root.tag != NS + 'project':
        raise ReleaseError('invalid embedded POM')
    parent = root.find(NS + 'parent')
    build = root.find(NS + 'build')
    plugins = build.find(NS + 'plugins') if build is not None else None
    if parent is None or plugins is None:
        raise ReleaseError('missing Maven parent or plugins')
    flyways = [p for p in plugins.findall(NS + 'plugin')
               if p.findtext(NS + 'groupId') == 'org.flywaydb'
               and p.findtext(NS + 'artifactId') == 'flyway-maven-plugin']
    if len(flyways) != 1:
        raise ReleaseError('invalid Flyway plugin')
    plugin = flyways[0]
    location = plugin.find(NS + 'configuration/' + NS + 'locations/' + NS + 'location')
    if location is None or location.text != 'filesystem:${project.basedir}/src/main/resources/db/foundation':
        raise ReleaseError('unexpected Flyway location')
    location.text = 'filesystem:${project.basedir}/sql'
    # Keep the original parent and plugin dependency management; omit application build/dependencies.
    minimal = ET.Element(NS + 'project')
    for tag in ('modelVersion', 'parent', 'groupId', 'artifactId', 'version', 'properties'):
        element = root.find(NS + tag)
        if element is None:
            raise ReleaseError('incomplete embedded POM')
        minimal.append(element)
    new_build = ET.SubElement(minimal, NS + 'build')
    ET.SubElement(new_build, NS + 'plugins').append(plugin)
    ET.register_namespace('', 'http://maven.apache.org/POM/4.0.0')
    result = {'migration/pom.xml': ET.tostring(minimal, encoding='utf-8', xml_declaration=True)}
    result.update({'migration/sql/' + name: selected[SQL_PREFIX + name] for name in names})
    return result


def schema_identity(jar):
    migrations = derived(jar)
    rows = []
    for index, name in enumerate(SQL_NAMES, 1):
        data = migrations.get('migration/sql/' + name)
        if data is None:
            break
        checksum = 0
        with io.TextIOWrapper(io.BytesIO(data), encoding='utf-8-sig', newline=None) as lines:
            for line in lines:
                checksum = zlib.crc32(line.removesuffix('\n').encode('utf-8'), checksum)
        if checksum >= 2 ** 31:
            checksum -= 2 ** 32
        rows.append(f'{index}|{name}|{checksum}')
    return ','.join(rows)


def inventory(directory):
    result = set()
    for base, dirs, files in os.walk(directory, followlinks=False):
        for name in dirs + files:
            path = ci.checked_path(Path(base) / name)
            if name in dirs and not path.is_dir():
                raise ReleaseError('invalid release directory')
        for name in files:
            result.add((Path(base) / name).relative_to(directory).as_posix())
    expected_dirs = {'app', 'migration', 'migration/sql'}
    actual_dirs = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_dir()}
    if actual_dirs != expected_dirs:
        raise ReleaseError('invalid release directory inventory')
    return result


def package_release(application_dir, output, revision, run_id):
    try:
        ci.candidate(revision, run_id)
        application_dir = ci.checked_path(application_dir)
        output = ci.checked_path(output)
        # Bound archive expansion before the existing generic JAR verifier reads it.
        migrations = derived(application_dir / 'application.jar')
        if set(migrations) != {'migration/pom.xml'} | {'migration/sql/' + name for name in SQL_NAMES}:
            raise ReleaseError('new releases must contain the current schema lineage')
        ci.verify(application_dir, revision, run_id)
        output.parent.mkdir(parents=True, exist_ok=True)
        ci.checked_path(output.parent)
        os.mkdir(output)
        for directory in ('app', 'migration', 'migration/sql'):
            ci.checked_path(output / directory).mkdir()
        files = {}
        for name in ('application.jar', 'manifest.json'):
            relative = 'app/' + name
            with ci.open_regular(application_dir / name) as source, ci.checked_path(output / relative).open('xb') as target:
                files[relative] = ci.digest_file(source, target)
        for relative, data in migrations.items():
            with ci.checked_path(output / relative).open('xb') as target:
                target.write(data)
            with ci.open_regular(output / relative) as source:
                files[relative] = ci.digest_file(source)
        ci.verify(output / 'app', revision, run_id)
        if derived(output / 'app/application.jar') != migrations:
            raise ReleaseError('application changed while packaging')
        manifest = {'schema': 1, 'project': 'lab-sales-systems', 'revision': revision,
                    'run_id': run_id, 'schema_versions': [str(i) for i in range(1, len(SQL_NAMES) + 1)], 'files': files}
        with ci.checked_path(output / 'release.json').open('x', encoding='ascii', newline='\n') as target:
            json.dump(manifest, target, sort_keys=True, indent=2, ensure_ascii=True)
            target.write('\n')
        return manifest
    except (ci.CheckError, OSError, ValueError, ET.ParseError, zipfile.BadZipFile, RuntimeError) as error:
        raise ReleaseError('release packaging failed') from error


def verify_release(directory, revision, run_id, manifest_sha256):
    try:
        ci.candidate(revision, run_id)
        if not isinstance(manifest_sha256, str) or not ci.HASH.fullmatch(manifest_sha256):
            raise ReleaseError('invalid external manifest SHA256')
        directory = ci.checked_path(directory)
        if not directory.is_dir():
            raise ReleaseError('release directory missing')
        with ci.open_regular(directory / 'release.json') as source:
            if ci.digest_file(source)['sha256'] != manifest_sha256:
                raise ReleaseError('release manifest SHA256 mismatch')
            source.seek(0)
            manifest = json.load(source, object_pairs_hook=ci.unique_object)
        if (not isinstance(manifest, dict) or set(manifest) != {'schema', 'project', 'revision', 'run_id', 'schema_versions', 'files'}
                or type(manifest['schema']) is not int or manifest['schema'] != 1
                or manifest['project'] != 'lab-sales-systems' or manifest['revision'] != revision
                or manifest['run_id'] != run_id or manifest['schema_versions'] not in
                ([str(i) for i in range(1, len(LEGACY_SQL_NAMES) + 1)],
                 [str(i) for i in range(1, len(SQL_NAMES) + 1)])):
            raise ReleaseError('invalid release manifest identity')
        names = LEGACY_SQL_NAMES if len(manifest['schema_versions']) == len(LEGACY_SQL_NAMES) else SQL_NAMES
        expected = {'app/application.jar', 'app/manifest.json', 'migration/pom.xml'} | {'migration/sql/' + name for name in names}
        files = manifest['files']
        if not isinstance(files, dict) or set(files) != expected or inventory(directory) != expected | {'release.json'}:
            raise ReleaseError('invalid release inventory')
        for name in expected:
            entry = files[name]
            if (not isinstance(entry, dict) or set(entry) != {'sha256', 'size'}
                    or not isinstance(entry['sha256'], str) or not ci.HASH.fullmatch(entry['sha256'])
                    or type(entry['size']) is not int or entry['size'] < 0):
                raise ReleaseError('invalid release file metadata')
            with ci.open_regular(directory / name) as source:
                if ci.digest_file(source) != entry:
                    raise ReleaseError('release file digest mismatch')
        migrations = derived(directory / 'app/application.jar')
        ci.verify(directory / 'app', revision, run_id)
        for name, data in migrations.items():
            with ci.open_regular(directory / name) as source:
                if source.read(len(data) + 1) != data:
                    raise ReleaseError('migration does not match JAR')
        return manifest
    except (ci.CheckError, OSError, ValueError, ET.ParseError, zipfile.BadZipFile, RuntimeError, RecursionError) as error:
        raise ReleaseError('release verification failed') from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    package = commands.add_parser('package')
    package.add_argument('--application-dir', required=True)
    package.add_argument('--output', required=True)
    verify = commands.add_parser('verify')
    verify.add_argument('--release-dir', required=True)
    verify.add_argument('--manifest-sha256', required=True)
    for command in (package, verify):
        command.add_argument('--revision', required=True)
        command.add_argument('--run-id', required=True)
    args = parser.parse_args(argv)
    try:
        if args.action == 'package':
            package_release(args.application_dir, args.output, args.revision, args.run_id)
        else:
            verify_release(args.release_dir, args.revision, args.run_id, args.manifest_sha256)
    except ReleaseError as error:
        print('error: ' + str(error), file=sys.stderr)
        return 1
    print(args.action + ': OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
