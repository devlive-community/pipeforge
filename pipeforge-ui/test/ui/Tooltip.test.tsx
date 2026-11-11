/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { IconButton, Tooltip } from "@/ui";

describe("Tooltip", () => {
  it("shows on focus, describes its child and hides on Escape", async () => {
    render(
      <Tooltip content="Saves the job">
        <span tabIndex={0}>Save</span>
      </Tooltip>,
    );
    await userEvent.tab();
    const tooltip = screen.getByRole("tooltip");
    expect(tooltip).toHaveTextContent("Saves the job");
    expect(screen.getByText("Save")).toHaveAttribute("aria-describedby", tooltip.id);
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("appears on hover only after a delay", () => {
    vi.useFakeTimers();
    try {
      render(
        <Tooltip content="Hint" placement="top">
          <span>Target</span>
        </Tooltip>,
      );
      const wrapper = screen.getByText("Target").parentElement as HTMLElement;
      act(() => {
        wrapper.dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
      });
      expect(screen.queryByRole("tooltip")).toBeNull();
      act(() => {
        vi.advanceTimersByTime(300);
      });
      expect(screen.getByRole("tooltip")).toHaveTextContent("Hint");
      act(() => {
        wrapper.dispatchEvent(new MouseEvent("mouseout", { bubbles: true }));
      });
      expect(screen.queryByRole("tooltip")).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it("gives icon buttons their label as name without repeating it as description", async () => {
    render(<IconButton icon="sun" label="Theme" />);
    await userEvent.tab();
    const button = screen.getByRole("button", { name: "Theme" });
    expect(screen.getByRole("tooltip")).toHaveTextContent("Theme");
    expect(button).not.toHaveAttribute("aria-describedby");
  });
});
