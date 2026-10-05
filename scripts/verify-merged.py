#!/usr/bin/env python3
"""Verify the merged SDK, signed publication and consumers without internal coordinates."""
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

from dex_inventory import verify_apk_classes
from gradle_environment import pgp_jars
from manifest_inventory import verify_aar_manifest
from generated_inventory import verify_generated_payload

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "build/reports/merged-validation"
ENV = os.environ.copy()
if not ENV.get("ANDROID_HOME") and not ENV.get("ANDROID_SDK_ROOT"):
    for line in (ROOT / "local.properties").read_text().splitlines():
        if line.startswith("sdk.dir="):
            ENV["ANDROID_HOME"] = line.split("=", 1)[1].replace("\\:", ":").replace("\\ ", " ")
PASSED = []


def run(name, arguments, expected=None):
    log = REPORTS / (name + ".log")
    print(f"{name}: {log}", flush=True)
    with log.open("w") as output:
        result = subprocess.run([str(a) for a in arguments], cwd=ROOT, env=ENV, stdout=output, stderr=subprocess.STDOUT)
    text = log.read_text()
    if (expected is None and result.returncode) or (expected is not None and (result.returncode == 0 or expected not in text)):
        print("\n".join(text.splitlines()[-100:]), file=sys.stderr)
        raise AssertionError(f"Unexpected outcome: {name}")
    PASSED.append(name)
    return text


def gradle(name, *args, expected=None):
    return run(name, [ROOT / "gradlew", *args, "--console=plain"], expected)


