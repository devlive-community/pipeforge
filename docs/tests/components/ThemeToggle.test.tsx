/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeToggle } from "@/components/layout/ThemeToggle";

const dict = { themeLight: "Light theme", themeDark: "Dark theme", themeSystem: "Follow system theme" };

describe("ThemeToggle", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.className = "";
    document.documentElement.dataset.themeMode = "system";
  });

  it("cycles light, dark and system, storing the choice and toggling the dark class", async () => {
    render(<ThemeToggle dict={dict} />);
    const button = await screen.findByRole("button", { name: "Follow system theme" });

    await userEvent.click(button);
    expect(screen.getByRole("button", { name: "Light theme" })).toBeInTheDocument();
    expect(localStorage.getItem("pipeforge-theme")).toBe("light");
    expect(document.documentElement).not.toHaveClass("dark");

    await userEvent.click(screen.getByRole("button", { name: "Light theme" }));
    expect(localStorage.getItem("pipeforge-theme")).toBe("dark");
    expect(document.documentElement).toHaveClass("dark");
    expect(document.documentElement.dataset.themeMode).toBe("dark");

    await userEvent.click(screen.getByRole("button", { name: "Dark theme" }));
    expect(localStorage.getItem("pipeforge-theme")).toBe("system");
    expect(document.documentElement).not.toHaveClass("dark");
  });

  it("starts from the mode set by the first-frame script", async () => {
    document.documentElement.dataset.themeMode = "dark";
    await act(async () => {
      render(<ThemeToggle dict={dict} />);
    });
    expect(screen.getByRole("button", { name: "Dark theme" })).toBeInTheDocument();
  });
});
