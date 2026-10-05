#!/usr/bin/env python3
"""Measure real 50/100/200 Android-module merges and validate every generated artifact."""
import argparse
import hashlib
from html.parser import HTMLParser
import io
import json
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
import zipfile
from dex_inventory import verify_apk_classes
from scale_fixture import generate

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'build/scale-projects'
REPORTS = ROOT / 'build/reports/scale-validation'
ENV = os.environ.copy()
if not ENV.get('ANDROID_HOME') and not ENV.get('ANDROID_SDK_ROOT'):
    for line in (ROOT / 'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='): ENV['ANDROID_HOME'] = line.split('=', 1)[1]


class Profile(HTMLParser):
    def __init__(self):
        super().__init__(); self.rows = []; self.row = None; self.cell = None
    def handle_starttag(self, tag, attrs):
        if tag == 'tr': self.row = []
        if tag in ('td', 'th') and self.row is not None: self.cell = ''
    def handle_data(self, data):
        if self.cell is not None: self.cell += data
    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            self.row.append(self.cell.strip()); self.cell = None
        if tag == 'tr' and self.row is not None: self.rows.append(self.row); self.row = None


def read_profile(path):
    parser = Profile(); parser.feed(path.read_text())
    result = {}
    for row in parser.rows:
        if len(row) != 2 or row[0] not in ('Total Build Time', 'Startup', 'Settings and buildSrc', 'Loading Projects', 'Configuring Projects', 'Task Execution'): continue
        match = re.fullmatch(r'(?:(\d+)m)?([\d.]+)s', row[1])
        if match: result[row[0]] = float(match[2]) + 60 * int(match[1] or 0)
    return result


def archive(path):
    with zipfile.ZipFile(path) as source:
        assert len(source.namelist()) == len(set(source.namelist())), path
        return {name: source.read(name) for name in source.namelist() if not name.endswith('/')}


def inventory(project, count, env=None):
    path = project / 'sdk/build/outputs/aar/sdk-release.aar'
    entries = archive(path)
    classes = archive(io.BytesIO(entries['classes.jar']))
    expected_classes = {f'com/fataar/scale/m{i:03}/Marker.class' for i in range(count)} | {'com/fataar/scale/sdk/ScaleSdk.class'}
    assert {name for name in classes if name.endswith('.class')} == expected_classes
    expected_assets = {}
    expected_values = {}
    expected_layouts = {}
    for i in range(count):
        module = f'm{i:03}'
        source = project / module / 'src/main'
        for asset in (source / 'assets').rglob('*'):
            if asset.is_file(): expected_assets['assets/' + asset.relative_to(source / 'assets').as_posix()] = asset.read_bytes()
        expected_layouts[f'res/layout/scale_layout_{module}.xml'] = (source / f'res/layout/scale_layout_{module}.xml').read_bytes()
        for item in ET.parse(source / 'res/values/strings.xml').getroot(): expected_values[item.get('name')] = item.text
        assert (f'-keep class com.fataar.scale.{module}.Marker').encode() in entries['proguard.txt']
    assert {name: data for name, data in entries.items() if name.startswith('assets/')} == expected_assets
    assert {name: data for name, data in entries.items() if name.startswith('res/layout/')} == expected_layouts
    actual_values = {}
    for name, data in entries.items():
        if name.startswith('res/values/') and name.endswith('.xml'):
            for item in ET.fromstring(data):
                assert item.get('name') not in actual_values
                actual_values[item.get('name')] = item.text
    assert actual_values == expected_values
    manifest = ET.fromstring(entries['AndroidManifest.xml'])
    android = '{http://schemas.android.com/apk/res/android}'
    metadata = {n.get(android + 'name'): n.get(android + 'value') for n in manifest.findall('application/meta-data')}
    assert metadata == {f'scale.m{i:03}': str(i + 1) for i in range(count)}
    # Execute every marker from the final classes.jar. This also detects stale
    # bytecode when the incremental stage changes a class alongside resources.
    runtime_env = env or ENV
    validation = project / 'build/marker-validation'; validation.mkdir(parents=True, exist_ok=True)
    jar = validation / 'classes.jar'; jar.write_bytes(entries['classes.jar'])
    calls, expected_markers = [], []
    for i in range(count):
        java = project / f'm{i:03}/src/main/java/com/fataar/scale/m{i:03}/Marker.java'
        expected_markers.append(int(re.search(r'marker\(\) \{ return (\d+);', java.read_text())[1]))
        calls.append(f'System.out.println(com.fataar.scale.m{i:03}.Marker.marker());')
    probe = validation / 'ScaleMarkerProbe.java'
    probe.write_text('public class ScaleMarkerProbe { public static void main(String[] args) { '
                     + ' '.join(calls) + ' } }')
    sdk = Path(runtime_env.get('ANDROID_HOME') or runtime_env['ANDROID_SDK_ROOT'])
    android = sdk / 'platforms/android-36/android.jar'
    java = Path(runtime_env['JAVA_HOME']) / 'bin/java' if runtime_env.get('JAVA_HOME') else 'java'
    output = subprocess.check_output([str(java), '--class-path', os.pathsep.join(map(str, (jar, android))),
                                      str(probe)], env=runtime_env, text=True)
    assert list(map(int, output.splitlines())) == expected_markers
    coordinate = project / 'build/repository/com/fataar/scale/sdk/1/sdk-1'
    assert coordinate.with_suffix('.aar').read_bytes() == path.read_bytes()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    deps = ET.parse(coordinate.with_suffix('.pom')).findall('m:dependencies/m:dependency', ns)
    assert not any(d.findtext('m:groupId', namespaces=ns) == 'com.fataar.scale' for d in deps)
    graph = (project / 'sdk/build/reports/fat-aar/release/dependencies.txt').read_text()
    assert len([line for line in graph.splitlines() if line.startswith('embed project::m')]) == count
    return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'aarBytes': path.stat().st_size,
            'classes': len(expected_classes), 'executedJvmMarkers': count, 'assets': len(expected_assets), 'values': len(actual_values),
            'layouts': len(expected_layouts), 'manifestMetadata': len(metadata)}


