/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { fileURLToPath } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { themeInitScript } from "@pipeforge/design-tokens";
import { defineConfig, type Plugin } from "vitest/config";

/** Puts the first-frame theme script into <head>, so the page never flashes the wrong theme. */
function themeScript(): Plugin {
  return {
    name: "pipeforge-theme-script",
    transformIndexHtml: () => [{ tag: "script", children: themeInitScript, injectTo: "head-prepend" }],
  };
}

/** The server the dev server forwards /api to; override with PIPEFORGE_SERVER. */
const server = process.env.PIPEFORGE_SERVER ?? "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react(), tailwindcss(), themeScript()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  server: { port: 5173, proxy: { "/api": server } },
  build: { outDir: "dist", sourcemap: true },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./test/setup.ts"],
    include: ["test/**/*.test.{ts,tsx}"],
    css: false,
    // longer than the 5 s that findBy and waitFor may wait (test/setup.ts)
    testTimeout: 15000,
  },
});
