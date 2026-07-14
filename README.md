<!-- SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC -->
<!-- SPDX-License-Identifier: MPL-2.0 -->

# Fluxy Java

Fluxy Java is the free, open-source Ignition Gateway module for the [Fluxy Python client](https://github.com/GreenPipePartners/Fluxy). It exposes a small authenticated allowlist of Gateway operations instead of accepting arbitrary scripts or function names.

This repository is the corresponding source for published Fluxy `.modl` artifacts. Authored module files are licensed under MPL-2.0. Fluxy is a third-party project and is not certified, approved, supported, or endorsed by Inductive Automation.

## Compatibility

| Artifact | Minimum Ignition version | Java |
| --- | --- | --- |
| `Fluxy-Ignition81-Free` | 8.1.50 | 17 |
| `Fluxy-Ignition83-Free` | 8.3.4 | 17 |

Separate binaries are required because Ignition 8.3 changed servlet and data-route APIs. Both artifacts use IA-prefix-compliant module ID `partners.greenpipe.fluxy`, expose the same HTTP contract, set `freeModule=true`, and do not require an Ignition module entitlement.

The Java implementation uses package namespace `partners.greenpipe.fluxy.gateway`, matching the stable module ID.

## Security Model

- Every route is authenticated.
- Ignition 8.3 uses native Gateway API keys and API read/write permissions.
- Ignition 8.1 verifies configured SHA-256 token hashes.
- Arbitrary Python source and arbitrary function names are never accepted.
- Large history exports use bounded native Java streaming instead of complete Dataset materialization.
- Mutations are correlated in Gateway logs and can be sent to the Gateway audit profile.

Use HTTPS outside a local development machine and grant write access only to automation that needs mutation routes.

## Build

Install JDK 17 and use the committed Gradle wrapper:

```bash
./gradlew -PignitionTarget=8.1 clean test packageDevelopment
./gradlew -PignitionTarget=8.3 clean test packageDevelopment
```

Development artifacts are written to `release/`, are unsigned, and are marked not for distribution. They require an Ignition development Gateway configured to allow unsigned modules.

The complete compatibility matrix is:

```text
8.1.50
8.1.51
8.3.4
8.3.6
```

## Public Releases

Public release candidates require a clean, pushed, immutable tag and embed the exact source commit and archive URL:

```bash
./gradlew \
  -PignitionTarget=8.3 \
  -PlicenseMode=free \
  -PpublicRelease=true \
  -PsourceCommit=<40-character-commit> \
  -PsourceTag=v0.2.0.20260714 \
  clean test packageReleaseCandidate
```

`packageReleaseCandidate` deliberately produces an unsigned candidate. Public `.modl` assets must be signed with Inductive Automation's module-signing tool before publication. Never commit signing keys, keystores, certificate chains, or passwords.

Inductive Automation identifies Green Pipe as the module author through the assigned `partners.greenpipe` module prefix. IA does not assign or require a separate numeric `vendorId` in `module.xml`. Commercial licensing remains separately gated on implementing and testing IA's licensing API.

See [docs/release.md](docs/release.md) for the complete release procedure.

## Python Usage

Install and configure the Python client from [GreenPipePartners/Fluxy](https://github.com/GreenPipePartners/Fluxy).

Ignition 8.3:

```python
from fluxy import Fluxy

fx = Fluxy(
    "https://gateway.example/data",
    api_token="fluxy-service:<secret>",
    tag_provider="default",
)

value = fx.tag.read_blocking("[default]Demo/Value")
capabilities = fx.util.get_capabilities()
fx.project.request_scan()
```

Ignition 8.1 uses `https://gateway.example/main/data`. See [docs/ignition-module.md](docs/ignition-module.md) for token configuration and the complete route contract.

## Native Operations

- Project resource scans through `ProjectManager.requestScan`
- Bounded history streaming through `TagHistoryManager.queryHistory`

The versioned route manifest exposes 28 operations on Ignition 8.3 and 26 on Ignition 8.1. In addition to the native operations above, the module supports capability and module discovery, project listing, tag configure/read/write/browse/copy/move/rename/import/export/query/delete, audit-log queries, and raw/aggregated historian data plus annotations. Historian metadata requires Ignition 8.3.

## Licensing And Provenance

- Authored Java, Jython, Gradle, and module documentation: MPL-2.0.
- Adapted Fluxy WebDev portions retain their historical MIT notice in `WEBDEV_MIT_NOTICE`.
- Gradle wrapper: Apache-2.0; see `LICENSES/Gradle-8.2.1.txt` for Gradle's license and bundled notices.
- Ignition SDK and host APIs: compile-only and not distributed in the module.

See `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, and `PROVENANCE.md`. Report vulnerabilities according to `SECURITY.md`.
