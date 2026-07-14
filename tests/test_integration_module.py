# SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
# SPDX-License-Identifier: MPL-2.0

import os
import time
from uuid import uuid4

import pytest

from fluxy import Fluxy


pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        os.getenv("FLUXY_MODULE_INTEGRATION") != "1",
        reason="set FLUXY_MODULE_INTEGRATION=1 to run against an installed Fluxy module",
    ),
]


def test_module_project_request_scan():
    fx = Fluxy(
        os.getenv("FLUXY_BASE_URL", "http://localhost:8088/data"),
        api_token=os.environ["FLUXY_API_TOKEN"],
    )
    try:
        result = fx.project.request_scan(timeout_seconds=30)
        assert result.ok is True
        assert result.message == "Project scan completed"
        assert result.duration_millis is not None
        assert result.duration_millis >= 0
    finally:
        fx.close()


def test_module_capabilities_and_gateway_inventory():
    fx = Fluxy(
        os.getenv("FLUXY_BASE_URL", "http://localhost:8088/data"),
        api_token=os.environ["FLUXY_API_TOKEN"],
    )
    try:
        capabilities = fx.util.get_capabilities()
        assert capabilities.transport == "ignition-module"
        assert capabilities.contract_version == 2
        assert capabilities.supports("tag/copy")
        assert capabilities.supports("historian/queryAggregatedPoints")
        assert capabilities.supports("project/getProjectNames")
        assert len([operation for operation in capabilities.operations if operation.available]) >= 26

        modules = fx.util.get_modules()
        assert modules
        assert any("Name" in module or "name" in module for module in modules)
        assert fx.project.get_project_names()
    finally:
        fx.close()


def test_module_expanded_tag_lifecycle():
    folder = "FluxyExpandedTags_%s" % uuid4().hex
    root = "[default]%s" % folder
    source_folder = "%s/Source" % root
    destination_folder = "%s/Destination" % root
    move_folder = "%s/MoveDestination" % root
    source_tag = "%s/CopiedString" % source_folder
    copied_tag = "%s/CopiedString" % destination_folder
    move_source = "%s/MoveString" % source_folder
    moved_tag = "%s/MoveString" % move_folder
    rename_source = "%s/RenameOriginal" % source_folder
    renamed_tag = "%s/Renamed" % source_folder
    fx = Fluxy(
        os.getenv("FLUXY_BASE_URL", "http://localhost:8088/data"),
        api_token=os.environ["FLUXY_API_TOKEN"],
        tag_provider="default",
    )

    try:
        fx.tag.configure(
            [
                {
                    "name": folder,
                    "tagType": "Folder",
                    "tags": [
                        {
                            "name": "Source",
                            "tagType": "Folder",
                            "tags": [
                                _memory_string("CopiedString", "copy-source"),
                                _memory_string("MoveString", "move-source"),
                                _memory_string("RenameOriginal", "rename-source"),
                            ],
                        },
                        {"name": "Destination", "tagType": "Folder"},
                        {"name": "MoveDestination", "tagType": "Folder"},
                    ],
                }
            ],
            base_path="[default]",
        )

        assert fx.tag.copy(source_tag, destination_folder).quality.startswith("Good")
        assert fx.tag.read_blocking(copied_tag).value == "copy-source"
        assert fx.tag.move(move_source, move_folder).quality.startswith("Good")
        assert fx.tag.read_blocking(moved_tag).value == "move-source"
        assert fx.tag.rename(rename_source, "Renamed").quality.startswith("Good")
        assert fx.tag.read_blocking(renamed_tag).value == "rename-source"

        query = {
            "condition": {"path": folder + "/*", "tagType": "AtomicTag"},
            "returnProperties": ["path", "tagType", "valueSource"],
        }
        results = fx.tag.query("default", query=query, limit=25)
        assert any("CopiedString" in str(result) for result in results)

        exported = fx.tag.export_tags(source_folder)
        exported.tags["name"] = "Imported"
        imported = fx.tag.import_tags(exported.tags, base_path=root)
        assert imported and all(result.quality.startswith("Good") for result in imported)
        assert fx.tag.read_blocking("%s/Imported/CopiedString" % root).value == "copy-source"
    finally:
        try:
            fx.tag.delete_tags(root)
        finally:
            fx.close()


