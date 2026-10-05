#!/usr/bin/env python3
"""Build, sign and inspect actual JNI/Prefab AARs and SDK-only CMake consumers."""
import hashlib
import io
import json
import os
import platform
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from dex_inventory import verify_apk_classes
from gradle_environment import pgp_jars

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'integration-tests/native'
CONSUMER = ROOT / 'integration-tests/native-consumer'
REPORTS = ROOT / 'build/reports/native-validation'
PUBLISHED = PROJECT / 'build/repository/com/fataar/nativeplayground/sdk/1/sdk-1.aar'
ABIS = ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
MODULES = ('fataar_math', 'fataar_bridge', 'fataar_registered', 'fataar_host', 'fataar_static', 'fataar_headers')
SHARED = MODULES[:4]
ENV = os.environ.copy()
if not ENV.get('ANDROID_HOME') and not ENV.get('ANDROID_SDK_ROOT'):
    for line in (ROOT / 'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='):
            ENV['ANDROID_HOME'] = line.split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ')
SDK = Path(ENV.get('ANDROID_HOME') or ENV.get('ANDROID_SDK_ROOT', ''))
HOST = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64', 'Windows': 'windows-x86_64'}[platform.system()]
READELF = SDK / ('ndk/25.1.8937393/toolchains/llvm/prebuilt/' + HOST + '/bin/llvm-readelf' + ('.exe' if os.name == 'nt' else ''))
PASSED = []


def run(name, arguments, expected=None):
    log = REPORTS / (name + '.log')
    print(f'{name}: {log}', flush=True)
    with log.open('w') as output:
        result = subprocess.run(list(map(str, arguments)), cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text()
    if (expected is None and result.returncode) or (expected and (not result.returncode or expected not in text)):
        print('\n'.join(text.splitlines()[-100:]), file=sys.stderr)
        raise AssertionError(f'Unexpected outcome: {name}')
    PASSED.append(name)
    return text


def gradle(name, *args, project=PROJECT, expected=None):
    return run(name, [ROOT / 'gradlew', '-p', project, *args, '--console=plain'], expected)


def archive(path):
    with zipfile.ZipFile(path) as source:
        assert len(source.namelist()) == len(set(source.namelist())), path
        return {name: source.read(name) for name in source.namelist() if not name.endswith('/')}


def write_archive(path, entries):
    with zipfile.ZipFile(path, 'w') as output:
        for name, data in sorted(entries.items()): output.writestr(name, data)


def inspect_elf(data, name, abi, needed=(), symbol=None, static=False):
    path = REPORTS / 'elf' / abi / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    text = subprocess.check_output([str(READELF), '-h', '-d', '-s', '-l', str(path)], env=ENV, text=True, stderr=subprocess.STDOUT)
    machines = {'armeabi-v7a': 'ARM', 'arm64-v8a': 'AArch64', 'x86': 'Intel 80386', 'x86_64': 'Advanced Micro Devices X86-64'}
    # llvm-readelf names x86_64 as "Advanced Micro Devices X86-64".
    assert 'Machine:' in text and machines[abi] in text, (path, text)
    if static:
        assert 'REL (Relocatable file)' in text, path
    else:
        assert 'DYN (Shared object file)' in text, path
        for library in needed: assert '[' + library + ']' in text, (path, library)
        loads = [line for line in text.splitlines() if line.strip().startswith('LOAD ')]
        assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads), (path, loads)
    if symbol: assert symbol in text, (path, symbol)
    (path.with_suffix(path.suffix + '.txt')).write_text(text)


