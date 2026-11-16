/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import type { CSSProperties } from "react";
import { SiteLink } from "@/components/layout/SiteLink";
import { CURRENT_VERSION, pageUrl } from "@/lib/content-core.mjs";
import type { Lang } from "@/lib/i18n";
import { Icon } from "../ui/Icon";
import { EDGES, NODES, type Stage } from "./dag";

export function HeroDag({ lang, label, stages }: { lang: Lang; label: string; stages: Record<Stage, string> }) {
  return (
    <figure className="pipeline-demo" aria-label={label}>
      <figcaption className="pipeline-caption">
        <Icon name="workflow" size={18} />
        <span>fake-to-console.yaml</span>
        <span className="pipeline-caption-label">{label}</span>
      </figcaption>
      <div className="pipeline-graph">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="pipeline-edges" aria-hidden="true">
          {EDGES.map((path) => (
            <path key={path} d={path} fill="none" vectorEffect="non-scaling-stroke" />
          ))}
        </svg>
        <ol>
          {NODES.map((node) => (
            <li
              key={node.kind}
              className="pipeline-node"
              data-stage={node.kind}
              style={{ "--node-x": `${node.x}%`, "--node-y": `${node.y}%` } as CSSProperties}
            >
              <SiteLink href={pageUrl(lang, CURRENT_VERSION, `plugins/${node.kind}/${node.plugin}`)}>
                <span className="pipeline-node-type">
                  <Icon name={node.icon} size={14} />
                  {stages[node.kind]}
                </span>
                <code>{node.plugin}</code>
                <span className="pipeline-node-id">{node.id}</span>
              </SiteLink>
            </li>
          ))}
        </ol>
      </div>
      <div className="pipeline-command">
        <span aria-hidden="true">$</span>
        <code>pipeforge run --local examples/fake-to-console.yaml</code>
      </div>
    </figure>
  );
}
