<!-- SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC -->
<!-- SPDX-License-Identifier: MPL-2.0 -->

# Ignition Module Transport

Fluxy includes separate Gateway-scoped artifacts for Ignition 8.1.50+ and 8.3. They replace the WebDev project with native Gateway data routes while retaining one Python request and response contract.

## Architecture

```text
Python 3 Fluxy client
  -> HTTPS + API token
  -> /data/fluxy/... (8.3) or /main/data/fluxy/... (8.1)
  -> version-specific RouteGroup authorization
  -> native Java operations or allowlisted Jython dispatcher
  -> Gateway SDK / system.tag / system.historian / system.util
```

The dispatcher does not accept Python source or arbitrary function names. Adding an operation requires a module code change and an explicit read or write assignment in the shared `fluxy-routes.json` manifest. Module startup fails if the manifest and version-specific dispatcher differ. Native SDK operations are preferred where Ignition exposes a stable cross-version API.

## Build

Install a Java 17 JDK, then run:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew -PignitionTarget=8.1 clean test packageDevelopment
JAVA_HOME=/path/to/jdk17 ./gradlew -PignitionTarget=8.3 clean test packageDevelopment
```

The development artifacts are:

- `release/Fluxy-Ignition81-Free-0.2.0.20260714-dev.unsigned.modl`
- `release/Fluxy-Ignition83-Free-0.2.0.20260714-dev.unsigned.modl`

Each development artifact is unsigned and explicitly marked not for distribution. Install only the artifact matching the Gateway major version. Both identify as `Fluxy Free`, set `<freeModule>true</freeModule>`, and require no module entitlement. Free changes licensing, not API security; every route remains authenticated.

## Development Install

1. Enable unsigned modules only on the development Gateway by adding `-Dignition.allowunsignedmodules=true` to `data/ignition.conf`.
2. Install the unsigned development `.modl` artifact from the Gateway module page.
3. Accept the unsigned module when prompted and follow any module-page reload instruction.
4. Confirm the module is running before creating credentials.

Production artifacts must be signed. Do not enable unsigned modules on a production Gateway.

Unsigned development builds declare `Green Pipe Partners, LLC` through the module descriptor. Public release assets are signed separately with Inductive Automation's module-signing tool. A self-signed certificate is supported by Ignition, while a CA-issued code-signing certificate provides stronger public identity assurance.

## Licensing

The public module is free and open source under MPL-2.0. It returns `true` from `isFreeModule()` and does not depend on the Gateway trial or Inductive Automation's third-party module licensing service.

IA assigned Green Pipe Partners the module prefix `partners.greenpipe`; the stable module ID is `partners.greenpipe.fluxy`. IA's current descriptor and Gradle plugin do not use a separate numeric vendor ID. A future commercial release remains separately gated on implementing and testing IA's licensing API.

The Java implementation namespace is `partners.greenpipe.fluxy.gateway`.

Neither the source nor a Green Pipe-signed binary should be represented as IA-certified, approved, supported, or endorsed.

## Authorization

### Ignition 8.3

Ignition API keys are subject to the Gateway API Access, Read, and Write permission settings under **Platform > Security > General Settings**. Configure those permissions with a static security level that the service API key is allowed to hold. Do not rely on user-source roles: Ignition intentionally ignores role levels granted directly by API-key configuration.

For a local-only trial, all three Gateway API permissions can require `Authenticated`, and the API key can hold `Authenticated`. A production deployment should use dedicated static levels and separate read-only and write-enabled keys.

Create an API key under **Platform > Security > API Keys**. Pass the complete credential displayed by Ignition, including its key name:

```python
from fluxy import Fluxy

