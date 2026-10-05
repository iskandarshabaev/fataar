#!/usr/bin/env python3
"""Build real source attachments and signatures locally. Never invoke a publish task."""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import zipfile

from gradle_environment import pgp_jars

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / 'build/reports/sources-validation'
WORK = ROOT / 'build/sources-validation'
ENV = os.environ.copy()
if not ENV.get('ANDROID_HOME') and not ENV.get('ANDROID_SDK_ROOT'):
    for line in (ROOT / 'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='):
            ENV['ANDROID_HOME'] = line.split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ')
PASSED = []
INDEX = 'META-INF/fataar/sources.json'


def run(name, args, expected=None):
    log = REPORTS / (name + '.log')
    print(f'{name}: {log}', flush=True)
    with log.open('w') as output:
        result = subprocess.run(list(map(str, args)), cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text()
    if (expected is None and result.returncode) or (expected and (result.returncode == 0 or expected not in text)):
        print('\n'.join(text.splitlines()[-90:]), file=sys.stderr)
        raise AssertionError(f'Unexpected outcome: {name}')
    PASSED.append(name)
    return text


def gradle(name, *args, project=ROOT, expected=None):
    assert not any('publish' in str(arg).lower() for arg in args if str(arg).startswith(':'))
    return run(name, [ROOT / 'gradlew', '-p', project, *args, '--configuration-cache',
                      '--configuration-cache-problems=fail', '--build-cache', '--console=plain'], expected)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def source_file(data):
    """Read the JVM SourceFile attribute from the compiled artifact, including inner classes."""
    stream = io.BytesIO(data)
    def u2(): return struct.unpack('>H', stream.read(2))[0]
    def u4(): return struct.unpack('>I', stream.read(4))[0]
    assert u4() == 0xcafebabe
    stream.read(4)
    size = u2()
    pool = [None] * size
    index = 1
    while index < size:
        tag = stream.read(1)[0]
        if tag == 1: pool[index] = stream.read(u2()).decode('utf-8', errors='replace')
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): stream.read(4)
        elif tag in (5, 6): stream.read(8); index += 1
        elif tag in (7, 8, 16, 19, 20): stream.read(2)
        elif tag == 15: stream.read(3)
        else: raise AssertionError(f'Unknown JVM constant tag {tag}')
        index += 1
    stream.read(6)
    stream.read(u2() * 2)
    for _ in range(2):
        for _ in range(u2()):
            stream.read(6)
            for _ in range(u2()): u2(); stream.read(u4())
    for _ in range(u2()):
        name, length = pool[u2()], u4()
        if name == 'SourceFile':
            assert length == 2
            return pool[u2()]
        stream.read(length)
    return None


