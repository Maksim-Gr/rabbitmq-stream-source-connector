# Contributing

Thanks for your interest in improving the RabbitMQ Streams source connector! Contributions of all
kinds — bug reports, documentation, tests and features — are welcome.

## Getting started

- Requires a JDK 17 (the build uses the Kotlin/JVM toolchain pinned to 17).
- Build and run the unit tests:

  ```bash
  ./gradlew clean build
  ```

- Integration tests use [Testcontainers](https://testcontainers.com/) and need a running Docker
  daemon. They are excluded from `build` and run separately:

  ```bash
  ./gradlew integrationTest
  ```

## Before you open a pull request

- **Format and lint.** The build enforces [ktlint](https://github.com/pinterest/ktlint). Run
  `./gradlew ktlintFormat` to auto-fix, then `./gradlew ktlintCheck` to verify.
- **Add tests** for behaviour changes. Pure logic (offset resolution, config validation, record
  building) belongs in unit tests; anything that needs a broker belongs in an integration test.
- **Keep commits focused** and write a clear description of *what* changed and *why*.
- **Update the README** when you add or change a configuration property.

## Reporting bugs

Open an issue with:

- the connector version and Kafka Connect runtime version,
- the connector configuration (redact credentials),
- relevant log output, and
- steps to reproduce.

## Code of conduct

Be respectful and constructive. Harassment or abusive behaviour will not be tolerated.