fx = Fluxy(
    "https://gateway.example/data",
    api_token="fluxy-service:<one-time-secret>",
    tag_provider="default",
    run_id="commissioning-20260714",
    script_name="build_tags.py",
)
```

Use HTTPS. An API key is a bearer credential even though it uses the `X-Ignition-API-Token` header.

### Ignition 8.1

Ignition 8.1 does not provide native Gateway API keys. The 8.1 artifact verifies SHA-256 token hashes configured as JVM properties and accepts the same `X-Ignition-API-Token` header used by `api_token=`.

Generate a random token, calculate its hash without a trailing newline, and add the hash to `data/ignition.conf` using the next available `wrapper.java.additional.N` index:

```bash
printf '%s' 'replace-with-a-random-token' | sha256sum
```

```properties
wrapper.java.additional.N=-Dfluxy.apiTokenSha256=<64-character-write-token-sha256>
wrapper.java.additional.M=-Dfluxy.readApiTokenSha256=<optional-read-only-token-sha256>
```

Restart the Gateway after changing the properties. The write token can call all routes; the optional read token can call only read routes. Store only hashes in Gateway configuration, use at least 256 random bits for each token, and put a TLS reverse proxy in front of the 8.1 Gateway for rate and request-size limits.

```python
fx = Fluxy(
    "https://gateway.example/main/data",
    api_token="replace-with-a-random-token",
)
```

## Traceability And Auditing

Fluxy assigns a UUID request ID to every Gateway call. A `Fluxy` instance also has one stable run ID, generated automatically unless `run_id` is supplied. Set `script_name` to identify the calling automation.

The client sends:

- `X-Fluxy-Request-Id`: unique per operation.
- `X-Fluxy-Run-Id`: stable across one script or batch run.
- `X-Fluxy-Script`: optional caller-provided script name.

The module returns the accepted request and run IDs as response headers. The latest request ID is available as:

```python
print(fx.client.last_request_id)
print(fx.client.last_run_id)
```

Successful mutations are logged at `INFO` under `Fluxy.Module`. Reads are logged at `DEBUG`, and failed calls are logged at `WARN` or `ERROR`. Each entry includes the API-key actor, operation, request ID, run ID, script, status, duration, target count, and safe target paths.

Mutation records are also sent to Ignition's configured Gateway audit profile. Configure one under **Platform > Security > Audit Profiles**, then select it as the Gateway audit profile under **Platform > Security > General Settings**. A local profile named `Fluxy Audit` works without an external database.

Audited module operations currently include:

- `tag/configure`
- `tag/writeBlocking`
- `tag/deleteTags`
- `tag/copy`
- `tag/move`
- `tag/rename`
- `tag/importTags`
- `historian/storeDataPoints`
- `historian/storeAnnotations`
- `historian/deleteAnnotations`
- `historian/storeMetadata` on Ignition 8.3

Audit records contain actor, host, operation, targets, request ID, run ID, script, HTTP status, duration, target count, contract version, timestamp, and result quality. Imported tag definitions, annotation data, metadata values, tag values, historian values, API keys, and complete request bodies are never logged or audited.

Query the resulting records through Fluxy:

```python
rows = fx.util.query_audit_log(
    "Fluxy Audit",
    action_filter="Fluxy.tag/writeBlocking",
    target_filter="[default]Area/Setpoint",
)

for row in rows:
    print(row)
```

If no Gateway audit profile is configured, mutations still execute and remain in Gateway logs. The module emits a one-time warning that durable auditing is disabled.

## Version Adapters

The Python API and route names remain future-facing on both versions. The 8.1 artifact performs these private adaptations:

| Public operation | Ignition 8.3 | Ignition 8.1 |
| --- | --- | --- |
| Tag timestamp formatting | `system.date.format` | `system.db.dateFormat` |
| `historian.browse` | `system.historian.browse` | `system.tag.browseHistoricalTags` |
| `historian.store_data_points` | `system.historian.storeDataPoints` | `system.tag.storeTagHistory` |
| `historian.query_raw_points` | `system.historian.queryRawPoints` | `system.tag.queryTagHistory` |
| `historian.query_aggregated_points` | `system.historian.queryAggregatedPoints` | `system.tag.queryTagCalculations` / `queryTagHistory` |
| Historian annotations | `system.historian.*Annotations` | `system.tag.*Annotations` |
| Historian metadata | `system.historian.storeMetadata` / `queryMetadata` | Unavailable and reported by capabilities |
| `historian.stream_raw_points` | Java `TagHistoryManager.queryHistory` | Java `TagHistoryManager.queryHistory` |
| `project.request_scan` | Java `ProjectManager.requestScan` | Java `ProjectManager.requestScan` |

For `historian.query_aggregated_points`, Ignition 8.1 maps `CALCULATION` format to `system.tag.queryTagCalculations` and `WIDE`/`TALL` to `system.tag.queryTagHistory`. Its legacy API supports Fluxy `DERIVED` and `NONE` fill modes when one consistent mode is used for every path. Other fill modes and `excludeObservations=true` return HTTP 400 instead of silently changing query semantics.

Call `fx.util.get_capabilities()` to inspect the installed module contract. The response includes every declared operation, its read/write classification, and whether it is available on the current Ignition family. The native module does not expose arbitrary SQL, datasource administration, user administration, arbitrary code execution, or Vision/Perspective session APIs.

Ordinary JSON requests are limited to 1 MiB and responses to 4 MiB. Collection requests accept at most 1,000 items, tag queries accept at most 10,000 results per page, and aggregated historian requests accept at most 100,000 rows. Use the bounded native history stream for larger raw-history workloads.

## Project Scans

Use a project scan after creating, editing, moving, or deleting project filesystem resources:

```python
result = fx.project.request_scan()
result = fx.project.request_scan(timeout_seconds=30)
```

The native route is `POST /data/fluxy/project/requestScan` on 8.3 and `POST /main/data/fluxy/project/requestScan` on 8.1. It calls the supported Java `ProjectManager.requestScan()` API directly and does not enter Jython. The default timeout is 10 seconds and the accepted range is 1 through 300 seconds.

If a scan is already running, Ignition waits for that scan rather than starting another. A timeout limits only the HTTP request's wait and does not cancel the scan. Do not restart the Gateway merely to activate project changes. Project scans do not reload file-backed Gateway resources under `data/config/resources/`; module installation/upgrades and some Gateway-level changes can still require a documented restart.

## Historian Setup

History calls require an enabled historian provider. For the built-in 8.3 provider:

1. Open **Connections > Historians**.
2. Create a provider named `Core Historian`.
3. Configure memory tags with `historyEnabled: true` and `historyProvider: "Core Historian"`.

Do not assume tag history is stored under `sys:gateway`. Directly injected points may use that system node, while tag-generated samples use the Gateway's normalized system name. Use `fx.historian.browse("histprov:Core Historian:/")` to discover the canonical path.

### Native history streaming

Use the native stream for large backfills and replication:

```python
for block in fx.historian.stream_raw_points(
    [history_path],
    start_time,
    end_time,
    block_rows=1000,
):
    write_to_questdb(block.mappings())
