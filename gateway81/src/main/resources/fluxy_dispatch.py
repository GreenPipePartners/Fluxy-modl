# SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
# SPDX-License-Identifier: MPL-2.0
# Ignition 8.1 implementation of the version-neutral Fluxy wire contract.

class BadRequest(Exception):
    pass


def _dataset_to_wire(dataset):
    rows = []
    column_names = list(dataset.getColumnNames())
    for row_index in range(dataset.getRowCount()):
        row = []
        for column_name in column_names:
            value = dataset.getValueAt(row_index, column_name)
            if hasattr(value, "getTime"):
                value = value.getTime()
            elif value is not None and not isinstance(value, (basestring, int, long, float, bool)):
                value = str(value)
            row.append(value)
        rows.append(row)
    return {"columns": column_names, "rows": rows}


def _value_to_wire(value):
    if value is None or isinstance(value, (basestring, int, long, float, bool)):
        return value
    if hasattr(value, "getTime"):
        return value.getTime()
    try:
        return _value_to_wire(value.toDict())
    except Exception:
        pass
    if isinstance(value, dict):
        return dict((str(key), _value_to_wire(item)) for key, item in value.items())
    if isinstance(value, (list, tuple)):
        return [_value_to_wire(item) for item in value]
    try:
        return system.util.jsonDecode(system.util.jsonEncode(value))
    except Exception:
        return str(value)


def _required_list(payload, key):
    value = payload.get(key)
    if not isinstance(value, list):
        raise BadRequest("Request must include %s list" % key)
    return value


def _util_get_version(payload):
    version = system.util.getVersion()
    return {
        "ok": True,
        "version": str(version),
        "major": int(version.major),
        "minor": int(version.minor),
        "transport": "ignition-module",
        "contractVersion": 1,
    }


def _util_query_audit_log(payload):
    profile = payload.get("auditProfileName")
    if not isinstance(profile, basestring):
        raise BadRequest("Request must include auditProfileName string")
    start_date = payload.get("startDate")
    end_date = payload.get("endDate")
    dataset = system.util.queryAuditLog(
        profile,
        system.date.fromMillis(long(start_date)) if start_date is not None else None,
        system.date.fromMillis(long(end_date)) if end_date is not None else None,
        payload.get("actorFilter"),
        payload.get("actionFilter"),
        payload.get("targetFilter"),
        payload.get("valueFilter"),
        payload.get("systemFilter"),
        payload.get("contextFilter"),
    )
    return {"ok": True, "result": _dataset_to_wire(dataset), "resultSource": "ignition.dataset"}


def _tag_read_blocking(payload):
    tag_paths = _required_list(payload, "tagPaths")
    timeout_ms = int(payload.get("timeoutMs") or 45000)
    qualified_values = system.tag.readBlocking(tag_paths, timeout_ms)
    values = []
    for index, qualified_value in enumerate(qualified_values):
        values.append({
            "tagPath": tag_paths[index],
            "value": _value_to_wire(qualified_value.value),
            "quality": str(qualified_value.quality),
            "timestamp": system.db.dateFormat(
                qualified_value.timestamp,
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            ) if qualified_value.timestamp is not None else None,
        })
    return {"ok": True, "values": values}


def _tag_write_blocking(payload):
    tag_paths = _required_list(payload, "tagPaths")
    values = _required_list(payload, "values")
    if len(tag_paths) != len(values):
        raise BadRequest("tagPaths and values must have the same length")
    quality_codes = system.tag.writeBlocking(
        tag_paths,
        values,
        int(payload.get("timeoutMs") or 45000),
    )
    return {
        "ok": True,
        "qualities": [
            {"tagPath": tag_paths[index], "quality": str(quality)}
            for index, quality in enumerate(quality_codes)
        ],
    }


def _tag_configure(payload):
    base_path = payload.get("basePath")
    if not isinstance(base_path, basestring):
        raise BadRequest("Request must include basePath string")
    tags = _required_list(payload, "tags")
    quality_codes = system.tag.configure(base_path, tags, payload.get("collisionPolicy") or "o")
    qualities = []
    for index, quality in enumerate(quality_codes):
        name = tags[index].get("name") if index < len(tags) and isinstance(tags[index], dict) else None
        qualities.append({"name": name, "quality": str(quality)})
    return {"ok": True, "qualities": qualities}


