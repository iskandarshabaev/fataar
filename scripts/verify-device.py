#!/usr/bin/env python3
"""Run both Android variants and reject successful Gradle invocations with zero discovered tests."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--serial", help="Restrict Gradle connected tests to this Android device")
parser.add_argument("--consumer", choices=("project", "maven", "maven-pom"), default="project")
args = parser.parse_args()
project = ROOT if args.consumer == "project" else ROOT / "integration-tests/consumer-maven"
env = os.environ.copy()
if args.serial:
    env["ANDROID_SERIAL"] = args.serial
if not env.get("ANDROID_HOME") and not env.get("ANDROID_SDK_ROOT"):
    for line in (ROOT / "local.properties").read_text().splitlines():
        if line.startswith("sdk.dir="):
            env["ANDROID_HOME"] = line.split("=", 1)[1].replace("\\:", ":").replace("\\ ", " ")
reports = ROOT / "build/reports" / ("playground-device-validation" if args.consumer == "project" else args.consumer + "-device-validation")
reports.mkdir(parents=True, exist_ok=True)
result_file = reports / "result.json"
result_file.unlink(missing_ok=True)
expected = {"allFixturePayloadsAreReachableThroughSdk", "sdkViewInflatesItsCustomXmlView",
            "resourceQualifiersAndBinaryPayloadsSurviveMergeAndShrinking",
            "mergedManifestComponentsAndPlaceholdersWorkAtRuntime",
            "generatedBindingsAndObservableUpdatesWorkThroughSdk",
            "consumerGeneratesBindingsIncludesAdaptersAndAidlImportsFromSdk",
            "aidlParcelableRoundTripUsesSeparateProcess"}
results = {}
for variant in ("debug", "release"):
    task = ":app:connected" + variant.capitalize() + "AndroidTest"
    command = [str(ROOT / "gradlew"), "-p", str(project), task, "--console=plain", "--rerun"]
    if args.consumer == "maven-pom":
        command.append("-PpomOnly=true")
    if variant == "release":
        command.append("-PtestBuildType=release")
    result_directory = project / "app/build/outputs/androidTest-results/connected" / variant
    for previous in result_directory.glob("TEST-*.xml"):
        previous.unlink()
    log = reports / (variant + ".log")
    print(f"{variant}: {log}", flush=True)
    with log.open("w") as output:
        completed = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    if completed.returncode:
        raise RuntimeError(f"{variant} Gradle run failed: {log}")
    xml_files = sorted((project / "app/build/outputs/androidTest-results/connected" / variant).glob("TEST-*.xml"))
    if not xml_files:
        raise AssertionError(f"{variant}: no device test reports")
    counts = []
    for xml_file in xml_files:
        tree = ET.parse(xml_file)
        cases = tree.findall(".//testcase")
        actual = {case.get("name") for case in cases}
        if not expected.issubset(actual):
            raise AssertionError(f"{variant}: smoke tests were not discovered: {expected - actual}; {xml_file}")
        if tree.findall(".//failure") or tree.findall(".//error") or tree.findall(".//skipped"):
            raise AssertionError(f"{variant}: failed or skipped tests: {xml_file}")
        counts.append({"report": xml_file.name, "tests": len(cases)})
    results[variant] = counts
result_file.write_text(json.dumps(results, indent=2) + "\n")
print("All smoke and resource tests actually ran and passed in debug and R8 release.", flush=True)
