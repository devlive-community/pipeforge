/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { act, fireEvent, render, screen } from "@testing-library/react";
import { vi } from "vitest";
import { CopyButton } from "@/components/mdx/CopyButton";

describe("CopyButton", () => {
  it("copies the text, shows the copied state and resets after 1.5 seconds", async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(<CopyButton text="./mvnw verify" label="Copy code" copiedLabel="Copied" />);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Copy code" }));
    });
    expect(writeText).toHaveBeenCalledWith("./mvnw verify");
    expect(screen.getByRole("button", { name: "Copied" })).toHaveAttribute("data-copied", "true");
    expect(screen.getByRole("status")).toHaveTextContent("Copied");

    act(() => {
      vi.advanceTimersByTime(1500);
    });
    expect(screen.getByRole("button", { name: "Copy code" })).toHaveAttribute("data-copied", "false");
  });

  it("stays unchanged when the clipboard is not available", async () => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
      configurable: true,
    });
    render(<CopyButton text="x" label="Copy code" copiedLabel="Copied" />);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Copy code" }));
    });
    expect(screen.getByRole("button", { name: "Copy code" })).toHaveAttribute("data-copied", "false");
  });
});
