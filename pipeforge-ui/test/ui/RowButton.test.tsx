/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RowButton } from "@/ui";

describe("RowButton", () => {
  it("is a pressable row that says whether it is selected", async () => {
    const onClick = vi.fn();
    const { rerender } = render(
      <RowButton data-output="loud" onClick={onClick}>
        loud
      </RowButton>,
    );
    const row = screen.getByRole("button", { name: "loud" });
    expect(row).toHaveAttribute("aria-pressed", "false");
    expect(row).toHaveAttribute("data-output", "loud");
    expect(row).toHaveAttribute("type", "button");
    await userEvent.click(row);
    expect(onClick).toHaveBeenCalledOnce();
    rerender(
      <RowButton selected highlighted>
        loud
      </RowButton>,
    );
    expect(screen.getByRole("button", { name: "loud" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "loud" }).className).toContain("border-dashed");
  });
});
