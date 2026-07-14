# SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
# SPDX-License-Identifier: MPL-2.0
# Ignition 8.1 implementation of the version-neutral Fluxy wire contract.

class BadRequest(Exception):
    pass


MAX_ITEMS = 1000


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
    if len(value) > MAX_ITEMS:
        raise BadRequest("%s must contain at most %d items" % (key, MAX_ITEMS))
    return value


def _required_string(payload, key):
    value = payload.get(key)
    if not isinstance(value, basestring):
        raise BadRequest("Request must include %s string" % key)
    return value


def _bounded_integer(value, key, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, (int, long)):
        raise BadRequest("%s must be an integer" % key)
    if value < minimum or value > maximum:
        raise BadRequest("%s must be between %d and %d" % (key, minimum, maximum))
    return int(value)


def _optional_parallel_list(payload, key, expected_length):
    value = payload.get(key)
    if value is None:
        return None
    if not isinstance(value, list):
        raise BadRequest("%s must be a list" % key)
    if len(value) != expected_length:
        raise BadRequest("%s must have the same length as paths" % key)
    return value


def _quality_list_to_wire(value):
    qualities = []
    try:
        items = list(value)
    except Exception:
        items = [value]
    for item in items:
        try:
            quality = item.quality
        except Exception:
            try:
                quality = item.getQuality()
            except Exception:
                quality = item
        qualities.append(str(quality))
    return qualities


def _optional_to_wire(value):
    if value is None:
        return None
    try:
        if value.isPresent():
            return str(value.get())
        return None
    except Exception:
        return str(value)


def _annotation_to_wire(annotation):
    return {
        "storageId": str(annotation.storageId),
        "path": str(annotation.path),
        "startTime": str(annotation.rangeStart),
        "endTime": str(annotation.rangeEnd) if annotation.rangeEnd is not None else None,
        "type": str(annotation.type),
        "data": str(annotation.data) if annotation.data is not None else None,
        "deleted": bool(annotation.deleted),
    }


def _util_get_version(payload):
    version = system.util.getVersion()
    return {
        "ok": True,
        "version": str(version),
        "major": int(version.major),
        "minor": int(version.minor),
        "transport": "ignition-module",
        "contractVersion": 2,
    }