def test_module_memory_tag_and_history_closed_loop():
    base_url = os.getenv("FLUXY_BASE_URL", "http://localhost:8088/data")
    api_token = os.environ["FLUXY_API_TOKEN"]
    folder = "FluxyModuleIntegration_%s" % uuid4().hex
    run_id = "fluxy-module-test-%s" % uuid4().hex
    audit_profile = os.getenv("FLUXY_AUDIT_PROFILE", "Fluxy Audit")
    root = "[default]%s" % folder
    tag_path = "%s/Value" % root
    fx = Fluxy(
        base_url,
        api_token=api_token,
        tag_provider="default",
        run_id=run_id,
        script_name="test_integration_module.py",
    )

    try:
        configured = fx.tag.configure(
            [
                {
                    "name": folder,
                    "tagType": "Folder",
                    "tags": [
                        {
                            "name": "Value",
                            "tagType": "AtomicTag",
                            "valueSource": "memory",
                            "dataType": "Float8",
                            "value": 0.0,
                            "historyEnabled": True,
                            "historyProvider": "Core Historian",
                            "historySampleMode": "OnChange",
                            "historicalDeadband": 0.0,
                        }
                    ],
                }
            ],
            base_path="[default]",
        )
        assert configured[0].quality.startswith("Good")

        config = fx.tag.get_configuration(tag_path)[0]
        assert config["historyEnabled"] is True
        assert config["historyProvider"] == "Core Historian"

        time.sleep(2)
        start_time = int(time.time() * 1000)
        assert fx.tag.write_blocking(tag_path, 11.0).quality.startswith("Good")
        time.sleep(1)
        assert fx.tag.write_blocking(tag_path, 22.0).quality.startswith("Good")
        assert fx.tag.read_blocking(tag_path).value == 22.0
        assert [item.name for item in fx.tag.browse(root)] == ["Value"]

        audit_rows = _eventually_query_audit(fx, audit_profile, tag_path, run_id, start_time)
        assert any("Fluxy.tag/writeBlocking" in str(row) for row in audit_rows)

        history_path = _eventually_find_history_path(fx, folder)
        rows = _eventually_query_history(fx, history_path, start_time, {11.0, 22.0})
        values = [_history_value(row) for row in rows]
        assert 11.0 in values
        assert 22.0 in values
    finally:
        try:
            fx.tag.delete_tags(root)
        finally:
            fx.close()


def test_module_advanced_historian_contract():
    base_url = os.getenv("FLUXY_BASE_URL", "http://localhost:8088/data")
    api_token = os.environ["FLUXY_API_TOKEN"]
    history_path = (
        "histprov:Core Historian:/sys:gateway:/prov:default:/tag:FluxyModuleExpanded/%s"
        % uuid4().hex
    )
    marker = "fluxy-module-annotation-%s" % uuid4().hex
    metadata_marker = "fluxy-module-metadata-%s" % uuid4().hex
    timestamp = int(time.time() * 1000) - 60_000
    fx = Fluxy(base_url, api_token=api_token)

    try:
        qualities = fx.historian.store_data_points(
            [history_path, history_path, history_path],
            [10.0, 20.0, 30.0],
            timestamps=[timestamp, timestamp + 1_000, timestamp + 2_000],
            qualities=[192, 192, 192],
        )
        assert all(quality.startswith("Good") for quality in qualities)

        aggregated = fx.historian.query_aggregated_points(
            [history_path],
            timestamp - 1_000,
            timestamp + 3_000,
            aggregates=["Maximum"],
            column_names=["Maximum"],
        )
        assert any(
            30.0 == float(value)
            for row in aggregated
            for value in row.values()
            if isinstance(value, (int, float))
        )

        annotation_qualities = fx.historian.store_annotations(
            [history_path],
            [timestamp],
            end_times=[timestamp + 2_000],
            types=["note"],
            data=[marker],
        )
        assert all(quality.startswith("Good") for quality in annotation_qualities)
        annotation = _eventually_query_annotation(
            fx, history_path, timestamp - 1_000, timestamp + 3_000, marker
        )
        deleted = fx.historian.delete_annotations([history_path], [annotation.storage_id])
        assert all(quality.startswith("Good") for quality in deleted)

        if fx.util.get_capabilities().supports("historian/storeMetadata"):
            metadata_qualities = fx.historian.store_metadata(
                [history_path],
                [timestamp],
                {"documentation": metadata_marker},
            )
            assert all(quality.startswith("Good") for quality in metadata_qualities)
            metadata = _eventually_query_metadata(
                fx, history_path, timestamp - 1_000, timestamp + 3_000, metadata_marker
            )
            assert metadata.properties is not None
            assert metadata.properties.get("documentation") == metadata_marker
    finally:
        fx.close()


