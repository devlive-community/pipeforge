/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Button, ConfirmDialog, Input } from "@/ui";

function Asking({ busy = false, error = null as string | null, onConfirm = () => {} }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Delete</Button>
      <ConfirmDialog
        open={open}
        title="Delete the job?"
        description="It cannot be undone."
        confirmLabel="Delete it"
        cancelLabel="Keep it"
        busyLabel="Deleting"
        tone="danger"
        busy={busy}
        error={error}
        onConfirm={onConfirm}
        onClose={() => setOpen(false)}
      >
        <Input label="Reason" />
      </ConfirmDialog>
    </>
  );
}

describe("ConfirmDialog", () => {
  it("asks with its fields focused, keeps the focus inside and returns it when closed", async () => {
    render(<Asking />);
    const trigger = screen.getByRole("button", { name: "Delete" });
    await userEvent.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Delete the job?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("It cannot be undone.");
    expect(screen.getByLabelText("Reason")).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "Keep it" })).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "Delete it" })).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByLabelText("Reason")).toHaveFocus();
    await userEvent.tab({ shift: true });
    expect(screen.getByRole("button", { name: "Delete it" })).toHaveFocus();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("confirms with the button or Enter, and closes with the backdrop or Keep", async () => {
    const confirmed = vi.fn();
    render(<Asking onConfirm={confirmed} />);
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    await userEvent.type(screen.getByLabelText("Reason"), "old{Enter}");
    await userEvent.click(screen.getByRole("button", { name: "Delete it" }));
    expect(confirmed).toHaveBeenCalledTimes(2);
    await userEvent.click(screen.getByRole("button", { name: "Keep it" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    await userEvent.click(document.querySelector("[aria-hidden='true'].bg-overlay") as HTMLElement);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("cannot be closed or confirmed while busy, and shows an error", async () => {
    const confirmed = vi.fn();
    render(<Asking busy error="The server refused" onConfirm={confirmed} />);
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("status")).toHaveTextContent("Deleting");
    expect(screen.getByRole("alert")).toHaveTextContent("The server refused");
    expect(screen.getByRole("button", { name: "Delete it" })).toBeDisabled();
    await userEvent.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(confirmed).not.toHaveBeenCalled();
  });
});
