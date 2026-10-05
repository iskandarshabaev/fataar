#!/usr/bin/env python3
"""Build and consume a real SDK on one Gradle/JDK combination; CI runs the matrix."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from dex_inventory import verify_apk_classes
from scale_fixture import generate
import importlib.util

_spec = importlib.util.spec_from_file_location('scale_validation', Path(__file__).with_name('verify-scale.py'))
_scale = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_scale)
inventory = _scale.inventory

ROOT = Path(__file__).resolve().parents[1]
CHECKSUMS = {
    '9.6.0': 'bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01',
    '9.8.0': 'bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-version', choices=CHECKSUMS, required=True)
    parser.add_argument('--jdk-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--expected-jdk', choices=['17', '21'], required=True)
    args = parser.parse_args()
    assert args.jdk_home and (args.jdk_home / 'bin/java').is_file(), 'Supply --jdk-home or JAVA_HOME'
    env = os.environ.copy()
    env['JAVA_HOME'] = str(args.jdk_home.resolve())
    if not env.get('ANDROID_HOME') and not env.get('ANDROID_SDK_ROOT'):
        for line in (ROOT / 'local.properties').read_text().splitlines():
            if line.startswith('sdk.dir='): env['ANDROID_HOME'] = line.split('=', 1)[1]
    case = args.gradle_version + '-jdk' + args.expected_jdk
    base = ROOT / 'build/compatibility'
    reports = ROOT / 'build/reports/compatibility-validation' / case
    reports.mkdir(parents=True, exist_ok=True)
    (reports / 'result.json').unlink(missing_ok=True)
    wrapper = base / 'wrappers' / args.gradle_version
    (wrapper / 'gradle/wrapper').mkdir(parents=True, exist_ok=True)
    for name in ('gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar'):
        shutil.copy2(ROOT / name, wrapper / name)
    (wrapper / 'gradle/wrapper/gradle-wrapper.properties').write_text(
        'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n'
        f'distributionUrl=https\\://services.gradle.org/distributions/gradle-{args.gradle_version}-bin.zip\n'
        f'distributionSha256Sum={CHECKSUMS[args.gradle_version]}\nnetworkTimeout=60000\n'
        'validateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n')
    gradle = wrapper / 'gradlew'
    timings = {}

    def run(name, project, *arguments):
        path = reports / (name + '.log')
        print(f'{case}/{name}: {path}', flush=True)
        started = time.monotonic()
        with path.open('w') as log:
            result = subprocess.run([str(gradle), '-p', str(project), *arguments, '--console=plain',
                                     '--no-daemon', '-Dorg.gradle.java.home=' + env['JAVA_HOME']],
                                    cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        timings[name] = round(time.monotonic() - started, 3)
        text = path.read_text()
        if result.returncode:
            print('\n'.join(text.splitlines()[-80:]))
            raise AssertionError(name)
        return text

    version = run('version', ROOT, '--version')
    assert f'Gradle {args.gradle_version}' in version
    assert re.search(r'Launcher JVM:\s+' + args.expected_jdk + r'\.', version), version
    assert env['JAVA_HOME'] in version, 'Gradle daemon must use the requested JDK'
    project, cache = base / ('project-' + case), base / ('cache-' + case)
    for directory in (project, cache):
        if directory.exists(): shutil.rmtree(directory)
    generate(project, 3, 'diamonds', cache)
    tasks = (':sdk:inspectReleaseAar', ':sdk:publishSdkPublicationToScaleRepository', '--no-build-cache')
    run('producer', project, *tasks)
    original = inventory(project, 3, env)
    text = run('producer-cache', project, *tasks)
    assert 'Reusing configuration cache.' in text and ':sdk:mergeReleaseFatAar UP-TO-DATE' in text
    assert inventory(project, 3, env) == original
    run('consumer', project / 'consumer', ':app:assembleDebug', '--refresh-dependencies')
    text = run('consumer-cache', project / 'consumer', ':app:assembleDebug')
    assert 'Reusing configuration cache.' in text
    definitions = verify_apk_classes(project / 'consumer/app/build/outputs/apk/debug/app-debug.apk',
                                     project / 'sdk/build/outputs/aar/sdk-release.aar')
    assert definitions == 4
    result = {'status': 'PASS', 'gradle': args.gradle_version, 'jdk': args.expected_jdk,
              'versionOutput': version, 'artifact': original, 'consumerSdkClassDefinitions': definitions,
              'producerAndConsumerConfigurationCacheReused': True, 'wallSeconds': timings}
    (reports / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'{case}: PASS', flush=True)


if __name__ == '__main__': main()