```

The route is `/historian/queryRawPointsStream` under the module mount. It builds raw, Tall `BasicTagHistoryQueryParams` with no seed values, interpolation, bounding values, or preprocessed data, then sends protocol-versioned `schema`, `block`, and `end` NDJSON events directly from `StreamingDatasetWriter`. It never constructs a complete Dataset and does not invoke Jython.

The iterator must reach `end` before a replication cursor advances. A terminal `error` event raises `FluxyError`. Closing the iterator early closes the HTTP response; the resulting servlet write failure stops the historian query at its next callback.

Initial bounds are 1,000 rows per block by default, 5,000 maximum requested rows per block, 4 MiB per encoded block, 1 GiB total block data, 30 minutes while callbacks continue, 128 paths, one history provider per request, and four concurrent streams. Streaming is synchronous and deliberately applies client backpressure to the historian query.

The API surface was compile-tested against Ignition 8.1.50, 8.1.51, 8.3.4, and 8.3.6. The relevant 8.3.4 and 8.3.6 public classfiles are unchanged. Explicit aggregation modes and raw-history flags also avoid known 8.3.4 null-list and seed-value defects.

## Live Verification

The opt-in test validates capabilities and Gateway inventory, exercises tag copy/move/rename/import/export/query, configures a history-enabled memory tag, queries raw and aggregated history, runs annotation and metadata lifecycles, verifies audit records, and cleans up disposable tags:

```bash
FLUXY_MODULE_INTEGRATION=1 \
FLUXY_BASE_URL=http://localhost:8088/data \
FLUXY_API_TOKEN='fluxy-service:<one-time-secret>' \
uv run pytest tests/test_integration_module.py
```

## Current Surface

Utility and project:

- `util/getVersion`, `util/getCapabilities`, `util/getModules`, `util/queryAuditLog`
- `project/requestScan`, `project/getProjectNames`

Tags:

- Read: `tag/readBlocking`, `tag/browse`, `tag/getConfiguration`, `tag/exportTags`, `tag/queryTags`
- Write: `tag/configure`, `tag/writeBlocking`, `tag/deleteTags`, `tag/copy`, `tag/move`, `tag/rename`, `tag/importTags`

Historian:

- Read: `historian/browse`, `historian/queryRawPoints`, `historian/queryRawPointsStream`, `historian/queryAggregatedPoints`, `historian/queryAnnotations`, `historian/queryMetadata`
- Write: `historian/storeDataPoints`, `historian/storeAnnotations`, `historian/deleteAnnotations`, `historian/storeMetadata`

Historian metadata routes are available only on Ignition 8.3, producing 28 available operations on 8.3 and 26 on 8.1. Capability discovery still lists the unavailable 8.1 metadata operations with `available: false`.

The WebDev transport remains available for existing deployments. The native artifacts default to 8.1.50 and 8.3.4 respectively because the major versions have incompatible servlet and route-authorization APIs. CI additionally compiles and tests against 8.1.51 and 8.3.6.

Current module release: `0.2.0 (b20260714)`, module ID `partners.greenpipe.fluxy`, vendor `Green Pipe Partners, LLC`.

The module source is MPL-2.0. See `../LICENSE`, `../NOTICE`, `../PROVENANCE.md`, and `release.md` for source, ownership, and official-release requirements.