def contents(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        assert len(names) == len(set(names))
        assert not any(n.endswith('.class') for n in names)
        entries = {name: jar.read(name) for name in names}
    index = json.loads(entries[INDEX])
    assert index['sourceCount'] == sum(n.endswith(('.java', '.kt', '.kts', '.aidl', '.groovy', '.scala', '.proto')) for n in entries)
    assert not any(str(ROOT).encode() in data for name, data in entries.items() if name.startswith('META-INF/fataar/'))
    return entries, index


def inspect_main(mode):
    path = ROOT / f'sdk/build/outputs/sources/sdk-{mode}-sources.jar'
    entries, index = contents(path)
    owners = {item['owner'] for item in index['inputs']}
    assert owners == {'project::sdk'} | {'project::fixtures:' + p.name for p in (ROOT / 'fixtures').iterdir() if (p / 'src').is_dir()}
    original_count = 0
    for owner in owners:
        module = ROOT / owner.removeprefix('project::').replace(':', '/')
        for file in (module / 'src/main').rglob('*'):
            if not file.is_file() or file.suffix not in ('.java', '.kt', '.aidl'): continue
            relative = file.relative_to(module / 'src/main')
            key = '/'.join(relative.parts[1:])
            assert entries[key] == file.read_bytes(), (mode, file, key)
            original_count += 1
    assert not any(name.startswith(('org/jetbrains/annotations/', 'androidx/constraintlayout/')) for name in entries)
    class_count = 0
    with zipfile.ZipFile(ROOT / f'sdk/build/outputs/aar/sdk-{mode}.aar') as aar:
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
            for name in jar.namelist():
                if not name.endswith('.class'): continue
                filename = source_file(jar.read(name))
                assert filename, (mode, name, 'missing SourceFile')
                key = name.rsplit('/', 1)[0] + '/' + filename
                assert key in entries, (mode, name, key)
                class_count += 1
    for generated in ('com/fataar/fixtures/aidl/IFixtureBridge.java',
                      'com/fataar/fixtures/viewbinding/databinding/FixtureViewBindingBinding.java',
                      'com/fataar/sdk/databinding/FixtureSdkBindingBindingImpl.java'):
        assert generated in entries
    return {'sha256': digest(path), 'owners': len(owners), 'sources': index['sourceCount'],
            'originalFilesCompared': original_count, 'compiledClassesWithSources': class_count}


def inspect_variants():
    project = ROOT / 'integration-tests/variants'
    variants = [color + mode for color in ('red', 'blue') for mode in ('Debug', 'Release', 'Staging')]
    tasks = [':sdk:merge' + v[0].upper() + v[1:] + 'FatAarSources' for v in variants]
    gradle('six-variant-sources', *tasks, '-Ptest.sources=true', project=project)
    assert 'Reusing configuration cache.' in gradle('six-variant-sources-cache', *tasks, '-Ptest.sources=true', project=project)
    results = {}
    for variant in variants:
        color = 'red' if variant.startswith('red') else 'blue'
        mode = variant[len(color):].lower()
        child_mode = 'debug' if mode == 'staging' else mode
        engine = 'modern' if color == 'red' else 'classic'
        path = project / f'sdk/build/outputs/sources/sdk-{variant}-sources.jar'
        entries, index = contents(path)
        checks = [('sdk', color, 'FlavorOnly'), ('sdk', mode, 'SdkBuild'),
                  ('sdk', variant, 'SdkExtras'), ('flavored', color, 'FlavorMarker'),
                  ('engine', engine, 'EngineMarker')]
        for module, scope, clazz in checks:
            key = f'com/fataar/variants/{module}/{clazz}.java'
            expected = project / f'{module}/src/{scope}/java/{key}'
            assert entries[key] == expected.read_bytes(), (variant, key)
        # The producer's chosen build type must follow the AAR's matchingFallbacks.
        origins = [json.loads(data) for name, data in entries.items() if name.endswith('/source-origin.json')]
        flavored = next(item for item in origins if item['owner'] == 'project::flavored')
        assert flavored['variant'] == color + child_mode.capitalize(), (variant, flavored)
        results[variant] = {'sha256': digest(path), 'owners': len(index['inputs']), 'sources': index['sourceCount']}
    PASSED.append('six-variant-exact-source-selection')
    return results


def remote_fixture():
    project = WORK / 'remote'
    repository = project / 'repository'
    write(project / 'settings.gradle.kts', f'''pluginManagement {{ includeBuild("{ROOT / 'plugin-build'}"); repositories {{ google(); mavenCentral(); gradlePluginPortal() }} }}
dependencyResolutionManagement {{ repositories {{ maven {{ url=uri("repository"); if (providers.gradleProperty("test.pomOnly").isPresent) metadataSources {{ mavenPom(); artifact(); ignoreGradleMetadataRedirection() }} }}; google(); mavenCentral() }}; versionCatalogs {{ create("libs") {{ from(files("{ROOT / 'gradle/libs.versions.toml'}")) }} }} }}
rootProject.name="SourcesMavenFixture"
include(":one", ":two", ":sdk")
''')
    write(project / 'build.gradle.kts', 'plugins { alias(libs.plugins.android.library) apply false }\n')
    write(project / 'gradle.properties', 'org.gradle.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.caching=true\norg.gradle.workers.max=4\norg.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n')
    for module, version in [('one', '1'), ('two', '2')]:
        write(project / f'{module}/build.gradle.kts', f'''plugins {{ `java-library`; `maven-publish` }}
group="com.fataar.sourcefixture"; version="{version}"
java {{ withSourcesJar(); toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }}
publishing.publications.register<MavenPublication>("fixture") {{ artifactId="remote"; from(components["java"]) }}
''')
        write(project / f'{module}/src/main/java/com/fataar/sourcefixture/Remote.java', f'package com.fataar.sourcefixture; public class Remote {{ public static String marker() {{ return "source-version-{version}"; }} }}\n')
    write(project / 'sdk/build.gradle.kts', '''plugins { id("com.android.library"); id("com.fataar"); `maven-publish` }
group="com.fataar.sourcefixture"; version="1"
android { namespace="com.fataar.sourcefixture.sdk"; compileSdk=36; defaultConfig { minSdk=24 }; compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 } }
fatAar { withSourcesJar() }
dependencies { "embed"("com.fataar.sourcefixture:remote:1"); constraints { implementation("com.fataar.sourcefixture:remote:2") } }
val publication=publishing.publications.register<MavenPublication>("sdk")
components.configureEach { if (name=="fatRelease") { val component=this; publication.configure { from(component) } } }
''')
    write(project / 'sdk/src/main/java/com/fataar/sourcefixture/sdk/Facade.java', 'package com.fataar.sourcefixture.sdk; public class Facade { public static String marker() { return com.fataar.sourcefixture.Remote.marker(); } }\n')
    build = [f':{m}:{t}' for m in ('one', 'two') for t in ('jar', 'sourcesJar', 'generatePomFileForFixturePublication', 'generateMetadataFileForFixturePublication')]
    gradle('remote-real-binaries-and-sources', *build, project=project)
    # Copy actual generated artifacts to a local Maven layout. No publisher is invoked.
    for module, version in [('one', '1'), ('two', '2')]:
        coordinate = repository / f'com/fataar/sourcefixture/remote/{version}'
        coordinate.mkdir(parents=True, exist_ok=True)
        base = coordinate / f'remote-{version}'
        for source, suffix in [(project / f'{module}/build/libs/{module}-{version}.jar', '.jar'),
                               (project / f'{module}/build/libs/{module}-{version}-sources.jar', '-sources.jar'),
                               (project / f'{module}/build/publications/fixture/pom-default.xml', '.pom'),
                               (project / f'{module}/build/publications/fixture/module.json', '.module')]:
            shutil.copyfile(source, Path(str(base) + suffix))
        metadata = Path(str(base) + '.module')
        content = json.loads(metadata.read_text())
        for variant in content['variants']:
            for file in variant.get('files', []):
                for key in ('name', 'url'): file[key] = file[key].replace(f'{module}-{version}', f'remote-{version}')
        metadata.write_text(json.dumps(content))
    results = {}
    for mode, options in [('metadata', []), ('pom', ['-Ptest.pomOnly=true'])]:
        gradle('remote-' + mode, ':sdk:inspectReleaseAar', ':sdk:mergeReleaseFatAarSources', '--refresh-dependencies', *options, project=project)
        entries, index = contents(project / 'sdk/build/outputs/sources/sdk-release-sources.jar')
        key = 'com/fataar/sourcefixture/Remote.java'
        assert entries[key] == (project / f'two/src/main/java/{key}').read_bytes()
        assert {item['owner'] for item in index['inputs']} == {'project::sdk', 'module:com.fataar.sourcefixture:remote'}
        with zipfile.ZipFile(project / 'sdk/build/outputs/aar/sdk-release.aar') as aar:
            with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
                data = jar.read('com/fataar/sourcefixture/Remote.class')
                assert b'source-version-2' in data and b'source-version-1' not in data
        results[mode] = 'binary-and-sources-version-2'
    PASSED.append('remote-version-alignment')
    selected = repository / 'com/fataar/sourcefixture/remote/2/remote-2-sources.jar'
    saved = selected.read_bytes()
    selected.unlink()
    try:
        output = gradle('missing-remote-sources', ':sdk:mergeReleaseFatAarSources', '--refresh-dependencies', project=project, expected='FATAAR_MISSING_SOURCES')
        assert 'com.fataar.sourcefixture:remote' in output
    finally:
        selected.write_bytes(saved)
    gradle('restore-remote-sources', ':sdk:mergeReleaseFatAarSources', '--refresh-dependencies', project=project)
    return results


def independent_source_consumer():
    project = WORK / 'consumer'
    coordinate = project / 'repository/com/fataar/playground/sdk/0.2.0-merged'
    coordinate.mkdir(parents=True, exist_ok=True)
    artifacts = {
        'sdk-0.2.0-merged.aar': ROOT / 'sdk/build/outputs/aar/sdk-release.aar',
        'sdk-0.2.0-merged-sources.jar': ROOT / 'sdk/build/outputs/sources/sdk-release-sources.jar',
        'sdk-0.2.0-merged.pom': ROOT / 'sdk/build/publications/merged/pom-default.xml',
        'sdk-0.2.0-merged.module': ROOT / 'sdk/build/publications/merged/module.json',
    }
    for name, file in artifacts.items(): shutil.copyfile(file, coordinate / name)
    write(project / 'settings.gradle.kts', '''rootProject.name="IndependentSourcesConsumer"
dependencyResolutionManagement { repositories { maven { url=uri("repository") } } }
''')
    write(project / 'build.gradle.kts', '''plugins { base }
val sources=configurations.create("sdkSources") {
    isCanBeResolved=true; isCanBeConsumed=false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies { add("sdkSources", "com.fataar.playground:sdk:0.2.0-merged") }
tasks.register<Sync>("resolveSdkSources") { from(sources); into(layout.buildDirectory.dir("sources")) }
''')
    write(project / 'gradle.properties', 'org.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx512m\n')
    gradle('independent-sdk-source-consumer', 'resolveSdkSources', project=project)
    assert digest(project / 'build/sources/sdk-0.2.0-merged-sources.jar') == digest(artifacts['sdk-0.2.0-merged-sources.jar'])
    assert len(list((project / 'build/sources').iterdir())) == 1
    assert 'Reusing configuration cache.' in gradle('independent-source-consumer-cache', 'resolveSdkSources', project=project)
    PASSED.append('consumer-selects-only-aggregated-sdk-sources')


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    (REPORTS / 'result.json').unlink(missing_ok=True)
    tasks = [':sdk:inspectDebugAar', ':sdk:inspectReleaseAar', ':sdk:mergeDebugFatAarSources', ':sdk:mergeReleaseFatAarSources']
    gradle('real-aar-and-sources', *tasks)
    output = gradle('sources-configuration-cache', *tasks)
    assert 'Reusing configuration cache.' in output
    assert ':sdk:mergeReleaseFatAarSources UP-TO-DATE' in output
    artifacts = {mode: inspect_main(mode) for mode in ('debug', 'release')}
    PASSED.append('all-original-and-generated-sources-exact')
    output = gradle('sources-from-build-cache', ':sdk:clean', *tasks)
    for mode in ('debug', 'release'):
        assert f':sdk:merge{mode.capitalize()}FatAarSources FROM-CACHE' in output
        assert inspect_main(mode) == artifacts[mode]
    keys = WORK / 'keys'
    keys.mkdir(parents=True, exist_ok=True)
    java = Path(ENV['JAVA_HOME']) / 'bin/java' if ENV.get('JAVA_HOME') else 'java'
    jars = pgp_jars(Path(ENV.get('GRADLE_USER_HOME', Path.home() / '.gradle')))
    assert len(jars) == 3
    pgp = [java, '--class-path', os.pathsep.join(map(str, jars)), ROOT / 'integration-tests/candidates/PgpFixture.java']
    run('disposable-signing-key', [*pgp, 'generate', keys])
    signing = '-PfatAar.signingKeyFile=' + str(keys / 'test-secret.asc')
    gradle('clean-before-standalone-sign', ':sdk:clean')
    output = gradle('standalone-sign-aar-and-sources', ':sdk:signMergedPublication', signing, '--no-build-cache')
    assert ':sdk:mergeReleaseFatAarSources' in output and ':sdk:mergeReleaseFatAar' in output
    artifact = ROOT / 'sdk/build/outputs/sources/sdk-release-sources.jar'
    assert inspect_main('release') == artifacts['release']
    pom = ROOT / 'sdk/build/publications/merged/pom-default.xml'
    module = ROOT / 'sdk/build/publications/merged/module.json'
    metadata = json.loads(module.read_text())
    documentation = [v for v in metadata['variants'] if v['attributes'].get('org.gradle.docstype') == 'sources']
    assert len(documentation) == 1
    doc = documentation[0]
    assert not doc.get('dependencies') and not doc.get('dependencyConstraints')
    assert not any(key.startswith('com.android.') for key in doc['attributes'])
    assert doc['files'][0]['name'] == 'sdk-0.2.0-merged-sources.jar'
    assert doc['files'][0]['sha256'] == digest(artifact)
    signed = [ROOT / 'sdk/build/outputs/aar/sdk-release.aar', artifact, pom, module]
    run('verify-signatures', [*pgp, 'verify', keys, *signed])
    tampered = keys / 'tampered-sources.jar'
    tampered.write_bytes(artifact.read_bytes() + b'tampered')
    shutil.copyfile(Path(str(artifact) + '.asc'), Path(str(tampered) + '.asc'))
    run('reject-tampered-sources', [*pgp, 'verify', keys, tampered], expected='Invalid signature')
    output = gradle('sign-configuration-cache', ':sdk:signMergedPublication', signing, '--no-build-cache')
    assert 'Reusing configuration cache.' in output and ':sdk:signMergedPublication UP-TO-DATE' in output
    independent_source_consumer()
    variants = inspect_variants()
    remote = remote_fixture()
    gradle("restore-main-debug-sources", ":sdk:mergeDebugFatAarSources")
    assert inspect_main("debug") == artifacts["debug"]
    result = {'status': 'PASS', 'publishTasksExecuted': False, 'stages': PASSED,
              'artifacts': artifacts, 'flavorArtifacts': variants, 'remoteSources': remote,
              'signedArtifacts': ['aar', 'sources.jar', 'pom', 'module']}
    (REPORTS / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
