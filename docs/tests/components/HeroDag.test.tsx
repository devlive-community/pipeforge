/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import { FeatureGrid } from "@/components/home/FeatureGrid";
import { HeroDag } from "@/components/home/HeroDag";
import { t } from "@/lib/i18n";

describe("home page", () => {
  it("links the shipped pipeline stages to their plugin documentation", () => {
    const dict = t("en-US");
    render(<HeroDag lang="en-US" label={dict.playground} stages={dict.pipelineStages} />);
    expect(screen.getByRole("figure", { name: dict.playground })).toBeInTheDocument();
    const nodes = screen.getAllByRole("link");
    expect(nodes.map((node) => node.getAttribute("href")?.replace(/\/$/, ""))).toEqual([
      "/en-US/next/plugins/source/fake",
      "/en-US/next/plugins/transform/field-mapper",
      "/en-US/next/plugins/sink/console",
    ]);
  });

  it("renders the product features immediately", () => {
    render(
      <FeatureGrid
        features={[
          ["A", "a"],
          ["B", "b"],
        ]}
      />,
    );
    expect(screen.getAllByRole("listitem")[1]).toHaveTextContent("Bb");
  });
});
