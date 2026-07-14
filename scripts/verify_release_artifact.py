#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
# SPDX-License-Identifier: MPL-2.0

from __future__ import annotations

import argparse
import hashlib
import io
import json
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


REQUIRED_FILES = {
    "LICENSE",
    "NOTICE",
    "README.md",
    "SOURCE.txt",
    "THIRD_PARTY_NOTICES.md",
    "WEBDEV_MIT_NOTICE",
    "module.xml",
}
FORBIDDEN_JAR_PREFIXES = (
    "com/inductiveautomation/",
    "com/google/gson/",
    "jakarta/servlet/",
    "javax/servlet/",
    "org/junit/",
    "org/python/",
)


def fail(message: str) -> None:
    raise ValueError(message)


def text(module: ET.Element, name: str) -> str:
    value = module.findtext(name)
    if value is None or not value.strip():
        fail(f"module.xml is missing {name}")
    return value.strip()


def verify(args: argparse.Namespace) -> str:
    artifact = args.artifact.resolve()
    expected_version = args.source_tag.removeprefix("v")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()

    with zipfile.ZipFile(artifact) as archive:
        bad_member = archive.testzip()
        if bad_member:
            fail(f"corrupt ZIP member: {bad_member}")
        names = archive.namelist()
        if len(names) != len(set(names)):
            fail("artifact contains duplicate ZIP members")
        missing = REQUIRED_FILES - set(names)
        if missing:
            fail(f"artifact is missing required files: {sorted(missing)}")

        jar_names = [name for name in names if name.endswith(".jar")]
        sbom_names = [name for name in names if name.endswith(".cdx.json")]
        if len(jar_names) != 1:
            fail(f"artifact must contain exactly one module JAR, found {jar_names}")
        if len(sbom_names) != 1:
            fail(f"artifact must contain exactly one CycloneDX SBOM, found {sbom_names}")

        module = ET.fromstring(archive.read("module.xml")).find("module")
        if module is None:
            fail("module.xml does not contain a module element")
        if text(module, "id") != "partners.greenpipe.fluxy":
            fail("unexpected module ID")
        if text(module, "name") != "Fluxy Free":
            fail("release is not identified as Fluxy Free")
        if text(module, "version") != expected_version:
            fail("module version does not match source tag")
        if text(module, "requiredIgnitionVersion") != args.ignition_version:
            fail("required Ignition version does not match the release target")
        if text(module, "freeModule").lower() != "true":
            fail("release must declare freeModule=true")
        if module.find("vendorId") is not None:
            fail("module.xml contains unsupported vendorId metadata")
        description = text(module, "description").lower()
        if "private" in description or "unlicensed" in description:
            fail("release description contains development-only wording")

        source = archive.read("SOURCE.txt").decode("utf-8")
        required_source_lines = {
            "Status: PUBLIC FREE RELEASE",
            f"Module version: {expected_version}",
            "License mode: free",
            f"Source commit: {args.source_commit}",
            f"Source tag: {args.source_tag}",
            f"Source tree: https://github.com/GreenPipePartners/Fluxy-modl/tree/{args.source_commit}",
        }
        for line in required_source_lines:
            if line not in source:
                fail(f"SOURCE.txt is missing: {line}")
        if "UNRELEASED" in source or "NOT FOR DISTRIBUTION" in source:
            fail("SOURCE.txt contains development-only identity")

        sbom = json.loads(archive.read(sbom_names[0]))
        component = sbom["metadata"]["component"]
        if component["version"] != expected_version:
            fail("SBOM version does not match module version")
        properties = {item["name"]: item["value"] for item in component.get("properties", [])}
        if properties.get("fluxy:ignitionTarget") != args.ignition_version:
            fail("SBOM Ignition target does not match")
        if properties.get("fluxy:licenseMode") != "free":
            fail("SBOM license mode is not free")

        with zipfile.ZipFile(io.BytesIO(archive.read(jar_names[0]))) as module_jar:
            jar_members = module_jar.namelist()
            forbidden = [
                name
                for name in jar_members
                if name.startswith(FORBIDDEN_JAR_PREFIXES)
            ]
            if forbidden:
                fail(f"module JAR bundles host/test classes: {forbidden[:5]}")
            required_classes = {
                "com/greenpipepartners/fluxy/gateway/FluxyGatewayHook.class",
                "com/greenpipepartners/fluxy/gateway/ProjectScanOperations.class",
                "com/greenpipepartners/fluxy/gateway/NativeHistoryStreamer.class",
            }
            missing_classes = required_classes - set(jar_members)
            if missing_classes:
                fail(f"module JAR is missing expected classes: {sorted(missing_classes)}")

    return digest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify an unsigned Fluxy public release candidate.")
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--ignition-version", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--source-tag", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        digest = verify(args)
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as exc:
        print(f"release verification failed: {exc}", file=sys.stderr)
        return 1
    print(f"release verification passed: {args.artifact} sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
