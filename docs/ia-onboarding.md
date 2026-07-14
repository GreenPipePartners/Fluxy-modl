<!-- SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC -->
<!-- SPDX-License-Identifier: MPL-2.0 -->

# Inductive Automation Onboarding

Status: developer account approved; Module Showcase submission and commercial licensing integration pending.

## Registration

- Module Showcase Developer Account approved for Green Pipe Partners, LLC.
- IA-assigned module prefix: `partners.greenpipe`.
- Fluxy module ID: `partners.greenpipe.fluxy`.
- Confirm the exact vendor name IA expects and align it with the code-signing certificate leaf `CN=Green Pipe Partners, LLC`.
- Module Showcase/API portal access received.

IA's author identity is the assigned module-ID prefix, not a separate numeric vendor ID. The independently hosted public free release does not use IA licensing. It must not be represented as a Module Showcase or IA-approved release until the submission process is complete.

## Licensing Design

- Define per-Gateway activation and redundant-Gateway treatment.
- Define permanent version entitlement so versions released during maintenance continue operating indefinitely.
- Define the restriction or license-detail field that tells a module which versions are entitled.
- Do not encode maintenance expiration as a runtime shutdown date.
- Test online and offline activation, unactivation, transfer, reset trial, expired trial, invalid state, version upgrade, rollback, and redundant failover.

`iaLicensingReady` in `build.gradle.kts` must remain `false` until these parameters are provisioned, documented, implemented, and tested. The `officialRelease` build rejects licensed release while it is false.

## Submission

- Build with the CA-issued certificate using controlled signing infrastructure.
- Install on a Gateway where unsigned modules are disabled.
- Complete IA's module installation and compatibility review.
- Publish compatibility and support statements without implying endorsement beyond IA's actual program language.