def inspect():
    snapshots = {}
    expected_jni = {f'jni/{abi}/lib{module}.so' for abi in ABIS for module in SHARED}
    for mode in ('debug', 'release'):
        path = PROJECT / f'sdk/build/outputs/aar/sdk-{mode}.aar'
        entries = archive(path)
        raw = archive(PROJECT / f'sdk/build/intermediates/aar/{mode}/bundle{mode.capitalize()}Aar/sdk-{mode}.aar')
        inputs = {'fataar_host': raw}
        for producer, modules in [('native-base', ('fataar_math', 'fataar_static', 'fataar_headers')),
                                  ('native-bridge', ('fataar_bridge',)), ('native-registered', ('fataar_registered',))]:
            source = archive(PROJECT / f'{producer}/build/outputs/aar/{producer}-{mode}.aar')
            for module in modules: inputs[module] = source
        assert {name for name in entries if name.startswith('jni/')} == expected_jni
        root = json.loads(entries['prefab/prefab.json'])
        assert root['name'] == 'sdk' and root['schema_version'] == 2 and root['dependencies'] == []
        assert {name.split('/')[2] for name in entries if name.startswith('prefab/modules/')} == set(MODULES)
        for module, source in inputs.items():
            prefix = f'prefab/modules/{module}/'
            # Every header, archive, ELF file and ABI record must survive byte-for-byte.
            expected = {name: data for name, data in source.items() if name.startswith(prefix) and not name.endswith('/module.json')}
            assert expected and {name: data for name, data in entries.items() if name.startswith(prefix) and not name.endswith('/module.json')} == expected
            for abi in ABIS:
                jni = f'jni/{abi}/lib{module}.so'
                if module in SHARED:
                    assert entries[jni] == source[jni], (mode, jni)
                    needed = ['libfataar_math.so'] if module in ('fataar_bridge', 'fataar_host') else []
                    inspect_elf(entries[jni], f'{mode}-lib{module}.so', abi, needed)
                elif module == 'fataar_static':
                    inspect_elf(entries[prefix + f'libs/android.{abi}/lib{module}.a'], f'{mode}-lib{module}.a', abi, symbol='fataar_static_value', static=True)
        assert json.loads(entries['prefab/modules/fataar_bridge/module.json'])['export_libraries'] == [':fataar_math']
        assert json.loads(entries['prefab/modules/fataar_math/module.json'])['export_libraries'] == [':fataar_headers']
        assert not any(name.startswith('prefab/modules/fataar_headers/libs/') for name in entries)
        classes = archive(io.BytesIO(entries['classes.jar']))
        expected_classes = set()
        for source in [raw, inputs['fataar_math'], inputs['fataar_bridge'], inputs['fataar_registered']]:
            expected_classes.update(name for name in archive(io.BytesIO(source['classes.jar'])) if name.endswith('.class'))
        assert {name for name in classes if name.endswith('.class')} == expected_classes
        for owner in ('sdk/NativeSdk', 'bridge/NativeBridge', 'registered/RegisteredBridge'):
            assert f'com/fataar/nativeplayground/{owner}.class' in classes
        rules = entries['proguard.txt'].decode()
        assert 'NativeBridge' in rules and 'RegisteredBridge' in rules and 'NativeSdk' in rules
        snapshots[mode] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'jniLibraries': len(expected_jni),
                           'prefabModules': len(MODULES), 'classes': len(expected_classes), 'abis': list(ABIS)}
    assert PUBLISHED.read_bytes() == (PROJECT / 'sdk/build/outputs/aar/sdk-release.aar').read_bytes()
    repository = PROJECT / 'build/repository'
    assert len(list(repository.rglob('*.pom'))) == 1
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    deps = ET.parse(PUBLISHED.with_suffix('.pom')).findall('m:dependencies/m:dependency', ns)
    assert not any(d.findtext('m:groupId', namespaces=ns) == 'com.fataar.nativeplayground' for d in deps)
    metadata = json.loads(PUBLISHED.with_suffix('.module').read_text())
    assert {v['attributes']['org.gradle.usage'] for v in metadata['variants']} == {'java-api', 'java-runtime'}
    assert all(not any(d['group'] == 'com.fataar.nativeplayground' for d in v.get('dependencies', [])) for v in metadata['variants'])
    for variant in metadata['variants']:
        assert len(variant['files']) == 1
        assert variant['files'][0]['sha256'] == snapshots['release']['sha256']
    return snapshots


