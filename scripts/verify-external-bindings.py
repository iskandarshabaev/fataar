#!/usr/bin/env python3
"""Build real external DataBinding AARs, validate ownership and Maven transitive API."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from dex_inventory import verify_apk_classes

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'integration-tests/external-bindings'
CONSUMER = ROOT / 'integration-tests/external-bindings-consumer'
REPORTS = ROOT / 'build/reports/external-binding-validation'
EXTERNAL = PROJECT / 'build/external-repository/com/fataar/external/binding-library/1/binding-library-1.aar'
PUBLISHED = PROJECT / 'build/sdk-repository/com/fataar/externaltest/sdk/1/sdk-1.aar'
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


def inspect():
    snapshots = {}
    external_namespace = 'com.fataar.external.binding'
    for mode in ('debug', 'release'):
        path = PROJECT / f'sdk/build/outputs/aar/sdk-{mode}.aar'
        with zipfile.ZipFile(path) as aar:
            names = aar.namelist()
            assert 'res/layout/external_binding.xml' not in names
            assert 'res/layout/embedded_binding.xml' in names and 'res/layout/sdk_external_binding.xml' in names
            assert not any(n.startswith('data-binding/' + external_namespace + '-') for n in names)
            assert not any(n.startswith('data-binding-base-class-log/' + external_namespace + '-') for n in names)
            references = 0
            for name in names:
                if not name.endswith('-binding_classes.json'): continue
                data = aar.read(name)
                log = json.loads(data.decode('utf-16') if data[:2] in (b'\xfe\xff', b'\xff\xfe') else data.decode())
                for layout, record in log['mappings'].items():
                    expected = external_namespace if layout == 'external_binding' else 'com.fataar.externaltest.sdk'
                    assert record['module_package'] == expected, (mode, layout, record)
                    references += layout == 'external_binding'
            assert references == 2, (mode, references)
            with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
                classes = {n for n in jar.namelist() if n.endswith('.class')}
                assert not any(n.startswith('com/fataar/external/binding/') for n in classes)
                assert any(n.startswith('com/fataar/externaltest/embedded/') for n in classes)
                assert any(n.startswith('com/fataar/externaltest/sdk/') for n in classes)
                assert not any('/R$' in n or n.endswith('/R.class') for n in classes)
        snapshots[mode] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'classes': len(classes),
                           'externalLayoutReferences': references}
    assert PUBLISHED.read_bytes() == (PROJECT / 'sdk/build/outputs/aar/sdk-release.aar').read_bytes()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    deps = ET.parse(PUBLISHED.with_suffix('.pom')).findall('m:dependencies/m:dependency', ns)
    scopes = {d.findtext('m:groupId', namespaces=ns) + ':' + d.findtext('m:artifactId', namespaces=ns):
              d.findtext('m:scope', namespaces=ns) for d in deps}
    assert scopes['com.fataar.external:binding-library'] == 'compile'
    assert not any(k.startswith('com.fataar.externaltest:') for k in scopes)
    for variant in json.loads(PUBLISHED.with_suffix('.module').read_text())['variants']:
        coords = {d['group'] + ':' + d['module'] for d in variant.get('dependencies', [])}
        assert 'com.fataar.external:binding-library' in coords
        assert not any(k.startswith('com.fataar.externaltest:') for k in coords)
    return snapshots


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'result.json').unlink(missing_ok=True)
    gradle('publish-external', ':external:publishExternalPublicationToExternalRepository')
    tasks = [':sdk:inspectDebugAar', ':sdk:inspectReleaseAar', ':sdk:publishSdkPublicationToSdkRepository']
    gradle('build-sdk', *tasks)
    assert 'Reusing configuration cache.' in gradle('configuration-cache', *tasks)
    artifacts = inspect()
    PASSED.append('artifact-ownership-and-api-scopes')
    text = gradle('build-cache', ':sdk:clean', *tasks)
    for mode in ('Debug', 'Release'):
        assert ':sdk:merge' + mode + 'FatAar FROM-CACHE' in text
    assert inspect() == artifacts
    for mode, options in [('metadata', []), ('pom', ['-PpomOnly=true'])]:
        consumer_tasks = [':app:assembleDebug', ':app:assembleRelease', ':app:lint']
        gradle('consumer-' + mode, *consumer_tasks, *options, '--refresh-dependencies', project=CONSUMER)
        assert 'Reusing configuration cache.' in gradle('consumer-cache-' + mode, *consumer_tasks, *options, project=CONSUMER)
        apk = CONSUMER / 'app/build/outputs/apk/debug/app-debug.apk'
        verify_apk_classes(apk, PUBLISHED)
        verify_apk_classes(apk, EXTERNAL)
        PASSED.append('consumer-dex-' + mode)
    gradle('external-api-required', ':sdk:inspectReleaseAar', '-Ptest.hideExternalApi=true', expected='FATAAR_API_DEPENDENCY')
    # Negative tests alter genuine published AAR bytes only in private test copies.
    with zipfile.ZipFile(EXTERNAL) as aar:
        entries = {name: aar.read(name) for name in aar.namelist()}
    probe = str(ROOT / 'integration-tests/scenarios/external-binding-probe.init.gradle')
    input_copy = REPORTS / 'external-input.aar'
    input_copy.write_bytes(EXTERNAL.read_bytes())
    probe_args = [':sdk:probeExternalBindingAar', '-I', probe, '-Ptest.externalInput=' + str(input_copy)]
    gradle('external-input-valid', *probe_args)
    assert 'Reusing configuration cache.' in gradle('external-input-cache', *probe_args)
    for scenario, expected in [('missing-resource', 'missing resource layout/external_binding in com.fataar.external.binding'),
                               ('missing-class', 'FATAAR_DATABINDING_CLASS')]:
        broken = dict(entries)
        if scenario == 'missing-resource':
            broken['R.txt'] = b'\n'.join(line for line in broken['R.txt'].splitlines() if b' layout external_binding ' not in line) + b'\n'
        else:
            with zipfile.ZipFile(io.BytesIO(broken['classes.jar'])) as jar:
                buffer = io.BytesIO()
                with zipfile.ZipFile(buffer, 'w') as output:
                    for name in jar.namelist():
                        if name.endswith('/ExternalBindingBindingImpl.class'): continue
                        output.writestr(name, jar.read(name))
                broken['classes.jar'] = buffer.getvalue()
        with zipfile.ZipFile(input_copy, 'w') as output:
            for name, data in sorted(broken.items()): output.writestr(name, data)
        text = gradle(scenario, *probe_args, expected=expected)
        assert 'Reusing configuration cache.' in text
    input_copy.write_bytes(EXTERNAL.read_bytes())
    gradle('restore-external-input', *probe_args)
    gradle('restore-positive-sdk', *tasks)
    assert inspect() == artifacts
    (REPORTS / 'result.json').write_text(json.dumps({'status': 'PASS', 'checks': PASSED, 'artifacts': artifacts}, indent=2) + '\n')
    print(f'All external binding artifact checks passed: {REPORTS / "result.json"}', flush=True)


if __name__ == '__main__':
    main()