def _tag_delete(payload):
    tag_paths = _required_list(payload, "tagPaths")
    quality_codes = system.tag.deleteTags(tag_paths)
    return {
        "ok": True,
        "qualities": [
            {"tagPath": tag_paths[index], "quality": str(quality)}
            for index, quality in enumerate(quality_codes)
        ],
    }


def _browse_result_to_wire(result):
    item = {}
    for key in ["name", "fullPath", "tagType", "dataType", "hasChildren"]:
        value = None
        try:
            value = result[key]
        except Exception:
            try:
                value = getattr(result, key)
            except Exception:
                pass
        if value is not None:
            item[key] = bool(value) if key == "hasChildren" else str(value)
    return item


def _tag_browse(payload):
    path = payload.get("path")
    if not isinstance(path, basestring):
        raise BadRequest("Request must include path string")
    browse_filter = payload.get("filter") or {}
    if not isinstance(browse_filter, dict):
        raise BadRequest("filter must be an object")
    results = system.tag.browse(path, browse_filter).getResults()
    return {"ok": True, "results": [_browse_result_to_wire(result) for result in results]}


def _config_value(config, key):
    try:
        value = config[key]
    except Exception:
        try:
            value = getattr(config, key)
        except Exception:
            return None
    if value is None:
        return None
    if key in ["tagType", "dataType", "valueSource"]:
        text = str(value)
        return text[1:-1] if len(text) >= 2 and text[0] == '"' and text[-1] == '"' else text
    return _value_to_wire(value)


def _config_to_wire(config):
    item = {}
    for key in [
        "name", "tagType", "valueSource", "dataType", "value", "historyEnabled",
        "historyProvider", "historySampleMode", "historySampleRate",
        "historySampleRateUnits", "historicalDeadband", "historicalDeadbandMode",
    ]:
        value = _config_value(config, key)
        if value is not None:
            item[key] = value
    children = _config_value(config, "tags")
    if children is not None:
        item["tags"] = [_config_to_wire(child) for child in children]
    return item


def _tag_get_configuration(payload):
    path = payload.get("path")
    paths = payload.get("paths")
    recursive = bool(payload.get("recursive", False))
    if paths is not None:
        if not isinstance(paths, list):
            raise BadRequest("paths must be a list")
        configs = []
        for current_path in paths:
            if not isinstance(current_path, basestring):
                raise BadRequest("paths must contain path strings")
            configs.extend([_config_to_wire(config) for config in system.tag.getConfiguration(current_path, recursive)])
    else:
        if not isinstance(path, basestring):
            raise BadRequest("Request must include path string")
        configs = [_config_to_wire(config) for config in system.tag.getConfiguration(path, recursive)]
    return {"ok": True, "configs": configs}


def _historian_browse_result_to_wire(result):
    row = {
        "path": str(result.getPath()),
        "displayPath": None,
        "hasChildren": bool(result.hasChildren()),
        "type": None,
        "metadata": None,
    }
    if result.getDisplayPath() is not None:
        row["displayPath"] = str(result.getDisplayPath())
    if result.getType() is not None:
        row["type"] = str(result.getType())
    if result.getMetadata() is not None:
        row["metadata"] = str(result.getMetadata())
    return row


def _historian_browse(payload):
    path = payload.get("path")
    if not isinstance(path, basestring):
        raise BadRequest("Request must include path string")
    continuation = payload.get("continuationPoint")
    results = system.tag.browseHistoricalTags(path, None, None, continuation) if continuation else system.tag.browseHistoricalTags(path)
    continuation_point = results.getContinuationPoint()
    return {
        "ok": True,
        "results": [_historian_browse_result_to_wire(result) for result in results.getResults()],
        "continuationPoint": str(continuation_point) if continuation_point is not None else None,
        "quality": str(results.getResultQuality()),
    }


