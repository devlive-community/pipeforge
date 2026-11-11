/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { BASE_PATH } from "@/lib/site";

// Sends visitors to the documentation in their browser language; links work without scripts.
const redirect = `(function(){var l=(navigator.languages||[navigator.language||""]).join(",").toLowerCase();location.replace(${JSON.stringify(
  BASE_PATH,
)}+(/(^|,)zh/.test(l)?"/zh-CN/":"/en-US/"));})();`;

export default function RootRedirect() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      <script dangerouslySetInnerHTML={{ __html: redirect }} />
      <h1 className="text-2xl font-semibold">PipeForge</h1>
      <p className="flex gap-4">
        <a className="text-primary underline" href={`${BASE_PATH}/en-US/`}>
          English
        </a>
        <a className="text-primary underline" href={`${BASE_PATH}/zh-CN/`}>
          简体中文
        </a>
      </p>
    </main>
  );
}
