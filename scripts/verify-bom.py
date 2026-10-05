#!/usr/bin/env python3
"""Verify real nested BOM publication, embedded version selection and consumer resolution."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from dex_inventory import dex_classes, verify_apk_classes

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'integration-tests/bom'
CONSUMER = ROOT / 'integration-tests/bom-consumer'
REPORTS = ROOT / 'build/reports/bom-validation'
ENV = os.environ.copy()
if not ENV.get('ANDROID_HOME') and not ENV.get('ANDROID_SDK_ROOT'):
    for line in (ROOT / 'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='): ENV['ANDROID_HOME'] = line.split('=', 1)[1]
PASSED = []


def run(name, command, expected=None):
    log = REPORTS / (name + '.log')
    print(f'{name}: {log}', flush=True)
    with log.open('w') as output:
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text()
    if (expected is None and result.returncode) or (expected and (not result.returncode or expected not in text)):
        print('\n'.join(text.splitlines()[-80:]), file=sys.stderr)
        raise AssertionError(name)
    PASSED.append(name)
    return text


def gradle(name, *args, project=PROJECT, expected=None):
    executable = ENV.get('FATAAR_GRADLE_EXECUTABLE', str(ROOT / 'gradlew'))
    return run(name, [executable, '-p', project, *args, '--console=plain'], expected)


def inspect(scope):
    coordinate = PROJECT / f'build/sdk-repository/com/fataar/bomfixture/sdk-{scope}/2/sdk-{scope}-2'
    aar = coordinate.with_suffix('.aar')
    assert aar.read_bytes() == (PROJECT / 'sdk/build/outputs/aar/sdk-release.aar').read_bytes()
    with zipfile.ZipFile(aar) as source, zipfile.ZipFile(io.BytesIO(source.read('classes.jar'))) as classes:
        assert 'com/fataar/bomfixture/ApiModel.class' not in classes.namelist()
        assert 'com/fataar/bomfixture/Engine.class' not in classes.namelist()
        path = 'com/fataar/bomfixture/EmbeddedBundle.class'
        with zipfile.ZipFile(PROJECT / 'build/family-repository/com/fataar/bomfixture/embedded-bundle/2/embedded-bundle-2.jar') as original:
            assert b'embedded-bundle-2' in original.read(path)
        assert b'embedded-bundle-2' in classes.read(path) and b'embedded-bundle-1' not in classes.read(path)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    pom = ET.parse(coordinate.with_suffix('.pom'))
    imports = pom.findall('m:dependencyManagement/m:dependencies/m:dependency', ns)
    assert len(imports) == 1
    assert tuple(imports[0].findtext('m:' + k, namespaces=ns) for k in ('artifactId', 'version', 'type', 'scope')) == ('nested-bom', '2', 'pom', 'import')
    deps = {d.findtext('m:artifactId', namespaces=ns): d for d in pom.findall('m:dependencies/m:dependency', ns)}
    assert deps['api-lib'].findtext('m:scope', namespaces=ns) == 'compile'
    assert deps['engine-lib'].findtext('m:scope', namespaces=ns) == 'runtime'
    assert 'feature' not in deps and 'embedded-bundle' not in deps and 'nested-bom' not in deps
    for variant in json.loads(coordinate.with_suffix('.module').read_text())['variants']:
        dependencies = {d['module']: d for d in variant.get('dependencies', [])}
        api = variant['attributes']['org.gradle.usage'] == 'java-api'
        assert ('nested-bom' in dependencies) == (scope == 'api' or not api)
        if 'nested-bom' in dependencies:
            assert dependencies['nested-bom']['attributes']['org.gradle.category'] == 'platform'
        assert ('engine-lib' in dependencies) == (not api)
        assert 'api-lib' in dependencies and 'embedded-bundle' not in dependencies and 'feature' not in dependencies
    return aar


def consumer_runtime(name, aar, upgrade=False):
    records = {}
    for line in (CONSUMER / 'app/build/reports/runtime.txt').read_text().splitlines():
        coordinate, path = line.split('\t'); records[coordinate] = Path(path)
    group = 'com.fataar.bomfixture:'
    assert group + 'api-lib:2' in records
    version = '3' if upgrade else '2'
    assert group + 'engine-lib:' + version in records
    assert not any(key.startswith(group + 'embedded-bundle:') for key in records)
    assert not any(key.startswith(group + 'feature:') for key in records)
    apk = CONSUMER / 'app/build/outputs/apk/debug/app-debug.apk'
    verify_apk_classes(apk, aar)
    with zipfile.ZipFile(apk) as archive:
        actual = set().union(*(dex_classes(archive.read(n)) for n in archive.namelist() if n.endswith('.dex')))
    for module in ('api-lib:2', 'engine-lib:' + version):
        with zipfile.ZipFile(records[group + module]) as jar:
            assert {n for n in jar.namelist() if n.endswith('.class')} <= actual
    # The fixture's SDK API is pure Java. Execute classes extracted from the actual
    # published AAR with the external JARs selected by the independent consumer.
    classes = REPORTS / (name + '-sdk.jar')
    with zipfile.ZipFile(aar) as archive: classes.write_bytes(archive.read('classes.jar'))
    helper = REPORTS / 'BomRuntime.java'
    helper.write_text('''class BomRuntime { public static void main(String[] args) {
        String actual = com.fataar.bomfixture.sdk.BomSdk.versions();
        if (!actual.equals(args[0])) throw new AssertionError(actual);
        if (com.fataar.bomfixture.sdk.BomSdk.model() == null) throw new AssertionError("API missing");
        System.out.println(actual);
    }}''')
    java = Path(ENV['JAVA_HOME']) / 'bin/java' if ENV.get('JAVA_HOME') else 'java'
    cp = os.pathsep.join(map(str, [classes, records[group + 'api-lib:2'], records[group + 'engine-lib:' + version]]))
    run(name + '-execute-published-classes', [java, '--class-path', cp, helper, 'api-lib-2:engine-lib-' + version + ':embedded-bundle-2'])


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'result.json').unlink(missing_ok=True)
    producers = [':family-bom:publish', ':nested-bom:publish']
    producers += [f':{name}{v}:publish' for name in ('api-lib', 'engine-lib', 'embedded-bundle') for v in (1, 2)]
    producers += [':engine-lib3:publish']
    gradle('publish-family-and-nested-boms', *producers)
    artifacts = {}
    for scope in ('api', 'implementation'):
        options = ['-Ptest.bomScope=' + scope]
        tasks = [':sdk:inspectReleaseAar', ':sdk:publishSdkPublicationToSdkRepository', *options]
        gradle('sdk-' + scope, *tasks)
        assert 'Reusing configuration cache.' in gradle('sdk-cache-' + scope, *tasks)
        aar = inspect(scope)
        artifacts[scope] = hashlib.sha256(aar.read_bytes()).hexdigest()
        PASSED.append('bom-scopes-and-embedded-version-' + scope)
        text = gradle('build-cache-' + scope, ':sdk:clean', *tasks)
        assert ':sdk:mergeReleaseFatAar FROM-CACHE' in text
        assert hashlib.sha256(inspect(scope).read_bytes()).hexdigest() == artifacts[scope]
        for metadata, extra in [('metadata', []), ('pom', ['-PpomOnly=true'])]:
            args = [':app:assembleDebug', ':app:reportRuntime', *options, *extra]
            name = scope + '-' + metadata
            gradle('consumer-' + name, *args, '--refresh-dependencies', project=CONSUMER)
            assert 'Reusing configuration cache.' in gradle('consumer-cache-' + name, *args, project=CONSUMER)
            consumer_runtime(name, aar)
            gradle('consumer-upgrade-' + name, *args, '-Ptest.upgrade=true', project=CONSUMER)
            consumer_runtime(name + '-upgrade', aar, upgrade=True)
    registry = PROJECT / 'sdk/fat-aar-dependencies.txt'
    original = registry.read_bytes() if registry.exists() else None
    gradle('missing-bom', ':sdk:validateReleaseDependencies', '-Ptest.omitBom=true', expected='FATAAR_MISSING_PLATFORM')
    gradle('missing-bom-no-unsafe-fix', ':sdk:addMissingDependencies', '-Ptest.omitBom=true', expected='FATAAR_FIX_REQUIRES_DECISION')
    assert (registry.read_bytes() if registry.exists() else None) == original
    gradle('bom-alone-does-not-deliver-libraries', ':sdk:validateReleaseDependencies', '-Ptest.onlyEmbedded=true', expected='FATAAR_MISSING_DEPENDENCY')
    gradle('cannot-embed-bom', ':sdk:validateReleaseDependencies', '-Ptest.bomScope=embed', expected='FATAAR_EMBED_PLATFORM')
    gradle('enforced-platform-needs-policy', ':sdk:validateReleaseDependencies', '-Ptest.enforced=true', expected='FATAAR_ENFORCED_PLATFORM')
    gradle('restore-positive-sdk', ':sdk:inspectReleaseAar')
    assert hashlib.sha256(inspect('api').read_bytes()).hexdigest() == artifacts['api']
    (REPORTS / 'result.json').write_text(json.dumps({'status': 'PASS', 'checks': PASSED, 'artifacts': artifacts,
                                                   'contract': 'explicit regular platform; separate delivery declarations; preserve platform metadata'}, indent=2) + '\n')
    print(f'All BOM checks passed: {REPORTS / "result.json"}', flush=True)


if __name__ == '__main__': main()
