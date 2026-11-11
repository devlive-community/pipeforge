# PipeForge

[English](README.md) | [简体中文](README.zh-CN.md)

PipeForge 是插件化的分布式数据集成平台：一套连接器 API 覆盖批处理、流处理和 CDC，作业由
**Source → Transform → Sink** 组成，运行在 PipeForge 自研的分布式引擎（Master / Worker）上。
数据源、目标、转换都是插件，你也可以开发并发布自己的插件。

> **状态：** 早期开发中（M0 工程骨架里程碑），尚未发布任何版本。

## 核心能力

- 一套连接器 API 覆盖批、流、CDC，与执行引擎无关。
- 自研分布式引擎：分片、背压、checkpoint、exactly-once、故障恢复。
- 每个连接器都是插件，使用独立隔离的类加载器。
- 所有连接器共用逻辑类型，支持 Schema 演进。
- 内置指标、作业诊断（`explain`）和自研 Web UI。

## 从源码构建

需要 JDK 21（见 `.java-version`），本地开发环境需要 Docker。

```bash
./mvnw verify          # 构建，并执行格式化、静态检查和许可证检查
./mvnw -Pquick install # 跳过质量门槛的快速本地构建
./mvnw spotless:apply  # 格式化 Java 代码并补上缺失的许可证头
```

## 文档

文档站位于 [`docs/`](docs/)，之后会发布到 GitHub Pages。

## 社区

贡献指南、行为准则和安全策略会陆续加入仓库。欢迎在 GitHub Discussions 中提问。

## 许可证

PipeForge 以 [MIT 许可证](LICENSE) 发布。
