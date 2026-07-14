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
EXPECTED_OPERATIONS = {
    "util/getVersion",
    "util/getCapabilities",
    "util/getModules",
    "util/queryAuditLog",
    "tag/readBlocking",
    "tag/browse",
    "tag/getConfiguration",
    "tag/exportTags",
    "tag/queryTags",
    "tag/configure",
    "tag/writeBlocking",
    "tag/deleteTags",
    "tag/copy",
    "tag/move",
    "tag/rename",
    "tag/importTags",
    "historian/browse",
    "historian/queryRawPoints",
    "historian/queryRawPointsStream",
    "historian/queryAggregatedPoints",
    "historian/queryAnnotations",
    "historian/queryMetadata",
    "historian/storeDataPoints",
    "historian/storeAnnotations",
    "historian/deleteAnnotations",
    "historian/storeMetadata",
    "project/requestScan",
    "project/getProjectNames",
}
EXPECTED_WRITE_OPERATIONS = {
    "tag/configure",
    "tag/writeBlocking",
    "tag/deleteTags",
    "tag/copy",
    "tag/move",
    "tag/rename",
    "tag/importTags",
    "historian/storeDataPoints",
    "historian/storeAnnotations",
    "historian/deleteAnnotations",
    "historian/storeMetadata",
}
EXPECTED_NATIVE_HANDLERS = {
    "util/getCapabilities": "capabilities",
    "historian/queryRawPointsStream": "native-history-stream",
    "project/requestScan": "project-scan",
}
IGNITION_83_ONLY_OPERATIONS = {
    "historian/queryMetadata",
    "historian/storeMetadata",
}


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
                "partners/greenpipe/fluxy/gateway/FluxyGatewayHook.class",
                "partners/greenpipe/fluxy/gateway/FluxyRouteManifest.class",
                "partners/greenpipe/fluxy/gateway/ProjectScanOperations.class",
                "partners/greenpipe/fluxy/gateway/NativeHistoryStreamer.class",
            }
            missing_classes = required_classes - set(jar_members)
            if missing_classes:
                fail(f"module JAR is missing expected classes: {sorted(missing_classes)}")
            if "fluxy-routes.json" not in jar_members:
                fail("module JAR is missing fluxy-routes.json")
            route_manifest = json.loads(module_jar.read("fluxy-routes.json"))
            if route_manifest.get("schemaVersion") != 1:
                fail("unexpected Fluxy route schema version")
            if route_manifest.get("contractVersion") != 2:
                fail("unexpected Fluxy contract version")
            routes = route_manifest.get("routes")
            if not isinstance(routes, list):
                fail("Fluxy route manifest is missing routes")
            operations = [route.get("operation") for route in routes]
            if len(operations) != len(set(operations)):
                fail("Fluxy route manifest contains duplicate operations")
            if set(operations) != EXPECTED_OPERATIONS:
                fail("Fluxy route manifest does not match the expected public contract")
            for route in routes:
                operation = route["operation"]
                expected_access = "write" if operation in EXPECTED_WRITE_OPERATIONS else "read"
                if route.get("access") != expected_access:
                    fail(f"unexpected access for {operation}")
                expected_handler = EXPECTED_NATIVE_HANDLERS.get(operation, "dispatch")
                if route.get("handler") != expected_handler:
                    fail(f"unexpected handler for {operation}")
                expected_versions = (
                    {"8.3"} if operation in IGNITION_83_ONLY_OPERATIONS else {"8.1", "8.3"}
                )
                if set(route.get("versions", [])) != expected_versions:
                    fail(f"unexpected versions for {operation}")
            target_family = "8.1" if args.ignition_version.startswith("8.1") else "8.3"
            available = [route for route in routes if target_family in route.get("versions", [])]
            expected_available = 26 if target_family == "8.1" else 28
            if len(available) != expected_available:
                fail(
                    f"Fluxy route manifest exposes {len(available)} routes for {target_family}; "
                    f"expected {expected_available}"
                )

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