def _historical_tag_parts(path):
    if not isinstance(path, basestring) or not path.startswith("histprov:"):
        raise BadRequest("Historical path must start with histprov:")
    provider_end = path.find(":/")
    tag_index = path.find(":/tag:")
    if provider_end < 0 or tag_index < 0:
        raise BadRequest("Historical path is missing provider or tag sections")
    history_provider = path[len("histprov:"):provider_end]
    tag_path = path[tag_index + len(":/tag:"):]
    provider_index = path.find(":/prov:")
    if provider_index >= 0 and provider_index < tag_index:
        return history_provider, path[provider_index + len(":/prov:"):tag_index], tag_path
    driver_index = path.find(":/drv:")
    if driver_index >= 0 and driver_index < tag_index:
        driver = path[driver_index + len(":/drv:"):tag_index]
        if ":" in driver:
            return history_provider, driver.split(":", 1)[1], tag_path
    raise BadRequest("Historical path is missing a tag provider section")


def _historian_store_data_points(payload):
    paths = _required_list(payload, "paths")
    values = _required_list(payload, "values")
    timestamps = _required_list(payload, "timestamps")
    qualities = payload.get("qualities") or [192 for path in paths]
    if len(paths) != len(values) or len(paths) != len(timestamps) or len(paths) != len(qualities):
        raise BadRequest("paths, values, timestamps, and qualities must have the same length")
    grouped = {}
    for index, path in enumerate(paths):
        history_provider, tag_provider, tag_path = _historical_tag_parts(path)
        key = (history_provider, tag_provider)
        if key not in grouped:
            grouped[key] = {"paths": [], "values": [], "qualities": [], "timestamps": []}
        grouped[key]["paths"].append(tag_path)
        grouped[key]["values"].append(values[index])
        grouped[key]["qualities"].append(qualities[index])
        grouped[key]["timestamps"].append(system.date.fromMillis(long(timestamps[index])))
    for key, group in grouped.items():
        system.tag.storeTagHistory(
            key[0], key[1], group["paths"], group["values"],
            group["qualities"], group["timestamps"],
        )
    return {"ok": True, "qualities": ["Good" for path in paths]}


def _historian_query_raw_points(payload):
    paths = _required_list(payload, "paths")
    start_time = payload.get("startTime")
    end_time = payload.get("endTime")
    if start_time is None or end_time is None:
        raise BadRequest("Request must include startTime and endTime")
    return_size = int(payload.get("returnSize") or 100)
    column_names = ["value_%d" % index for index in range(len(paths))]
    dataset = system.tag.queryTagHistory(
        paths=paths,
        startDate=system.date.fromMillis(long(start_time)),
        endDate=system.date.fromMillis(long(end_time)),
        returnSize=return_size,
        aggregationMode="LastValue",
        returnFormat="Tall",
        columnNames=column_names,
        includeBoundingValues=False,
        noInterpolation=True,
    )
    return {
        "ok": True,
        "result": _dataset_to_wire(dataset),
        "resultSource": "ignition.dataset",
        "resultMessage": "Ignition Dataset serialized as columns/rows; Fluxy converted to row mappings",
    }


_OPERATIONS = {
    "util/getVersion": _util_get_version,
    "util/queryAuditLog": _util_query_audit_log,
    "tag/readBlocking": _tag_read_blocking,
    "tag/writeBlocking": _tag_write_blocking,
    "tag/configure": _tag_configure,
    "tag/deleteTags": _tag_delete,
    "tag/browse": _tag_browse,
    "tag/getConfiguration": _tag_get_configuration,
    "historian/browse": _historian_browse,
    "historian/storeDataPoints": _historian_store_data_points,
    "historian/queryRawPoints": _historian_query_raw_points,
}


def dispatch(operation, payload_json):
    try:
        handler = _OPERATIONS.get(operation)
        if handler is None:
            return system.util.jsonEncode({
                "status": 404,
                "body": {"ok": False, "error": "Unsupported Fluxy operation"},
            })
        payload = system.util.jsonDecode(payload_json or "{}")
        if not isinstance(payload, dict):
            raise BadRequest("Request body must be a JSON object")
        return system.util.jsonEncode({"status": 200, "body": handler(payload)})
    except BadRequest, exc:
        return system.util.jsonEncode({"status": 400, "body": {"ok": False, "error": str(exc)}})
    except Exception, exc:
        system.util.getLogger("Fluxy.Module.Dispatch").error("%s failed: %s" % (operation, exc))
        return system.util.jsonEncode({"status": 500, "body": {"ok": False, "error": str(exc)}})
