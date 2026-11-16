/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { DEFAULT_LANGUAGE, LANGUAGES } from "./content-core.mjs";

export type Lang = "en-US" | "zh-CN";

export function isLang(value: string): value is Lang {
  return (LANGUAGES as readonly string[]).includes(value);
}

export function toLang(value: string): Lang {
  return isLang(value) ? value : (DEFAULT_LANGUAGE as Lang);
}

const dictionaries = {
  "en-US": {
    siteName: "PipeForge",
    tagline: "Pluggable distributed data integration",
    heroTitle: ["Build pipelines", "that fit your data."],
    playground: "Example pipeline",
    pipelineStages: { source: "Source", transform: "Transform", sink: "Sink" },
    exploreDocs: "Explore the documentation",
    homepageResources: [
      ["getting-started/quickstart", "Quick start", "Build the CLI and run your first job."],
      ["reference/job-dsl", "Job DSL", "Learn how a pipeline is defined."],
      ["plugins", "Plugin catalog", "Browse sources, transforms and sinks."],
    ],
    featureTitle: "How PipeForge works",
    editorTitle: "Inspect your pipeline as you build it",
    editorText:
      "Write job YAML with completion and validation, then inspect the graph and execution plan in the Web UI.",
    editorLink: "Explore the job editor",
    docsDescription: "Run your first pipeline, learn how the engine works, or look up a command, API or plugin.",
    browseDocs: "Browse by topic",
    docsSeries: "Development docs",
    sectionSummaries: {
      "getting-started": "Install PipeForge and run your first job.",
      concepts: "Understand execution, schemas and the plugin model.",
      guides: "Work with the Web UI, job editor and field mappings.",
      reference: "Look up the CLI, job DSL, APIs and functions.",
      plugins: "Explore sources, transforms and sinks.",
      operations: "Configure and run the PipeForge server.",
      development: "Build plugins and contribute to PipeForge.",
    },
    heroText:
      "Connect sources, transforms and sinks with a single plugin API for batch, streaming and CDC on PipeForge’s distributed engine.",
    getStarted: "Get started",
    viewSource: "View on GitHub",
    docs: "Documentation",
    breadcrumb: "Breadcrumb",
    pageNavigation: "Article navigation",
    search: "Search",
    searchPlaceholder: "Search the documentation",
    searchEmpty: "No results",
    searchLoading: "Loading search index…",
    searchUnavailable: "Search is unavailable. Close and reopen to try again.",
    searchHint: "to navigate, Enter to open, Esc to close",
    onThisPage: "On this page",
    previous: "Previous",
    next: "Next",
    copy: "Copy code",
    copied: "Copied",
    theme: "Theme",
    themeLight: "Light theme",
    themeDark: "Dark theme",
    themeSystem: "Follow system theme",
    language: "中文",
    languageLabel: "Switch language to Chinese",
    menu: "Menu",
    skipToContent: "Skip to content",
    editPage: "Edit this page",
    sections: {
      "getting-started": "Getting started",
      concepts: "Concepts",
      guides: "Guides",
      reference: "Reference",
      plugins: "Plugins",
      operations: "Operations",
      development: "Development",
    },
    callout: { note: "Note", tip: "Tip", warning: "Warning", danger: "Danger" },
    features: [
      [
        "One API for batch, streaming and CDC",
        "Connectors implement one engine-independent API with split enumeration, readers, writers and committers.",
      ],
      [
        "Distributed from day one",
        "Master and workers with split assignment, back pressure, checkpoints and regional failover.",
      ],
      [
        "Everything is a plugin",
        "Each plugin runs in its own class loader, so libraries in different versions never clash.",
      ],
      ["Unified schema", "Shared logical types, type mapping per dialect, schema inference and schema evolution."],
      [
        "Observable by design",
        "Metrics for jobs, nodes, tasks, splits and tables, plus explain for plans and bottlenecks.",
      ],
      [
        "Inspect the job before running it",
        "Validate YAML against installed plugins, then inspect schemas and the execution plan.",
      ],
    ],
  },
  "zh-CN": {
    siteName: "PipeForge",
    tagline: "插件化的分布式数据集成平台",
    heroTitle: ["按需组合", "你的数据管道。"],
    playground: "管道示例",
    pipelineStages: { source: "数据源", transform: "转换", sink: "目标" },
    exploreDocs: "探索文档",
    homepageResources: [
      ["getting-started/quickstart", "快速开始", "构建 CLI，运行第一个作业。"],
      ["reference/job-dsl", "作业 DSL", "了解如何定义一条数据管道。"],
      ["plugins", "插件目录", "浏览数据源、转换和目标插件。"],
    ],
    featureTitle: "PipeForge 如何工作",
    editorTitle: "边写作业，边查看管道",
    editorText: "在 Web UI 中编写 YAML，使用自动补全和实时校验，并查看作业拓扑和执行计划。",
    editorLink: "了解作业编辑器",
    docsDescription: "运行第一条数据管道，了解引擎原理，或查找命令、API 和插件。",
    browseDocs: "按主题浏览",
    docsSeries: "开发版文档",
    sectionSummaries: {
      "getting-started": "安装 PipeForge 并运行第一个作业。",
      concepts: "了解执行机制、数据模型和插件架构。",
      guides: "使用 Web UI、作业编辑器和字段映射。",
      reference: "查阅 CLI、作业 DSL、API 和函数。",
      plugins: "探索数据源、转换和目标插件。",
      operations: "配置和运行 PipeForge 服务端。",
      development: "开发插件并参与 PipeForge 项目。",
    },
    heroText: "用一套插件 API 连接数据源、转换和目标，在 PipeForge 自研分布式引擎上处理批、流和 CDC 作业。",
    getStarted: "开始使用",
    viewSource: "在 GitHub 上查看",
    docs: "文档",
    breadcrumb: "面包屑导航",
    pageNavigation: "前后文章",
    search: "搜索",
    searchPlaceholder: "搜索文档",
    searchEmpty: "没有结果",
    searchLoading: "正在加载搜索索引…",
    searchUnavailable: "搜索暂时不可用。请关闭后重新打开再试。",
    searchHint: "选择，Enter 打开，Esc 关闭",
    onThisPage: "本页目录",
    previous: "上一页",
    next: "下一页",
    copy: "复制代码",
    copied: "已复制",
    theme: "主题",
    themeLight: "浅色主题",
    themeDark: "深色主题",
    themeSystem: "跟随系统主题",
    language: "English",
    languageLabel: "切换为英文",
    menu: "菜单",
    skipToContent: "跳到正文",
    editPage: "编辑此页",
    sections: {
      "getting-started": "快速开始",
      concepts: "概念",
      guides: "指南",
      reference: "参考",
      plugins: "插件",
      operations: "运维",
      development: "开发",
    },
    callout: { note: "说明", tip: "提示", warning: "注意", danger: "警告" },
    features: [
      ["一套 API 覆盖批、流、CDC", "连接器实现一套与引擎无关的 API：分片枚举、读取、写入和提交。"],
      ["从第一天起就是分布式", "Master 与 Worker，分片分配、背压、checkpoint 和局部故障恢复。"],
      ["一切皆插件", "每个插件运行在独立的类加载器中，同一个库的不同版本互不冲突。"],
      ["统一 Schema", "共用的逻辑类型、按方言的类型映射、Schema 推导与演进。"],
      ["天生可观测", "作业、节点、Task、分片、表各层的指标，以及用于计划和瓶颈分析的 explain。"],
      ["运行前检查作业", "用已安装的插件校验 YAML，再查看 Schema 和执行计划。"],
    ],
  },
} as const;

export type Dictionary = (typeof dictionaries)[Lang];

export function t(lang: Lang): Dictionary {
  return dictionaries[lang];
}

export function otherLang(lang: Lang): Lang {
  return lang === "en-US" ? "zh-CN" : "en-US";
}
