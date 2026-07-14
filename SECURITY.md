# Security Policy

## Reporting

Report suspected vulnerabilities through this repository's private vulnerability reporting form under the **Security** tab or through a private channel identified in an active Green Pipe Partners agreement. Do not open a public issue for an unpatched vulnerability, exposed credential, or signing-key concern.

Include affected versions, deployment mode, impact, reproduction steps, and any proposed mitigation. Do not include production credentials or customer data.

## Scope

Security fixes for the public free module are provided on a best-effort basis unless an applicable agreement states otherwise. Published MPL source remains available without implying continuing support, warranty, or security updates.

## Credentials

- Never commit Ignition API keys, activation credentials, signing keys, keystores, or certificate passwords.
- Production module signing keys must be held in an HSM or equivalent controlled signing service.
- Development credentials exposed in logs, chat, scripts, or test output must be rotated immediately.

Green Pipe Partners will coordinate disclosure after a fix or mitigation is available. Response targets exist only where a signed contract states them.
