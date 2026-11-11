# 参与贡献 PipeForge

[English](CONTRIBUTING.md) | [简体中文](CONTRIBUTING.zh-CN.md)

感谢你参与 PipeForge！本文说明如何提出改动、如何搭建开发环境，以及 Pull Request 合并前需要满足的要求。

## 1. 开始之前

- 小的修复（错别字、明显的缺陷、缺少的测试）可以直接提 Pull Request。
- 新功能、新插件，以及涉及 `pipeforge-api`、通信协议或 checkpoint 格式的改动，先开一个**功能提议** Issue，
  确认方向后再动手。
- 提问请到 [GitHub Discussions](https://github.com/devlive-community/pipeforge/discussions)，不要在 Issue 中提问。

## 2. 开发环境

需要 JDK 21（见 `.java-version`）、Docker，以及用于 UI 和文档站的 Node.js 与 pnpm。其他工具都由 Maven Wrapper 提供。

```bash
./mvnw verify                      # 完整构建，执行全部质量门槛
./mvnw -Pquick install             # 快速构建，跳过格式化、静态检查和覆盖率
./mvnw -pl pipeforge-api verify    # 只构建并测试一个模块
./dev/up.sh                        # 拉起本地依赖服务（数据库、对象存储等）
```

## 3. 代码规范

- Java 由 Spotless + palantir-java-format 格式化，并经过 Error Prone 和 SpotBugs 检查。提交前运行 `./mvnw spotless:apply`。
- 前端代码使用 ESLint 和 Prettier，运行 `pnpm lint:fix`。
- 所有源文件开头都带 MIT 许可证头。`./mvnw spotless:apply` 会给 Java 文件补上，其他类型的文件用
  `tools/license-header/check.sh --fix` 补上。
- 用户可见的错误都要带登记过的错误码；日志使用英文，不包含任何密钥。

## 4. 提交规范

- 使用英文 [Conventional Commits](https://www.conventionalcommits.org/)：`feat`、`fix`、`perf`、`refactor`、`test`、
  `docs`、`build`、`ci`、`chore`，范围为模块名，例如 `feat(connector-fake): add fake source`。
- 一个提交只做一件事，单独检出后能构建并通过测试。
- 每个插件恰好一个提交，包含它的测试和文档页。
- 不兼容改动在提交正文中写 `BREAKING CHANGE: ...`。

## 5. 测试

新功能和缺陷修复都要带测试。插件必须通过连接器测试套件（testkit）并满足插件清单的验收标准。改动分布式协议时要补充混沌测试场景。

## 6. 文档

每一个用户可见的功能都要**在同一个提交中**更新文档站，中英文各一份。确实不需要文档时，在提交信息末尾写
`Docs-Not-Needed: <原因>`，评审时会确认原因是否成立。

## 7. 贡献一个插件

1. 生成插件工程骨架。
2. 实现插件并通过 testkit。
3. 为文档站编写插件页。
4. 在插件清单中登记。
5. 提交 Pull Request。

第三方插件也可以在自己的仓库中独立维护，并申请收录到插件仓库索引中。

## 8. 评审与合并

- 至少一位维护者批准；`pipeforge-api`、协议、checkpoint 格式的改动需要两位维护者批准。
- CI 全部通过（`ci-ok` 检查）。
- 采用 rebase 合并，保留每一个提交。
