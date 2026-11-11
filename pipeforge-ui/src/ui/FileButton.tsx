/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { useRef } from "react";
import { Button, type ButtonVariant } from "./Button";
import type { IconName } from "./Icon";

/** Reads a file as text (FileReader works in every browser and in test environments). */
function readText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(file);
  });
}

/** A button that opens the file picker and hands over the chosen file's name and text. */
export function FileButton({
  children,
  accept,
  onFile,
  icon = "upload",
  variant = "secondary",
}: {
  children: string;
  accept?: string;
  onFile: (name: string, text: string) => void;
  icon?: IconName;
  variant?: ButtonVariant;
}) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <>
      <Button icon={icon} variant={variant} onClick={() => input.current?.click()}>
        {children}
      </Button>
      <input
        ref={input}
        type="file"
        accept={accept}
        hidden
        aria-hidden="true"
        tabIndex={-1}
        onChange={async (e) => {
          const file = e.target.files?.[0];
          e.target.value = "";
          if (file) onFile(file.name, await readText(file));
        }}
      />
    </>
  );
}
