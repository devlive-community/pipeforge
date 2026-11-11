# Contributing to PipeForge

[English](CONTRIBUTING.md) | [简体中文](CONTRIBUTING.zh-CN.md)

Thank you for helping build PipeForge! This guide explains how to propose a change, how to set
up a development environment and what a pull request needs before it can be merged.

## 1. Before you start

- Small fixes (typos, obvious bugs, missing tests) can go straight to a pull request.
- New features, new plugins and any change to `pipeforge-api`, the wire protocol or the
  checkpoint format start with a **feature proposal** issue. Agree on the direction first,
  then write the code.
- Questions belong in [GitHub Discussions](https://github.com/devlive-community/pipeforge/discussions),
  not in issues.

## 2. Development environment

You need JDK 21 (see `.java-version`), Docker, and Node.js with pnpm for the UI and the
documentation site. Everything else comes through the Maven Wrapper.

```bash
./mvnw verify                      # full build with every quality gate
./mvnw -Pquick install             # fast build without formatting, static analysis and coverage
./mvnw -pl pipeforge-api verify    # build and test a single module
./dev/up.sh                        # start the local services (databases, object storage, ...)
```

## 3. Code style

- Java is formatted by Spotless with palantir-java-format, and checked by Error Prone and
  SpotBugs. Run `./mvnw spotless:apply` before committing.
- Frontend code uses ESLint and Prettier; run `pnpm lint:fix`.
- Every source file starts with the MIT license header. `./mvnw spotless:apply` adds it to Java
  files and `tools/license-header/check.sh --fix` to every other file type.
- User-visible errors carry a registered error code; logs are in English and never contain
  secrets.

## 4. Commits

- English [Conventional Commits](https://www.conventionalcommits.org/):
  `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, with the module
  name as scope, for example `feat(connector-fake): add fake source`.
- One commit does one thing and builds and passes its tests on its own.
- Each plugin is exactly one commit that contains its tests and its documentation page.
- Breaking changes say `BREAKING CHANGE: ...` in the commit body.

## 5. Tests

New features and bug fixes come with tests. Plugins must pass the connector testkit and the
acceptance criteria of the plugin catalog. Changes to the distributed protocol add a chaos
test scenario.

## 6. Documentation

Every user-visible feature updates the documentation site **in the same commit**, in both
English and Chinese. If a change really needs no documentation, end the commit message with
`Docs-Not-Needed: <reason>`; reviewers check that the reason holds.

## 7. Contributing a plugin

1. Generate the project skeleton.
2. Implement the plugin and make it pass the testkit.
3. Write the plugin page for the documentation site.
4. Register the plugin in the plugin catalog.
5. Open a pull request.

Third-party plugins may also live in their own repository and apply for a listing in the
plugin repository index.

## 8. Review and merge

- At least one maintainer approves. Changes to `pipeforge-api`, the protocol or the checkpoint
  format need two maintainers.
- CI must be green (the `ci-ok` check).
- Pull requests are merged with rebase so that every commit is kept.