def main():
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "result.json").unlink(missing_ok=True)
    run("dex-inventory-tests", [sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"])
    keys = ROOT / "build/merged-test-signing"
    keys.mkdir(parents=True, exist_ok=True)
    home = Path(ENV.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    jars = pgp_jars(home)
    if len(jars) != 3:
        gradle("install-wrapper", "--version")
        jars = pgp_jars(home)
    assert len(jars) == 3
    java = Path(ENV["JAVA_HOME"]) / "bin/java" if ENV.get("JAVA_HOME") else "java"
    pgp = [java, "--class-path", os.pathsep.join(map(str, jars)), ROOT / "integration-tests/candidates/PgpFixture.java"]
    run("test-key", [*pgp, "generate", keys])
    signing = "-PfatAar.signingKeyFile=" + str(keys / "test-secret.asc")
    gradle("playground", "verifyPlayground", ":app:assembleDebugAndroidTest")
    repeated = gradle("configuration-cache", "verifyPlayground", ":app:assembleDebugAndroidTest")
    assert "Reusing configuration cache." in repeated
    verify_apk_classes(ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                       ROOT / "sdk/build/outputs/aar/sdk-debug.aar")
    PASSED.append("project-consumer-dex-definitions")
    dependencies = gradle("demo-runtime-graph", ":app:dependencies", "--configuration", "releaseRuntimeClasspath")
    assert "project :sdk" in dependencies.replace("'", "") and ":fixtures:" not in dependencies
    repository = ROOT / "build/merged-repository"
    if repository.exists():
        shutil.rmtree(repository)
    publish = ":sdk:publishMergedPublicationToMergedRepository"
    gradle("publish-signed", publish, signing)
    repeated = gradle("publish-configuration-cache", publish, signing)
    assert "Reusing configuration cache." in repeated
    coordinate = repository / "com/fataar/playground/sdk/0.2.0-merged"
    aar = coordinate / "sdk-0.2.0-merged.aar"
    pom = aar.with_suffix(".pom")
    module = aar.with_suffix(".module")
    assert len(list(repository.rglob("*.pom"))) == 1
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    scopes = {d.findtext("m:groupId", namespaces=ns) + ":" + d.findtext("m:artifactId", namespaces=ns):
              d.findtext("m:scope", namespaces=ns) for d in ET.parse(pom).findall("m:dependencies/m:dependency", ns)}
    assert scopes["androidx.constraintlayout:constraintlayout"] == "compile"
    assert scopes["androidx.constraintlayout:constraintlayout-core"] == "compile"
    assert scopes["org.jetbrains:annotations"] == "runtime"
    for dependency in ("androidx.collection:collection", "androidx.databinding:databinding-runtime", "androidx.databinding:databinding-adapters"):
        assert scopes[dependency] == "compile", (dependency, scopes)
    assert not any(d.startswith("com.fataar.playground:") for d in scopes)
    metadata = json.loads(module.read_text())
    usages = set()
    for variant in metadata["variants"]:
        usage = variant["attributes"]["org.gradle.usage"]
        usages.add(usage)
        assert "com.android.build.api.attributes.BuildTypeAttr" not in variant["attributes"]
        deps = {d["group"] + ":" + d["module"] for d in variant.get("dependencies", [])}
        assert not any(d.startswith("com.fataar.playground:") for d in deps)
        if variant["attributes"].get("org.gradle.docstype") == "sources":
            assert not deps
            assert variant["files"][0]["name"].endswith("-sources.jar")
            continue
        assert "androidx.constraintlayout:constraintlayout" in deps
        assert "androidx.constraintlayout:constraintlayout-core" in deps
        assert ("org.jetbrains:annotations" in deps) == (usage == "java-runtime")
    assert usages == {"java-api", "java-runtime"}
    sources = coordinate / "sdk-0.2.0-merged-sources.jar"
    assert sources.is_file()
    run("signatures", [*pgp, "verify", keys, aar, pom, module, sources])
    tampered = keys / "tampered.aar"
    tampered.write_bytes(aar.read_bytes() + b"tampered")
    shutil.copyfile(Path(str(aar) + ".asc"), Path(str(tampered) + ".asc"))
    run("signature-negative", [*pgp, "verify", keys, tampered], expected="Invalid signature")
    original_hash = hashlib.sha256(aar.read_bytes()).hexdigest()
    resource_count = 0
    with zipfile.ZipFile(aar) as archive:
        entries = archive.namelist()
        assert "assets/fixture/config.json" in entries
        assert "res/raw/fataar_resources_values_keep.xml" in entries
        assert "ReflectionProbe" in archive.read("proguard.txt").decode() or "HiddenGreeting" in archive.read("proguard.txt").decode()
        assert "FixtureProvider" in archive.read("AndroidManifest.xml").decode()
        with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as jar:
            classes = [n for n in jar.namelist() if n.endswith(".class")]
            assert len(classes) == len(set(classes))
            assert "META-INF/services/com.fataar.fixtures.jvmcore.MessageProvider" in jar.namelist()
            expected_classes = set()
            for path in [ROOT / "sdk/build/intermediates/aar/release/bundleReleaseAar/sdk-release.aar",
                         *(ROOT / "fixtures").glob("*/build/outputs/aar/*-release.aar")]:
                with zipfile.ZipFile(path) as original:
                    for name in original.namelist():
                        if name.endswith("/") or not name.startswith(("res/", "assets/")):
                            continue
                        if name.startswith("res/values") and name.endswith(".xml"):
                            folder = name.rsplit("/", 1)[0] + "/"
                            assert any(n.startswith(folder) and archive.read(n) == original.read(name)
                                       for n in entries if not n.endswith("/")), f"Missing values payload: {path}: {name}"
                        else:
                            assert name in entries and archive.read(name) == original.read(name), f"Changed or missing payload: {path}: {name}"
                        resource_count += 1
                    if "classes.jar" in original.namelist():
                        with zipfile.ZipFile(io.BytesIO(original.read("classes.jar"))) as original_jar:
                            expected_classes.update(n for n in original_jar.namelist() if n.endswith(".class"))
            with zipfile.ZipFile(ROOT / "fixtures/jvm-core/build/libs/jvm-core-0.1.0-baseline.jar") as original_jar:
                expected_classes.update(n for n in original_jar.namelist() if n.endswith(".class"))
            assert expected_classes <= set(classes), expected_classes - set(classes)
    PASSED.append("publication-scopes-and-payload")
    manifest_checks = verify_aar_manifest(aar)
    PASSED.append("merged-manifest-contract")
    generated_checks = verify_generated_payload(ROOT, aar)
    PASSED.append("generated-payload-and-compile-resource-contract")
    cached = gradle("build-cache", ":sdk:clean", ":sdk:inspectReleaseAar")
    assert ":sdk:mergeReleaseFatAar FROM-CACHE" in cached
    gradle("rebuild-no-cache", ":sdk:inspectReleaseAar", "--rerun-tasks", "--no-build-cache")
    assert hashlib.sha256((ROOT / "sdk/build/outputs/aar/sdk-release.aar").read_bytes()).hexdigest() == original_hash
    consumer = ["-p", "integration-tests/consumer-maven", ":app:assembleDebug", ":app:assembleRelease"]
    gradle("consumer-metadata", *consumer, "--refresh-dependencies")
    verify_apk_classes(ROOT / "integration-tests/consumer-maven/app/build/outputs/apk/debug/app-debug.apk", aar)
    PASSED.append("maven-metadata-dex-definitions")
    gradle("consumer-pom", *consumer, "-PpomOnly=true", "--rerun-tasks")
    verify_apk_classes(ROOT / "integration-tests/consumer-maven/app/build/outputs/apk/debug/app-debug.apk", aar)
    PASSED.append("maven-pom-dex-definitions")
    gradle("negative-api", ":sdk:inspectReleaseAar", "-I", "integration-tests/scenarios/hide-sdk-api.init.gradle.kts",
           expected="FATAAR_API_DEPENDENCY: com/fataar/sdk/SdkPanel")
    gradle("restore-api", ":sdk:inspectReleaseAar")
    for scenario, code in (("aidl-conflict", "FATAAR_PAYLOAD_CONFLICT"),
                           ("invalid-binding-metadata", "FATAAR_DATABINDING_METADATA"),
                           ("missing-binding-metadata", "FATAAR_DATABINDING_METADATA"),
                           ("missing-binding-class", "FATAAR_DATABINDING_CLASS")):
        output = gradle("negative-" + scenario, ":sdk:inspectReleaseAar", "-I",
                        "integration-tests/scenarios/generated-payload.init.gradle",
                        "-PfatAar.payloadScenario=" + scenario, expected=code)
        assert "project::fixtures:" in output
        gradle("restore-" + scenario, ":sdk:inspectReleaseAar")
        assert hashlib.sha256((ROOT / "sdk/build/outputs/aar/sdk-release.aar").read_bytes()).hexdigest() == original_hash
    conflict = ROOT / "build/resource-conflict-fixture/values"
    conflict.mkdir(parents=True, exist_ok=True)
    (conflict / "collision.xml").write_text('<resources><string name="fixture_title">collision</string></resources>')
    conflict_output = gradle("negative-resource-conflict", ":sdk:inspectReleaseAar", "-I",
                            "integration-tests/scenarios/resource-conflict.init.gradle", expected="FATAAR_RESOURCE_CONFLICT")
    assert "project::fixtures:assets" in conflict_output and "project::fixtures:resources-values" in conflict_output
    gradle("restore-resources", ":sdk:inspectReleaseAar")
    manifests = ROOT / "build/manifest-fixtures"
    manifests.mkdir(parents=True, exist_ok=True)
    (manifests / "attribute.xml").write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
        '<meta-data android:name="com.fataar.fixture" android:value="conflicting-value"/></application></manifest>')
    (manifests / "authority.xml").write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
        '<provider android:name="com.fataar.fixtures.manifestcomponents.SecondaryProvider" '
        'android:authorities="${applicationId}.other;${applicationId}.fixture" android:exported="false"/></application></manifest>')
    for scenario, code in (("attribute", "FATAAR_MANIFEST_CONFLICT"), ("authority", "FATAAR_DUPLICATE_PROVIDER_AUTHORITY")):
        output = gradle("negative-manifest-" + scenario, ":sdk:inspectReleaseAar", "-I",
                        "integration-tests/scenarios/manifest-conflict.init.gradle",
                        "-PfatAar.manifestScenario=" + scenario, expected=code)
        assert "project::fixtures:assets" in output and "project::fixtures:manifest-components" in output
        gradle("restore-manifest-" + scenario, ":sdk:inspectReleaseAar")
    output = gradle("negative-min-sdk", ":sdk:inspectReleaseAar", "-I",
                    "integration-tests/scenarios/min-sdk-conflict.init.gradle", expected="FATAAR_MIN_SDK_CONFLICT")
    assert "project::fixtures:manifest-components" in output and "requires 25" in output
    gradle("restore-min-sdk", ":sdk:inspectReleaseAar")
    for version in ("1", "2"):
        directory = ROOT / "build/version-fixture-repository/com/fataar/versionfixture/payload" / version
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"payload-{version}.pom").write_text(
            '<project><modelVersion>4.0.0</modelVersion><groupId>com.fataar.versionfixture</groupId>'
            f'<artifactId>payload</artifactId><version>{version}</version></project>')
        with zipfile.ZipFile(directory / f"payload-{version}.jar", "w") as fixture:
            fixture.writestr("version-fixture.txt", version)
    gradle("embedded-version-alignment", ":sdk:inspectReleaseAar", "-I",
           "integration-tests/scenarios/embedded-version.init.gradle.kts")
    with zipfile.ZipFile(ROOT / "sdk/build/outputs/aar/sdk-release.aar") as aligned:
        with zipfile.ZipFile(io.BytesIO(aligned.read("classes.jar"))) as jar:
            assert jar.read("version-fixture.txt") == b"2"
    gradle("restore-version-fixture", ":sdk:inspectReleaseAar")
    assert hashlib.sha256((ROOT / "sdk/build/outputs/aar/sdk-release.aar").read_bytes()).hexdigest() == original_hash
    result = {"mergedAar": True, "passed": PASSED, "pomScopes": scopes, "classes": len(classes), "resourcePayloads": resource_count, "manifest": manifest_checks, "generated": generated_checks,
              "sha256": original_hash, "runtimeTests": "run scripts/verify-device.py separately"}
    (REPORTS / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print("Merged AAR, signed POM/module, both consumers and cache checks passed.", flush=True)


if __name__ == "__main__":
    main()
