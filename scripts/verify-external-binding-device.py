#!/usr/bin/env python3
"""Require real debug/R8 runtime tests of external bindings through SDK-only dependencies."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'integration-tests/external-bindings-consumer'


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
    reports = ROOT / 'build/reports/external-binding-device-validation'
    reports.mkdir(parents=True, exist_ok=True)
    (reports / 'result.json').unlink(missing_ok=True)
    results = {}
    for consumer in ('metadata', 'pom'):
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

            directory = PROJECT / f'app/build/outputs/androidTest-results/connected/{mode}'
            for old in directory.rglob('TEST-*.xml'): old.unlink()
            command = [str(ROOT / 'gradlew'), '-p', str(PROJECT), ':app:connected' + mode.capitalize() + 'AndroidTest',
                       '--rerun', '--console=plain', '-PtestBuildType=' + mode]
            if consumer == 'pom': command.append('-PpomOnly=true')
            with log.open('w') as output:
                completed = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
            if completed.returncode: reject('Gradle run failed')
            files = list(directory.rglob('TEST-*.xml'))
            if not files: reject('no instrumentation reports')
            count = 0
            for path in files:
                tree = ET.parse(path)
                cases = tree.findall('.//testcase')
                if not any(c.get('name') == 'transitiveApiBindingsInflateAndPropagateValues' for c in cases):
                    reject(f'expected test was not discovered in {path}')
                if tree.findall('.//failure') or tree.findall('.//error') or tree.findall('.//skipped'):
                    reject(f'failed or skipped test in {path}')
                count += len(cases)
            results[name] = count
    (reports / 'result.json').write_text(json.dumps({'status': 'PASS', 'tests': results}, indent=2) + '\n')
    print(f'All external binding runtime checks passed: {reports / "result.json"}', flush=True)


if __name__ == '__main__':
    main()
