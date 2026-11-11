/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

"use client";

import { THEME_STORAGE_KEY } from "@pipeforge/design-tokens";
import { useEffect, useState } from "react";
import { IconButton } from "../ui/IconButton";

type Mode = "light" | "dark" | "system";
const ORDER: Mode[] = ["light", "dark", "system"];

function systemDark() {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function apply(mode: Mode) {
  const root = document.documentElement;
  root.classList.toggle("dark", mode === "dark" || (mode === "system" && systemDark()));
  root.dataset.themeMode = mode;
}

/**
 * Cycles light → dark → system. The new theme spreads as a circle from the button
 * (View Transitions); browsers without support, or reduced motion, switch instantly.
 */
export function ThemeToggle({ dict }: { dict: { themeLight: string; themeDark: string; themeSystem: string } }) {
  const [mode, setMode] = useState<Mode>("system");

  useEffect(() => {
    const current = document.documentElement.dataset.themeMode as Mode | undefined;
    setMode(current && ORDER.includes(current) ? current : "system");
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      if (document.documentElement.dataset.themeMode === "system") apply("system");
    };
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);

  function choose(next: Mode, x: number, y: number) {
    const update = () => {
      try {
        localStorage.setItem(THEME_STORAGE_KEY, next);
      } catch {
        // Storage can be unavailable (private mode); the choice then lasts for this page.
      }
      apply(next);
      setMode(next);
    };
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const doc = document as Document & { startViewTransition?: (cb: () => void) => { ready: Promise<void> } };
    if (!doc.startViewTransition || reduced) {
      update();
      return;
    }
    const radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));
    doc.startViewTransition(update).ready.then(() => {
      document.documentElement.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
        { duration: 400, easing: "cubic-bezier(0.16, 1, 0.3, 1)", pseudoElement: "::view-transition-new(root)" },
      );
    });
  }

  const labels: Record<Mode, string> = { light: dict.themeLight, dark: dict.themeDark, system: dict.themeSystem };
  const icon = mode === "light" ? "sun" : mode === "dark" ? "moon" : "monitor";
  return (
    <IconButton
      icon={icon}
      label={labels[mode]}
      onClick={(event) => {
        const rect = event.currentTarget.getBoundingClientRect();
        const next = ORDER[(ORDER.indexOf(mode) + 1) % ORDER.length] ?? "system";
        choose(next, rect.left + rect.width / 2, rect.top + rect.height / 2);
      }}
    />
  );
}
