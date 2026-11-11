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
    heroTitle: "Move data between any systems, at any scale",
    heroText:
      "One connector API for batch, streaming and CDC, running on a self-built distributed engine. Sources, transforms and sinks are all plugins.",
    getStarted: "Get started",
    viewSource: "View on GitHub",
    docs: "Documentation",
    search: "Search",
    searchPlaceholder: "Search the documentation",
    searchEmpty: "No results",
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
        "Documentation in every commit",
        "Every user-visible feature ships with its documentation page in English and Chinese.",
      ],
    ],
  },
  "zh-CN": {
    siteName: "PipeForge",
    tagline: "插件化的分布式数据集成平台",
    heroTitle: "在任意系统之间搬运数据，规模不设上限",
    heroText: "一套连接器 API 覆盖批处理、流处理和 CDC，运行在自研的分布式引擎上。数据源、转换和目标都是插件。",
    getStarted: "开始使用",
    viewSource: "在 GitHub 上查看",
    docs: "文档",
    search: "搜索",
    searchPlaceholder: "搜索文档",
    searchEmpty: "没有结果",
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
      ["每个提交都带文档", "每一个用户可见的功能都带有中英文文档页。"],
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
