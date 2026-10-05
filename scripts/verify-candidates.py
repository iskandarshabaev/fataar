#!/usr/bin/env python3
"""Isolated, reproducible merger comparison; never publishes outside build/candidates."""
import argparse
import hashlib
from gradle_environment import pgp_jars
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
WORK = ROOT / "build/candidates"
REPORTS = ROOT / "build/reports/candidate-validation"
WRAPPER = ROOT / "gradlew"
RESULT = {}
ENV = os.environ.copy()
if not ENV.get("ANDROID_HOME") and not ENV.get("ANDROID_SDK_ROOT"):
    for line in (ROOT / "local.properties").read_text().splitlines():
        if line.startswith("sdk.dir="):
            ENV["ANDROID_HOME"] = line.split("=", 1)[1].replace("\\:", ":").replace("\\ ", " ")


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def command(name, arguments, expected=None):
    log = REPORTS / (name + ".log")
    print(f"{name}: {log}", flush=True)
    with log.open("w") as output:
        process = subprocess.run([str(a) for a in arguments], cwd=ROOT, env=ENV,
                                 stdout=output, stderr=subprocess.STDOUT)
    content = log.read_text()
    if (expected is None and process.returncode) or (expected is not None and
            (not process.returncode or expected not in content)):
        print("\n".join(content.splitlines()[-100:]), file=sys.stderr)
        raise AssertionError(f"Unexpected outcome of {name}: exit {process.returncode}")
    return content


def gradle(name, project, *tasks, expected=None):
    return command(name, [WRAPPER, "-p", project, *tasks, "--console=plain"], expected)


def copy_project(source, destination):
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns(
        "build", ".gradle", ".kotlin", "local.properties", ".playground-repository"))


def prepare():
    # Generated sandboxes only; all editable test definitions live outside this directory.
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)
    properties = (ROOT / "gradle.properties").read_text()
    repositories = "repositories { google(); mavenCentral(); gradlePluginPortal() }"
    fused = WORK / "fused"
    write(fused / "settings.gradle.kts", f"pluginManagement {{ {repositories} }}\n"
          'rootProject.name = "fused-candidate"\n')
    write(fused / "gradle.properties", properties)
    write(fused / "build.gradle.kts", '''plugins {
    id("com.android.fused-library") version "9.4.0"
    `maven-publish`
}
androidFusedLibrary { namespace = "com.fataar.candidate"; minSdk { version = release(24) } }
when (providers.gradleProperty("probe").orNull) {
    "signing" -> pluginManager.apply("signing")
    "library" -> pluginManager.apply("com.android.library")
    "api" -> dependencies.add("api", "androidx.constraintlayout:constraintlayout:2.2.1")
}
''')
    umbrella = WORK / "umbrella"
    for directory in ("fixtures", "sdk", "gradle"):
        copy_project(ROOT / directory, umbrella / directory)
    settings = (ROOT / "settings.gradle.kts").read_text().replace(
        'includeBuild("plugin-build")', f'includeBuild("{ROOT / "plugin-build"}")').replace(
        'include(":app", ":sdk")', 'include(":sdk")')
    write(umbrella / "settings.gradle.kts", settings)
    write(umbrella / "gradle.properties", properties)
    write(umbrella / "build.gradle.kts", "")
    # Keep the candidate independent of the production SDK plugin/publication wiring.
    exports = "\n".join(line.replace('"embed"', '"export"') for line in
                        (ROOT / "sdk/build.gradle.kts").read_text().splitlines()
                        if line.strip().startswith('"embed"('))
    sdk = '''plugins {
    id("com.fataar.playground.android-library")
    id("io.github.tiper.umbrellaaar") version "3.0.0"
    id("io.github.tiper.umbrellaaar.pom") version "3.0.0"
    signing
}
android { namespace = "com.fataar.sdk" }
dependencies {
''' + exports + '''
    api(libs.androidx.constraintlayout)
    api(libs.androidx.constraintlayout.core)
}
'''
    sdk += '''
// export selects packaging inputs; the SDK's own sources also need these on their classpath.
configurations.named("implementation") { extendsFrom(configurations.getByName("export")) }
// A deliberate private external dependency to test whether publication preserves scopes.
dependencies { implementation("org.jetbrains:annotations:26.0.2") }
publishing {
    repositories.maven { name = "Candidate"; url = uri(rootProject.layout.projectDirectory.dir("repository")) }
    publications.withType<MavenPublication>().configureEach {
        groupId = "com.fataar.candidate"
        artifactId = "sdk-" + name
        version = "1.0"
    }
}
val testKey = providers.fileContents(rootProject.layout.projectDirectory.file("test-secret.asc")).asText
signing {
    useInMemoryPgpKeys(testKey.get(), "")
    publishing.publications.withType<MavenPublication>().configureEach {
        if (name == "androidReleaseUmbrellaAar") sign(this)
    }
}
'''
    write(umbrella / "sdk/build.gradle.kts", sdk)
    consumer = WORK / "consumer"
    copy_project(ROOT / "integration-tests/consumer-maven", consumer)
    settings = (consumer / "settings.gradle.kts").read_text().replace(
        "../../build/merged-repository", "../umbrella/repository").replace(
        "com.fataar.playground", "com.fataar.candidate").replace(
        "../../gradle/libs.versions.toml", "../umbrella/gradle/libs.versions.toml")
    write(consumer / "settings.gradle.kts", settings)
    build = (consumer / "app/build.gradle.kts").read_text().replace(
        "com.fataar.playground:sdk:0.2.0-merged", "com.fataar.candidate:sdk-androidReleaseUmbrellaAar:1.0")
    write(consumer / "app/build.gradle.kts", build)