def _util_get_modules(payload):
    return {
        "ok": True,
        "result": _dataset_to_wire(system.util.getModules()),
        "resultSource": "ignition.dataset",
        "resultMessage": "Ignition Dataset serialized as columns/rows; Fluxy converted to row mappings",
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


def _tag_copy(payload):
    tag_paths = _required_list(payload, "tagPaths")
    destination_path = _required_string(payload, "destinationPath")
    collision_policy = payload.get("collisionPolicy") or "o"
    quality_codes = system.tag.copy(tag_paths, destination_path, collision_policy)
    return {
        "ok": True,
        "qualities": [
            {
                "tagPath": tag_paths[index],
                "destinationPath": destination_path,
                "quality": str(quality),
            }
            for index, quality in enumerate(quality_codes)
        ],
    }


def _tag_move(payload):
    source_path = _required_string(payload, "sourcePath")
    destination_path = _required_string(payload, "destinationPath")
    quality_codes = system.tag.move([source_path], destination_path, "o")
    return {
        "ok": True,
        "quality": {
            "sourcePath": source_path,
            "destinationPath": destination_path,
            "quality": str(quality_codes[0]),
        },
    }


def _tag_rename(payload):
    tag_path = _required_string(payload, "tagPath")
    new_name = _required_string(payload, "newName")
    quality = system.tag.rename(tag_path, new_name)
    return {
        "ok": True,
        "quality": {"tagPath": tag_path, "newName": new_name, "quality": str(quality)},
    }


def _tag_import(payload):
    import os
    import tempfile

    tags = payload.get("tags")
    if tags is None:
        raise BadRequest("Request must include tags")
    base_path = _required_string(payload, "basePath")
    collision_policy = payload.get("collisionPolicy") or "o"
    raw_json = tags if isinstance(tags, basestring) else system.util.jsonEncode(tags)
    temp_path = None
    try:
        handle, temp_path = tempfile.mkstemp(suffix=".json", prefix="fluxy-import-tags-")
        os.close(handle)
        temp_file = open(temp_path, "w")
        try:
            temp_file.write(raw_json)
        finally:
            temp_file.close()
        qualities = system.tag.importTags(temp_path, base_path, collision_policy)
        return {"ok": True, "qualities": [{"quality": str(quality)} for quality in qualities]}
    finally:
        if temp_path is not None:
            try:
                os.remove(temp_path)
            except Exception:
                pass


def _tag_export(payload):
    import os
    import tempfile

    tag_paths = _required_list(payload, "tagPaths")
    recursive = payload.get("recursive", True)
    if not isinstance(recursive, bool):
        raise BadRequest("recursive must be a boolean")
    temp_path = None
    try:
        handle, temp_path = tempfile.mkstemp(suffix=".json", prefix="fluxy-export-tags-")
        os.close(handle)
        system.tag.exportTags(temp_path, tag_paths, recursive)
        temp_file = open(temp_path, "r")
        try:
            raw_json = temp_file.read()
        finally:
            temp_file.close()
        return {"ok": True, "tags": system.util.jsonDecode(raw_json), "rawJson": raw_json}
    finally:
        if temp_path is not None:
            try:
                os.remove(temp_path)
            except Exception:
                pass


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


def _tag_query(payload):
    provider = _required_string(payload, "provider")
    query = payload.get("query") or {}
    if not isinstance(query, dict):
        raise BadRequest("query must be an object")
    limit = payload.get("limit", 1000)
    continuation = payload.get("continuation")
    limit = _bounded_integer(limit, "limit", 1, 10000)
    if continuation is not None and not isinstance(continuation, basestring):
        raise BadRequest("continuation must be a string")
    if continuation:
        query_results = system.tag.query(provider, query, limit, continuation)
    else:
        query_results = system.tag.query(provider, query, limit)

    results = []
    for result in query_results:
        item = _value_to_wire(result)
        results.append(item if isinstance(item, dict) else {"raw": str(result)})
    continuation_point = None
    try:
        continuation_point = query_results.continuationPoint
    except Exception:
        try:
            continuation_point = query_results.getContinuationPoint()
        except Exception:
            pass
    return {
        "ok": True,
        "results": results,
        "continuationPoint": str(continuation_point) if continuation_point is not None else None,
    }


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
    if continuation:
        results = system.tag.browseHistoricalTags(path, None, None, continuation)
    else:
        results = system.tag.browseHistoricalTags(path)
    quality = results.getResultQuality()
    result_rows = results.getResults()
    if result_rows is None or quality is None or not quality.isGood():
        raise RuntimeError("Historian browse failed: %s" % quality)
    continuation_point = results.getContinuationPoint()
    return {
        "ok": True,
        "results": [_historian_browse_result_to_wire(result) for result in result_rows],
        "continuationPoint": str(continuation_point) if continuation_point is not None else None,
        "quality": str(quality),
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


def _historian_query_aggregated_points(payload):
    paths = _required_list(payload, "paths")
    start_time = payload.get("startTime")
    end_time = payload.get("endTime")
    if start_time is None or end_time is None:
        raise BadRequest("Request must include startTime and endTime")
    aggregates = payload.get("aggregates")
    fill_modes = payload.get("fillModes")
    column_names = payload.get("columnNames")
    for key, value in [
        ("aggregates", aggregates),
        ("fillModes", fill_modes),
        ("columnNames", column_names),
    ]:
        if value is not None and not isinstance(value, list):
            raise BadRequest("%s must be a list" % key)
    if column_names is None:
        column_names = ["value_%d" % index for index in range(len(paths))]
    return_format = payload.get("returnFormat") or "WIDE"
    return_size = _bounded_integer(payload.get("returnSize", 1), "returnSize", 1, 100000)
    include_bounds = payload.get("includeBounds", False)
    exclude_observations = payload.get("excludeObservations", False)
    if not isinstance(include_bounds, bool) or not isinstance(exclude_observations, bool):
        raise BadRequest("includeBounds and excludeObservations must be booleans")
    if exclude_observations:
        raise BadRequest("excludeObservations is not supported on Ignition 8.1")
    normalized_fill_modes = [str(mode).upper() for mode in fill_modes] if fill_modes else []
    unsupported_fill_modes = [
        mode for mode in normalized_fill_modes if mode not in ["DERIVED", "NONE"]
    ]
    if unsupported_fill_modes:
        raise BadRequest(
            "Ignition 8.1 supports only DERIVED or NONE fillModes through Fluxy"
        )
    if normalized_fill_modes and len(set(normalized_fill_modes)) != 1:
        raise BadRequest("Ignition 8.1 requires one consistent fillMode for all paths")
    no_interpolation = bool(normalized_fill_modes and normalized_fill_modes[0] == "NONE")
    start_date = system.date.fromMillis(long(start_time))
    end_date = system.date.fromMillis(long(end_time))
    if str(return_format).upper() == "CALCULATION":
        if not isinstance(aggregates, list) or not aggregates:
            raise BadRequest("CALCULATION returnFormat requires aggregates list")
        dataset = system.tag.queryTagCalculations(
            paths=paths,
            calculations=aggregates,
            startDate=start_date,
            endDate=end_date,
            aliases=column_names,
            includeBoundingValues=include_bounds,
            noInterpolation=no_interpolation,
        )
    else:
        if str(return_format).upper() not in ["WIDE", "TALL"]:
            raise BadRequest("returnFormat must be WIDE, TALL, or CALCULATION")
        dataset = system.tag.queryTagHistory(
            paths=paths,
            startDate=start_date,
            endDate=end_date,
            returnSize=return_size,
            aggregationModes=aggregates,
            returnFormat=return_format,
            columnNames=column_names,
            includeBoundingValues=include_bounds,
            noInterpolation=no_interpolation,
        )
    return {
        "ok": True,
        "result": _dataset_to_wire(dataset),
        "resultSource": "ignition.dataset",
        "resultMessage": "Ignition Dataset serialized as columns/rows; Fluxy converted to row mappings",
    }


def _dates_or_none_from_millis(values):
    if values is None:
        return None
    return [system.date.fromMillis(long(value)) for value in values]


def _historian_store_annotations(payload):
    paths = _required_list(payload, "paths")
    start_times = _required_list(payload, "startTimes")
    if len(start_times) != len(paths):
        raise BadRequest("startTimes must have the same length as paths")
    end_times = _optional_parallel_list(payload, "endTimes", len(paths))
    types = _optional_parallel_list(payload, "types", len(paths))
    data = _optional_parallel_list(payload, "data", len(paths))
    storage_ids = _optional_parallel_list(payload, "storageIds", len(paths))
    deleted = _optional_parallel_list(payload, "deleted", len(paths))
    start_dates = _dates_or_none_from_millis(start_times)
    end_dates = _dates_or_none_from_millis(end_times)
    if deleted is not None:
        qualities = system.tag.storeAnnotations(
            paths, start_dates, end_dates, types, data, storage_ids, deleted
        )
    elif storage_ids is not None:
        qualities = system.tag.storeAnnotations(
            paths, start_dates, end_dates, types, data, storage_ids
        )
    elif data is not None:
        qualities = system.tag.storeAnnotations(paths, start_dates, end_dates, types, data)
    elif types is not None:
        qualities = system.tag.storeAnnotations(paths, start_dates, end_dates, types)
    elif end_times is not None:
        qualities = system.tag.storeAnnotations(paths, start_dates, end_dates)
    else:
        qualities = system.tag.storeAnnotations(paths, start_dates)
    return {"ok": True, "qualities": _quality_list_to_wire(qualities)}


def _historian_query_annotations(payload):
    paths = _required_list(payload, "paths")
    start_date = payload.get("startDate")
    end_date = payload.get("endDate")
    allowed_types = payload.get("allowedTypes")
    if start_date is None:
        raise BadRequest("Request must include startDate")
    if allowed_types is not None and not isinstance(allowed_types, list):
        raise BadRequest("allowedTypes must be a list")
    start = system.date.fromMillis(long(start_date))
    end = system.date.fromMillis(long(end_date)) if end_date is not None else None
    if allowed_types is not None:
        results = system.tag.queryAnnotations(paths, start, end, allowed_types)
    else:
        results = system.tag.queryAnnotations(paths, start, end)
    return {
        "ok": True,
        "annotations": [_annotation_to_wire(annotation) for annotation in results],
        "quality": "Good",
    }


def _historian_delete_annotations(payload):
    paths = _required_list(payload, "paths")
    storage_ids = _required_list(payload, "storageIds")
    if len(storage_ids) != len(paths):
        raise BadRequest("storageIds must have the same length as paths")
    qualities = system.tag.deleteAnnotations(paths, storage_ids)
    return {"ok": True, "qualities": _quality_list_to_wire(qualities)}


def _project_get_project_names(payload):
    return {
        "ok": True,
        "projectNames": [str(project_name) for project_name in system.project.getProjectNames()],
    }


_OPERATIONS = {
    "util/getVersion": _util_get_version,
    "util/getModules": _util_get_modules,
    "util/queryAuditLog": _util_query_audit_log,
    "tag/readBlocking": _tag_read_blocking,
    "tag/writeBlocking": _tag_write_blocking,
    "tag/configure": _tag_configure,
    "tag/deleteTags": _tag_delete,
    "tag/copy": _tag_copy,
    "tag/move": _tag_move,
    "tag/rename": _tag_rename,
    "tag/importTags": _tag_import,
    "tag/exportTags": _tag_export,
    "tag/browse": _tag_browse,
    "tag/getConfiguration": _tag_get_configuration,
    "tag/queryTags": _tag_query,
    "historian/browse": _historian_browse,
    "historian/storeDataPoints": _historian_store_data_points,
    "historian/queryRawPoints": _historian_query_raw_points,
    "historian/queryAggregatedPoints": _historian_query_aggregated_points,
    "historian/storeAnnotations": _historian_store_annotations,
    "historian/queryAnnotations": _historian_query_annotations,
    "historian/deleteAnnotations": _historian_delete_annotations,
    "project/getProjectNames": _project_get_project_names,
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