def inspect_apks(project, sdk_aar):
    for mode in ('debug', 'release'):
        delivered_aar = PROJECT / f'sdk/build/outputs/aar/sdk-{mode}.aar' if project == PROJECT else sdk_aar
        sdk_entries = archive(delivered_aar)
        apk = project / f'app/build/outputs/apk/{mode}/app-{mode}.apk'
        entries = archive(apk)
        native = {name for name in entries if name.startswith('lib/')}
        expected = {f'lib/{abi}/lib{module}.so' for abi in ABIS for module in (*SHARED, 'fataar_consumer')}
        assert native == expected, (apk, native ^ expected)
        for abi in ABIS:
            for module in SHARED:
                assert entries[f'lib/{abi}/lib{module}.so'] == sdk_entries[f'jni/{abi}/lib{module}.so'], (apk, abi, module)
            inspect_elf(entries[f'lib/{abi}/libfataar_consumer.so'], f'{project.name}-{mode}-consumer.so', abi,
                        needed=[f'lib{module}.so' for module in SHARED], symbol='Java_com_fataar_nativeplayground_consumer_ConsumerProbe_nativeValue')
    verify_apk_classes(project / 'app/build/outputs/apk/debug/app-debug.apk', sdk_aar)


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'result.json').unlink(missing_ok=True)
    assert READELF.is_file(), 'Install fixture NDK 25.1.8937393, or update the pinned fixture/tool together'
    assert (SDK / 'cmake/3.22.1/bin/cmake').is_file(), 'Fixture CMake 3.22.1 is required'
    keys = ROOT / 'build/native-test-signing'
    keys.mkdir(parents=True, exist_ok=True)
    home = Path(ENV.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    jars = pgp_jars(home)
    assert len(jars) == 3
    java = Path(ENV['JAVA_HOME']) / 'bin/java' if ENV.get('JAVA_HOME') else 'java'
    pgp = [java, '--class-path', os.pathsep.join(map(str, jars)), ROOT / 'integration-tests/candidates/PgpFixture.java']
    run('test-key', [*pgp, 'generate', keys])
    signing = '-PfatAar.signingKeyFile=' + str(keys / 'test-secret.asc')
    tasks = [':sdk:inspectDebugAar', ':sdk:inspectReleaseAar', ':sdk:publishSdkPublicationToNativeRepository',
             ':app:assembleDebug', ':app:assembleRelease', ':app:lint', signing]
    if (PROJECT / 'build/repository').exists(): shutil.rmtree(PROJECT / 'build/repository')
    gradle('build-and-sign', *tasks)
    text = gradle('signing-configuration-cache', *tasks)
    assert 'Reusing configuration cache.' in text and ':sdk:signSdkPublication UP-TO-DATE' in text
    artifacts = inspect()
    inspect_apks(PROJECT, PROJECT / 'sdk/build/outputs/aar/sdk-debug.aar')
    PASSED.append('real-aar-prefab-jni-and-project-apks')
    signed = [PUBLISHED, PUBLISHED.with_suffix('.pom'), PUBLISHED.with_suffix('.module')]
    run('signatures-of-final-artifacts', [*pgp, 'verify', keys, *signed])
    # A valid ZIP with a missing native payload must fail cryptographic verification.
    tampered = keys / 'tampered.aar'
    entries = archive(PUBLISHED)
    write_archive(tampered, {name: data for name, data in entries.items() if name != 'jni/arm64-v8a/libfataar_registered.so'})
    shutil.copyfile(Path(str(PUBLISHED) + '.asc'), Path(str(tampered) + '.asc'))
    run('signature-rejects-changed-aar', [*pgp, 'verify', keys, tampered], expected='Invalid signature')
    text = gradle('signed-build-cache', ':sdk:clean', *tasks)
    for mode in ('Debug', 'Release'): assert ':sdk:merge' + mode + 'FatAar FROM-CACHE' in text
    assert inspect() == artifacts
    run('signatures-after-cache-restore', [*pgp, 'verify', keys, *signed])
    # Sign alone must carry the final AAR producer dependency; an earlier assemble
    # or simultaneous inspect task must not hide broken publication wiring.
    gradle('clean-before-sign-alone', ':sdk:clean')
    release_aar = PROJECT / 'sdk/build/outputs/aar/sdk-release.aar'
    assert not release_aar.exists()
    text = gradle('sign-alone-builds-final-aar', ':sdk:signSdkPublication', signing, '--no-build-cache')
    assert ':sdk:mergeReleaseFatAar' in text and ':sdk:signSdkPublication' in text
    assert hashlib.sha256(release_aar.read_bytes()).hexdigest() == artifacts['release']['sha256']
    standalone = [release_aar, PROJECT / 'sdk/build/publications/sdk/pom-default.xml', PROJECT / 'sdk/build/publications/sdk/module.json']
    run('standalone-signatures', [*pgp, 'verify', keys, *standalone])
    gradle('sign-alone-cache-first', ':sdk:signSdkPublication', signing)
    assert 'Reusing configuration cache.' in gradle('sign-alone-configuration-cache', ':sdk:signSdkPublication', signing)
    gradle('restore-after-sign-alone', *tasks)
    assert inspect() == artifacts
    for mode, options in [('metadata', []), ('pom', ['-PpomOnly=true'])]:
        consumer_tasks = [':app:assembleDebug', ':app:assembleRelease', ':app:lint', *options]
        gradle('maven-consumer-' + mode, *consumer_tasks, '--refresh-dependencies', project=CONSUMER)
        assert 'Reusing configuration cache.' in gradle('maven-consumer-cache-' + mode, *consumer_tasks, project=CONSUMER)
        inspect_apks(CONSUMER, PUBLISHED)
        PASSED.append('maven-consumer-artifacts-' + mode)
    probe = ROOT / 'integration-tests/scenarios/native-probe.init.gradle'
    input_copy = REPORTS / 'native-input.aar'
    input_copy.write_bytes(PUBLISHED.read_bytes())
    probe_args = [':sdk:probeNativeAar', '-I', probe, '-Ptest.nativeInput=' + str(input_copy)]
    gradle('native-input-valid', *probe_args)
    assert 'Reusing configuration cache.' in gradle('native-input-cache', *probe_args)
    negatives = [('missing-transitive-jni', 'FATAAR_NATIVE_MISSING_LIBRARY'),
                 ('missing-literal-loaded-jni', 'FATAAR_NATIVE_MISSING_LIBRARY'),
                 ('wrong-jni-abi', 'FATAAR_NATIVE_ELF'), ('broken-elf', 'FATAAR_NATIVE_ELF'),
                 ('missing-static-library', 'FATAAR_NATIVE_MISSING_LIBRARY'),
                 ('missing-prefab-abi', 'FATAAR_NATIVE_MISSING_ABI'),
                 ('wrong-static-abi', 'FATAAR_NATIVE_ELF'),
                 ('invalid-prefab-schema', 'FATAAR_PREFAB_METADATA'),
                 ('missing-prefab-export', 'FATAAR_PREFAB_METADATA'),
                 ('prefab-min-sdk', 'FATAAR_NATIVE_MIN_SDK')]
    for scenario, expected in negatives:
        broken = dict(entries)
        if scenario == 'missing-transitive-jni': del broken['jni/arm64-v8a/libfataar_math.so']
        elif scenario == 'missing-literal-loaded-jni': del broken['jni/x86_64/libfataar_registered.so']
        elif scenario == 'wrong-jni-abi': broken['jni/arm64-v8a/libfataar_registered.so'] = broken['jni/x86/libfataar_registered.so']
        elif scenario == 'broken-elf': broken['jni/x86/libfataar_registered.so'] = b'broken ELF'
        elif scenario == 'missing-static-library': del broken['prefab/modules/fataar_static/libs/android.x86/libfataar_static.a']
        elif scenario == 'missing-prefab-abi':
            broken = {name: data for name, data in broken.items() if not name.startswith('prefab/modules/fataar_static/libs/android.x86/')}
        elif scenario == 'wrong-static-abi': broken['prefab/modules/fataar_static/libs/android.arm64-v8a/libfataar_static.a'] = broken['prefab/modules/fataar_static/libs/android.x86/libfataar_static.a']
        elif scenario == 'invalid-prefab-schema': broken['prefab/prefab.json'] = b'{"name":"sdk","schema_version":1}'
        elif scenario == 'missing-prefab-export': broken['prefab/modules/fataar_bridge/module.json'] = b'{"export_libraries":[":absent"]}'
        elif scenario == 'prefab-min-sdk':
            path = 'prefab/modules/fataar_static/libs/android.x86/abi.json'
            record = json.loads(broken[path]); record['api'] = 25; broken[path] = json.dumps(record).encode()
        write_archive(input_copy, broken)
        text = gradle(scenario, *probe_args, expected=expected)
        assert 'Reusing configuration cache.' in text, scenario
    input_copy.write_bytes(PUBLISHED.read_bytes())
    extra = REPORTS / 'extra-input.aar'
    extra_manifest = b'<manifest package="extra"><uses-sdk xmlns:android="http://schemas.android.com/apk/res/android" android:minSdkVersion="24"/></manifest>'
    extra_entries = {'AndroidManifest.xml': extra_manifest, 'jni/x86/libfataar_math.so': b'conflicting JNI payload'}
    extra_args = [*probe_args, '-Ptest.additionalInput=' + str(extra)]
    write_archive(extra, extra_entries)
    gradle('conflicting-jni-owners', *extra_args, expected='FATAAR_PAYLOAD_CONFLICT')
    write_archive(extra, {'AndroidManifest.xml': extra_manifest, 'prefab/prefab.json': b'{"name":"other","schema_version":2}',
                          'prefab/modules/fataar_headers/module.json': b'{}', 'prefab/modules/fataar_headers/include/other.h': b'// duplicate module'})
    text = gradle('conflicting-prefab-owners', *extra_args, expected='FATAAR_PREFAB_MODULE_CONFLICT')
    assert 'Reusing configuration cache.' in text
    gradle('restore-native-input', *probe_args)
    gradle('restore-positive-sdk', *tasks)
    assert inspect() == artifacts
    run('final-signatures', [*pgp, 'verify', keys, *signed])
    (REPORTS / 'result.json').write_text(json.dumps({'status': 'PASS', 'checks': PASSED, 'artifacts': artifacts,
                                                   'signing': {'plugin': 'org.gradle.signing', 'verified': ['aar', 'pom', 'module'], 'tamperRejected': True}}, indent=2) + '\n')
    print(f'All native artifact and signing checks passed: {REPORTS / "result.json"}', flush=True)


if __name__ == '__main__':
    main()
