# Security Policy

## Reporting a vulnerability

**Do not report security problems in public issues, discussions or pull requests.**

Use GitHub's private vulnerability reporting instead: open the repository's **Security** tab
and choose **Report a vulnerability**. Please include:

- the affected version and deployment mode (local, single node, cluster, Kubernetes);
- the affected component or plugin;
- steps to reproduce, or a proof of concept;
- the impact as you understand it.

## Response times

| Step | Target |
|---|---|
| Acknowledge the report | within 3 working days |
| Initial assessment and severity | within 10 working days |
| Fix or mitigation for critical and high severity issues | as fast as possible, usually within 30 days |

We coordinate the disclosure date with the reporter and credit them in the advisory unless
they prefer to stay anonymous.

## Supported versions

Security fixes are provided for the four most recent minor versions. Until the first release,
only the `main` branch is supported.

| Version | Supported |
|---|---|
| `main` (unreleased) | yes |