def _memory_string(name: str, value: str) -> dict[str, object]:
    return {
        "name": name,
        "tagType": "AtomicTag",
        "valueSource": "memory",
        "dataType": "String",
        "value": value,
    }


def _eventually_query_annotation(fx, path, start_time, end_time, marker):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        annotations = fx.historian.query_annotations([path], start_time, end_date=end_time)
        for annotation in annotations:
            if annotation.data == marker:
                return annotation
        time.sleep(1)
    pytest.fail("Historian did not return annotation %s" % marker)


def _eventually_query_metadata(fx, path, start_time, end_time, marker):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        metadata_rows = fx.historian.query_metadata([path], start_date=start_time, end_date=end_time)
        for metadata in metadata_rows:
            if metadata.properties and metadata.properties.get("documentation") == marker:
                return metadata
        time.sleep(1)
    pytest.fail("Historian did not return metadata %s" % marker)


def _eventually_find_history_path(fx: Fluxy, folder: str) -> str:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        for system_node in fx.historian.browse("histprov:Core Historian:/"):
            for provider_node in fx.historian.browse(system_node.path):
                for tag_node in fx.historian.browse(provider_node.path):
                    if tag_node.path.endswith("/tag:%s" % folder):
                        children = fx.historian.browse(tag_node.path)
                        if children:
                            return children[0].path
        time.sleep(1)
    pytest.fail("Historian did not expose a path for %s" % folder)


def _eventually_query_history(
    fx: Fluxy,
    history_path: str,
    start_time: int,
    expected_values: set[float],
):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        fallback_rows = fx.historian.query_raw_points(
            [history_path],
            start_time - 1_000,
            int(time.time() * 1000) + 1_000,
        )
        blocks = list(
            fx.historian.stream_raw_points(
                [history_path],
                start_time - 1_000,
                int(time.time() * 1000) + 1_000,
                block_rows=1,
            )
        )
        rows = [row for block in blocks for row in block.mappings()]
        actual_values = {_history_value(row) for row in rows}
        if expected_values <= actual_values:
            fallback_values = {float(row["value"]) for row in fallback_rows}
            assert expected_values <= fallback_values
            return rows
        time.sleep(1)
    pytest.fail("Historian did not return two samples for %s" % history_path)


def _history_value(row) -> float:
    for key in ["value", "value_0"]:
        if key in row:
            return float(row[key])
    pytest.fail("Native history row has no value column: %r" % row)


def _eventually_query_audit(
    fx: Fluxy,
    profile: str,
    tag_path: str,
    run_id: str,
    start_time: int,
):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        rows = fx.util.query_audit_log(
            profile,
            start_date=start_time - 1_000,
            end_date=int(time.time() * 1000) + 1_000,
            action_filter="Fluxy.tag/writeBlocking",
            target_filter=tag_path,
        )
        matching = [row for row in rows if run_id in str(row)]
        if matching:
            return matching
        time.sleep(1)
    pytest.fail("Audit profile %s did not record run %s" % (profile, run_id))
