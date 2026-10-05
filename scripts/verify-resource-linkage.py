#!/usr/bin/env python3
"""Build actual Android archives, overlay resources and break runtime members. Local only."""
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

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'build/resource-linkage-playground'
REPORTS = ROOT / 'build/reports/resource-linkage-validation'
ENV = os.environ.copy()
if not ENV.get('ANDROID_HOME') and not ENV.get('ANDROID_SDK_ROOT'):
    for line in (ROOT / 'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='):
            ENV['ANDROID_HOME'] = line.split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ')
CHECKS = []


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def gradle(name, *args, expected=None):
    log = REPORTS / (name + '.log')
    print(f'{name}: {log}', flush=True)
    with log.open('w') as output:
        result = subprocess.run([str(ROOT / 'gradlew'), '-p', str(PROJECT), *args,
                                 '--configuration-cache', '--configuration-cache-problems=fail',
                                 '--build-cache', '--console=plain'], cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text()
    if (expected is None and result.returncode) or (expected and (not result.returncode or expected not in text)):
        print('\n'.join(text.splitlines()[-80:]), file=sys.stderr)
        raise AssertionError(name)
    CHECKS.append(name)
    return text


def setup():
    write(PROJECT / 'settings.gradle.kts', f'''pluginManagement {{ includeBuild("{ROOT / 'plugin-build'}"); repositories {{ google(); mavenCentral(); gradlePluginPortal() }} }}
dependencyResolutionManagement {{ repositories {{ google(); mavenCentral() }}; versionCatalogs {{ create("libs") {{ from(files("{ROOT / 'gradle/libs.versions.toml'}")) }} }} }}
rootProject.name="ResourceLinkagePlayground"
include(":sdk", ":a", ":b", ":compile-api", ":runtime", ":app")
''')
    write(PROJECT / 'build.gradle.kts', '''plugins { alias(libs.plugins.android.library) apply false; alias(libs.plugins.android.application) apply false }
allprojects { group="com.fataar.resourcelinkage"; version="1" }
''')
    write(PROJECT / 'gradle.properties', 'org.gradle.workers.max=4\norg.gradle.jvmargs=-Xmx2g\norg.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.caching=true\nandroid.useAndroidX=true\n')
    for name in ('compile-api', 'runtime'):
        write(PROJECT / f'{name}/build.gradle.kts', 'plugins { `java-library` }\njava { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }\n')
        write(PROJECT / f'{name}/src/main/java/outside/Api.java', API)
    for name in ('sdk', 'a', 'b'):
        plugins = 'id("com.fataar")' if name == 'sdk' else 'id("com.fataar.embeddable")'
        build = f'''plugins {{ id("com.android.library"); {plugins} }}
android {{ namespace="com.fataar.resourcelinkage.{name}"; compileSdk=36; defaultConfig {{ minSdk=24 }}; compileOptions {{ sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }} }}
'''
        if name == 'sdk':
            build += '''fatAar { if (providers.gradleProperty("test.winner").isPresent) resourceWinners.put("/string/shared", providers.gradleProperty("test.winner").get()) }
dependencies { "embed"(project(":a")); "embed"(project(":b")); implementation(project(":runtime")) }
'''
        elif name == 'a': build += 'dependencies { compileOnly(project(":compile-api")) }\n'
        write(PROJECT / f'{name}/build.gradle.kts', build)
        write(PROJECT / f'{name}/src/main/res/values/values.xml', '<resources><string name="shared">common</string><string name="' + name + '_unique">' + name + '</string></resources>\n')
    for name in ('a', 'b'):
        write(PROJECT / f'{name}/src/main/res/drawable/shared_shape.xml', '<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="#ff0000"/></shape>\n')
        write(PROJECT / f'{name}/src/main/res/values-night/values.xml', '<resources><string name="shared">night</string></resources>\n')
    write(PROJECT / 'a/src/main/java/com/fataar/resourcelinkage/a/Probe.java', '''package com.fataar.resourcelinkage.a;
public class Probe {
    public static int resource() { return R.string.shared; }
    public static String call() { return privateBody(); }
    private static String privateBody() { return outside.Api.removed() + outside.Api.field; }
}
''')
    write(PROJECT / 'b/src/main/java/com/fataar/resourcelinkage/b/Probe.java', 'package com.fataar.resourcelinkage.b; public class Probe { public static int resource() { return R.string.shared; } }\n')
    write(PROJECT / 'sdk/src/main/java/com/fataar/resourcelinkage/sdk/Facade.java', '''package com.fataar.resourcelinkage.sdk;
public class Facade { public static String call() { return com.fataar.resourcelinkage.a.Probe.call(); } }
''')
    write(PROJECT / 'app/build.gradle.kts', '''plugins { id("com.android.application") }
android { namespace="com.fataar.resourcelinkage.app"; compileSdk=36; defaultConfig { applicationId="com.fataar.resourcelinkage.app"; minSdk=24; targetSdk=36 }; compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 } }
dependencies { implementation(project(":sdk")) { capabilities { requireCapability("com.fataar.resourcelinkage:sdk-fat") } } }
''')
    write(PROJECT / 'app/src/main/AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="@string/shared"/></manifest>\n')


API = 'package outside; public class Api { public static String field="field"; public static String removed() { return "runtime"; } }\n'


def inspect(expected='common', filename='sdk-debug.aar'):
    path = PROJECT / ('sdk/build/outputs/aar/' + filename)
    definitions = {}
    with zipfile.ZipFile(path) as aar:
        for name in aar.namelist():
            if name.startswith('res/values') and name.endswith('.xml'):
                qualifier = name.rsplit('/', 1)[0]
                for element in ET.fromstring(aar.read(name)):
                    key = qualifier + '/' + element.tag + '/' + element.get('name')
                    assert key not in definitions, key
                    definitions[key] = element.text
        assert definitions['res/values/string/shared'] == expected
        assert [v for k, v in definitions.items() if k.startswith('res/values-night') and k.endswith('/string/shared')] == ['night']
        for owner in ('sdk', 'a', 'b'): assert definitions['res/values/string/' + owner + '_unique'] == owner
        assert aar.read('res/drawable/shared_shape.xml') == (PROJECT / 'a/src/main/res/drawable/shared_shape.xml').read_bytes()
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
            assert 'outside/Api.class' not in jar.namelist()  # It must remain an external runtime dependency.
            assert {'com/fataar/resourcelinkage/a/Probe.class', 'com/fataar/resourcelinkage/b/Probe.class'} <= set(jar.namelist())
    report = PROJECT / 'sdk/build/reports/fat-aar/debug/linkage.txt'
    assert 'stage=final-aar' in report.read_text() and 'result=PASS' in report.read_text()
    return hashlib.sha256(path.read_bytes()).hexdigest()


def execute_sdk():
    directory = PROJECT / 'build/runtime-check'
    directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(PROJECT / 'sdk/build/outputs/aar/sdk-debug.aar') as aar:
        (directory / 'sdk-classes.jar').write_bytes(aar.read('classes.jar'))
    launcher = directory / 'RunSdk.java'
    launcher.write_text('''class RunSdk { public static void main(String[] args) {
        String actual=com.fataar.resourcelinkage.sdk.Facade.call();
        if (!"runtimefield".equals(actual)) throw new AssertionError(actual);
        System.out.println("SDK runtime code PASS");
    } }
''')
    java = Path(ENV['JAVA_HOME']) / 'bin/java' if ENV.get('JAVA_HOME') else 'java'
    output = subprocess.check_output([str(java), '--class-path', os.pathsep.join(map(str, [
        directory / 'sdk-classes.jar', PROJECT / 'runtime/build/libs/runtime-1.jar'])), str(launcher)], env=ENV, text=True)
    assert 'SDK runtime code PASS' in output
    CHECKS.append('execute-code-from-final-sdk-jar')


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'result.json').unlink(missing_ok=True)
    setup()
    tasks = [':sdk:inspectDebugAar', ':sdk:inspectReleaseAar', ':app:assembleDebug', ':app:assembleRelease']
    gradle('identical-resources-and-binary-linkage', *tasks)
    original = inspect()
    execute_sdk()
    assert 'Reusing configuration cache.' in gradle('configuration-cache', *tasks)
    output = gradle('build-cache', ':sdk:clean', ':sdk:inspectDebugAar')
    assert ':sdk:validateDebugLinkage FROM-CACHE' in output
    assert inspect() == original
    values = PROJECT / 'sdk/src/main/res/values/values.xml'
    saved = values.read_bytes()
    try:
        values.write_text(saved.decode().replace('>common<', '>sdk-wins<'))
        gradle('conflicting-values', ':sdk:inspectDebugAar', expected='FATAAR_RESOURCE_CONFLICT')
        gradle('explicit-sdk-overlay', ':sdk:inspectDebugAar', ':app:assembleDebug', '-Ptest.winner=sdk')
        inspect('sdk-wins')
        gradle('explicit-child-overlay', ':sdk:inspectDebugAar', '-Ptest.winner=project::a')
        inspect('common')
        gradle('unknown-overlay-owner', ':sdk:inspectDebugAar', '-Ptest.winner=project::missing', expected='FATAAR_INVALID_RESOURCE_WINNER')
    finally:
        values.write_bytes(saved)
    runtime = PROJECT / 'runtime/src/main/java/outside/Api.java'
    try:
        for label, text, failure in [
            ('removed-method', API.replace('String removed()', 'String renamed()'), 'FATAAR_LINKAGE_MISSING_METHOD'),
            ('private-method', API.replace('public static String removed()', 'private static String removed()'), 'FATAAR_LINKAGE_ACCESS'),
            ('changed-descriptor', API.replace('String removed() { return "runtime"; }', 'int removed() { return 1; }'), 'FATAAR_LINKAGE_MISSING_METHOD'),
            ('removed-field', API.replace('String field=', 'String renamedField='), 'FATAAR_LINKAGE_MISSING_FIELD'),
            ('static-to-instance', API.replace('static String removed()', 'String removed()'), 'FATAAR_LINKAGE_STATIC_MISMATCH'),
            ('missing-class', API.replace('class Api', 'class Replacement'), 'FATAAR_LINKAGE_MISSING_CLASS'),
        ]:
            if label == 'missing-class':
                runtime.unlink()
                replacement = runtime.with_name('Replacement.java')
                replacement.write_text(text)
            else: runtime.write_text(text)
            output = gradle(label, ':sdk:inspectDebugAar', expected=failure)
            assert 'Probe#privateBody' in output and 'outside/Api' in output
            if label == 'missing-class': replacement.unlink()
    finally:
        runtime.write_text(API)
    gradle('restore-and-build-consumers', *tasks)
    assert inspect() == original
    result = {'status': 'PASS', 'checks': CHECKS, 'originalAarSha256': original,
              'publishTasksExecuted': False, 'compiledConsumerApks': ['debug', 'release'],
              'runtimeExecutedOnDevice': False, 'finalSdkJvmCodeExecuted': True}
    (REPORTS / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__': main()
