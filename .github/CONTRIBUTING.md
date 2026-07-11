# Contributing

Thanks for your interest in improving the Regulatory Approval System.

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (optional, for building/running the container)

## Build & test

```bash
mvn verify          # compile + run the unit and integration tests
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # run locally (enables the H2 console)
```

The integration test (`SecurityAuthorizationIntegrationTest`) starts the full application on port
`8080`, so make sure that port is free when running the suite.

## Branching & pull requests

- Branch off `main`; use a descriptive branch name.
- Keep pull requests focused; one logical change per PR.
- Fill in the pull request template, including how you verified the change.
- CI (build + tests, Docker build, CodeQL, dependency review) must pass before merge.

## Coding conventions

- Match the style of the surrounding code (indentation, naming, comment density).
- Never commit secrets, tokens, or credentials. Configuration defaults must be safe for production or
  gated behind the `dev` profile.
- Add or update tests for behavioural changes — the security and authorization rules in particular are
  covered by tests and should stay that way.
- Update the README when you change behaviour, endpoints, or configuration.

## Reporting security issues

See [SECURITY.md](./SECURITY.md) — please report vulnerabilities privately.
