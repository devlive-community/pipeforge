/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { Icon } from "../ui/Icon";

const icons = ["workflow", "layers", "box", "database", "activity", "sliders"] as const;

export function FeatureGrid({ features }: { features: ReadonlyArray<readonly [string, string]> }) {
  return (
    <ul className="feature-grid">
      {features.map(([title, text], index) => (
        <li key={title}>
          <Icon name={icons[index] ?? "layers"} size={22} />
          <h3>{title}</h3>
          <p>{text}</p>
        </li>
      ))}
    </ul>
  );
}
