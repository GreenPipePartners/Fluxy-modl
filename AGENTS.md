# Fluxy Java Agent Guidance

## Ignition Change Activation

- Never restart or reset an Ignition Gateway merely to load project filesystem changes.
- After changing files under `data/projects/`, call the Fluxy MCP tool `project_request_scan` or `fx.project.request_scan()`.
- A project scan defaults to a 10-second wait. A timeout does not cancel the scan.
- Project scans do not reload file-backed Gateway configuration under `data/config/resources/`.
- Restart only when a module or documented Gateway-level change requires it, and state the concrete reason first.

## Module Engineering

- Prefer supported native Ignition SDK APIs over Jython dispatch when cross-version APIs exist.
- Preserve compatibility with Ignition 8.1.50/8.1.51 and 8.3.4/8.3.6.
- Keep all routes allowlisted and authenticated. Never add arbitrary script or function execution.
- Keep SDK and host dependencies compile-only; do not bundle Inductive Automation classes.

## Releases

- Never publish an unsigned `.modl` or enable unsigned modules on a production Gateway.
- Never commit keystores, private keys, certificate chains, passwords, API keys, or customer data.
- Public release artifacts must identify an exact pushed source commit and immutable tag.
- Pre-vendor public builds must use `licenseMode=free` and omit `vendorId`; never publish `vendorId=0`.
- Do not imply Inductive Automation certification, approval, endorsement, or support.
