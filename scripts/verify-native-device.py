#!/usr/bin/env python3
"""Require real debug/R8 runtime tests of JNI and Prefab through SDK-only dependencies."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'integration-tests/native-consumer'
NATIVE = ROOT / 'integration-tests/native'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    env = os.environ.copy()
    env['ANDROID_SERIAL'] = args.serial
    if not env.get('ANDROID_HOME') and not env.get('ANDROID_SDK_ROOT'):
        for line in (ROOT / 'local.properties').read_text().splitlines():
            if line.startswith('sdk.dir='):
                env['ANDROID_HOME'] = line.split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ')
    reports = ROOT / 'build/reports/native-device-validation'
    reports.mkdir(parents=True, exist_ok=True)
    (reports / 'result.json').unlink(missing_ok=True)
    sdk = env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
    adb = Path(sdk) / 'platform-tools/adb'
    def prop(name):
        return subprocess.check_output([str(adb), '-s', args.serial, 'shell', 'getprop', name],
                                       env=env, text=True, timeout=20).strip()
    device = {'serial': args.serial, 'api': prop('ro.build.version.sdk'),
              'abi': prop('ro.product.cpu.abi'), 'supportedAbis': prop('ro.product.cpu.abilist').split(','),
              'model': prop('ro.product.model')}
    assert device['abi'] and device['api'], 'Device ABI/API must be known for runtime claims'
    results = {}
    for consumer in ('project', 'metadata', 'pom'):
        project = NATIVE if consumer == 'project' else PROJECT
        for mode in ('debug', 'release'):
            name = consumer + '-' + mode
            log = reports / (name + '.log')
            print(f'{name}: {log}', flush=True)

            def reject(message):
                # Gradle may return success when the runner crashes before discovering
                # any tests. Preserve focused device diagnostics with the failed report.
                diagnostics = reports / (name + '-runner.log')
                sdk = env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
                try:
                    with diagnostics.open('w') as output:
                        subprocess.run([str(Path(sdk) / 'platform-tools/adb'), '-s', args.serial,
                                        'logcat', '-d', '-v', 'brief', '-s', 'AndroidJUnitRunner:V',
                                        'AndroidRuntime:E', 'TestLoader:V', 'TestRequestBuilder:V'],
                                       env=env, stdout=output, stderr=subprocess.STDOUT, timeout=20)
                except (OSError, TypeError, subprocess.TimeoutExpired) as error:
                    diagnostics.write_text(str(error))
                raise AssertionError(f'{name}: {message}; Gradle log: {log}; runner log: {diagnostics}')

            directory = project / f'app/build/outputs/androidTest-results/connected/{mode}'
            for old in directory.rglob('TEST-*.xml'): old.unlink()
            command = [str(ROOT / 'gradlew'), '-p', str(project), ':app:connected' + mode.capitalize() + 'AndroidTest',
                       '--rerun', '--console=plain', '-PtestBuildType=' + mode]
            if consumer == 'pom': command.append('-PpomOnly=true')
            with log.open('w') as output:
                completed = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
            if completed.returncode: reject('Gradle run failed')
            files = list(directory.rglob('TEST-*.xml'))
            if not files: reject('no instrumentation reports')
            count = 0
            discovered = set()
            for path in files:
                tree = ET.parse(path)
                cases = tree.findall('.//testcase')
                discovered.update(c.get('name') for c in cases)
                if tree.findall('.//failure') or tree.findall('.//error') or tree.findall('.//skipped'):
                    reject(f'failed or skipped test in {path}')
                count += len(cases)
            expected = {'embeddedJniAndRegisteredNativesWorkThroughSdk', 'consumerCompilesAndRunsSharedStaticAndHeaderOnlyPrefabModules'}
            if discovered != expected or count != 2:
                reject(f'expected exactly both native tests; discovered {discovered}, count={count}')
            results[name] = count
    (reports / 'result.json').write_text(json.dumps({'status': 'PASS', 'device': device, 'tests': results}, indent=2) + '\n')
    print(f'All JNI/Prefab runtime checks passed: {reports / "result.json"}', flush=True)


if __name__ == '__main__':
    main()
