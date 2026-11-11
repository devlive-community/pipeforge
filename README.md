# PipeForge

[English](README.md) | [简体中文](README.zh-CN.md)

PipeForge is a pluggable, distributed data integration platform. One connector API covers
batch, streaming and CDC; jobs are built from **Source → Transform → Sink** and run on
PipeForge's own distributed engine (Master / Worker). Sources, sinks and transforms are all
plugins, so you can build and ship your own.

> **Status:** early development (milestone M0, project skeleton). Nothing is released yet.

## Highlights

- One connector API for batch, streaming and CDC, independent of the execution engine.
- Self-developed distributed engine: splits, back pressure, checkpoints, exactly-once, failover.
- Every connector is a plugin with its own isolated class loader.
- Unified logical types and schema evolution across all connectors.
- Built-in metrics, job diagnosis (`explain`) and a self-built web UI.

## Building from source

Requirements: JDK 21 (see `.java-version`), Docker for the local development environment.

```bash
./mvnw verify          # build with formatting, static analysis and license checks
./mvnw -Pquick install # fast local build without the quality gates
./mvnw spotless:apply  # format Java sources and add missing license headers
```

## Documentation

The documentation site lives in [`docs/`](docs/) and will be published to GitHub Pages.

## Community

- Read the [contribution guide](CONTRIBUTING.md) before opening a pull request.
- Everyone is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).
- Report security problems privately as described in [SECURITY.md](SECURITY.md).
- How decisions are made: [GOVERNANCE.md](GOVERNANCE.md); who maintains what: [MAINTAINERS.md](MAINTAINERS.md).
- Questions and ideas are welcome in GitHub Discussions.

## License

PipeForge is released under the [MIT License](LICENSE).
