<!-- SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC -->
<!-- SPDX-License-Identifier: MPL-2.0 -->

# Third-Party Notices

The Fluxy Gateway JAR and `.modl` do not currently bundle third-party runtime libraries. Ignition SDK, Jython, Gson, Jakarta Servlet, and related APIs are supplied by the customer's Ignition installation and are not redistributed in the Fluxy module.

The source tree includes the official Gradle 8.2.1 wrapper. Gradle is licensed under Apache License 2.0. The wrapper scripts retain their upstream copyright and license headers, and Gradle's complete license and bundled notices are in `LICENSES/Gradle-8.2.1.txt`. The wrapper JAR is not part of the Fluxy `.modl`.

`fluxy_dispatch.py` adapts portions of the preexisting MIT-licensed Fluxy WebDev implementation. Green Pipe Partners owns the assigned underlying rights, while the original MIT notice remains in `WEBDEV_MIT_NOTICE` to preserve historical provenance and prior grants.

Build and test dependencies are not shipped in the module:

| Component | Use | License/status |
| --- | --- | --- |
| Inductive Automation Ignition SDK 8.1.50/8.1.51/8.3.4/8.3.6 | Compile-only host API | Inductive Automation Software License |
| IA Gradle module plugin 0.1.1 | Build tooling | License metadata not declared in its published POM; not distributed |
| Gson/IA Gson 2.10.1 | Compile-only host JSON API | Apache-2.0 upstream; host-provided |
| JUnit 5.11.4 | Tests | EPL-2.0; not distributed |
| Gradle 8.2.1 | Build wrapper | Apache-2.0; not distributed in module |

Generated SBOMs describe the contents of the distributed module, not the complete Ignition host platform or build toolchain. Review this inventory before every release and add notices if a runtime dependency is ever bundled.