def pgp(name, *args, expected=None):
    # Use the OpenPGP implementation shipped in the pinned Gradle distribution.
    gradle_user_home = Path(ENV.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    jars = pgp_jars(gradle_user_home)
    if len(jars) != 3:
        raise AssertionError(f"Expected three Gradle OpenPGP jars, found {jars}")
    java = Path(ENV["JAVA_HOME"]) / "bin/java" if ENV.get("JAVA_HOME") else "java"
    command(name, [java, "--class-path", os.pathsep.join(map(str, jars)),
                   ROOT / "integration-tests/candidates/PgpFixture.java", *args], expected=expected)


def fused_probes():
    fused = WORK / "fused"
    gradle("fused-control", fused, "help")
    repeated = gradle("fused-control-cache", fused, "help")
    assert "Reusing configuration cache." in repeated
    failures = {"api": "Configuration with name 'api' not found",
                "signing": "Cannot add task 'assemble'",
                "library": "plugins cannot be applied in the same project"}
    for probe, expected in failures.items():
        gradle("fused-" + probe, fused, "help", "-Pprobe=" + probe, expected=expected)
    RESULT["fused"] = {"version": "9.4.0", "configurationCacheControl": True,
                       "reproducedFailures": list(failures), "mergeNotRun": "single-module and signing gates failed"}


def umbrella_probes():
    umbrella = WORK / "umbrella"
    pgp("test-signing-key", "generate", umbrella)
    task = ":sdk:publishAndroidReleaseUmbrellaAarPublicationToCandidateRepository"
    gradle("umbrella-publish", umbrella, task)
    repeat = gradle("umbrella-publish-cache", umbrella, task)
    assert "Reusing configuration cache." in repeat
    published = umbrella / "repository/com/fataar/candidate/sdk-androidReleaseUmbrellaAar/1.0"
    aar = published / "sdk-androidReleaseUmbrellaAar-1.0.aar"
    pom_path = aar.with_suffix(".pom")
    artifacts = [aar, pom_path, published / "sdk-androidReleaseUmbrellaAar-1.0-sources.jar"]
    pgp("umbrella-signatures", "verify", umbrella, *artifacts)
    tampered = umbrella / "tampered.aar"
    tampered.write_bytes(aar.read_bytes() + b"tampered")
    shutil.copyfile(Path(str(aar) + ".asc"), Path(str(tampered) + ".asc"))
    pgp("umbrella-signature-negative", "verify", umbrella, tampered, expected="Invalid signature")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(pom_path)
    dependencies = {d.findtext("m:groupId", namespaces=ns) + ":" + d.findtext("m:artifactId", namespaces=ns):
                    d.findtext("m:scope", namespaces=ns) for d in pom.findall("m:dependencies/m:dependency", ns)}
    assert not any(d.startswith("com.fataar.playground:") for d in dependencies), dependencies
    assert dependencies.get("androidx.constraintlayout:constraintlayout") == "compile", dependencies
    assert dependencies.get("androidx.constraintlayout:constraintlayout-core") == "compile", dependencies
    with zipfile.ZipFile(aar) as archive:
        entries = sorted(archive.namelist())
        with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
            class_entries = sorted(classes.namelist())
            assert len(class_entries) == len(set(class_entries)), "Duplicate entries in classes.jar"
        assert "com/fataar/sdk/SdkPanel.class" in class_entries
        assert "com/fataar/fixtures/jvmcore/MessageProvider.class" in class_entries
        assert len(entries) == len(set(entries)), "Duplicate entries in AAR"
        expected_classes = set()
        inputs = list((umbrella / "fixtures").glob("*/build/outputs/aar/*-release.aar"))
        inputs += list((umbrella / "sdk/build/outputs/aar").glob("*-release.aar"))
        assert len(inputs) == 12, f"Expected SDK + 11 Android fixtures, found {len(inputs)}"
        for source in inputs:
            with zipfile.ZipFile(source) as source_aar:
                with zipfile.ZipFile(io.BytesIO(source_aar.read("classes.jar"))) as source_classes:
                    expected_classes.update(n for n in source_classes.namelist() if n.endswith(".class"))
        with zipfile.ZipFile(umbrella / "fixtures/jvm-core/build/libs/jvm-core-0.1.0-baseline.jar") as source_classes:
            expected_classes.update(n for n in source_classes.namelist() if n.endswith(".class"))
        assert expected_classes <= set(class_entries), sorted(expected_classes - set(class_entries))
        assert "META-INF/services/com.fataar.fixtures.jvmcore.MessageProvider" in class_entries
        assert "com/fataar/fixture.properties" in class_entries
        assert "res/raw/fataar_resources_values_keep.xml" in entries
    write(REPORTS / "umbrella-inventory.json", json.dumps({"aar": entries, "classesJar": class_entries}, indent=2) + "\n")
    original_hash = hashlib.sha256(aar.read_bytes()).hexdigest()
    clean = gradle("umbrella-clean-cache", umbrella, "clean", task)
    cached_tasks = ["extractReleaseDependencies", "extractReleaseMainClasses",
                    "mergeReleaseUmbrellaAarDependencies", "bundleReleaseUmbrellaAar"]
    for cached in cached_tasks:
        assert f":sdk:{cached} FROM-CACHE" in clean, f"No build-cache hit for {cached}"
    assert original_hash == hashlib.sha256(aar.read_bytes()).hexdigest(), "Non-reproducible AAR after clean"
    gradle("umbrella-rebuild-no-cache", umbrella, task, "--rerun-tasks", "--no-build-cache")
    assert original_hash == hashlib.sha256(aar.read_bytes()).hexdigest(), "AAR differs after actual rebuild"
    asset = umbrella / "fixtures/assets/src/main/assets/fixture/config.json"
    original_asset = asset.read_bytes()
    try:
        changed_asset = b'{"kind":"asset","version":2}\n'
        asset.write_bytes(changed_asset)
        gradle("umbrella-input-invalidation", umbrella, task)
        with zipfile.ZipFile(aar) as archive:
            assert archive.read("assets/fixture/config.json") == changed_asset, "Stale asset after source edit"
        assert hashlib.sha256(aar.read_bytes()).hexdigest() != original_hash
    finally:
        asset.write_bytes(original_asset)
    gradle("umbrella-restore-input", umbrella, task)
    assert original_hash == hashlib.sha256(aar.read_bytes()).hexdigest(), "AAR did not restore after input edit"
    # Known 3.0.0 defect: SDK-owned bytecode retains references to embedded namespaces' R.
    # Reproducing it is a successful experiment, NOT acceptance of the candidate.
    missing = ["com.fataar.fixtures.resourcesfiles.R$layout", "com.fataar.fixtures.resourcesfiles.R$raw",
               "com.fataar.fixtures.resourcesvalues.R$integer"]
    for mode, flags in (("metadata", []), ("pom", ["-PpomOnly=true", "--rerun-tasks"])):
        gradle("umbrella-consumer-debug-" + mode, WORK / "consumer", ":app:assembleDebug", *flags)
        failure = gradle("umbrella-consumer-release-" + mode, WORK / "consumer", ":app:assembleRelease", *flags,
                         expected="Missing class " + missing[0])
        for name in missing:
            assert "Missing class " + name in failure, name
    assert dependencies.get("org.jetbrains:annotations") == "compile", "Expected private-dependency scope leak"
    assert not aar.with_suffix(".module").exists(), "Update experiment: candidate now publishes Gradle metadata"
    # Only the single SDK coordinate is available to the independent consumer.
    assert len(list((umbrella / "repository").rglob("*.pom"))) == 1
    RESULT["umbrella"] = {"version": "3.0.0", "acceptedAsBackend": False, "configurationCache": True,
                          "verifiedSignatures": [p.name for p in artifacts], "tamperedSignatureRejected": True,
                          "pomScopes": dependencies, "gradleModuleMetadata": aar.with_suffix(".module").exists(),
                          "allInputClassDefinitionsPresent": len(expected_classes), "buildCacheTasks": cached_tasks,
                          "reproducibleAarWithoutCache": True, "assetInputInvalidation": True,
                          "consumerDebug": ["metadata-with-pom-fallback", "pom-only"],
                          "consumerRelease": "rejected in both metadata modes", "missingRClasses": missing,
                          "privateImplementationLeakedAsApi": "org.jetbrains:annotations",
                          "runtimeTests": "not run", "aarSha256": original_hash}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--reuse", action="store_true", help="Keep generated workspaces for debugging")
    parser.add_argument("--candidate", choices=("fused", "umbrella", "all"), default="all")
    args = parser.parse_args()
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "result.json").unlink(missing_ok=True)
    if not args.reuse:
        prepare()
    if not args.prepare_only:
        if args.candidate in ("fused", "all"):
            fused_probes()
        if args.candidate in ("umbrella", "all"):
            umbrella_probes()
        write(REPORTS / "result.json", json.dumps(RESULT, indent=2) + "\n")
        print("Candidate comparison complete. See result.json; runtime is a separate gate.")
