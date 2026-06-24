# Security Policy

## Supported versions

This project is pre-1.0 and under active development. Security fixes are applied to the latest
released version. Please upgrade to the most recent release before reporting an issue.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, report privately through GitHub's
[private vulnerability reporting](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/security/advisories/new)
(Security → Advisories → "Report a vulnerability").

When reporting, include:

- a description of the vulnerability and its impact,
- the connector version affected, and
- steps to reproduce or a proof of concept.

You can expect an initial response within a few business days. Once a fix is available, a new release
will be published and the advisory disclosed.

## Handling credentials

RabbitMQ and TLS keystore passwords are configured as Kafka Connect `PASSWORD`-type properties so they
are redacted from logs and the REST API. Avoid placing secrets in plaintext connector configs where
possible; prefer your Connect runtime's secret-provider mechanism.
