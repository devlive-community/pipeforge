/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import { Callout } from "@/components/mdx/Callout";
import { Step, Steps } from "@/components/mdx/Steps";

describe("Callout", () => {
  it("uses the localized default title of its type", () => {
    render(
      <>
        <Callout type="warning">careful</Callout>
        <Callout type="tip" lang="zh-CN">
          提示内容
        </Callout>
      </>,
    );
    const notes = screen.getAllByRole("note");
    expect(notes[0]).toHaveTextContent("Warning");
    expect(notes[0]).toHaveTextContent("careful");
    expect(notes[1]).toHaveTextContent("提示");
  });

  it("prefers an explicit title and falls back to a note for unknown types", () => {
    render(
      <Callout type={"bogus" as never} title="Custom">
        body
      </Callout>,
    );
    expect(screen.getByRole("note")).toHaveTextContent("Custom");
    expect(screen.getByRole("note").className).toContain("bg-info-soft");
  });
});

describe("Steps", () => {
  it("numbers each step in order", () => {
    render(
      <Steps>
        <Step title="Install">first</Step>
        <Step title="Run">second</Step>
      </Steps>,
    );
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("1Installfirst");
    expect(items[1]).toHaveTextContent("2Runsecond");
  });
});
