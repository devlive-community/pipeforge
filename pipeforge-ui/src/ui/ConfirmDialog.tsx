/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Button } from "./Button";
import { LoadingLogo } from "./LoadingLogo";

const FOCUSABLE = 'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Asks before an action: a modal with a title, what will happen, optional fields and two buttons.
 * Its content is complete when it opens (the caller has what it shows), its height does not change,
 * and while `busy` a loading overlay covers it and it cannot be closed. Escape, the backdrop and
 * the cancel button close it; the focus goes to its first field or button, stays inside while it is
 * open and returns to where it was afterwards. The confirm button submits, so Enter in a field does
 * too.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  children,
  confirmLabel,
  cancelLabel,
  busyLabel,
  tone = "primary",
  busy = false,
  error,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  description: ReactNode;
  children?: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  /** Said under the loading logo while the action runs. */
  busyLabel: string;
  tone?: "primary" | "danger";
  busy?: boolean;
  /** Why the action failed; shown in the dialog, which stays open. */
  error?: string | null;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const id = useId();
  const panel = useRef<HTMLFormElement>(null);
  const returnTo = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return;
    returnTo.current = document.activeElement as HTMLElement | null;
    const first = panel.current?.querySelector<HTMLElement>(FOCUSABLE);
    first?.focus();
    return () => {
      returnTo.current?.focus();
    };
  }, [open]);

  if (!open) return null;

  const close = () => {
    if (!busy) onClose();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLFormElement>) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      close();
      return;
    }
    if (event.key !== "Tab" || !panel.current) return;
    const focusable = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* the backdrop: a click outside closes, like Escape */}
      <div aria-hidden="true" className="absolute inset-0 bg-overlay" onMouseDown={close} />
      <form
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-description`}
        aria-busy={busy || undefined}
        onKeyDown={onKeyDown}
        onSubmit={(event) => {
          event.preventDefault();
          if (!busy) onConfirm();
        }}
        className="relative flex min-h-64 w-full max-w-md flex-col rounded-card border border-border bg-surface p-5 shadow-modal"
      >
        <h2 id={`${id}-title`} className="text-base font-semibold text-fg">
          {title}
        </h2>
        <div id={`${id}-description`} className="mt-2 text-sm text-fg-muted">
          {description}
        </div>
        <div className="mt-4 flex-1 space-y-3">{children}</div>
        <p role="alert" className="mt-3 min-h-5 text-sm text-danger">
          {error ?? ""}
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={close} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button type="submit" variant={tone === "danger" ? "danger" : "primary"} disabled={busy}>
            {confirmLabel}
          </Button>
        </div>
        {busy ? (
          <div
            role="status"
            className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-card bg-surface/85 text-sm text-fg-muted"
          >
            <LoadingLogo size={32} />
            {busyLabel}
          </div>
        ) : null}
      </form>
    </div>,
    document.body,
  );
}
