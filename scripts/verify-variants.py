#!/usr/bin/env python3
"""Build and inspect real flavored AARs and independent Maven consumers."""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from dex_inventory import verify_apk_classes

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'integration-tests/variants'
CONSUMER = ROOT / 'integration-tests/variants-consumer'
REPORTS = ROOT / 'build/reports/variant-validation'
VARIANTS = [color + mode for color in ('red', 'blue') for mode in ('Debug', 'Release', 'Staging')]
ENV = os.environ.copy()
if not ENV.get('ANDROID_HOME') and not ENV.get('ANDROID_SDK_ROOT'):
    for line in (ROOT / 'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='):
            ENV['ANDROID_HOME'] = line.split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ')
PASSED = []


def gradle(name, *args, project=PROJECT, expected=None):
    log = REPORTS / (name + '.log')
    print(f'{name}: {log}', flush=True)
    with log.open('w') as output:
        result = subprocess.run([str(ROOT / 'gradlew'), '-p', str(project), *args, '--console=plain'],
                                cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text()
    if (expected is None and result.returncode) or (expected and (not result.returncode or expected not in text)):
        print('\n'.join(text.splitlines()[-100:]), file=sys.stderr)
        raise AssertionError(f'Unexpected outcome: {name}')
    PASSED.append(name)
    return text


def aar_path(variant):
    # AGP uses a dash between flavor and build type in the output filename.
    color = 'red' if variant.startswith('red') else 'blue'
    mode = variant[len(color):].lower()
    return PROJECT / f'sdk/build/outputs/aar/sdk-{color}-{mode}.aar'


def inspect(variant):
    color = 'red' if variant.startswith('red') else 'blue'
    mode = variant[len(color):].lower()
    child_mode = 'debug' if mode == 'staging' else mode
    engine = 'modern' if color == 'red' else 'classic'
    expected_owners = {'base', 'engine', 'flavored', color + '-only'}
    if color == 'red': expected_owners.add('red-leaf')
    if color == 'red' and mode == 'release': expected_owners.add('release-only')
    if mode == 'staging': expected_owners.add('staging-only')
    path = aar_path(variant)
    with zipfile.ZipFile(path) as aar:
        for name, value in {'color': color, 'mode': child_mode, 'engine': engine, 'branch': color + '-only'}.items():
            assert aar.read(f'assets/variant/{name}.txt').decode() == value, (variant, name)
        assert ('assets/variant/release.txt' in aar.namelist()) == (color == 'red' and mode == 'release')
        assert ('assets/variant/staging.txt' in aar.namelist()) == (mode == 'staging')
        strings = {}
        for name in aar.namelist():
            if name.startswith('res/values/') and name.endswith('.xml'):
                strings.update({e.get('name'): e.text for e in ET.fromstring(aar.read(name)).findall('string')})
        assert strings['variant_sdk_marker'] == 'host-' + color
        assert strings['variant_color'] == 'color-' + color
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
            classes = set(n for n in jar.namelist() if n.endswith('.class'))
            for owner, marker in [('red-only', 'RedOnlyMarker'), ('blue-only', 'BlueOnlyMarker'),
                                  ('red-leaf', 'RedLeafMarker'), ('release-only', 'ReleaseOnlyMarker'),
                                  ('staging-only', 'StagingOnlyMarker')]:
                key = f'com/fataar/variants/{owner.replace("-", "")}/{marker}.class'
                assert (key in classes) == (owner in expected_owners), (variant, key)
            assert f'flavor-{color}:{child_mode}'.encode() in jar.read('com/fataar/variants/flavored/FlavorMarker.class')
            assert f'engine-{engine}:{child_mode}'.encode() in jar.read('com/fataar/variants/engine/EngineMarker.class')
    report = (PROJECT / f'sdk/build/reports/fat-aar/{variant}/dependencies.txt').read_text()
    actual_owners = {line.split('project::', 1)[1].split(':', 1)[0] for line in report.splitlines() if line.startswith('embed project::')}
    assert actual_owners == expected_owners, (variant, actual_owners, expected_owners)
    return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'classes': len(classes), 'embedded': sorted(actual_owners)}


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'result.json').unlink(missing_ok=True)
    rules = PROJECT / 'sdk/fat-aar-dependencies.txt'
    original = rules.read_bytes() if rules.exists() else None
    # The fixture must start without fixes so negative scenarios really fail.
    rules.unlink(missing_ok=True)
    try:
        tasks = [':sdk:inspect' + v[0].upper() + v[1:] + 'Aar' for v in VARIANTS]
        apps = [':app:assemble' + v[0].upper() + v[1:] for v in VARIANTS] + [':app:lint']
        gradle('six-variants', *tasks, *apps)
        assert 'Reusing configuration cache.' in gradle('configuration-cache', *tasks, *apps)
        artifacts = {v: inspect(v) for v in VARIANTS}
        for v in VARIANTS:
            color = 'red' if v.startswith('red') else 'blue'
            mode = v[len(color):].lower()
            if mode != 'release':
                verify_apk_classes(PROJECT / f'app/build/outputs/apk/{color}/{mode}/app-{color}-{mode}.apk', aar_path(v))
        PASSED.append('project-dex-definitions')
        cached = gradle('build-cache', ':sdk:clean', *tasks)
        for v in VARIANTS:
            assert ':sdk:merge' + v[0].upper() + v[1:] + 'FatAar FROM-CACHE' in cached
            assert inspect(v) == artifacts[v]
        repository = PROJECT / 'build/repository'
        if repository.exists(): shutil.rmtree(repository)
        publish = [':sdk:publishRedPublicationToVariantsRepository', ':sdk:publishBluePublicationToVariantsRepository']
        gradle('publish', *publish)
        assert 'Reusing configuration cache.' in gradle('publish-configuration-cache', *publish)
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        assert len(list(repository.rglob('*.pom'))) == 2
        for color in ('red', 'blue'):
            base = repository / f'com/fataar/variants/sdk-{color}/1/sdk-{color}-1'
            assert hashlib.sha256(base.with_suffix('.aar').read_bytes()).hexdigest() == artifacts[color + 'Release']['sha256']
            deps = ET.parse(base.with_suffix('.pom')).findall('m:dependencies/m:dependency', ns)
            scopes = {d.findtext('m:groupId', namespaces=ns) + ':' + d.findtext('m:artifactId', namespaces=ns): d.findtext('m:scope', namespaces=ns) for d in deps}
            assert scopes['org.jetbrains:annotations'] == ('compile' if color == 'blue' else 'runtime')
            assert not any(k.startswith('com.fataar.variants:') for k in scopes)
            for v in json.loads(base.with_suffix('.module').read_text())['variants']:
                assert not any(k.startswith('com.android.') for k in v['attributes'])
                coords = {d['group'] + ':' + d['module'] for d in v.get('dependencies', [])}
                assert not any(k.startswith('com.fataar.variants:') for k in coords)
                assert ('org.jetbrains:annotations' in coords) == (color == 'blue' or v['attributes']['org.gradle.usage'] == 'java-runtime')
        PASSED.append('flavor-publication-scopes')
        consumer_tasks = [':app:assemble' + c + m for c in ('Red', 'Blue') for m in ('Debug', 'Release')]
        for name, options in [('metadata', []), ('pom', ['-PpomOnly=true'])]:
            gradle('consumer-' + name, *consumer_tasks, *options, '--refresh-dependencies', project=CONSUMER)
            for color in ('red', 'blue'):
                verify_apk_classes(CONSUMER / f'app/build/outputs/apk/{color}/debug/app-{color}-debug.apk', aar_path(color + 'Release'))
            PASSED.append('consumer-' + name + '-dex-definitions')
        output = gradle('missing-dimension', ':sdk:validateRedDebugDependencies', '-Ptest.noDimension=true', expected='FATAAR_UNRESOLVED_DEPENDENCY')
        assert 'engine' in output and 'modern' in output and 'classic' in output
        output = gradle('missing-build-fallback', ':sdk:validateRedStagingDependencies', '-Ptest.noBuildFallback=true', expected='FATAAR_UNRESOLVED_DEPENDENCY')
        assert 'flavored' in output and 'staging' in output and 'debug' in output and 'release' in output
        gradle('missing-red-leaf', ':sdk:inspectRedDebugAar', '-Ptest.omitRedLeaf=true', expected='FATAAR_MISSING_DEPENDENCY')
        fix = [':sdk:addMissingDependencies', '-PfatAar.variant=redDebug', '-Ptest.omitRedLeaf=true']
        gradle('fix-red-debug', *fix)
        fixed = rules.read_bytes()
        assert b'redDebugEmbed project :red-leaf' in fixed
        assert not any(line.startswith('embed ') for line in fixed.decode().splitlines())
        gradle('fix-idempotent', *fix)
        assert rules.read_bytes() == fixed
        assert 'Reusing configuration cache.' in gradle('fix-configuration-cache', *fix)
        gradle('fixed-red-and-blue', ':sdk:inspectRedDebugAar', ':sdk:inspectBlueDebugAar', '-Ptest.omitRedLeaf=true')
        assert inspect('redDebug') == artifacts['redDebug']
        assert inspect('blueDebug') == artifacts['blueDebug']
        gradle('fix-does-not-leak', ':sdk:validateRedReleaseDependencies', '-Ptest.omitRedLeaf=true', expected='FATAAR_MISSING_DEPENDENCY')
    finally:
        if original is None: rules.unlink(missing_ok=True)
        else: rules.write_bytes(original)
    rules.write_text('unknownEmbed project :base\n')
    try:
        gradle('unknown-rule-scope', ':sdk:validateRedDebugDependencies', expected='FATAAR_INVALID_RULE_SCOPE')
    finally:
        if original is None: rules.unlink(missing_ok=True)
        else: rules.write_bytes(original)
    gradle('restore-six-variants', *tasks)
    assert {v: inspect(v) for v in VARIANTS} == artifacts
    (REPORTS / 'result.json').write_text(json.dumps({'status': 'PASS', 'checks': PASSED, 'variants': artifacts}, indent=2) + '\n')
    print(f'All flavor artifact checks passed: {REPORTS / "result.json"}', flush=True)


if __name__ == '__main__':
    main()
