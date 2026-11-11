/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import { Screenshot } from "@/components/mdx/Screenshot";

describe("Screenshot", () => {
  it("shows the light and dark screenshot of the page language", () => {
    render(<Screenshot name="overview" alt="The overview page" lang="zh-CN" />);
    const images = screen.getAllByRole("img", { name: "The overview page" });
    expect(images.map((i) => i.getAttribute("src"))).toEqual([
      "/screenshots/ui/overview-light-zh-CN.png",
      "/screenshots/ui/overview-dark-zh-CN.png",
    ]);
    expect(images[1]).toHaveClass("screenshot-dark");
  });

  it("fails the build when a screenshot is missing", () => {
    expect(() => render(<Screenshot name="nothing" alt="x" />)).toThrow(/nothing-light-en-US\.png is missing/);
  });
});