def execute(project, name, reports, tasks, gradle):
    reports.mkdir(parents=True, exist_ok=True)
    log = reports / (name + '.log')
    gc_dir = reports / 'gc'; gc_dir.mkdir(exist_ok=True)
    before_gc = set(gc_dir.glob('*.log'))
    profiles = project / 'build/reports/profile'
    before_profiles = set(profiles.glob('*.html'))
    # Fixed JVM arguments across stages preserve configuration cache identity.
    jvm_args = '-Xmx3g -Dfile.encoding=UTF-8 -Xlog:gc*:file=' + str(gc_dir / 'gc-%p.log') + ':time,level,tags'
    command = [str(gradle), '-p', str(project), *tasks, '--console=plain', '--no-daemon', '--profile', '-Dorg.gradle.jvmargs=' + jvm_args]
    print(f'{reports.name}/{name}: {log}', flush=True)
    peak_rss_kib = 0
    started = time.monotonic()
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT)
        def sample():
            nonlocal peak_rss_kib
            while process.poll() is None:
                # JVM %p expansion identifies our single-use Gradle daemon without
                # reading process command lines or measuring unrelated user daemons.
                owned = {process.pid}
                for path in set(gc_dir.glob('*.log')) - before_gc:
                    match = re.fullmatch(r'gc-(\d+)\.log', path.name)
                    if match: owned.add(int(match[1]))
                try:
                    rows = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,rss='], text=True)
                    records = [tuple(map(int, line.split())) for line in rows.splitlines() if len(line.split()) == 3]
                    while True:
                        expanded = owned | {pid for pid, parent, rss in records if parent in owned}
                        if expanded == owned: break
                        owned = expanded
                    peak_rss_kib = max(peak_rss_kib, sum(rss for pid, parent, rss in records if pid in owned))
                except (OSError, subprocess.CalledProcessError): pass
                time.sleep(0.5)
        sampler = threading.Thread(target=sample, daemon=True); sampler.start()
        status = process.wait(); sampler.join(timeout=2)
    duration = time.monotonic() - started
    text = log.read_text()
    if status:
        print('\n'.join(text.splitlines()[-80:]), file=sys.stderr)
        raise AssertionError(name)
    metrics = {'wallSeconds': round(duration, 3), 'sampledPeakRssMiB': round(peak_rss_kib / 1024, 1),
               'rssSamplingSeconds': 0.5, 'configurationCacheReused': 'Reusing configuration cache.' in text}
    fresh = set(profiles.glob('*.html')) - before_profiles
    if fresh:
        newest = max(fresh, key=lambda p: p.stat().st_mtime)
        metrics['gradleProfileSeconds'] = read_profile(newest)
        shutil.copyfile(newest, reports / (name + '-profile.html'))
        for support in ('css', 'js'):
            if (profiles / support).exists(): shutil.copytree(profiles / support, reports / support, dirs_exist_ok=True)
    (reports / (name + '-metrics.json')).write_text(json.dumps(metrics, indent=2) + '\n')
    return text, metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sizes', nargs='+', type=int, default=[50, 100, 200])
    parser.add_argument('--shape', choices=['chain', 'diamonds', 'fanout'], help='Override default chain/diamonds/fanout for 50/100/200')
    parser.add_argument('--payload-kib', type=int, default=64)
    parser.add_argument('--gradle', type=Path, default=ROOT / 'gradlew', help='Alternative installed Gradle executable for compatibility runs')
    args = parser.parse_args()
    assert all(1 <= count <= 1000 for count in args.sizes) and args.payload_kib >= 1
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'result.json').unlink(missing_ok=True)
    toolchain = subprocess.check_output([str(args.gradle), '--version'], env=ENV, text=True)
    results = {}
    for count in args.sizes:
        shape = args.shape or {50: 'chain', 100: 'diamonds', 200: 'fanout'}.get(count, 'fanout')
        project = BASE / f'{count}-{shape}'
        relocated = BASE / f'{count}-{shape}-relocated'
        cache = BASE / f'cache-{count}-{shape}'
        reports = REPORTS / f'{count}-{shape}'
        # These directories contain only files owned by this generator.
        for directory in (project, relocated, cache):
            if directory.exists(): shutil.rmtree(directory)
        configuration = generate(project, count, shape, cache, args.payload_kib)
        tasks = [':sdk:inspectReleaseAar', ':sdk:publishSdkPublicationToScaleRepository']
        stages = {}
        text, stages['first'] = execute(project, 'first', reports, tasks, args.gradle)
        original = inventory(project, count)
        text, stages['warm'] = execute(project, 'warm', reports, tasks, args.gradle)
        assert 'Reusing configuration cache.' in text and ':sdk:mergeReleaseFatAar UP-TO-DATE' in text
        assert inventory(project, count) == original
        for directory in [project / 'sdk', *[project / f'm{i:03}' for i in range(count)]]:
            shutil.rmtree(directory / 'build')
        text, stages['restore-cache'] = execute(project, 'restore-cache', reports, tasks, args.gradle)
        assert 'Reusing configuration cache.' in text and ':sdk:mergeReleaseFatAar FROM-CACHE' in text
        assert inventory(project, count) == original
        leaf = project / f'm{count - 1:03}/src/main'
        java = next((leaf / 'java').rglob('Marker.java'))
        values = leaf / 'res/values/strings.xml'
        asset = leaf / f'assets/scale/m{count - 1:03}.bin'
        obsolete = leaf / f'assets/scale/m{count - 1:03}-obsolete.txt'
        saved = {path: path.read_bytes() for path in (java, values, asset, obsolete)}
        java.write_text(java.read_text().replace(f'return {count};', 'return 1000000;'))
        values.write_text(values.read_text().replace(f'Module m{count - 1:03}', 'Changed leaf'))
        asset.write_bytes(b'changed leaf binary payload'); obsolete.unlink()
        text, stages['incremental-leaf'] = execute(project, 'incremental-leaf', reports, tasks, args.gradle)
        assert 'Reusing configuration cache.' in text
        assert ':sdk:mergeReleaseFatAar FROM-CACHE' not in text and ':sdk:mergeReleaseFatAar UP-TO-DATE' not in text
        changed = inventory(project, count)
        assert changed['sha256'] != original['sha256'] and changed['assets'] == original['assets'] - 1
        assert sum(f':m{i:03}:compileReleaseJavaWithJavac UP-TO-DATE' in text for i in range(count - 1)) == count - 1
        for path, data in saved.items(): path.write_bytes(data)
        text, stages['restore-input'] = execute(project, 'restore-input', reports, tasks, args.gradle)
        assert inventory(project, count) == original
        shutil.copytree(project, relocated, ignore=shutil.ignore_patterns('build', '.gradle', '.cxx', '.kotlin'))
        text, stages['relocated'] = execute(relocated, 'relocated', reports, tasks, args.gradle)
        assert ':sdk:mergeReleaseFatAar FROM-CACHE' in text
        assert inventory(relocated, count) == original
        text, stages['consumer'] = execute(project / 'consumer', 'consumer', reports, [':app:assembleDebug', '--refresh-dependencies'], args.gradle)
        classes = verify_apk_classes(project / 'consumer/app/build/outputs/apk/debug/app-debug.apk', project / 'sdk/build/outputs/aar/sdk-release.aar')
        assert classes == count + 1
        result = {'status': 'PASS', **configuration, 'artifact': original, 'changedArtifact': changed, 'stages': stages,
                  'consumerSdkClassDefinitions': classes, 'incrementalUnchangedJavaModules': count - 1}
        results[str(count)] = result
        (reports / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(f'{count} modules: PASS, {original["aarBytes"]} bytes, {original["classes"]} SDK class definitions', flush=True)
    summary = {'status': 'PASS', 'cases': results, 'gradleVersionOutput': toolchain, 'host': platform.platform(), 'measurement': 'single-use daemons; warm tool/dependency caches; aggregate owned-process RSS sampled every 0.5s; no hard time threshold'}
    (REPORTS / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'All scale checks passed: {REPORTS / "result.json"}', flush=True)


if __name__ == '__main__': main()
