#!/usr/bin/env python3
"""Build real signed SDK artifacts, verify them, and run all consumer/device checks."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def plugin_test_results():
    files = list((ROOT / 'plugin-build/build/test-results/test').glob('TEST-*.xml'))
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
    if not totals['tests'] or any(totals[key] for key in ('failures', 'errors', 'skipped')):
        raise AssertionError(f'Plugin JVM/TestKit tests did not all pass: {totals}')
    return totals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Use an existing device; do not start or stop it')
    parser.add_argument('--avd', help='Start this installed AVD; otherwise use the first installed AVD')
    args = parser.parse_args()
    if args.serial and args.avd:
        parser.error('--serial and --avd are mutually exclusive')
    env = os.environ.copy()
    sdk = env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
    if not sdk:
        for line in (ROOT / 'local.properties').read_text().splitlines():
            if line.startswith('sdk.dir='):
                sdk = line.split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ')
    if not sdk:
        raise RuntimeError('Set ANDROID_HOME or sdk.dir in local.properties')
    env['ANDROID_HOME'] = sdk
    adb = Path(sdk) / 'platform-tools/adb'
    emulator = Path(sdk) / 'emulator/emulator'
    reports = ROOT / 'build/reports/full-validation'
    reports.mkdir(parents=True, exist_ok=True)
    result = reports / 'result.json'
    result.unlink(missing_ok=True)

    def adb_run(*arguments):
        return subprocess.run([str(adb), *arguments], env=env, capture_output=True, text=True, timeout=20)

    def run(script, *arguments):
        subprocess.run([sys.executable, str(ROOT / 'scripts' / script), *arguments],
                       cwd=ROOT, env=env, check=True)

    # Check prerequisites first. Never install system images or stop an existing emulator.
    process = None
    serial = args.serial
    avd = None
    if not serial:
        avds = subprocess.check_output([str(emulator), '-list-avds'], env=env, text=True).splitlines()
        avd = args.avd or next(iter(avds), None)
        if not avd or avd not in avds:
            raise RuntimeError('No matching installed AVD; supply --serial or create an AVD first')
    elif adb_run('-s', serial, 'get-state').stdout.strip() != 'device':
        raise RuntimeError(f'Device {serial} is not ready')
    run('verify-merged.py')
    run('verify-sources.py')
    run('verify-variants.py')
    run('verify-external-bindings.py')
    run('verify-native.py')
    run('verify-bom.py')
    try:
        if avd:
            existing = adb_run('devices').stdout
            port = None
            for candidate in range(5554, 5586, 2):
                if f'emulator-{candidate}' in existing:
                    continue
                sockets = []
                try:
                    for number in (candidate, candidate + 1):
                        sock = socket.socket()
                        sockets.append(sock)
                        sock.bind(('127.0.0.1', number))
                    port = candidate
                    break
                except OSError:
                    pass
                finally:
                    for sock in sockets:
                        sock.close()
            if port is None:
                raise RuntimeError('No free emulator console/ADB port pair')
            serial = f'emulator-{port}'
            print(f'Starting {avd} as {serial}', flush=True)
            with (reports / 'emulator.log').open('w') as log:
                process = subprocess.Popen([str(emulator), '-avd', avd, '-port', str(port),
                                            '-read-only', '-no-window', '-no-audio', '-no-snapshot'],
                                           env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Emulator exited: see full-validation/emulator.log')
                if adb_run('-s', serial, 'shell', 'getprop', 'sys.boot_completed').stdout.strip() == '1':
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Emulator boot timeout')
        devices = {}
        for consumer in ('project', 'maven', 'maven-pom'):
            run('verify-device.py', '--serial', serial, '--consumer', consumer)
            name = 'playground' if consumer == 'project' else consumer
            devices[consumer] = json.loads((ROOT / f'build/reports/{name}-device-validation/result.json').read_text())
        artifacts = json.loads((ROOT / 'build/reports/merged-validation/result.json').read_text())
        run('verify-variant-device.py', '--serial', serial)
        run('verify-external-binding-device.py', '--serial', serial)
        run('verify-native-device.py', '--serial', serial)
        bom = json.loads((ROOT / 'build/reports/bom-validation/result.json').read_text())
        sources = json.loads((ROOT / 'build/reports/sources-validation/result.json').read_text())
        native = json.loads((ROOT / 'build/reports/native-validation/result.json').read_text())
        native_devices = json.loads((ROOT / 'build/reports/native-device-validation/result.json').read_text())
        external = json.loads((ROOT / 'build/reports/external-binding-validation/result.json').read_text())
        external_devices = json.loads((ROOT / 'build/reports/external-binding-device-validation/result.json').read_text())
        variants = json.loads((ROOT / 'build/reports/variant-validation/result.json').read_text())
        variant_devices = json.loads((ROOT / 'build/reports/variant-device-validation/result.json').read_text())
        result.write_text(json.dumps({'status': 'PASS', 'artifacts': artifacts, 'devices': devices,
                                     'pluginTests': plugin_test_results(),
                                     'variants': variants, 'variantDevices': variant_devices,
                                     'externalBindings': external, 'externalBindingDevices': external_devices,
                                     'native': native, 'nativeDevices': native_devices, 'bom': bom,
                                     'sources': sources}, indent=2) + '\n')
        print(f'All artifact and device checks passed: {result}', flush=True)
    finally:
        if process is not None and process.poll() is None:
            try:
                adb_run('-s', serial, 'emu', 'kill')
            finally:
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=10)


if __name__ == '__main__':
    main()
