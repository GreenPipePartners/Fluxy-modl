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
