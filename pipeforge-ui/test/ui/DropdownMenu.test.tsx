/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { DropdownMenu, IconButton } from "@/ui";

function Menu({ onSelect = () => {} }: { onSelect?: (id: string) => void }) {
  const [value, setValue] = useState("dark");
  return (
    <>
      <DropdownMenu
        label="Theme"
        value={value}
        onSelect={(id) => {
          setValue(id);
          onSelect(id);
        }}
        items={[
          { id: "light", label: "Light", icon: "sun" },
          { id: "dark", label: "Dark", icon: "moon" },
          { id: "system", label: "System", icon: "monitor" },
        ]}
        trigger={(props) => <IconButton icon="moon" label="Theme" {...props} />}
      />
      <span>outside</span>
    </>
  );
}

describe("DropdownMenu", () => {
  it("opens on the chosen item and moves with the arrow keys, Home and End", async () => {
    render(<Menu />);
    const trigger = screen.getByRole("button", { name: "Theme" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(trigger);
    expect(screen.getByRole("menu", { name: "Theme" })).toBeInTheDocument();
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("menuitemradio", { name: "Dark" })).toHaveFocus();
    expect(screen.getByRole("menuitemradio", { name: "Dark" })).toHaveAttribute("aria-checked", "true");
    await userEvent.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "System" })).toHaveFocus();
    await userEvent.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "Light" })).toHaveFocus();
    await userEvent.keyboard("{ArrowUp}");
    expect(screen.getByRole("menuitemradio", { name: "System" })).toHaveFocus();
    await userEvent.keyboard("{Home}");
    expect(screen.getByRole("menuitemradio", { name: "Light" })).toHaveFocus();
    await userEvent.keyboard("{End}");
    expect(screen.getByRole("menuitemradio", { name: "System" })).toHaveFocus();
  });

  it("chooses with Enter and returns focus to the trigger", async () => {
    const onSelect = vi.fn();
    render(<Menu onSelect={onSelect} />);
    const trigger = screen.getByRole("button", { name: "Theme" });
    trigger.focus();
    await userEvent.keyboard("{ArrowUp}");
    expect(screen.getByRole("menuitemradio", { name: "System" })).toHaveFocus();
    await userEvent.keyboard("{ArrowUp}{Enter}");
    expect(onSelect).toHaveBeenCalledWith("dark");
    expect(screen.queryByRole("menu")).toBeNull();
    expect(trigger).toHaveFocus();
    await userEvent.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "Dark" })).toHaveFocus();
  });

  it("closes on Escape, Tab and a click outside", async () => {
    render(<Menu />);
    const trigger = screen.getByRole("button", { name: "Theme" });
    await userEvent.click(trigger);
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).toBeNull();
    expect(trigger).toHaveFocus();
    await userEvent.click(trigger);
    await userEvent.click(screen.getByText("outside"));
    expect(screen.queryByRole("menu")).toBeNull();
    await userEvent.click(trigger);
    await userEvent.tab();
    expect(screen.queryByRole("menu")).toBeNull();
    await userEvent.click(trigger);
    await userEvent.click(trigger);
    expect(screen.queryByRole("menu")).toBeNull();
  });
});
