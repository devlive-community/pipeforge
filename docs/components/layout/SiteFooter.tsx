/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

import { t, type Lang } from "@/lib/i18n";

export function SiteFooter({ lang }: { lang: Lang }) {
  return (
    <footer className="border-t border-border">
      <div className="mx-auto max-w-screen-2xl px-4 py-8 text-sm text-fg-muted sm:px-6">
        © 2026 devlive-community/pipeforge · MIT License · {t(lang).tagline}
      </div>
    </footer>
  );
}
