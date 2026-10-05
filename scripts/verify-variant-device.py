#!/usr/bin/env python3
"""Run flavored SDK consumers and require actual passing instrumentation reports."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


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
    reports = ROOT / 'build/reports/variant-device-validation'
    reports.mkdir(parents=True, exist_ok=True)
    (reports / 'result.json').unlink(missing_ok=True)
    results = {}
    for consumer in ('project', 'metadata', 'pom'):
        project = ROOT / ('integration-tests/variants' if consumer == 'project' else 'integration-tests/variants-consumer')
        modes = ('debug', 'release', 'staging') if consumer == 'project' else ('debug', 'release')
        for mode in modes:
            name = consumer + '-' + mode
            log = reports / (name + '.log')
            print(f'{name}: {log}', flush=True)
            for color in ('red', 'blue'):
                for old in (project / f'app/build/outputs/androidTest-results/connected/{mode}/flavors/{color}').rglob('TEST-*.xml'):
                    old.unlink()
            command = [str(ROOT / 'gradlew'), '-p', str(project)]
            for color in ('Red', 'Blue'):
                command.extend([':app:connected' + color + mode.capitalize() + 'AndroidTest', '--rerun'])
            command.extend(['--console=plain', '-PtestBuildType=' + mode])
            if consumer == 'pom': command.append('-PpomOnly=true')
            with log.open('w') as output:
                completed = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
            if completed.returncode:
                raise RuntimeError(f'{name} Gradle run failed: {log}')
            for color in ('red', 'blue'):
                variant = color + mode.capitalize()
                files = list((project / f'app/build/outputs/androidTest-results/connected/{mode}/flavors/{color}').rglob('TEST-*.xml'))
                if not files: raise AssertionError(f'{name}: no reports for {variant}')
                count = 0
                for path in files:
                    tree = ET.parse(path)
                    cases = tree.findall('.//testcase')
                    assert any(c.get('name') == 'chosenVariantHasCorrectCodeResourcesAssetsAndScopes' for c in cases), path
                    assert not tree.findall('.//failure') and not tree.findall('.//error') and not tree.findall('.//skipped'), path
                    count += len(cases)
                results[consumer + '-' + variant] = count
    (reports / 'result.json').write_text(json.dumps({'status': 'PASS', 'tests': results}, indent=2) + '\n')
    print(f'All flavor runtime checks passed: {reports / "result.json"}', flush=True)


if __name__ == '__main__':
    main()
