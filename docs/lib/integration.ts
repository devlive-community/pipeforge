/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { Lang } from "./i18n";

const labels = {
  "en-US": {
    overview: "Overview",
    connectors: "Connectors",
    heroTitle: ["Open-source", "data integration", "engine"],
    heroText:
      "Read from a database, transform fields and write to a destination. Run the same job locally or on a cluster.",
    findConnectors: "Browse connectors",
    readDocs: "Read the docs",
    howItWorks: "From connectors to run diagnostics",
    capabilities: [
      ["Read and write", "Use source and sink connectors for systems such as MySQL and PostgreSQL."],
      ["Transform fields", "Rename, project and compute fields with the field mapper."],
      ["Diagnose runs", "Inspect execution plans, split progress, throughput and task logs."],
    ],
    homepage: {
      useCasesTitle: "What you can build",
      useCases: {
        migration: ["Migrate a table", "Read a large table in parallel by key range and write it to another database."],
        query: [
          "Extract a query result",
          "Select only the columns and rows you need, then write them to a target table.",
        ],
        mapping: [
          "Prepare data for a target schema",
          "Rename columns, drop unused fields or compute new values before writing.",
        ],
      },
      connectorsTitle: "Database connectors",
      connectorsText:
        "Both systems can be used as sources and sinks. Each connector guide covers configuration, supported types and examples.",
      allConnectors: "Browse connector docs",
      workflowTitle: "From the job definition to the run",
      workflow: {
        author: ["Define the job", "Write YAML or assemble the pipeline in the visual job editor."],
        validate: ["Check the plan", "Use explain to check the selected plugins and the schema each node produces."],
        run: ["Follow the run", "Read split progress, throughput, logs and the bottleneck diagnosis."],
      },
      screenshotAlt: "PipeForge run details showing row counts, split progress and the target bottleneck",
      startTitle: "Run a job locally or deploy a cluster",
      startText: "Start with the bundled example, then deploy a Master and Workers to run jobs across a cluster.",
      startLocal: "Run the local example",
      startCluster: "Cluster quick start",
    },
    benchmark: {
      title: "Full orders table sync",
      passed: "Local benchmark passed",
      source: "Source",
      sink: "Sink",
      fieldType: "Field type",
      sampleRows: "Sample order rows",
      steps: [
        ["Split by primary key", "32 query ranges"],
        ["Schedule parallel reads", "8 parallel tasks"],
        ["Write in batches", "JDBC batch commits"],
      ],
      stats: [
        ["2 million", "rows synced"],
        ["15.4 s", "job runtime"],
        ["32/32", "splits read"],
      ],
      environment: "One host · 3 Worker processes",
      details: "Benchmark methodology",
    },
    docs: {
      title: "Data integration docs",
      intro: "Configure sources and sinks, map fields, and run jobs locally or on a cluster.",
      search: "Search connectors, settings and error codes",
      stagesTitle: "Browse by pipeline stage",
      stages: ["Read from a source", "Transform fields", "Write to a sink"],
      entries: [
        ["MySQL Source", "Tables, queries and splits"],
        ["PostgreSQL Source", "Queries and JDBC reads"],
        ["Field mapping", "Rename, project and compute columns"],
        ["Expressions", "Type conversion and built-in functions"],
        ["MySQL Sink", "JDBC batch writes"],
        ["PostgreSQL Sink", "Batch size and target tables"],
      ],
      firstJob: "Run your first job",
      firstJobText: "The bundled example generates rows, maps fields and prints the result in your terminal.",
      startLinks: ["Install PipeForge and Java 21", "Read the job configuration", "Understand the execution plan"],
      reference: [
        ["Job DSL", "Configuration, variables and parallelism"],
        ["Deployment", "Configure Master, Workers and the server"],
        ["Run metrics", "Understand progress and diagnose failures"],
      ],
    },
  },
  "zh-CN": {
    overview: "概览",
    connectors: "连接器",
    heroTitle: ["开源分布式", "数据集成", "引擎"],
    heroText: "从数据库读取，按需转换字段，再写入目标端。用同一份作业配置在本地或集群运行。",
    findConnectors: "查找连接器",
    readDocs: "阅读文档",
    howItWorks: "从连接器到运行诊断",
    capabilities: [
      ["读取与写入", "通过 Source 和 Sink 对接 MySQL、PostgreSQL 等端点。"],
      ["字段转换", "用字段映射插件重命名列、计算表达式和投影字段。"],
      ["运行诊断", "查看执行计划、分片进度、吞吐和任务日志。"],
    ],
    homepage: {
      useCasesTitle: "可以用来做什么",
      useCases: {
        migration: ["整表迁移", "按主键范围并行读取大表，把数据批量写入另一个数据库。"],
        query: ["按查询抽取", "用 SQL 选出需要的行和列，只把查询结果写入目标表。"],
        mapping: ["适配目标表结构", "写入前重命名列、舍弃多余字段，或用表达式计算新值。"],
      },
      connectorsTitle: "数据库连接器",
      connectorsText: "两种数据库都支持读取和写入。连接器文档列出了配置、支持的类型与示例。",
      allConnectors: "浏览连接器文档",
      workflowTitle: "从编写作业到查看运行结果",
      workflow: {
        author: ["定义作业", "用 YAML 配置作业，或在可视化编辑器里组装数据链路。"],
        validate: ["检查执行计划", "用 explain 核对插件、选项和各节点的输出表结构。"],
        run: ["跟踪运行", "查看分片进度、读写速率和任务日志，定位瓶颈。"],
      },
      screenshotAlt: "PipeForge 运行详情页，展示读写行数、分片进度和目标端瓶颈",
      startTitle: "从本地示例开始，逐步部署集群",
      startText: "先运行仓库自带的示例，再用 Master 和 Worker 在集群里执行作业。",
      startLocal: "运行本地示例",
      startCluster: "集群快速开始",
    },
    benchmark: {
      title: "订单表全量同步",
      passed: "本地压测通过",
      source: "源端 · Source",
      sink: "目标端 · Sink",
      fieldType: "字段类型",
      sampleRows: "订单表样例数据",
      steps: [
        ["按主键切分", "32 个查询范围"],
        ["调度并行读取", "8 个并行任务"],
        ["批量写入", "JDBC 分批提交"],
      ],
      stats: [
        ["200 万行", "同步规模"],
        ["15.4 秒", "作业耗时"],
        ["32/32", "分片读完"],
      ],
      environment: "单机 · 3 个 Worker 进程",
      details: "基准测试方法",
    },
    docs: {
      title: "数据集成文档",
      intro: "配置源和目标、编写字段映射，在本地或集群运行作业。",
      search: "搜索连接器、配置项和错误码",
      stagesTitle: "按作业链路查文档",
      stages: ["读取源端", "转换字段", "写入目标端"],
      entries: [
        ["MySQL Source", "表、查询与分片"],
        ["PostgreSQL Source", "JDBC 查询与读取"],
        ["字段映射", "重命名、投影与计算列"],
        ["表达式", "类型转换与内置函数"],
        ["MySQL Sink", "JDBC 批量写入"],
        ["PostgreSQL Sink", "批大小与目标表"],
      ],
      firstJob: "运行第一个作业",
      firstJobText: "在本地运行仓库自带的示例。它会生成数据、执行字段映射并打印结果。",
      startLinks: ["安装 PipeForge 与 Java 21", "查看 YAML 配置", "理解执行计划与运行结果"],
      reference: [
        ["作业 DSL", "配置结构、变量与并行度"],
        ["部署与运维", "配置 Master、Worker 和服务端"],
        ["运行指标", "看懂作业进度并定位瓶颈"],
      ],
    },
  },
} as const;

export function integrationLabels(lang: Lang) {
  return labels[lang];
}
